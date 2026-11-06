# Agent 能力层与记忆系统计划（D1）

> 状态：**Phase 1 已发布 `v0.13.0-agent.1`（WP1.1~WP1.5，1453 断言，prerelease）**
> 目标版本：`0.13.0-agent.1`（已发布，beta 验证中）
> 发布：<https://github.com/xuanyuanluoxue/xiaoi-plus/releases/tag/v0.13.0-agent.1>
> 基线：`0.12.0-beta.1`（versionCode 16）
> 日期：2026-11-06
> 关联：`docs/refactor/PLAN.md`（0.12 核心重构）、`skill/xiaoi-mcp/SKILL.md`

## 1. 背景

xiaoi-plus 现在是一个“小爱能力桥”：App 控制面 + 小爱进程内的 MCP 数据面，提供
`xiaoai_submit`、`xiaoai_status`、`xiaoai_config`、`xiaoai_switch_model`、
`xiaoai_logs`、`xiaoai_stats`、`xiaoai_island`、`xiaoai_cli` 八个工具。

问题：

- 数据面寄生在小爱进程；小爱不运行就没有 MCP。
- 所有能力都经过小爱的 agent，缺少结构化的设备动作。
- 记忆系统没有暴露；Agent 无法跨会话记住用户偏好。
- 错误、确认、幂等、审计、分权都还比较原始。

本项目的主要使用者是 Agent，不是人。因此下一阶段的重点不是“再堆几个工具”，
而是做一层稳定的 **Agent Action Plane**：可直接执行的动作、可读写的记忆、可发现的
能力、可确认的危险操作。

## 2. 目标与非目标

### 2.1 目标

1. App 前台服务承载主 MCP 端点 `8770`；小爱不运行时，直接动作仍可用；Agent 长期记忆由 PC 持有。
2. 第一批 5 个动作：`app.open`、`device.info`、`alarm.set`、`notification.send`、
   `media.control`。
3. **手机端默认无状态**：Agent 记忆真相在电脑 Agent；手机只接收本次上下文并执行任务。
   小爱记忆只做可选读取/镜像，不作为 Agent 记忆的真相源。
4. 危险动作走 MCP 确认；超级岛作为用户可见提醒；无通知能力时退化为 confirm token + 轮询。
5. 能力发现、结构化返回、稳定错误码、幂等键、审计、权限分级。
6. root / Shizuku 可选；按动作所需权限开放功能，没有特权时降级而不是崩溃。
7. 开机自启；前台服务带常驻通知，可暂停/停止。

### 2.2 非目标

- 不重做小爱的 ASR/TTS/唤醒词。
- 不做支付、转账、账号安全、风控绕过。
- 不把任意 shell、任意 CLI、任意 URL 暴露给 Agent。
- 不把反编译的小米代码/资源打包分发。
- 不承诺小爱记忆的云端同步协议；只做本地互操作和可验证的镜像。

## 3. 已确认决策

| 编号 | 决策 |
|---|---|
| D1 | App 前台服务承载 Action Plane，开机自启 |
| D2 | `8770` 为主端点；`8771` 保留兼容，App 可代理回退 |
| D3 | 第一批 5 个动作按建议执行 |
| D4 | 小爱记忆写入：root + 备份 + 打标 + 只删 Agent 条目；仅作可选镜像 |
| D5 | 危险动作走 MCP 确认；超级岛作为提醒；不支持时用 confirm token |
| D6 | root / Shizuku 可选，按动作声明权限 |
| D7 | **PC Agent 是记忆真相**；手机端默认无状态，不持久化 Agent 长期记忆 |
| D8 | 8770/8771 共用**一个 API key**；LAN 默认关闭，降低泄露面 |
| D9 | Shizuku 只预留接口；第一版 root + Android，不引入 Shizuku 依赖 |
| D10 | 功能按权限开放：能力表显示所需 Android/root/Shizuku 权限 |

### 3.1 Phase 0 结论（2026-11-06）

详见 `docs/agent/PHASE0-REPORT.md`：

- 小爱 CLI handler 只有 `session.*` / `agent.submit` / `consent.respond` / `auth.*` /
  `asr.*` / `tts.*` / `schema.commands`；**没有本地设备动作**。
  `device.*` 是多设备互联 facade，不是打开应用/闹钟/通知。
  → 5 个动作必须走 Android/root。
