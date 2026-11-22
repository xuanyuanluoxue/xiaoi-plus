# PLAN-MEMORY：记忆产品线规划

> 状态：**规划（未改代码、未提交、未用 adb）**
> 上游：`reverse/docs/06-memory.md`、`08-internal-api.md`、`04-toolchain.md`、`docs/agent/P2-PROMPT-MEMORY.md`
> 证据标记：**[实]** 实测 · **[静]** 静态/反编译 · **[推]** 推测（未验证，见 §5）

## 1. 一句话结论

**转调官方，自建只留影子。** 记忆的读、写、检索一律走官方
`readMemory` / `writeMemory`（进程内 apptool 通道 → `UIAgentServiceForOSBot`）；
自建只保留三件官方不给的东西——**只读快照与导出**（离线可读、可迁移）、
**本机 UI 与外部 Agent 的浏览检索**、**我们自己 Agent 侧的记忆摘要**。
`MEMORY.md` 直写降级为「默认关闭的离线兜底」，system 摘要注入正式下线。

### 1.1 三条支撑事实

| # | 事实 | 级别 | 对我们的含义 |
|---|---|---|---|
| F1 | 记忆真相在云端（ClawMemory），本地只有 `MEMORY.md` / `outline.md` / 加密库 | [实] 文件结构；[静] `MiclawSyncService` | 我们写 `MEMORY.md` 随时会被回写覆盖，不能当主写入路径 |
| F2 | `build*Request` 返回值改写**不影响实际发送内容** | [实] 0.17.0（system 变成 123 字符，回复完全不变） | 已实现的 P2-5 摘要注入对云端回复无效，**不要再投成本** |
| F3 | 18 个进程内工具含 `write_memory` / `read_memory` / `get_memory_data`，且已注册为 `voiceassist__*` | [静] `app_tools.json`、`tool-catalog.md` | 官方自己走这条链路，我们复用即可，不必自造写入协议 |

### 1.2 两条决定 P0 是否成立的推测

| # | 推测 | 依据 | 不成立时的回退 |
|---|---|---|---|
| S1 | 在小爱进程内 bindService 到**它自己**的 `APP_TOOL_PROVIDER` 可行 | 同 uid、同包；`BIND_APP_TOOL` 为 signature 级；`platform/.../agent/AppToolClient.java` 已按这条路径实现（`queryIntentServices` + bind + `handleRequest`），但**真机结果没有记录**——`docs/agent/OSBOT-PROTOCOL.md` §5 明确写「未真机验证 Notes provider」 | Hook `UIAgentServiceForOSBot.onCreate` 抓实例，反射 `readMemory` / `writeMemory` |
| S2 | `writeMemory(query)` 是**自然语言**入参，官方会过 门控→抽取→主题化，文本会被改写甚至拒记 | [静] 工具描述 + `assets/memory-topic/` 流水线 | 只承诺「能被 `read_memory` 检索到」，不承诺原文留存 |

> 推论：**不要承诺"写进去是什么样就一直是那样"**；也不要再造一套抽取流水线与官方抢写。

## 2. 功能点表

价值：5＝没有它这条线不成立，4＝明显提升，3＝有用，2＝锦上添花，1＝可砍。
依赖里的路径均相对仓库根；「新」表示需要新增文件。

