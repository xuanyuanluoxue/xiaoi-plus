# 风险、开放问题与回滚

> 状态：**规划中，未实现**。本文是安全评审与决策的输入，不是结论文档。
> 关联：[architecture.md](architecture.md)、[workpackages.md](workpackages.md)、[migration.md](migration.md)。

## 1. 风险登记表

概率/影响：H/M/L。负责人是 Agent 角色，不是具体人。

| ID | 风险 | 概率 | 影响 | 缓解 | 负责人 |
|---|---|---|---|---|---|
| R01 | exported Provider 的 UID 校验写漏（只校验 `call()`，漏 `query/openFile`），被本机应用当成 confused deputy | M | H | `UidGuard` 在 Provider 所有入口第一行执行；单测覆盖每个 override；安全评审专项 | 开发 Agent B / 安全评审 |
| R02 | Provider 被任意应用高频调用，导致 App 进程被反复拉起（DoS/耗电） | M | M | 未授权调用在 `getCallingUid` 后立即抛异常，不读文件、不分配大对象；系统层面无法完全阻止，记录并接受 | 开发 Agent B |
| R03 | Provider 返回完整配置（含 apiKey）给 voiceassist UID，但 voiceassist 进程本身被第三方代码注入 | L | H | 威胁模型不含「已在 voiceassist 内执行代码」；但仍最小化返回字段、不返回 token 给非必要调用 | 架构 Agent |
| R04 | HMAC 被透明 TCP 中继绕过（占 8771 转发到 8772） | L | H | TH 绑定 `port + instanceId`；中继改不了 `serverProof`；单测覆盖端口篡改 | 开发 Agent A |
| R05 | `/auth/begin` 的 `clientProof` 成为离线口令验证器，弱 token 可被爆破 | M | H | 新 token 强制 32 hex（128 bit）；旧弱 token 只警告；begin 限流；文档声明残余风险 | 安全评审 / 开发 Agent A |
| R06 | 用户把旧短 token / 复用密码带进 0.12 | M | H | 保存时检测长度/字符集；UI 标「弱令牌」；提供一键轮换 | 开发 Agent C |
| R07 | 岛配对码被旁观/通知读取，或 6 位码被暴力尝试 | L | M | 5 分钟、3 次、一次性、常数时间比较；仅 loopback；失败即销毁 | 开发 Agent C |
| R08 | 复制 MCP 连接信息把 token 放进剪贴板，被其他应用读取 | M | M | 保留现状但在复制前提示；0.13 评估「只复制 URL + 一次性令牌」 | 开发 Agent C |
| R09 | LAN 模式是明文 HTTP，同 Wi-Fi 可嗅探 Bearer | M | H | 显式开关 + 非空 token + 文档警告；默认关闭；不推荐公共 Wi-Fi；未来 TLS | 安全评审 / 架构 Agent |
| R10 | 日志/错误/导出泄露 apiKey 或 token | M | H | 全链路脱敏测试；`Logs` 只存 model/端口/布尔；导出默认 `***`；异常消息过滤 | 开发 Agent C / 验证 Agent |
| R11 | `config.json` 在 `Android/data`，可被 adb/MANAGE_EXTERNAL_STORAGE 应用读取 | M | H | Android 11+ 普通应用不可读；文档标注为敏感；0.13 评估私有目录 + 导入导出 | 架构 Agent |
| R12 | MIUI 杀 App 进程导致 Provider 不可用，桥只能用陈旧缓存 | H | M | target 缓存 + 60 s 重试；UI 明示「控制面不可用」；电池白名单引导；不做静默覆盖 | 开发 Agent B |
| R13 | `ContentObserver` 跨应用不触发/被节流，配置长时间不生效 | M | M | 60 s 轮询兜底；保存后 UI 显示 `PENDING(rev)`；提供「立即重载」命令 | 开发 Agent B |
| R14 | 主进程判定错误（`processName` 为空/别名），导致没有桥 | L | H | 以 `lp.processName` 为准；为空时回退 `Application.getProcessName()`；真机覆盖三进程；注册表显示 `dataPlane=none` | 开发 Agent B / 验证 Agent |
| R15 | HMAC 实现错误（nonce 复用、字段歧义、HKDF 错误） | M | H | RFC 4231/5869 向量；transcript 快照；篡改矩阵；禁止自写 SHA/HMAC | 开发 Agent A / 安全评审 |
| R16 | UI 与 MCP 同时写配置，`rev` 冲突导致丢失更新 | M | M | `writeConfig(expectedRev)` 乐观锁；冲突返回 409；UI 提示重新载入 | 开发 Agent B |
| R17 | 迁移/原子写失败写坏唯一事实源 | L | H | tmp + fsync + `.bak`；失败保留原文件；迁移失败只读；测试断言字节不变 | 开发 Agent B |
| R18 | 关闭 MCP 后 socket 未真正关闭（accept 线程/连接池仍持有） | M | H | `close()` + 置空 + `sStarted=false`；测试 `connect refused`；真机 `ss` 验证 | 开发 Agent B |
| R19 | Binder 事务超限（provider 多、配置大） | L | M | 配置上限（provider ≤20、model ≤100、JSON ≤64 KB）；超限拒绝保存并提示 | 开发 Agent B |
| R20 | 页面拆分引入回归（草稿、旋转、主题、冲突条） | M | M | 先 controller 后 view；每步真机冒烟；保留 `onSaveInstanceState`；WP8 P0 | 开发 Agent D |
| R21 | CI 门禁被绕过：tag 用临时密钥仍建 Release | M | H | `signed=true` 才允许 Release；`verify-apk.sh` 校验证书指纹；负向测试 | 开发 Agent E |
| R22 | 降级回 0.11 恢复「向所有端口发 Bearer」，用户不知情 | M | H | 降级文档显著警告；legacy 镜像只为可用性；0.13 移除 | 主 Agent |
| R23 | 契约基线被全量重存，掩盖真实回归 | M | M | 基线 diff 必须逐条解释；CI 禁止自动 `--save-baseline` | 验证 Agent |
| R24 | 60 s 轮询 + 日志推送造成耗电 | L | M | 轮询只在进程活着时；日志批量 50 行/2 s；屏幕关闭降频 | 开发 Agent B |
| R25 | `safe_mode` 误触发，桥永久不启动 | L | M | 熔断阈值 3 次/10 分钟；UI 一键退出；日志记录触发原因 | 开发 Agent B |
| R26 | `/health` 仍接受 Bearer，成为在线 token 猜测接口 | M | M | 保持契约但加限流（20/min）；错误统一 401；token 128 bit | 开发 Agent A |