- `agent.submit` 需要 `sessionId` + `text`，session 归属当前 CLI 连接；
  当前 `xiaoai_submit` 不保证返回 sessionId → Phase 1 必须返回并管理。
- 超级岛可用，但 `actions` 为空，只能提醒；右侧大字岛文本约 22 字符。
- 小爱记忆 DB 是加密/自定义格式，不是明文 SQLite；`MEMORY.md` 是明文 Markdown
  （约 17KB、63 条、分节），读取需要 root。→ 只读先做 Markdown，不碰 DB。
- Shizuku 覆盖 shell 命令（settings/input/am/media_session），读不了 `/data/data`；
  记忆读写只能 root。SELinux Enforcing，root 写文件需修 owner + context。

## 4. 总体架构

```text
Agent / CLI
    │  MCP JSON-RPC (8770)
    ▼
App Foreground Service
├── McpServer（复用 Mcp 协议层，Host 改为 AppHost）
├── CapabilityRegistry
├── ActionRegistry
│   ├── AndroidExecutor
│   ├── RootExecutor
│   ├── ShizukuExecutor（可选）
│   └── XiaoAiProxy ──────────────► 127.0.0.1:8771（兼容/复杂任务）
├── MemoryAdapter（可选）
│   └── XiaoAiMemoryAdapter（只读/镜像；PC Agent 才是记忆真相）
├── ConfirmationManager
└── AuditLog
```

### 4.1 进程与端口

- App 进程：`ActionService`（前台服务），监听 `8770`。
- 小爱进程：保留现有 `8771`，仅用于兼容和 `xiaoai_submit` 等小爱专属工具。
- App 的 `8770` 默认只绑 loopback；LAN 需要单独开关，默认关闭。
- `8770` 与 `8771` 共用**一个 API key**，降低配置成本；安全靠 8770 默认 loopback、
  LAN 关闭、动作白名单和危险动作确认，而不是多套 token。
- skill/连接信息可以带 key，但只能生成本机临时文件；不得提交到 Git 或公开分享。
- 旧客户端继续用 `8771`；新 Agent 文档只推 `8770`。

### 4.2 生命周期

```text
BOOT_COMPLETED
  └─ ActionService.startForeground()
       ├─ 读 config / token
       ├─ 启动 8770（若 action_plane=true）
       ├─ 初始化 Executors（root/Shizuku 按可用性）
       └─ 常驻通知：运行中 / 暂停 / 打开 App

App 打开
  └─ 若服务未运行且 action_plane=true，启动服务

用户停止
  └─ 设置 stopRequested=true，停止监听；开机不自动恢复，直到用户再次开启
```

- Android 12+ 用 `startForegroundService`，5 秒内 `startForeground`。
- Android 14+ 声明前台服务类型（建议 `specialUse`，带 subtype 说明“本机 Agent 能力服务”）。
- Android 13+ 需要 `POST_NOTIFICATIONS` 运行时权限。
- HyperOS 需要用户在系统设置里允许自启动、关闭电池优化；App 内给出引导。
- `START_STICKY` 只用于异常被杀；用户主动停止不自动重启。
- 首次开启需要一次性授权：通知权限、自启动引导、电池优化白名单、root（可选）、
  LAN（可选）。没有完成授权时，能力表要显示对应动作不可用。

### 4.3 线程与并发

- MCP 请求处理走有界线程池；动作执行走 `AppExecutors.io` 或专用有界池，
  禁止裸 `new Thread`，禁止在主线程做 I/O。
- Root/Shizuku 调用串行化，避免同时弹多个授权框或竞争系统命令。
- 小爱记忆写入全局单写者；同一时间只允许一个写事务。
- 长动作异步化（jobId），队列满时返回 `BUSY`，不无限排队。
- 前台服务不持有 wake lock；依靠前台通知和系统调度。

## 5. Agent 接口

### 5.1 工具清单