| 编号 | 功能 | 用户故事 | 依赖（类/方法/文件） | 实现要点 | 价值 | 成本 | 风险 | 验收标准 |
|---|---|---|---|---|---|---|---|---|
| M01 | 官方记忆只读通道 | 我想看到小爱**真正**记住了什么，而不是猜一个明文文件 | 新 `domain/.../agent/MemoryToolProtocol.java`（buildReadRequest / parseItems，纯 JVM）；`platform/.../agent/AppToolClient.java` 抽出通用 `callProvider(ctx,pkg,service,tool,args)`；组件 `com.miui.voiceassist/.UIAgentServiceForOSBot` | 复用 Notes 的 JSON-RPC `tools/call`：`name=read_memory`、`args={keywords,page,page_size}`；沿用 5s bind / 5s call 超时；失败回结构化错误码；**返回正文不落日志** | 5 | S | 自绑定未验证（S1）；readMemory 返回结构未知 | `op=official` 返回 ≥1 条真实条目；与小爱 App 内可见内容人工比对 3 条一致；`page=2` 与 `page=1` 不重复；通道失败时返回 `CHANNEL_UNAVAILABLE` 而非空数组 |
| M02 | 官方记忆写入 | 我让 Agent 记一件事，小爱跨会话、跨设备都真的记得 | 同 M01 + `MemoryPort.officialWrite(query)`；`application/.../Mcp.java` 新增 op | 只收自然语言 `query`（≤500 字符）；关键词黑名单拒绝「忘掉/删除/清空」（无删除入口原则）；不自动重试（防重复记忆）；返回 `ok` + 「由小爱抽取，可能被改写」提示 | 5 | S | 门控可能拒记；抽取后结构丢失 | 写「我每周三晚上打羽毛球」→ 10s 内 `read_memory?keywords=羽毛球` 命中；本地 `MEMORY.md` 字节不变（确认没走老路径）；「忘掉…」被拒且无副作用 |
| M03 | `xiaoai_memory` 契约 v2（双通道） | 现有脚本不用改写就能切到官方通道 | `application/.../Mcp.java`（`memoryTool()` / `memoryToolCall()` / `MemoryPort`）；`skill/xiaoi-mcp/references/tools.md`；`tests/.../McpTest.java` | 新增 `op=official` / `official_write` / `digest`，以及 `source=official|file`；**默认 get 语义不变**（仍读文件，不偷偷切换）；`append`/`replace` 保留但标 deprecated，描述写明「只改本地明文，可能被云端覆盖」；新参数进 `unexpectedArg` 白名单 | 4 | S | 契约漂移 → `tools.md` 与实现逐字一致的门禁会挡住 | `tools.md` 与 `Mcp` 描述逐字一致；每个 op 至少 1 条单测；未知 op 回明确错误并列出可用值 |
| M04 | 记忆页双视图与可见性 | 我一眼能分清「小爱真记住的」「本地文件里的」「哪些会离开手机」 | `ui/.../screen/xiaoai/MemoryScreen.kt`；`application/.../runtime/MemoryAccess.java`；`platform/AndroidAppRuntime.java`；M03 | 顶部切换「官方 / 本地」；官方视图分页（20/页）并可搜索；本地视图保留现有分节；两视图都**只读 + 复制单条**（不提供一键写回）；顶部一行可见性说明：`本地 N 条 / 官方 M 条 / 会离开设备：无` | 3 | M | 官方通道失败时整页不可用 → 本地视图必须能独立兜底 | 断网或通道失败时不白屏，显示可操作原因；两视图条目数可与 `xiaoai_memory` 输出对照；页面不写任何正文到日志 |
| M05 | 隐私加固（日志 / 导出） | 我把诊断包发给别人时，不会顺手把健康状况、社交关系发出去 | `domain/.../core/Redact.java`；`domain/.../control/ControlLogRing.java`；导出包构造（App 侧）；`tools.md` | 新增 `Redact.memory(text)` 兜底脱敏；导出包**默认不含**任何记忆正文与官方返回体；显式勾选「包含记忆」需二次确认，包内加 `PRIVACY.txt`；日志只记长度/条数/正文 hash 前 8 位；`xiaoai_memory` 返回体不进 control log | 4 | S | 新增日志路径漏掉 → 用**负向断言**测试守住 | 单测：含 3 条记忆片段的日志与导出包，断言正文不出现；`grep` 导出包零命中；勾选后出现 `PRIVACY.txt` 且文案明示风险 |
| M06 | 记忆搜索（本地 + 官方） | 我不记得记在哪个小节，只记得一个词 | `application/.../memory/MemoryService.java`（新增 `search`）；`domain/.../core/memory/MemoryDoc.java`（子串匹配）；M01 | 本地逐行 `contains`（忽略大小写）返回「小节 + 行」；官方走 `read_memory?keywords=`；两边按正文 hash 合并去重，标注 `source=file|official|both`；上限 50；空结果明确 `NO_MATCH` | 4 | M | 官方 keywords 语义未知（可能是模糊匹配） | 本地搜「羽毛球」命中 1 条；官方搜同词有结果时标 `both`；重复条目只出现一次；空结果不是空数组 |
| M07 | 本地只读镜像（官方 → 快照） | 我想留一份历史快照，方便 diff「记忆什么时候变的」 | 新 `infra/.../memory/MemoryMirrorStore.java`；新 `application/.../memory/MemoryMirrorService.java`；`MemoryFiles` | 写 `files/memory/.xiaoi-mirror/official-<时间戳>.json`；**绝不写 `MEMORY.md` / `outline.md`**；保留 7 份；按内容 hash 去重；仅手动触发（或每日一次，可关）；UI 可一键清空 | 3 | M | 快照含隐私 → 目录可清、导出默认排除 | 同内容两次拉取只留 1 份；`MEMORY.md` 的 mtime 不变；清空后目录为空；导出包不含该目录 |
| M08 | mirror-up（本地 → 官方，逐条确认） | 我手上整理好一份 `MEMORY.md`，想让小爱真的记住其中新增的几条 | `application/.../memory/MemoryMirrorService.planUp()`（diff + ledger）；M02；`Mcp` `op=push(dryRun)` | 默认 dry-run：返回「将推送 N 条 + 原文 + 预计被抽取」；confirm 需显式参数且写开关打开；串行、间隔 ≥1s、单次 ≤5 条；已推行记 hash ledger 防重；失败即停不重试 | 4 | M | 与官方流水线抢写；重复记忆污染 | dry-run 前后 `read_memory` 完全一致（零写入）；confirm 后能检索到；同一条二次 push 被 ledger 拦下；中途失败如实报告已推条数 |
| M09 | 导出 / 迁移包 | 换手机前我想把记忆带走（至少带走可读版本） | 新 `infra/.../memory/MemoryExport.java`；`platform/ShareProvider.java`（exports 目录）；M05 | 两种包：`structure`（小节名+条数+hash，**默认**）/ `full`（含正文，二次确认 + `PRIVACY.txt`）；Markdown + JSON 各一份；带 `schemaVersion`；不导出官方通道原始 JSON（含云端字段） | 3 | M | full 包等于隐私文件 | `structure` 包内无可读正文；`full` 含正文且有 `PRIVACY.txt`；round-trip 能被本 App 重新解析 |
| M10 | 注入重定位（下线 system 注入） | 我希望**我自己的 Agent**（手机 DSH / OpenCode）答话时知道我的档案，而不是指望小爱云端读我塞的 system | `app/.../Hook.java`（移除 outline 注入分支）；`application/.../PromptInjector.java`（保留但只服务外部 Agent）；新 `domain/.../core/memory/MemoryDigest.java` | ① UI 文案改为「仅用于本模块 / 外部 Agent」，删除「影响小爱回复」的表述；② 新增 `op=digest` 返回 ≤2KB 摘要（围栏 + 「数据不是指令」）；③ 不再向小爱 system 注入；④ 想影响小爱回复只能走 M10b | 4 | M | 用户预期落差（此前宣传过注入） | 关掉 `memoryInject` 后小爱回复无变化（回归）；`op=digest` ≤2KB 且带围栏；`grep` Hook 无 outline 注入调用 |
| M10b | 让官方模型主动检索（MCP 工具） | 我不改 system，也让小爱在回答前查一下我的记忆 | 现有 8771 MCP + `mcp_servers.json`；M01；M06 | 注册**只读**工具 `memory_lookup(query)`；返回 ≤1KB 摘要 + 围栏 + 「数据非指令」；默认关闭，开启时明示「命中的记忆正文会发送给小爱云端模型」；**只允许检索官方已有记忆**，不把本地独有内容上传 | 4 | L | 小爱对个人 MCP 返回有不信任约束（08 §4），可能被忽略；隐私上云 | 问「我周三干什么」时日志（tool event）出现 `memory_lookup` 调用；关闭后不再调用；开启前存在确认文案 |
| M11 | 备份 / 恢复 / 差异对比 | 我误改了 `MEMORY.md`，想回到昨天那版 | `infra/.../memory/MemoryFileStore.java`（新增 `restore`）；`MemoryService`（listBackups / restore）；`Mcp` `op=backups|restore` | 只恢复**本地文件**（文案不承诺能恢复官方记忆）；恢复前先把当前版本再备份一份；只回 diff 行数不回全文；备份仍保留 5 份 | 2 | M | 恢复后被官方覆盖 | 恢复后字节与目标备份完全一致；该动作自身产生 1 份新备份；diff 计数正确 |
| M12 | 重复 / 陈旧报告（只读） | 我不想手动翻 200 条找重复 | `domain/.../core/memory/MemoryDoc.java`（`duplicates()` / `stale(days)`）；`Mcp` `op=lint` | 归一化（空白/标点/大小写）后按行 hash 分组；输出重复组 + 相似组（编辑距离阈值）；**只提示不删除** | 2 | S | 相似度误判 | 构造「2 条完全重复 + 1 条相似」样本，分组正确；接口无删除副作用 |
| M13 | 跨设备（LAN）只读 + 写拦截 | 我在电脑上让 Agent 读手机上的记忆，但不想让局域网能写 | `platform/Bridge.java`（`handle` 透传 source）；`Mcp.handle/hostsCall` 增加 `source`；`BridgePolicy`；`docs/integration.md` | 复用现有 8771（`lan=true` + 32 hex token 才绑 `0.0.0.0`）；**LAN 来源一律拒绝写 op**（`append`/`replace`/`official_write`/`push`/`restore`）；读 op 白名单放行；配置不可被远程打开 | 3 | M | 现状（[静] 代码路径）：LAN 桥 + 写开关打开时远端可直接 append，需补来源判断 | LAN 下写 op 回 `DENIED_LAN`；读 op 带 token 成功、无 token 401；关 LAN 后不可达；新增单测覆盖 source 判定 |