---

## 2. 安全评审重点

### 2.1 攻击面清单

| 入口 | 鉴权 | 能读到 | 能写到 | 最坏情况 | 评审问题 |
|---|---|---|---|---|---|
| `BridgeControlProvider.call/query/...` | `Binder.getCallingUid()` == voiceassist UID | 完整配置（含 apiKey/token） | config.json（经 `writeConfig`） | UID 校验绕过 = 全量泄露 | 每个 override 是否都校验？UID 缓存是否会过期/串号？ |
| `Messenger` 回调（桥注册） | 仅 App 进程持有 binder | 桥状态/日志 | 重载/弹岛/flush | App 被攻破 = 控制桥 | binder 是否会被转发？死亡监听是否清理？ |
| `GET /hello` | 无 | proto/port/instance/startedAt | 无 | 端口/进程指纹 | 是否返回了多余字段？ |
| `POST /auth/begin` | 客户端 HMAC | 无 | 会话 | 离线 token 验证器 / 暴力 | 限流是否生效？token 熵是否足够？ |
| `POST /mcp` | HMAC 或 Bearer | 配置摘要（无 key） | 模型配置（经 Provider） | token 泄露 = 远程控制 | Bearer 是否只在显式配置后使用？ |
| `GET /logs` | HMAC/Bearer | hook 日志 | 无 | 端点/模型泄露 | 是否含 apiKey/对话正文？ |
| `GET /stats` | HMAC/Bearer | 调用量 | 无 | 使用模式泄露 | 是否含模型名？可接受 |
| `GET /cli` | HMAC/Bearer | CLI 输出 | 驱动小爱 | 远程执行 | 与 `/mcp` 同权；LAN 是否必须显式？ |
| `POST /island` | loopback 免 token | 模型名 | 弹提示 | 本机骚扰 | 是否需要限流/鉴权？ |
| `POST /config` | 无（410） | 无 | 无 | 无 | 是否真的不再解析 body？ |
| `POST /pair` | 一次性码 | 无 | target 缓存 | 抢占配对 | 失败次数/过期/一次性是否严格？ |
| `ShareProvider` | `exported=false` + grant | 导出文件 | 无 | 分享泄露 | 是否只暴露 exports 目录？ |
| `LogsActivity` 分享 | 无 | 本地日志 | 无 | 用户主动分享 | 默认是否脱敏？ |

### 2.2 HMAC 设计评审清单

- [ ] **规范化**：transcript 字段顺序固定、分隔符固定、所有变长字段用 base64url/hex；
      不存在「字段值含 `\n`」导致的歧义。