| 工具 | 作用 |
|---|---|
| `xiaoai_capabilities` | 列出动作/记忆能力、可用后端、风险、schema、示例 |
| `xiaoai_action` | 执行一个动作（通用入口，参数含 `action` + `args`） |
| `xiaoai_confirm` | 确认/拒绝待执行动作 |
| `xiaoai_notify` | 提醒：优先超级岛，或普通通知 |
| `xiaoai_memory_read` | 读小爱记忆（root，只读，可选；PC Agent 不依赖它） |
| `xiaoai_memory_mirror` | 显式把小爱记忆镜像到本地/回写；实验性，root |
| `xiaoai_ask` | 回退到小爱 `xiaoai_submit`；小爱不在时返回 `XIAOI_OFFLINE` |
| `xiaoai_job_status` | 长任务状态（Phase 3） |
| `xiaoai_audit` | 读审计摘要（admin） |

第一批动作使用 `xiaoai_action` 通用入口，避免工具列表爆炸；schema 通过
`xiaoai_capabilities` 暴露。后续如果客户端对 typed tools 更友好，可再拆。

### 5.1.1 本地 CLI

给 shell/脚本 Agent 一个与 MCP 共用 ActionRegistry 的 CLI：

```bash
xiaoi status
xiaoi capabilities [--json]
xiaoi action app.open --package com.tencent.mm
xiaoi action alarm.set --hour 7 --minute 0
xiaoi memory read [--section 用户画像]
xiaoi memory mirror --dry-run
xiaoi ask "把刚才那段话整理成待办"
```

Agent 长期记忆不放在手机上；`memory read/mirror` 只是可选的“小爱记忆”互操作。

- CLI 只连接 `127.0.0.1:8770`（或 `XIAOI_MCP_URL`），不直接读手机文件。
- token 从 `~/.config/xiaoi/token` 或环境变量读取，不打印。
- `--json` 输出与 MCP 返回同一结构，便于脚本解析。
- CLI 不实现第二套动作逻辑，避免 MCP/CLI 行为漂移。

### 5.1.2 公共参数

| 参数 | 类型 | 说明 |
|---|---|---|
| `context` | string/object | PC Agent 传入的本次记忆/任务上下文，≤8KB |
| `sessionId` | string | 复用小爱会话；不传则按动作需要新建 |
| `idempotencyKey` | string | 写动作必填；24 小时内重复键返回首次结果 |
| `dryRun` | boolean | 只校验/返回计划，不落盘 |
| `async` | boolean | 长动作返回 `jobId` |
| `timeoutMs` | int | 可选；服务端上限内生效 |
| `clientId` | string | 审计标签，不作为安全边界 |

### 5.2 返回信封

```json
{
  "ok": true,
  "action": "app.open",
  "executor": "android",
  "result": {"package": "com.tencent.mm"},
  "warnings": [],
  "elapsedMs": 120,
  "requestId": "req_...",
  "auditId": "aud_..."
}
```

部分成功时 `ok=true` 但 `warnings` 非空，例如“动作已执行，但超级岛提醒失败”；
不要把部分成功伪装成完全成功。

错误：

```json
{
  "ok": false,
  "code": "PERMISSION_DENIED",
  "message": "需要 root 才能读取小爱记忆",
  "action": "memory.list",
  "retryable": false,
  "requestId": "req_..."
}
```

稳定错误码：

`INVALID_ARGUMENT`、`NOT_AVAILABLE`、`PERMISSION_DENIED`、`NEEDS_CONFIRMATION`、
`CONFIRMATION_DENIED`、`CONFIRMATION_EXPIRED`、`TIMEOUT`、`CONFLICT`、`BUSY`、
`XIAOI_OFFLINE`、`MEMORY_CORRUPT`、`INTERNAL`。

### 5.3 危险动作确认

`initialize` 时客户端通过 `clientInfo` / `capabilities` 声明是否支持 SSE 通知和自定义
`xiaoi/confirmation`；服务端在 `serverInfo`/`instructions` 里声明动作面版本和确认模式。
不声明则一律走 confirm token。

当前 MCP 是 stateless HTTP，没有 SSE，不能直接 server push。确认分三层：

> 超级岛只负责“提醒用户”，不作为批准输入。批准仍由 MCP confirm token 或手机通知按钮完成。
> 普通提醒可以走 `xiaoai_notify` / `xiaoai_island`，危险动作的确认必须可追溯。

1. **首选：MCP 通知渠道 + 超级岛提醒**
   - 增加 `GET /events` SSE（clientId 订阅），服务端发 `notifications/message`
     或自定义 `xiaoi/confirmation` 通知。
   - 客户端必须在 `initialize` 声明确认能力；不支持则降级。
