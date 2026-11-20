# 日志子系统重构（LOGGING-1）

> 状态：**已落地（WP-L1 ~ WP-L5）**
> 基线：`main` @ `9785546`（0.16.2，2026-11-20）
> 上游：[NEXT.md](NEXT.md) 的 P2（UI 去控制面）/ [LAYERING-2.md](LAYERING-2.md) 的分层纪律
> 关系：只改日志子系统；不动 Hook 目标、8771 MCP 协议、Binder 事务号与 `config.json` 格式

## 0. 一句话

让「事后排查一次失败请求」变成：**按 `seq` / `ts` 定位、按 `tag` 过滤、错误行不因批量落盘丢失、进程被回收前能显式 flush**；同时保持 `/logs`、`xiaoai_logs` 的参数和字段名向后兼容。

## 1. 现状与证据

| 文件 | 现状（行号基于 0.16.2） |
|---|---|
| `platform/.../Logs.java` | 进程内静态 `ArrayDeque`，`CAP = 300`（:22），满后丢最旧；`add` 每次同时写 `XposedBridge.log`（:86）和文件镜像（:87） |
| `platform/.../Logs.java` | `mirror` 每条日志 `new FileOutputStream(..., true)` → `write` → `close`（:122-141）；文件 256KB，`.1` 备份（:126-130） |
| `platform/.../Logs.java` | `dump(limit, since)` 返回 `{count,lastSeq,lines:[{seq,t,level,msg}]}`；`t` 是 `HH:mm:ss.SSS` 本地时区（:105-119）；`limit<=0` 返回空数组（:99-101） |
| `platform/.../Logs.java` | `event(needed, severity, tag, msg)` 按 `LogSettings` 过滤，E 级不受限（:69-75）；`tag` 以 `[tag] ` 前缀塞进 `msg`（:73） |
| `domain/.../core/LogLevel.java` | OFF / BRIEF / STANDARD / DETAILED / DEBUG 五档，`parse` 非法回落 STANDARD（:32-40） |
| `application/.../log/LogSettings.java` | 每进程一份 `volatile LogLevel`；App 与每个小爱进程各自持有一份（:12-37） |
| `domain/.../control/ControlLogRing.java` | App 侧纯 JVM 环形缓冲，200 行 / 512 字符，逐行 `Redact`，不落盘（:19-35） |
| `application/.../agent/AgentCallLog.java` | App 侧 8770 调用日志，300 行，纯文本 `HH:mm:ss.SSS L [tag] msg`（:27-39） |
| `platform/.../control/BridgeControlService.java` | `logDump()` 把控制面 ring 与 `AgentCallLog` 按整行字符串排序拼接（:381-397）；`lastError()` 硬解析 `HH:mm:ss.SSS` 位置（:403-416） |
| `platform/.../Bridge.java` | `GET /logs?limit=&since=` → `Logs.dump(BridgeHttpPolicy.logLimit(limit), since)`（:890-896）；`onToolCall` 写 `[mcp] tool=...`（:200-204） |
| `application/.../Mcp.java` | `xiaoai_logs` 只接受 `limit`/`since`（:691-700），schema 上限来自 `Contract.LOGS_LIMIT_MAX`（:408-412） |
| `platform/.../bridge/BridgeControlClient.java` | 每次 pull 后 `Logs.dump(80, sLogSeq)`，把 `t/level/msg` 重新拼成文本经 Binder 上报（:431-472）；收到 `S2C_FLUSH_LOGS` 只记一句「无需刷新」（:495-496） |
| `app/.../Hook.java` | 0.16.2 三条关键诊断：`改写命中: ...`（:572）、`模型调用: 三方/官方 ...`（:349）、`第三方模型已启用，但配置 getter 从未被读取`（:355-356） |
| `domain/.../core/Contract.java` | `LOGS_LIMIT_DEFAULT = 80`、`LOGS_LIMIT_MAX = 300`（:53-54）；容量 300 目前只硬编码在 `Logs` |
| `tests/` | `LogsTest` 锁定 300 容量、`lastSeq`、`t` 格式与字段顺序；`ControlLogRingTest` 覆盖脱敏/裁剪；`AgentCallLogTest` 覆盖档位门控 |

小爱主进程、`:core`、`:voice_trigger` 各有一份 `Logs`；App 侧另有 `ControlLogRing` + `AgentCallLog`。`/logs` 只返回当前 leader 进程的 ring，App 日志页看到的是控制面上报的文本镜像。

## 2. 问题清单与结论