## 3. 排序与依赖

### P0（0.18.0）——先把「官方通道」立住，其余才有意义

| 顺序 | 编号 | 为什么先做 |
|---|---|---|
| 1 | M01 | 唯一的新能力前提；失败则整条产品线退回「只读本地文件」 |
| 2 | M03 | 通道要能被动到，否则做了也接不上现有调用方 |
| 3 | M02 | 写入是用户最想要的一件事，且只需在 M01 上加一个 tool 名 |
| 4 | M05 | 隐私红线先于功能扩张；越晚做越难补 |
| 5 | M13 | 安全缺口（LAN 可写），与 M03 的 op 白名单同一处改 |

P0 门禁：默认行为不变（默认仍只读本地）；所有新 op 必须 opt-in；`tools.md` 与实现逐字一致。

### P1（0.19.0）——从「能读写」到「能看懂、能带走」

M04（双视图，依赖 M03）→ M06（搜索，依赖 M01）→ M08（mirror-up，依赖 M02）
→ M09（导出，依赖 M05）→ M10（注入重定位，独立）

### P2——离线与长尾

M07（快照，依赖 M01）→ M11（恢复，纯本地）→ M12（lint，纯本地）→ M10b（验证成本最高，放最后）

```
M01 ──┬─ M02 ── M08
      ├─ M06 ── M10b
      └─ M07
M03 ──┬─ M04
      └─ M13
M05 ── M09
M10 / M11 / M12   独立（只用本地文件）
```