2. **降级：confirm token**
   - `xiaoai_action` 返回 `NEEDS_CONFIRMATION` + `confirmToken` + 摘要。
   - 客户端调 `xiaoai_confirm(token, approve)`。
   - token 一次性、60 秒过期、绑定 clientId/action/参数哈希。
3. **人机兜底：手机通知**
   - 服务端发一条 Android 通知，带“允许/拒绝”。
   - Agent 轮询 `xiaoai_job_status` 或 `xiaoai_confirm` 状态。

危险等级：

- `safe`：直接执行（device.info、app.open）。
- `confirm`：需要确认（notification.send、memory.delete、settings）。
- `danger`：默认拒绝（root shell、批量删除、系统设置）。

**现实限制**：当前 MCP 协议版本是 `2025-03-26`，没有标准的 elicitation；`notifications/message`
也不是“确认”原语。因此：

- `confirm token` 必须是**默认可用路径**；
- SSE 通知是增强项，只有客户端声明支持时才用；
- 手机通知是最后兜底，适合人机协作，不适合纯 Agent 自动化。

确认 token 必须绑定：clientId、action、参数 hash、过期时间；确认后只执行一次。

### 5.3.1 提醒与超级岛

`xiaoai_notify` 统一提醒入口：

```json
{"channel":"island","text":"Agent 已完成：3 个待办","force":true}
```

- `island`：优先走小爱超级岛（App 8770 代理到 8771 的 `xiaoai_island`，或直接调用
  小爱 `/island` loopback）。小爱未运行时降级为普通通知。
- `notification`：走 Android `NotificationManager`，需要通知权限。
- 超级岛**只做提醒**，不承担批准/拒绝；危险动作仍走 `xiaoai_confirm`。
- 右侧大字岛约 22 字符；正文可稍长但建议 ≤60 字；限频（每 10 秒 1 次）；避免刷屏。
- 锁屏/勿扰模式下是否显示由系统决定，返回值要说明实际渠道。

### 5.4 幂等与长任务

- 所有写动作支持 `idempotencyKey`，24 小时内重复键返回第一次结果。
- 长动作（>5s）返回 `jobId`，通过 `xiaoai_job_status` 查询。
- 动作级超时；超时只标记失败，不假装成功。
- `xiaoai_action` 默认同步；`async=true` 时返回 job。

## 6. ActionRegistry

### 6.1 动作描述

```text
ActionDescriptor
├── name / version
├── description / examples
├── inputSchema / outputSchema
├── executorPreference: [android, shizuku, root]
├── requiredCapability: none | shell | root
├── risk: safe | confirm | danger
├── idempotent: bool
├── timeoutMs
└── availability()
```

### 6.2 执行流水线

```text
validate args
  → dryRun? (只校验并返回将要做什么)
  → capability/availability
  → permission check
  → confirmation (if needed)
  → idempotency check
  → execute with timeout
  → verify result
  → audit
  → structured response
```

所有写动作必须支持 `dryRun=true`，返回将要修改的路径/条目/摘要，不落盘。
这是 Agent 自检和人工审核的最低成本方式。

失败不自动重试；只有声明 `retryable` 的 `NOT_AVAILABLE` 才允许客户端重试。

### 6.3 第一批动作

| 动作 | 参数 | 返回 | 备注 |
|---|---|---|---|
| `app.open` | `package` 或 `intentUri` | 是否成功、实际组件 | `getLaunchIntentForPackage` + `FLAG_ACTIVITY_NEW_TASK`；找不到返回 `NOT_AVAILABLE`；锁屏/后台限制可能失败 |
| `device.info` | `fields?` | 型号、Android、电量、充电、网络、前台应用 | 前台应用需要 root/usage stats；拿不到就省略 |
| `alarm.set` | `hour`、`minute`、`label?`、`skipUi?` | 是否已打开闹钟界面/已创建 | 先用 `AlarmClock.ACTION_SET_ALARM`；`skipUi` 兼容性需真机验证 |
| `notification.send` | `title`、`text`、`channel?`、`id?` | notificationId | 需要 `POST_NOTIFICATIONS` |
| `media.control` | `command: play/pause/next/prev/volume` | 当前状态/音量 | 无 root 优先 `AudioManager`/MediaSession；`cmd media_session` 走 Shizuku/root |

### 6.4 权限与能力开放

能力表按权限显示“可用/不可用”，不做静默降级：