> 结论分为「本次做」「本次只记录理由」「不做」。不做不是遗忘，而是有明确代价。

| # | 现象 | 结论 | 处置 |
|---|---|---|---|
| 1 | 容量 300 行，无级别保留策略，一次调试很容易被后续日志冲掉 | **不改淘汰策略**。按级别保留会破坏 `since` 增量语义：保留的旧 E 行 `seq < lastSeq`，`BridgeControlClient` 永远拉不到。提高容量只延后问题，且与文档/上限表耦合 | 容量收敛到 `Contract.LOGS_CAPACITY`；用 `ts`/`pid`/`tag` 提升可追溯性；把「独立错误 ring」写进未做项 |
| 2 | 每行一次 `open/write/close`，在 getter/请求热路径上有开销 | **做批量落盘**。无新线程、无异步队列：内存 `StringBuilder` 累积，达到 16 行 / 8KB 或遇到 E 行时 flush；控制面 `flushLogs` 变成真正的 flush | 新增 `infra/LogFileSink`；`Logs` 只负责拼行 |
| 3 | `tag` 与正文混在一个字符串里，`/logs` 与 UI 只能按 level 过滤 | **内部结构化 + JSON 增字段**。`msg` 继续保留 `[tag] ` 前缀（UI 的来源筛选、Binder 文本协议、旧消费者都不用改），新增 `tag` 供结构化消费者使用 | `LogEntry` 保存 `tag`；`dump` 在 `msg` 之后追加 `tag` |
| 4 | 内存行只有 `HH:mm:ss.SSS`，跨天歧义；落盘行有 `MM-dd` | **增 `ts`（epoch 毫秒）**，保留 `t` 原格式。`t` 是兼容字段，`ts` 是排序/跨天字段 | `dump` 每行追加 `ts` |
| 5 | 三个小爱进程 + App ring 是四份事实源，拼接顺序/去重未定义 | **本次只加 `pid`**。真正的合并需要改 Binder 文本协议（每行带进程/序号）并定义去重键，风险高于收益 | 每行加 `pid`；四源合并写进未做项 |
| 6 | 日志档位 per-process，App 改档位如何传播、断连如何补 | **现有路径已闭环**：App 保存 `logLevel` → `BridgeEndpoint.requestReload()` → 控制面广播 reload → `BridgeControlClient` pull → `LogSettings.setWire` + `Logs.setLevel`。断连期间保留旧档位，重连后 pull 覆盖 | 不改 Binder；只把 `flushLogs` 做成真实 flush，并在文档写明路径 |
| 7 | `Redact` 只处理键值与 Bearer；URL/手机号/设备标识没有统一出口 | **扩大键集合 + 明确 URL 出口**。URL 继续只允许 `Redact.host()`（已有调用点 `Hook.display`）；新增 `secret/password/oaid/imei/android_id/device_id/phone/mobile` 键值脱敏 | 改 `Redact.KEY_VALUE` 并补测试 |
| 8 | 没有「本次请求」关联标识 | **做最小同进程 trace**。`Mcp.handle` 在 `tools/call` 期间设置 ThreadLocal，`Logs.event` 在正文前加 `req=<6hex>`；只覆盖同进程同步调用链，跨 Binder/跨进程明确不承诺 | 新增 `application/log/RequestTrace` |

## 3. 目标与非目标

### 目标

1. 一次失败请求的日志可以按 `seq` / `ts` / `req` 串起来，错误行能定位到进程（`pid`）。
2. 落盘从「每行一次 open/close」变成「批量写 + 显式 flush」，保持 256KB / `.1` 轮转不变。
3. 环形缓冲、过滤、JSON 装配这些纯逻辑可脱离 Android/Xposed 单测。
4. `/logs`、`xiaoai_logs` 的参数不变；返回 JSON 只做**追加字段**，旧字段名、顺序、含义不变。
5. 三条 0.16.2 诊断日志的语义与可检索性不变（`改写命中` / `模型调用` / `getter 从未被读取`）。

### 非目标

- 不重做日志页 UI，不改级别筛选/来源筛选交互。
- 不改 Binder 事务号、`ControlKeys`、`config.json` 的 `logLevel` 字段。
- 不引入异步线程、队列、日志框架、数据库。
- 不改变 `LogLevel` 五档语义与默认值。
- 不把对话正文、apiKey、token 写进日志；新增字段同样不写。

## 4. 分层落点