- [ ] **域分离**：`K_id` 与 `K_mac` 用不同 `info`；`C0/S2C/request` 前缀不同；
      不能把服务端 proof 当请求 MAC 复用。
- [ ] **密钥派生**：HKDF-Extract 的 salt 含双方 nonce；`K_mac` 绑定 `sid`；
      token 轮换后所有会话失效。
- [ ] **随机性**：`SecureRandom`；nonce 32B、sid 16B；禁止 `Math.random`/时间戳当 nonce。
- [ ] **时间窗**：±120 s；同一设备时钟；超窗拒绝而不是自动放宽；记录 `clock_skew`。
- [ ] **重放**：每会话 nonce LRU；`ts` 也在 MAC 内；同 nonce 第二次拒绝。
- [ ] **常数时间**：`MessageDigest.isEqual`；比较定长 32B；不先做 `String.equals` 短路。
- [ ] **会话**：绝对 600 s、空闲 120 s、每端口最多 8 个；失败 5 次销毁；
      sid 不可预测；会话表不落盘。
- [ ] **错误统一**：所有失败 401 + 同一 body；不返回「sid 不存在 / MAC 错 / 过期」。
- [ ] **限流**：begin 20/min、200/h；超限 429；限流状态不跨进程共享（单桥）。
- [ ] **降级**：0.12 App 不因桥旧而回退 Bearer；`/config` 410 不解析 body。
- [ ] **秘密不外泄**：token 不出现在 URL、日志、异常、响应、`/hello`；
      测试用固定 token 扫描所有序列化字节。

### 2.3 Bootstrap 评审

| 问题 | 结论/要求 |
|---|---|
| Provider 是否会被非 voiceassist 应用调用成功？ | 必须不能；UID 校验在解析参数前 |
| UID 缓存是否会被 reinstall 改变？ | 缓存 60 s；每次调用可重新查询；失败即拒绝 |
| 配对码是否可被本机应用爆破？ | 6 位 + 3 次/5 分钟 + 一次性；失败即销毁；仅 loopback |
| 岛内容是否会被 NotificationListener 读取？ | 可能；配对码本身一次性且短时有效；接受残余风险 |
| 无岛设备怎么办？ | 提示打开 App（Provider）/ADB；不开放匿名 `/config` |
| ADB 注入是否扩大攻击面？ | 仅 shell UID 可用的显式命令；不新增 exported 组件 |

### 2.4 LAN 评审

- `mcp.lan=true` 时必须同时满足：`mcp.enabled=true`、token ≥128 bit、用户显式保存。
- `/config`、`/pair` 永远只接受 loopback；`/island` 保持 loopback（LAN 403）。
- `/mcp`、`/cli`、`/logs`、`/stats` 在 LAN 下可用，但必须 Bearer；无 TLS 是已知限制。
- 不在 LAN 下暴露 `xiaoai_switch_model` 的 token 修改能力（禁止远程轮换 token）。
- 局域网 URL 只显示私有 IPv4；不主动扫描网段。
- 评审需确认：IPv4-mapped IPv6（`::ffff:127.0.0.1`）是否被 `isLoopbackAddress()` 正确覆盖；
  `adb forward` 进来的连接算 loopback，这是预期。

### 2.5 日志/导出评审

- 日志字段白名单：`t/level/msg`；`msg` 只允许固定模板 + model/port/布尔。
- 禁止把 `Json.write(config)`、请求体、异常堆栈原样写日志。
- 导出：默认脱敏 `***`；含密钥导出二次确认；分享默认脱敏。
- `config.json.corrupt.<ts>` 含密钥，必须与主文件同权限，且 UI 提示「含密钥」。
- 日志页分享前显示「可能含端点/模型名」；不包含 token/apiKey。

---

## 3. 开放问题（需用户/主 Agent 拍板，≤8）

