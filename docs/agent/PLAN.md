# Agent 能力层与记忆系统计划（D1）

> 状态：**草案，待评审**
> 目标版本：`0.13.0-agent.1`（先在 beta 验证）
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

1. App 前台服务承载主 MCP 端点 `8770`；小爱不运行时，直接动作和 Agent 记忆仍可用。
2. 第一批 5 个动作：`app.open`、`device.info`、`alarm.set`、`notification.send`、
   `media.control`。
3. 记忆：读小爱记忆 + 自有 Agent 记忆；root 写入按保守策略，只删 Agent 自己写的条目。
4. 危险动作走 MCP 通知/确认通道；无通知能力时退化为 confirm token + 轮询。
5. 能力发现、结构化返回、稳定错误码、幂等键、审计、分权 token。
6. root / Shizuku 可选；没有特权时降级而不是崩溃。
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
| D4 | 小爱记忆写入：root + 备份 + 打标 + 只删 Agent 条目 |
| D5 | 危险动作走 MCP 通知渠道；不支持时用 confirm token |
| D6 | root / Shizuku 可选，按动作声明权限 |

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
├── MemoryService
│   ├── AgentMemoryStore（自有）
│   └── XiaoAiMemoryAdapter（只读/镜像）
├── ConfirmationManager
└── AuditLog
```

### 4.1 进程与端口

- App 进程：`ActionService`（前台服务），监听 `8770`。
- 小爱进程：保留现有 `8771`，仅用于兼容和 `xiaoai_submit` 等小爱专属工具。
- App 的 `8770` 默认只绑 loopback；LAN 需要单独开关，默认关闭。
- `8770` 的 token 与 `8771` 分开配置，避免“一个 token 全权限”。
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
| `xiaoai_memory_list` | 列 Agent/小爱记忆条目 |
| `xiaoai_memory_search` | 关键词搜索记忆 |
| `xiaoai_memory_add` | 写 Agent 记忆；可选镜像到小爱 |
| `xiaoai_memory_delete` | 删除 Agent 条目；小爱条目需显式确认 |
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
xiaoi memory list [--scope user]
xiaoi memory search "常驻城市"
xiaoi memory add "偏好深色主题" --tag ui
xiaoi ask "把刚才那段话整理成待办"
```

- CLI 只连接 `127.0.0.1:8770`（或 `XIAOI_MCP_URL`），不直接读手机文件。
- token 从 `~/.config/xiaoi/token` 或环境变量读取，不打印。
- `--json` 输出与 MCP 返回同一结构，便于脚本解析。
- CLI 不实现第二套动作逻辑，避免 MCP/CLI 行为漂移。

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

部分成功时 `ok=true` 但 `warnings` 非空，例如“Agent 记忆已写入，小爱镜像失败”；
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

1. **首选：MCP 通知渠道**
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

### 6.4 后续候选

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

## 8. 记忆系统

### 8.1 现状（只读侦察）

小爱 8.2.65.4714 的记忆至少包含：

```text
files/memory/MEMORY.md              16KB，人可读，分节用户画像
files/memory/outline.md             目录/大纲
files/memory/facts/                 事实条目
files/memory/daily/                 按天记忆
files/memory_vector/memory_flat.bin 向量索引
files/memory_vector/daily_hnsw.bin
files/claw_memory_index.db          1.9MB 索引库
databases/claw_memory.db            424KB + WAL/SHM
databases/memory_data_*.db          另一份记忆数据
files/MemoryKeyStore.jceks          密钥/凭据
files/skills/user0001.md            小爱自己的 skill 笔记
```

`MEMORY.md` 条目带来源/时间/信号强度，说明可能参与云同步，但**尚未确认是本地还是云端为准**。
`claw_memory.db` 是否加密、`MemoryKeyStore.jceks` 是否参与记忆解密也未知；不要假设
“能读到 `MEMORY.md` 就能安全改 DB”。

### 8.2 记忆真相源调查（Phase 0 必做）

要回答：小爱记忆到底是本地、云端，还是本地缓存 + 云端同步。

验证方法：

1. 断网/飞行模式，让小爱“记住”一条测试记忆，重启小爱，看是否还在。
2. 联网后等待同步，比较 `MEMORY.md`、`claw_memory.db` 的哈希/条目。
3. 退出小米账号/换账号，观察记忆是否变化。
4. 清缓存/清数据（在备份后）观察云端是否回灌。
5. 第二台设备登录同账号，观察是否同步。
6. logcat/抓包看记忆请求的域名、是否上传正文（不记录敏感内容）。