| 类 | 模块 | 职责 | 为什么放这里 |
|---|---|---|---|
| `LogEntry`（新） | `:application` `application/log` | 一条结构化日志：`seq/tsMillis/level/tag/msg/pid` | 纯数据，无 I/O，可被 application 与 platform 共用 |
| `LogRing`（新） | `:application` `application/log` | 容量、`seq`、`since`/`limit` 快照、并发安全 | 纯 JVM；让 `LogsTest` 不必依赖 Xposed 桩也能测边界 |
| `RequestTrace`（新） | `:application` `application/log` | `ThreadLocal` 请求 id，`enter/current/exit` | 纯 JVM；`Mcp`（application）写、`Logs`（platform）读 |
| `LogFileSink`（新） | `:infra` | 批量 append、flush、256KB 轮转、`.1` 备份 | 落盘是 I/O，只能在 infra/platform；infra 已是纯 JVM 可单测 |
| `Logs`（改） | `:platform` | 档位过滤、`[tag]` 前缀、`req` 前缀、Xposed logcat、JSON 装配、FileSink 适配 | 保留现有调用点（`Logs.i/w/e/d/event/add`）不变 |
| `Redact`（改） | `:domain` `core` | 键值/Bearer/host 脱敏 | 纯策略，已有单测 |
| `Contract`（改） | `:domain` `core` | `LOGS_CAPACITY = 300` | 文档/测试/实现只引用一处 |
| `ControlLogRing` / `AgentCallLog` / `LogsScreen` | 不变 | App 侧镜像与 UI | 本次不动 Binder 文本协议与 UI |

依赖方向不变：`platform -> {infra, application, domain}`；`infra -> application/domain`；`application -> domain`；`domain` 无 I/O。

## 5. 对外契约影响

### 5.1 不变

- `GET /logs?limit=&since=`：参数、`limit` 语义（缺省 80、`0` 空数组、上限 300）、Bearer 鉴权不变。
- `xiaoai_logs` 的 `limit`/`since`/`port` 与 `tools/list` schema 不变。
- `Logs.dump` 的顶层字段 `count` / `lastSeq` / `lines` 与每行前四个字段 `seq` / `t` / `level` / `msg` 的顺序和含义不变。
- 落盘行格式 `MM-dd HH:mm:ss.SSS LEVEL msg`、256KB 上限、`.1` 备份不变。
- `LogLevel` 五档与默认 `STANDARD` 不变。

### 5.2 追加（向后兼容）

每行在 `msg` 之后追加三个字段：