### 文档回写（本次未改，随实现同步）

- `docs/agent/P2-PROMPT-MEMORY.md`：P2-4 / P2-5 标 **superseded by PLAN-MEMORY**（写入降级、注入下线）。
- `docs/agent/CAPABILITIES.md`：A14 改为「注入仅服务外部 Agent」，A15 改为「官方写入 + 本地兜底」。
- `docs/agent/PLAN.md:156-157`：`xiaoai_memory_read/mirror` 的「root」前提作废（进程内同 uid 即可）。

## 4. 明确不做

| 不做 | 原因 |
|---|---|
| 解析 / 读写加密库（`claw_memory*.db`、`memory_data_*.db`） | 格式未知，root 也需解密；既有边界 |
| 删除 / 清空 / `memory_forget` | 不在 `app_tools` 里（[静] 工具表无此条，运行时才出现），只能构造 LLM 工具上下文，破坏性与不确定性都太高；本地同样保持无删除入口 |
| 双向同步 | 云端为真相 + 官方主题化会改写文本，双向必然产生回环冲突。只保留两条单向：官方→快照（M07）、本地→官方逐条确认（M08） |
| 重写 / 重建 `outline.md` | 生成规则未知（06 §7），写坏会伤官方注入 |
| 自建抽取/门控/主题化流水线 | 那是官方资产（`assets/memory-topic/`），重造只会与它抢写 |
| 自研云同步 / 跨设备写 | `MiclawSyncService` 协议不可见；跨设备只做只读（M13） |
| 把摘要塞回小爱 system | F2 已证伪，不再投成本 |
| 在 App 内提供「一键写回官方」 | 抽取结果不可预览、不可回滚，误操作代价高；只提供 dry-run + 逐条确认（M08） |