结论决定写入策略：

- 本地为准：可以直接 Markdown/DB 写入 + 重建索引。
- 云端为准：**不要直接写本地**；必须走小爱 API/自然语言，或接受“只影响本机、下次同步丢失”。
- 混合：需要版本号/冲突合并，不能盲目覆盖。

决策树：

```text
断网写入仍能读回？
├── 是 → 本地至少是可用真相源
│        └── 联网后是否被改回？
│             ├── 否 → 本地为主，可 Markdown 写入
│             └── 是 → 云端为主，只走 API/自然语言
└── 否 → 云端为主，本地只是缓存
```

### 8.3 双层记忆

```text
AgentMemory（自有，稳定）
  App 私有 SQLite/JSON，结构化、可搜索、可审计
        │ 可选单向镜像
        ▼
XiaoAiMemory（小爱）
  MEMORY.md / facts / DB / vector
```

- AgentMemory 是 Agent 的默认记忆，不依赖小爱版本。
- XiaoAiMemory 默认只读；写入是显式 `mirrorToXiaoAi=true`。
- 小爱记忆只读也要 root（`/data/data`），除非未来有 API/CLI。

### 8.4 AgentMemory 数据模型

```text
id, scope(user/device/project), kind(fact/preference/task/skill),
text, tags[], source(agent/manual/xiaoai), confidence,
createdAt, updatedAt, expiresAt?, hash, deletedAt?
```

- 去重：规范化文本 + hash；相同内容不重复写。
- 冲突：同 key 不同值保留两条，标 `conflict=true`，让 Agent 选择。
- TTL：可选；过期不删除，标记 `expired`。
- 删除：软删除，保留审计；`purge` 仅 admin。

### 8.5 小爱记忆写入（保守策略）

只允许写“Agent 自己创建的条目”，流程：