```json
{"seq":123,"t":"13:45:01.234","level":"I","msg":"[mcp] tool=xiaoai_logs ok=true ms=3 args=2","tag":"mcp","ts":1795019101234,"pid":12345}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `tag` | string | 来源标签；`""` 表示无标签。`msg` 仍保留 `[tag] ` 前缀，旧消费者不受影响 |
| `ts` | integer | epoch 毫秒，用于跨天排序；`t` 仍是本地 `HH:mm:ss.SSS` |
| `pid` | integer | 写入进程 pid；桌面单测/取不到时为 `0` |

> 旧消费者按字段名读取，忽略追加字段；契约测试只断言 `has("count") && has("lastSeq") && has("lines")`，不破。

### 5.3 同步点

改字段必须同时改：

1. `domain/.../core/Contract.java`（容量/上限常量）
2. `application/.../Mcp.java`（`xiaoai_logs` 描述）
3. `docs/refactor/LOGGING-1.md`（本文件）
4. `README.md` 工具表
5. `skill/xiaoi-mcp/references/tools.md` 与 `.opencode/skills/xiaoi-mcp/references/tools.md`（逐字节副本）
6. `tests/src/.../LogsTest.java`

## 6. 分步迁移计划

| 步骤 | 内容 | 文件 | 门禁 | 回滚 |
|---|---|---|---|---|
| WP-L1 | 抽出纯 JVM `LogEntry` + `LogRing`，`Logs` 委托；容量常量进 `Contract` | `application/log/*`、`platform/Logs.java`、`domain/core/Contract.java`、`tests/LogRingTest.java`、`TestMain` | 单测 + 分层 | 恢复 `Logs` 内联 ring |
| WP-L2 | 新增 `LogFileSink`，批量写 + E 行立即 flush；`flushLogs` 真正 flush | `infra/LogFileSink.java`、`platform/Logs.java`、`platform/bridge/BridgeControlClient.java`、`tests/LogFileSinkTest.java` | 单测 + 构建 | 恢复 `mirror` 逐行写 |
| WP-L3 | JSON 追加 `tag` / `ts` / `pid`；更新 schema 描述与两份 tools.md | `platform/Logs.java`、`Mcp.java`、`README.md`、两份 `tools.md`、`LogsTest` | 契约形状 + 单测 | 删除三行 `put` |
| WP-L4 | `RequestTrace` + `Mcp.handle` 包裹 `tools/call` + `Logs` 前缀 | `application/log/RequestTrace.java`、`Mcp.java`、`platform/Logs.java`、`tests/RequestTraceTest.java` | 单测 + MCP 测试 | 去掉 ThreadLocal 与前缀 |
| WP-L5 | `Redact` 扩键（secret/password/设备标识/手机号） | `domain/core/Redact.java`、`tests/RedactTest.java` | 单测 | 恢复原正则 |

每个步骤独立本地 commit；不 push、不打 tag。

## 7. 风险与回滚

| 风险 | 影响 | 缓解 |
|---|---|---|
| 批量落盘在进程被 kill 时丢最后 <16 行 | 丢失最后几条调试信息 | 阈值 16 行 / 8KB；E 行立即 flush；控制面 `flushLogs` 可用；文档写明 |
| 追加字段被旧消费者误判 | 极小 | 只追加、不改旧字段；`LogsTest` 锁定 `seq/t/level/msg` 顺序；契约测试只查存在性 |
| `RequestTrace` ThreadLocal 泄漏到线程池 | 后续日志带错误请求 id | `Mcp.handle` 用 `try/finally` 必然 `exit`；`RequestTraceTest` 验证 |
| `pid` 反射失败 | 字段为 0 | 桌面 JVM 预期 0；`LogsTest` 断言 `>=0` |
| `LogFileSink` 写失败导致 pending 增长 | 内存上涨 | pending 超过 4 倍 flush 阈值时丢弃并重置；不阻塞业务 |
| `Redact` 过度脱敏 | 日志可读性下降 | 只匹配「键 + 分隔符」形式；单测覆盖普通文本不误伤 |

回滚粒度：每一步一个文件组，单步 `git revert` 即可；不涉及协议删除或数据迁移。

## 8. 本次不做

1. **按级别保留 / 独立错误 ring**：会破坏 `since > lastSeq` 的增量语义，除非同时改 `BridgeControlClient` 与协议；本次只记录理由。
2. **四份事实源合并/去重**：需要 Binder 文本协议带 `pid/seq` 并定义跨进程去重键；本次只加 `pid` 字段。
3. **UI 结构化渲染（日期分组、tag 筛选、错误跳转）**：属于 UI 交互改动，先不做。
4. **异步落盘线程/队列**：不引入新线程；批量缓冲已覆盖主要开销。
5. **日志档位 push 通道**：现有 pull + reload 已能传播；不做新 Binder 事务。
6. **全量 URL/手机号/设备标识脱敏器**：只扩 `Redact` 键集合，并维持「端点只记 host」的调用纪律；不扫描任意 URL 路径。
7. **修改 300 容量 / 上限**：`LOGS_LIMIT_MAX` 与 `LOGS_CAPACITY` 保持 300，避免文档与契约漂移。
8. **跨进程 `seq` 命名空间**：`seq` 仍是每进程从 1 开始；跨进程用 `pid + ts` 区分。

## 9. 验收

```bash
wsl -e bash -c "cd /mnt/d/code/Android/xiaoi-plus && bash tests/run.sh"
wsl -e bash -c "cd /mnt/d/code/Android/xiaoi-plus && bash scripts/check-consistency.sh"
wsl -e bash -c "cd /mnt/d/code/Android/xiaoi-plus && bash scripts/check-layering.sh --strict"
# PowerShell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"; .\gradlew.bat :app:assembleRelease --console=plain
wsl -e bash -c "cd /mnt/d/code/Android/xiaoi-plus && ANDROID_SDK_ROOT=/mnt/c/Users/Chatxavier/AppData/Local/Android/Sdk bash scripts/verify-apk.sh app/build/outputs/apk/release/app-release.apk"
```

通过标准：

- `LogRingTest` 覆盖容量、`since`、`limit=0`、空值、并发写入不丢 `seq`。
- `LogFileSinkTest` 覆盖批量 flush、轮转、UTF-8、写失败不抛。
- `LogsTest` 仍锁定旧字段顺序，并断言 `tag/ts/pid`。
- `RequestTraceTest` 覆盖 enter/current/exit 与唯一性。
- `RedactTest` 覆盖新键与普通文本不误伤。
- 四条门禁全绿，APK 验签通过。