| 动作 | 无特权 | Shizuku | root |
|---|---|---|---|
| app.open | 可用（受后台限制） | 可用 | 可用 |
| device.info | 可用 | 可用 | 可用 |
| alarm.set | 可用（打开系统界面） | 可用 | 可用 |
| notification.send | 可用（需通知权限） | 可用 | 可用 |
| media.control | 音量可用；播放控制受限 | 可用 | 可用 |
| settings.get/set | 不可用 | 可用 | 可用 |
| screenshot | 不可用 | 部分可用 | 可用 |
| 小爱记忆读取 | 不可用 | 不可用 | 可用 |
| 小爱记忆写入 | 不可用 | 不可用 | 可用（实验） |

原则：**功能按所需权限开放**，没有权限时返回 `PERMISSION_DENIED` 并在
`xiaoai_capabilities` 里标明，避免 Agent 反复尝试。

### 6.5 后续候选

`clipboard.get/set`、`volume.set`、`brightness.set`、`screenshot`、`settings.get/set`、
`app.foreground`、`app.list`、`wifi.toggle`、`bluetooth.toggle`、`ui.tap/text`（Accessibility）。

高风险/暂不做：支付、转账、删除、发消息、改账号安全设置、任意 shell。

## 7. 执行器

### 7.1 AndroidExecutor

- 普通 App 权限可完成：`app.open`、`notification.send`、`device.info`、`alarm.set`、
  部分 `media.control`。
- 受 HyperOS 后台启动限制；失败返回明确原因，不循环重试。

### 7.2 RootExecutor

- 通过 `su -c` 执行**固定白名单命令**，参数逐项校验，不拼接用户输入。
- 输出限长；超时杀进程；stderr 只进审计摘要。
- 用于：小爱记忆文件、`settings`、`screenshot`、`am`、`input`。
- 写入小爱目录后必须修 `owner/group/mode` 和 SELinux context，再做读回校验。

### 7.3 ShizukuExecutor（可选）

- Shizuku 是 shell 权限，不能读 `/data/data/com.miui.voiceassist`，因此**不能改小爱记忆**。
- 适合：`settings`、`input`、`am`、`cmd media_session`。
- 项目当前“无第三方运行时依赖”；接入 Shizuku API 需要单独决策：
  - 方案 A：引入 Shizuku API（打破无第三方依赖约束）。
  - 方案 B：通过 `rish`/反射最小实现（维护成本高）。
  - 方案 C：第一版只做 root，Shizuku 后续再加。
- 建议第一版 root 优先，Shizuku 留接口不实现。

### 7.4 XiaoAiProxy

- App 通过 `127.0.0.1:8771/mcp` 调小爱现成工具。
- 只代理白名单工具：`xiaoai_submit`、`xiaoai_status`、`xiaoai_config`、`xiaoai_logs`、
  `xiaoai_stats`、`xiaoai_switch_model`。
- 不代理 `xiaoai_cli`（避免 raw CLI 穿透到 8770）。
- 小爱不在时返回 `XIAOI_OFFLINE`。

## 8. 记忆与上下文

### 8.1 定位：PC Agent 是记忆真相

这个项目的定位是“借小爱执行任务”，不是把手机做成第二大脑。

- 长期记忆、用户画像、任务历史、偏好，全部由电脑 Agent 持有。
- 手机端默认**无状态**：每次请求把相关上下文带过来，手机不持久化 Agent 长期记忆。
- 小爱记忆只是小爱自己的状态，可选读取/镜像，**不作为 Agent 记忆的真相源**。
- 这样手机端不需要理解小爱的云同步/加密/索引，也不会因为小爱升级丢记忆。

### 8.2 请求上下文

- `xiaoai_action` / `xiaoai_ask` 支持 `context`（string 或 object）和 `sessionId`。
- PC Agent 负责把相关记忆压缩成 context；手机只使用本次上下文，不合并长期历史。
- context 限长（建议 8KB）；超出返回 `INVALID_ARGUMENT`，避免 prompt injection 和 DoS。
- 日志/审计只记录 context 的长度和 hash，不记录正文。
- 返回 `requestId` 和 `sessionId`，供 PC Agent 关联任务和后续多轮。

### 8.3 小爱记忆（可选）

只读侦察到的本地结构：