| # | 问题 | 选项 | 推荐 | 理由 | 截止 |
|---|---|---|---|---|---|
| Q1 | 控制面用 exported Provider 还是 bound Service？ | A Provider；B Service；C 两者 | **A** | `call()` 返回 Bundle 简单；UID 校验点集中；Service 生命周期在 MIUI 上更不稳 | WP3 开工前 |
| Q2 | `/config` 是彻底 410 还是保留 Bearer 一个版本？ | A 410；B 保留但只写 mcp 字段 | **A** | 保留就是第二写者；审计核心泄露面；410 + hint 可行动 | WP3 开工前 |
| Q3 | 多进程：主进程单桥还是每进程桥？ | A 每进程；B 主进程 | **B** | 单端口/单会话/单注册；A 会重现三份状态 | WP3 开工前 |
| Q4 | MCP 开启是否强制 token 非空并自动生成？ | A 强制；B 允许空 token 但只 loopback | **A** | 空 token 没有安全意义；自动生成消除首次配置阻塞 | WP2 开工前 |
| Q5 | 日志用 Provider 推送还是保留独立诊断端口？ | A Provider；B 独立 loopback 只读端口 | **A** | B 与「MCP 关闭无端口」冲突；A 可离线/批量 | WP5 开工前 |
| Q6 | 令牌轮换是立即失效还是 60 s 宽限？ | A 立即；B 宽限 | **A** | 宽限让「已轮换」变谎言；轮换是显式操作 | WP2 开工前 |
| Q7 | 0.12 是否对 `/config`/MCP 写请求做 payload 加密？ | A 不做；B AES-GCM | **A** | 新架构 HTTP 不再承载秘密；加密增加兼容/性能成本 | WP3 开工前 |
| Q8 | 配置损坏时是否自动用 `.bak` 恢复？ | A 提示后手动；B 自动 | **A** | 自动会静默丢弃用户刚 push 的文件；损坏必须可见 | WP2 开工前 |

> 若 Q1 选 B（Service），R01 的评审点改为「`onBind` 返回的 binder 是否被任意应用拿到」，
> 并需要额外验证 MIUI 后台绑定限制；其余设计不变。

---

## 4. 回滚与熔断

### 4.1 分层回滚

| 层 | 触发 | 动作 | 数据影响 |
|---|---|---|---|
| 单个 WP | 评审不通过/测试红 | `git revert` 该 WP 的提交 | 无（未发布） |
| beta 版本 | P0 失败/崩溃 | 发 beta.N+1；旧 beta 标 prerelease | 配置向后兼容 |
| 正式版 | 严重安全问题 | 隐藏 Release；指导 `adb install -d` 回 beta.4 | 降级会恢复旧漏洞，需公告 |
| 配置 | 迁移/编辑损坏 | 用 `.bak` 或用户备份恢复 | 可能丢失最后一次修改 |
| 数据面 | 端口无法关闭/被滥用 | `safe_mode=true`，桥不监听 | 模型重定向保留 |

### 4.2 熔断条件（自动）

满足任一，桥进入 `safe_mode`，只保留 Hook：

1. 同一进程 10 分钟内 3 次未捕获异常导致 `BridgeLifecycle` 重启；
2. `startServer` 连续 10 次失败（端口/权限异常）；
3. Provider 调用连续 20 次超时且 target 缓存不可用；
4. 配置 `rev` 连续 5 次冲突（疑似双写者）。

`safe_mode` 的行为：

- `BridgePolicy.shouldListen=false`；
- 不注册数据面实例；仍注册 hook 状态；
- UI 显示「安全模式：MCP 已停用」+「退出安全模式」按钮；
- 退出需用户确认，并清除 `safe_mode` 标记。

**为什么自动熔断**：桥在目标进程内，任何未捕获的循环/异常都可能拖垮小爱；
宁可停 MCP，也不能影响模型重定向。

### 4.3 发布回滚检查

- tag 不删除、不重签；只发更高 versionCode。
- Release Notes 顶部标注「已知问题，请用 x.y.z」。
- 回滚包必须同签名；否则用户只能卸载重装（丢配置）。
- 回滚后必须重启小爱，避免新旧桥代码并存。

---

## 5. 与 `0.11.0-beta.4` 的差异清单