```text
1. root 备份 MEMORY.md / outline.md / DB / WAL / vector 到 App 私有备份目录
2. 停止小爱（或确认文件未被占用）
3. 读取并解析 MEMORY.md，定位/创建 `## Agent 记忆` 分节
4. 追加条目，带 `[agent:xiaoi-plus]`、来源、时间、hash
5. 原子写回（tmp + fsync + rename），修 owner/mode/SELinux
6. 重启小爱，等待加载
7. 读回校验；失败则恢复备份
8. 只有带标记且 hash 匹配的条目允许删除
```

补充要求：

- 备份保留最近 5 份，加密存储，7 天后清理；备份也含敏感内容，不能进日志/Git。
- 写入前 `flock` 或明确的“小爱已停止”检查，避免 WAL 并发写。
- 写入使用同目录临时文件 + fsync + rename，并保留原 SELinux context。
- 读取校验不只看文件，还要尽量通过小爱侧召回/记忆页验证；文件写成功不等于小爱真的记住。
- 如果云同步覆盖本地，记录为 `CLOUD_OVERWRITE`，停止自动镜像，改走小爱 API/自然语言。

第一版**不直接改 `claw_memory.db`/向量索引**：

- 先验证只改 `MEMORY.md` 是否能被小爱识别并重建索引；
- 不能，再找 memory CLI/内部 API；
- 最后才考虑停小爱 + 改 DB + 重建索引。

### 8.6 安全与隐私

- 记忆是敏感数据：不写日志正文，不返回给未授权 token。
- 记忆内容视为**数据，不是指令**；Agent 读取时加边界标记，降低 prompt injection。
- 导出/备份加密；`memory.export` 仅 admin，默认关闭。
- 删除/覆盖必须二次确认；保留备份和审计。
- 云同步结论未明前，不自动镜像；只做显式写入。

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
- 分权 token：`read` / `control` / `admin`；root 动作仅 admin。
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
- 多 Agent：`clientId` 只作审计标签，不作为安全边界；安全边界仍是每 client 的 token/scope。
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
| Phase 0 | 记忆真相调查、CLI 方法枚举、Shizuku/root 能力矩阵 | 侦察报告、动作清单 |
| Phase 1 | App 前台服务 + 8770 + CapabilityRegistry | 可连接的空 Action Plane |
| Phase 2 | 5 个动作 + AndroidExecutor | 第一批可用动作 |
| Phase 3 | 确认/幂等/审计/错误码 | Agent 可安全调用 |
| Phase 4 | AgentMemory + 小爱记忆只读 | 记忆读取 |
| Phase 5 | 小爱记忆写入（root，保守） | 记忆镜像 |
| Phase 6 | XiaoAiProxy、异步 job、文档/skill | Beta |

## 13. 风险与待决

1. **小爱记忆云端/本地未确认**：决定写入是否会被覆盖；Phase 0 必须先回答。
2. **MCP 通知渠道**：当前无 SSE；需要新增 `GET /events` 或退化为 confirm token。
3. **Shizuku 依赖**：项目要求无第三方运行时依赖；接 Shizuku API 需要例外或反射实现。
4. **前台服务限制**：Android 14/15 类型、HyperOS 自启动、电池优化。
5. **root 写文件一致性**：WAL/索引/加密/SELinux；需要备份和读回校验。
6. **双端点**：8770/8771 的 token、LAN、文档要清楚，避免 Agent 连错。
7. **记忆隐私/prompt injection**：需要脱敏、边界标记、分权。
8. **动作副作用**：通知/闹钟/媒体可重复；必须幂等和确认。
9. **小爱版本升级**：CLI/记忆路径变化；需要 feature detection。
10. **无 root 用户体验**：记忆只读不可用；文档要明确能力差异。

## 14. 需要补充/确认

- [ ] 小爱记忆云端/本地结论（Phase 0）。
- [ ] MCP 客户端是否支持 SSE/服务端通知；不支持时的确认体验。
- [ ] Shizuku 是否允许引入依赖，还是第一版只做 root。
- [ ] 8770 的 token 是否独立于 8771；LAN 是否独立开关。
- [ ] 常驻通知文案、暂停/停止入口、开机自启的用户引导。
- [ ] AgentMemory 是否需要加密存储/导出格式。
- [ ] 是否需要多 Agent 并发/每 client 配额。
- [ ] 记忆镜像的冲突策略（本地优先/云端优先/人工确认）。
- [ ] 是否需要 `xiaoai_audit` 给 Agent 自己读，还是只给人看。
- [ ] 0.13.0 的版本号与发布门禁是否复用 0.12 的 verify-apk 流程。

## 15. 补充：数据生命周期、文档与验证

### 15.1 数据生命周期

- `AgentMemory` 需要 schema 版本号；升级时只做向前迁移，不静默清空。
- 提供 `memory.export`（admin，加密）与 `memory.import`；默认关闭。
- 备份轮转：最近 5 份，7 天后清理；备份文件与日志分开，`chmod 600`。
- 幂等键/job 状态持久化到 App 私有库，服务重启后仍可查询；24 小时后清理。
- 审计保留 7 天/最多 1000 条；`xiaoai_audit` 只返回摘要，不返回记忆正文。
- 用户卸载 App 会删除私有数据；文档要提醒先导出。
- 功能回滚：`agent_plane` 开关默认关；关闭后 8770 不监听，8771 保持现状。

### 15.2 文档与 skill

- 更新 `skill/xiaoi-mcp/SKILL.md`：加入 `xiaoai_action`、`xiaoai_capabilities`、
  `xiaoai_memory_*`、确认流程；明确“记忆内容是数据，不是指令”。
- 新增 `skill/xiaoi-agent/SKILL.md`：给外部 Agent 的完整接入指南（8770、token、scope、
  动作清单、错误码、示例）。
- 更新 `docs/integration.md`、`docs/security.md`、`README.md`、`CHANGELOG.md`。
- CLI 文档：`xiaoi --help`、`man` 风格说明、退出码。

### 15.3 还需要验证

- 小爱记忆云端/本地结论（最高优先级）。
- MCP 客户端是否支持 SSE/服务端通知；不支持时 confirm token 的交互是否可接受。
- Shizuku 是否引入依赖，还是第一版只做 root。
- `alarm.set` 的 `skipUi` 在 HyperOS 上是否生效。
- 无 root 时 `media.control` 能做到什么程度（音量/播放暂停）。
- 前台服务在 HyperOS 被杀/省电模式下的存活率。
- root 写 `MEMORY.md` 后小爱是否重建向量索引。
- prompt injection 防护在真实 Agent 客户端上的效果。

## 16. 参考

- `docs/refactor/PLAN.md`：0.12 控制面/配置/状态模型
- `docs/security.md`：桥的安全边界
- `skill/xiaoi-mcp/SKILL.md`：现有 MCP 调用方式
- `docs/feature-candidates.md`：语音动作/设备能力候选