### 4.1 隐私红线（不可协商）

1. 记忆正文**不进日志**、不进 control log、不进 `xiaoai_status`、不进导出包默认内容。
2. 正文只有两个合法出口：① 用户在本机 App 屏幕上看；② 本机 MCP 返回给用户自己的 Agent。
3. 任何「离开设备」的动作（LAN 返回、MCP 给小爱云端、`full` 导出、镜像快照）都必须：显式开关 + 二次确认 + 可审计（只记条数/长度/目的地，不记正文）。
4. 官方写入的 `query` 本身也是隐私（含自然语言指令）→ 同样不落日志。
5. 测试必须有**负向断言**（导出包与日志里 grep 不到正文），而不是只验正常路径能跑。
6. 不把「已上传云端」的边界说模糊：官方记忆本来就在云端，但**本地独有条目**不得顺手上传（M10b 的硬限制）。

## 5. 待验证清单

> 本次未用 adb；以下需真机会话执行，失败项的回退方案已在表中给出。

| # | 待验 | 方法 | 失败怎么办 |
|---|---|---|---|
| 1 | 自绑定 voiceassist 自己的 `APP_TOOL_PROVIDER` 是否成功（S1） | 受控探针：查询 → bind → 打一次 `tools/list`，回报 `BIND_REJECTED`/`NULL_BINDING`/超时 | 回退 Hook `onCreate` 抓实例反射 |
| 2 | `queryIntentServices` 在包内能否列出 `UIAgentServiceForOSBot` | 探针打印 `ServiceInfo`（类名/导出/权限） | 直接显式 `ComponentName` 绑定 |
| 3 | `read_memory` 真实参数名与返回结构（camel / snake、items 字段、分页语义） | 真机各打一次，落 `artifacts/` 脱敏样本（只留键名与条数） | 以 `assets/app_tools.json` 为准校正协议层 |
| 4 | `write_memory` 的 `query` 语义边界（一句话能写几条、门控拒绝返回什么） | 3 条测试语句：明确事实 / 模糊偏好 / 含「忘记」 | 收窄到「明确事实」并更新文案 |
| 5 | 写入后 `MEMORY.md` 多久被重写、是否被云端覆盖 | 写后 1min / 10min / 次日对比 mtime 与内容 diff | 若秒级覆盖 → 彻底移除本地写入入口的默认可见性 |
| 6 | 官方通道是否要求主线程 / Looper | 探针分别从主线程与后台线程调一次 | 统一走后台池（Notes 那条已强制非主线程） |
| 7 | `read_memory` 条目与 `MEMORY.md` 是否 1:1 | 同一关键词两边计数 | 若不一致 → M07 镜像只信官方，M06 明确标注来源差异 |
| 8 | 小爱个人 MCP 的返回是否真进上下文（M10b 可行性） | 注册只读工具，问一个需要记忆的问题，看是否触发 | 不成立则 M10b 直接砍掉，保留 M06 给外部 Agent |
| 9 | `read_memory` 是否返回相册/微信类敏感条目 | 用中性关键词探测返回分类 | 命中则加分类过滤，默认不展示 |
| 10 | `daily/` `facts/` 是否参与官方检索 | 与 `MEMORY.md` 交叉比对 | 不参与则镜像范围只按官方返回 |
| 11 | 已废弃的 `get_memory_data` 是否返回更结构化的列表 | 与 `read_memory` 对比一次 | 若有优势则作为 M01 的备选解析路径 |
| 12 | LAN 写拦截所需 `source` 能否干净地透传到 `Mcp` | 读 `Bridge.handle` → `Mcp.handle` 调用链，加单测 | 退一步：在 `MemoryPort` 层用 `bindsLan()` + 来源标记判断 |