| 维度 | beta.4 现状 | 0.12 目标 | 用户可见变化 |
|---|---|---|---|
| 端口发现 | 8771~8775 每个端口带 Bearer `/health` | 注册表 + `/hello` + HMAC | 首次连接略慢；不再有「端口猜错」 |
| Token 传输 | 发现在网络上发送 | 永不上网；Provider/HMAC | 抓包看不到 token |
| 配置投递 | `POST /config`（含 apiKey） | Provider pull + `notifyChange` | 保存不再依赖小爱在线 |
| 保存反馈 | 可能假失败「令牌不一致」 | 「已保存 rev N / 已应用 rev N」 | 不再误报 |
| MCP 关闭 | 只回 loopback，端口仍在 | 关闭全部监听 | `ss` 看不到 877x |
| 重启保持 | target prefs 硬编码 bridge=true | 缓存带 rev，缺字段不猜 | 关闭后重启仍关闭 |
| 多进程 | 每进程一个桥 | 主进程单桥 | 只有一个端口 |
| Token 轮换 | 新→旧→上次成功，可能死锁 | 写 rev，会话立即失效 | 轮换一次成功 |
| 运行时确认 | App 探针可伪造 | 只有桥能写 | 「已确认」可信 |
| 模型校验 | 成功即「已确认」 | 只改「端点可达」 | 文案更准确 |
| 暂停 | 模型卡整体锁死 | 可编辑，仅禁校验 | 可以先改再启用 |
| 日志 | 依赖 MCP `/logs` | Provider 推送 + 本地 | 关 MCP 也能看 |
| 配置损坏 | 静默回默认 | 只读 + `.bak` 恢复 | 不会悄悄清空 |
| 导入 | 直接载入 | diff + 二次确认 | 多一步，但不会误覆盖 |
| LAN | 开关只改草稿 | 脏状态 + 保存后重绑 | 不再误解「已生效」 |
| `/config` | 200/401 | 410 Gone | 旧脚本需改 MCP |
| schema | v2 | v3（rev/backup） | 文件多两个字段 |
| 发布门禁 | CI 不跑一致性；release 不验签 | 全部强制 | 发布更慢但可信 |
| 安全姿态 | loopback 视为可信 | loopback 不可信，UID/HMAC 证明 | 本机应用拿不到 token |

---

## 6. 最容易被安全评审打穿的点（自评）

按「被攻破后的影响 × 被发现概率」排序。评审时应优先攻击这五处。

### 6.1 exported Provider 的 UID 校验（最高危）

攻击：任意应用 `content call --uri content://plus.xiaoi.module.control --method pullConfig`。
如果实现只在 `call()` 校验，而 `query()`/`openFile()` 没校验，或 UID 缓存把
`Binder.getCallingUid()` 与 `Process.myUid()` 混用，就会直接吐出 apiKey/token。
**必须**：所有 override 第一行 `UidGuard.requireVoiceassist()`；单测/真机用 shell UID 2000 验证；
拒绝路径不读文件、不拼 JSON、不记敏感日志。

### 6.2 HMAC 的「离线验证器」

`clientProof = HMAC(token, 已知串)` 对任何抓包者都是一个离线口令验证器。
如果用户沿用 6 位/8 位弱 token，或旧 token 很短，可在本地高速爆破。
**必须**：新 token ≥32 hex；保存时拒绝短 token；UI 标弱 token；begin 限流；
文档明确「128 bit 是安全前提」。PAKE 是根本解，但本周期不做。

### 6.3 降级/兼容路径重新引入 Bearer 广播

为了「旧 App 能用」，如果 0.12 App 在握手失败后回退到「带 Bearer 扫描」，
或 0.12 桥保留空 token `/config`，审计第 1/2 条会原样复活。
**必须**：新 App 永不回退 Bearer；`/config` 410；空 token 不监听；
`check-consistency.sh` 用 grep 把这条写成门禁。

### 6.4 局域网 `/island` 与 loopback 判定

`isLoopback()` 如果只比较 `127.0.0.1` 字符串，可能漏掉 `::1` / `::ffff:127.0.0.1`；
反过来，`adb forward` 进来的连接确实是 loopback，不能误杀。
**必须**：用 `InetAddress.isLoopbackAddress()` + 显式 IPv4/IPv6 测试；
LAN 下 `/island` 必须 403；`/config`、`/pair` 永远 loopback。

### 6.5 「已确认」仍然可以被页面伪造

即使引入了 `StateModel`，如果 `HomeActivity` 仍在 `testModel()` 成功回调里传
`runtimeActive=true`，或把 `D2=OK` 映射成「运行中」，用户仍会被骗。
**必须**：`StateModel` 的 D4 只接受 `RuntimeStatus`（桥来源）；单测断言
`probe=OK` 不改变 D4；代码评审搜索所有 `runtimeActive` 赋值点，只允许 Provider 回调。

### 6.6 次高危：Provider DoS 与进程拉起

任何应用都能调用 Provider（UID 校验发生在进程内）。虽然拿不到数据，但可以让
App 进程反复启动/占用 CPU。当前无系统级解法；只能做到「拒绝路径极轻」
（不读文件、不打日志、不创建对象），并在风险表接受。评审若要求更强，
可评估 `android:permission` + `protectionLevel="signatureOrSystem"` 是否能在
LSPosed 场景下由 voiceassist 持有（大概率不可行，因为签名不同）。

---

## 7. 评审记录模板

安全评审 Agent 每条意见按以下格式提交，主 Agent 逐条裁决：

```text
[SEC-编号] 标题
攻击路径：
前置条件：
影响：
复现步骤/命令：
建议修复：
对应风险 ID：
裁决：接受 / 修复 / 延后（必须写理由与截止版本）
```