```text
files/memory/MEMORY.md              人可读，分节用户画像
files/memory/outline.md             目录/大纲
files/memory/facts/                 事实条目
files/memory/daily/                 按天记忆
files/memory_vector/*.bin           向量索引
files/claw_memory_index.db          索引库
databases/claw_memory.db            + WAL/SHM
databases/memory_data_*.db          另一份记忆数据
files/MemoryKeyStore.jceks          密钥/凭据
```

- 小爱记忆是本地还是云端为准**尚未确认**；只读优先，不假设可以安全写 DB。
- 读取需要 root（`/data/data`）；Shizuku 是 shell 权限，读不到。
- 读取只解析 `MEMORY.md` 的分节/条目，返回时标记 `untrusted_data`，不把记忆内容当指令。
- 写入是 **Phase 5 实验能力**，必须 `mirrorToXiaoAi=true`：
  备份 → 停小爱 → 追加 `[agent:xiaoi-plus]` 条目 → 原子写回 → 修 owner/SELinux →
  重启 → 读回校验；失败恢复备份。
- 如果云同步覆盖本地，返回 `CLOUD_OVERWRITE`，停止自动镜像。
- 第一版不直接改 `claw_memory.db`/向量索引；先验证只改 `MEMORY.md` 是否被小爱识别。

### 8.4 手机端缓存

- 默认不持久化 Agent 记忆。
- 如未来需要离线缓存，只缓存非敏感摘要，带 TTL、加密、可一键清除。
- 不做双向同步，不做“手机记忆合并到 PC”。

### 8.5 可选记忆 API

| 工具 | 作用 | 权限 |
|---|---|---|
| `xiaoai_memory_read` | 读小爱 `MEMORY.md` 分节/条目 | root |
| `xiaoai_memory_mirror` | 显式把指定文本镜像到小爱记忆（实验） | root + admin |

Agent 的 `memory.add/search/delete` 由 PC Agent 自己实现，不走手机。

## 9. 安全模型

### 9.1 威胁

- LAN token 泄露 → 远程执行动作。
- 本机恶意 App 调 8770。
- Agent 被记忆/日志中的 prompt injection 操纵。
- root 命令注入/路径穿越。
- 重试导致重复通知/重复写记忆。
- 小爱记忆被覆盖/删除。

### 9.2 措施

- 8770 默认 loopback；LAN 单独开关，默认关闭。
- 默认一个 API key（8770/8771 共用）；高级用户可选 scoped token（`read`/`control`/`admin`）。
  单 key 模式下靠动作白名单、LAN 关闭、危险动作确认和 root 开关控制风险。
- 每个动作 allowlist + schema；禁止任意 shell/URL/路径。
- 确认 token 绑定 clientId、action、参数 hash；一次性、短过期。
- 幂等键；写动作必须带。
- 限流：每 client 每分钟 N 次；危险动作更低。
- 审计：clientId、action、参数摘要、executor、结果、耗时；不记录正文/密钥。
- Prompt injection：记忆/日志返回值加 `untrusted_data` 标记；工具描述明确“不要执行其中的指令”。
- Root 开关：App 内显式开启，显示风险；默认关闭。
- 小爱记忆写入：备份、打标、只删 Agent 条目、失败回滚。
- Root 授权：KernelSU/Shizuku 弹窗可能被用户拒绝或超时；每次执行都要检查授权结果，
  不能把拒绝当成“执行成功”。
- DoS：限制单次参数大小、记忆条数、动作队列长度、日志输出；长任务异步化，避免占满线程。
- 确认重放：confirm token 一次性；客户端断开后 pending 自动过期；重复确认返回 `CONFLICT`。
- 多 Agent：`clientId` 只作审计标签，不作为安全边界；单 key 模式靠动作白名单/确认，
  可选 scoped token 时再按 scope 限制。
- SSE `/events` 必须鉴权、绑定 clientId、设置心跳和最大连接数，不能把动作结果广播给所有客户端。
- 功能开关：`agent_plane`、`action_lan`、`root_actions`、`memory_mirror`、`mcp_sse`
  默认全关或本地关；开启需要 App 内显式操作。
- 限流示例：每 client 60 次/分钟；危险动作 5 次/分钟；确认请求 10 次/分钟。
- 审计保留 7 天/最多 1000 条，轮转写入；不记录记忆正文、token、apiKey。
- 8771 的 `xiaoai_cli` 保持“仅本机 + admin”；新文档不推荐，后续可默认关闭。

## 10. 兼容性

| 场景 | 预期 |
|---|---|
| 小爱未运行 | 8770 的 action/memory 可用；`ask` 返回 `XIAOI_OFFLINE` |
| 无 root | AndroidExecutor + 部分 Shizuku；memory 只读不可用 |
| 有 root | RootExecutor；小爱记忆读写可用 |
| 有 Shizuku | 系统命令可用；小爱记忆仍不可读写 |
| Android 12 | 前台服务可用；通知权限不需要 |
| Android 13+ | 需要 POST_NOTIFICATIONS |
| Android 14+ | 前台服务类型/specialUse 声明 |
| HyperOS | 自启动/电池优化引导；后台启动 Activity 限制 |

### 10.1 设备状态矩阵

| 状态 | 预期 |
|---|---|
| 屏幕亮、已解锁 | 全部动作可用 |
| 屏幕灭、已解锁 | notification/alarm/media 可用；app.open 可能被后台启动限制 |
| 锁屏 | app.open 可能失败；返回 `NOT_AVAILABLE`，不循环重试 |
| 省电模式 | 前台服务可能被限；给出明确提示 |
| 无网 | 动作可用；`ask` 失败；记忆本地读写可用 |
| 小爱未运行 | 动作/记忆可用；`ask` 返回 `XIAOI_OFFLINE` |
| 小爱正在启动 | `ask` 返回 `XIAOI_OFFLINE` 或 `NOT_AVAILABLE`，不阻塞 |
| root 未授权 | root 动作返回 `PERMISSION_DENIED`；能力表显示不可用 |

## 11. 测试与验收

### 11.1 纯 JVM

- ActionRegistry：schema、风险、权限、幂等、确认 token。
- 执行流水线：参数非法、无权限、确认拒绝、超时、重复键。
- Memory：Markdown 解析/写回、去重、冲突、软删除、备份恢复。
- 错误码/返回信封。
- Token scope/审计脱敏。

### 11.2 真机 P0

- 开机自启：重启后 8770 可用；用户停止后不自启。
- 小爱未运行：action/memory 可用；`ask` 明确 offline。
- 5 个动作：成功/失败/权限不足/前台/锁屏。
- 确认：MCP 通知/confirm token/手机通知三条路径。
- 幂等：同一 key 重复调用只执行一次。
- 记忆：备份→写→读回→重启→校验；失败恢复。
- 记忆删除：只能删 Agent 条目；用户条目拒绝。
- 安全：错 token、LAN 关闭、路径穿越、shell 注入、超大参数。
- 无 root / 有 root / Shizuku 三种环境。

### 11.3 记忆云端/本地专项

- 飞行模式写入/重启/联网同步。
- 账号切换/第二设备。
- 清数据后回灌。
- 记录结论到 `docs/agent/MEMORY.md`。

## 12. 阶段与里程碑

| 阶段 | 内容 | 产出 |
|---|---|---|
| Phase 0 | CLI 方法枚举、权限矩阵、超级岛通知、小爱记忆只读可行性 | **已完成**（PHASE0-REPORT.md） |
| Phase 1 | App 前台服务 + 8770 + CapabilityRegistry + context 字段 + 返回 sessionId | 可连接的空 Action Plane |
| Phase 2 | 5 个动作 + AndroidExecutor | 第一批可用动作 |
| Phase 3 | 确认/幂等/审计/错误码/超级岛提醒 | Agent 可安全调用 |
| Phase 4 | 小爱记忆只读（可选，root） | `memory_read` |
| Phase 5 | 小爱记忆镜像（实验，root） | `memory_mirror` |
| Phase 6 | XiaoAiProxy、异步 job、CLI、文档/skill | Beta |

## 13. 风险与待决

1. **MCP 确认渠道**：当前无 SSE；confirm token 是默认，超级岛只做提醒，不能当批准。
2. **单 API key**：8770/8771 共用一个 key，泄露面更大；必须默认 loopback + LAN 关闭，
   并支持一键轮换。
3. **Shizuku 依赖**：项目要求无第三方运行时依赖；第一版只做 root，Shizuku 留接口。
4. **前台服务限制**：Android 14/15 类型、HyperOS 自启动、电池优化。
5. **root 写小爱记忆一致性**：WAL/索引/加密/SELinux；只读优先，写入是实验能力。
6. **小爱记忆云端/本地未确认**：只影响可选镜像，不影响 Agent 记忆真相。
7. **Prompt injection**：PC Agent 传入的 context、小爱记忆、日志都视为不可信数据。
8. **动作副作用**：通知/闹钟/媒体可重复；必须幂等和确认。
9. **小爱版本升级**：CLI/记忆路径变化；需要 feature detection。
10. **无 root 用户体验**：记忆读写不可用；文档要明确能力差异。
11. **context 大小/会话**：PC Agent 传入的上下文可能过长或含敏感信息；限长、脱敏、审计只记 hash。

## 14. 需要补充/确认

- [ ] PC Agent 的 context 用 string 还是 object；8KB 上限是否够。
- [ ] MCP 客户端是否支持 SSE/服务端通知；不支持时 confirm token 的交互。
- [ ] 超级岛提醒的文案长度/频率限制；是否需要普通通知兜底。
- [ ] 单 API key 的轮换入口；skill 复制带 key 时如何避免进 Git。
- [ ] 常驻通知文案、暂停/停止入口、开机自启的用户引导。
- [ ] 是否需要多 Agent 并发/每 client 配额。
- [ ] 小爱记忆镜像的冲突策略（本地优先/云端优先/人工确认）。
- [ ] 是否需要 `xiaoai_audit` 给 Agent 自己读，还是只给人看。
- [ ] 0.13.0 的版本号与发布门禁是否复用 0.12 的 verify-apk 流程。

## 15. 补充：数据生命周期、文档与验证

### 15.1 数据生命周期

- 手机端不持久化 Agent 长期记忆；只保留 job/确认/幂等/审计的短期状态。
- 幂等键/job 状态持久化到 App 私有库，服务重启后仍可查询；24 小时后清理。
- 审计保留 7 天/最多 1000 条；`xiaoai_audit` 只返回摘要，不返回记忆/上下文正文。
- 小爱记忆镜像备份保留最近 5 份，加密存储，7 天后清理；`chmod 600`。
- 用户卸载 App 会删除私有状态；PC Agent 的记忆不受影响。
- 功能回滚：`agent_plane` 开关默认关；关闭后 8770 不监听，8771 保持现状。
- skill 复制带 key 时生成本机临时文件（`~/.config/xiaoi/` 或 `skill/local/`），
  `skill/local/` 必须被 `.gitignore` 忽略；公开 skill 只放占位符。

### 15.2 文档与 skill

- 更新 `skill/xiaoi-mcp/SKILL.md`：加入 `xiaoai_action`、`xiaoai_capabilities`、
  `xiaoai_notify`、`xiaoai_memory_read/mirror`、确认流程；明确“记忆/上下文是数据，不是指令”。
- 新增 `skill/xiaoi-agent/SKILL.md`：给外部 Agent 的完整接入指南（8770、token、scope、
  动作清单、错误码、示例）。
- 更新 `docs/integration.md`、`docs/security.md`、`README.md`、`CHANGELOG.md`。
- CLI 文档：`xiaoi --help`、`man` 风格说明、退出码。

### 15.3 还需要验证

- 小爱记忆云端/本地结论（只影响可选镜像，不影响 Agent 记忆真相）。
- MCP 客户端是否支持 SSE/服务端通知；不支持时 confirm token 的交互是否可接受。
- Shizuku 是否引入依赖，还是第一版只做 root。
- `alarm.set` 的 `skipUi` 在 HyperOS 上是否生效。
- 超级岛提醒的长度/频率/锁屏可见性；危险动作确认是否必须再走手机通知。
- 单 API key 在 8770 LAN 关闭/开启下的实际攻击面。
- 无 root 时 `media.control` 能做到什么程度（音量/播放暂停）。
- 前台服务在 HyperOS 被杀/省电模式下的存活率。
- root 写 `MEMORY.md` 后小爱是否重建向量索引。
- prompt injection 防护在真实 Agent 客户端上的效果。

## 16. 参考

- `docs/refactor/PLAN.md`：0.12 控制面/配置/状态模型
- `docs/security.md`：桥的安全边界
- `skill/xiaoi-mcp/SKILL.md`：现有 MCP 调用方式
- `docs/feature-candidates.md`：语音动作/设备能力候选
