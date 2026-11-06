# Phase 0 侦察报告

> 状态：**已完成**
> 日期：2026-11-06
> 设备：`192.168.0.118:5555`（Android 17，KernelSU Next，Shizuku 13.6.0）
> 小爱：8.2.65.4714（versionCode 508002065）
> 关联：`docs/agent/PLAN.md`、`docs/agent/PHASE0.md`

## 1. 结论摘要

1. **小爱 CLI 没有本地设备动作层**：CLI handler 只有 session / agent / auth / ASR / TTS /
   framework；`device.*` 是“多设备互联”facade，不是打开应用/闹钟/通知。
   → Phase 1 的 5 个动作必须走 Android/root，不能指望 CLI。
2. **`agent.submit` 是连接态会话**：需要 `sessionId` + `text`，session 归属当前 CLI 连接；
   当前 `xiaoai_submit` 自动开会话但不返回 sessionId。→ Phase 1 必须返回/管理 sessionId。
3. **超级岛可用，但只能提醒**：MCP `xiaoai_island` 可自定义文本，6 秒消失；
   `actions` 为空，不能批准/拒绝。→ 危险动作确认仍走 confirm token。
4. **小爱记忆 DB 是加密的**：`claw_memory.db` / `claw_memory_index.db` / `memory_data_*.db`
   不是明文 SQLite；`MEMORY.md` 是明文 Markdown（17KB，63 条，分节）。
   → 只读可以先做 `MEMORY.md`；直接改 DB 不可行，写入只考虑 Markdown 实验。
5. **权限边界明确**：Shizuku 可做 shell 命令，但读不了 `/data/data`；root 才能读/写小爱记忆。
   设备 SELinux 为 Enforcing，root 写文件必须修 owner + context。

## 2. P0-1 CLI 方法枚举

APK：`/product/priv-app/VoiceAssistAndroidT/VoiceAssistAndroidT.apk`（240MB，多 dex）。
反编译定位到 `com/aios/osbot/cli/server/`：

| Handler | 方法 |
|---|---|
| `CliSessionHandler` | `session.open`、`session.close` |
| `CliAgentHandler` | `agent.submit`、`consent.respond` |
| `CliAuthHandler` | `auth.login`、`auth.status` |
| `CliAsrHandler` | `asr.start_session`、`asr.stop_session`、`asr.cancel_session` |
| `CliTtsHandler` | `tts.speak_stream`、`tts.cancel` |
| `CliFrameworkHandler` | `schema.commands` |

补充：

- `agent.submit` 参数：`sessionId`、`text`；会校验 session 属于当前连接；
  正在执行时返回 `SESSION_BUSY`。
- `consent.respond` 参数：`requestId`、`choice`，用于小爱自己的授权确认。
- `device.list` / `device.get` 通过 facade 可用（多设备互联），不是本地动作。
  实测 `device.list` 返回手机与 PC 两个设备；`device.get` 需要 `suid`。
- `agent.list` 返回 agent 能力（含 `enable_island_notification` 等），可作为只读诊断。

**判断**：路线 B（只复用小爱动作层）不可行；D1 的 AndroidExecutor / RootExecutor 是必需的。

## 3. P0-2 超级岛

实现：`app/src/main/java/plus/xiaoi/module/Island.java`

- 走焦点通知：channel `super_island`，importance 4。
- 参数在 `extras["miui.focus.param"]`，结构 `{"param_v2":{...}}`。
- 文本：
  - `iconTextInfo.content` / `aodTitle` 用完整文本；
  - 右侧大字岛标题走 `shortModel`，最长 22 字符；
  - 标题固定“小爱+”。
- 动作：`actions` 为空数组，**没有按钮/批准能力**。
- 展示 6 秒后自动取消；`force=true` 可绕过“每进程一次”。
- 实测 MCP `xiaoai_island` 返回 `{"ok":true,"alreadyShown":false,"text":"..."}`，
  logcat 可见 `FocusPlugin` / `DynamicIsland` 接收。
- `POST /island` 只接受 loopback，且只显示当前模型，不接受自定义文本；
  自定义文本走 MCP `xiaoai_island`（需要 Bearer）。

**结论**：`xiaoai_notify(channel=island)` 可用；文案建议 ≤22 字符（右侧），
失败/小爱不在时降级普通通知；不能作为危险动作批准。

## 4. P0-3 权限矩阵

实测/确认：

| 动作 | 无特权 | Shizuku | root |
|---|---|---|---|
| app.open | 可用，但受后台启动 Activity 限制 | 可用（`am start`） | 可用 |
| device.info | 可用（电量/网络/型号）；前台应用需 usage stats | 可用 | 可用 |
| alarm.set | 可用（打开系统闹钟界面，`skipUi` 未验证） | 可用 | 可用 |
| notification.send | 可用；App 已授予 `POST_NOTIFICATIONS` | 可用 | 可用 |
| media.control | 音量可用；播放/暂停需 MediaSession 权限，受限 | `cmd media_session` 可用 | 可用 |
| 截图 | 不可用 | 部分可用 | `screencap` 可用 |
| settings.get/set | 不可用 | `settings` 可用 | 可用 |
| 读小爱记忆 | 不可用 | 不可用 | 可用 |
| 写小爱记忆 | 不可用 | 不可用 | 可用（实验） |

设备状态：

- root：`su -c id` 返回 uid=0，KernelSU。
- Shizuku：`moe.shizuku.privileged.api` 13.6.0，`shizuku_server` 在跑。
- SELinux：`Enforcing`；小爱文件 context `u:object_r:app_data_file:s0:c512,c768`。
- 当前 App 只有 `INTERNET` 权限；`POST_NOTIFICATIONS` 已授予。
- 尚无前台服务、开机自启、通知渠道等 Phase 1 需要的 manifest 项。

**结论**：能力表必须按权限显示；Shizuku 只覆盖系统命令，记忆只能 root。

## 5. P0-4 小爱记忆只读

本地结构（只读，未修改）：

```text
files/memory/MEMORY.md              ~17KB，UTF-8，63 条，分节
files/memory/outline.md             605B，目录摘要
files/memory/daily/YYYY/MM/*.md     按天 markdown
files/memory/facts/                 空
files/memory_vector/*.bin           向量索引
files/claw_memory_index.db          2.0MB，非明文 SQLite
databases/claw_memory.db            424KB，非明文 SQLite
databases/memory_data_*.db          116KB，非明文 SQLite
files/MemoryKeyStore.jceks          157B
files/memory_config/                dreamer_progress.json 等
```

关键发现：

- 三个 `.db` 文件头都不是 `SQLite format 3`，Python sqlite3 打不开 → 加密/自定义格式。
- `MEMORY.md` 明文可读，分节包括用户画像、长期记忆、AI 助手身份、工具/环境等；
  条目带来源/时间/信号强度。
- `outline.md` 是每条记忆的摘要，写 `MEMORY.md` 后可能需要同步/重建。
- 记忆里存在“AI 助手身份/操作要求”这类**指令性文本**，Agent 读取时必须当作
  不可信数据，不能直接执行。
- 读取需要 root；Shizuku 读不了 `/data/data`。

**结论**：`xiaoai_memory_read` 可以做（只读 `MEMORY.md`/`outline.md`）；
`xiaoai_memory_mirror` 先只做 Markdown 追加实验，不碰 DB/向量索引。
云端/本地真相未验证；由于 PC Agent 是记忆真相，此项仅作可选能力。

## 6. P0-5 Phase 1 接口清单

### 6.1 ActionDescriptor

```text
name, version, description, examples,
inputSchema, outputSchema,
executorPreference: [android, shizuku, root],
requiredCapability: none | shell | root,
risk: safe | confirm | danger,
idempotent: bool,
timeoutMs,
availability()
```

### 6.2 MCP 工具

| 工具 | 参数 | 返回 |
|---|---|---|
| `xiaoai_capabilities` | `includeUnavailable?` | 动作/权限/风险/schema |
| `xiaoai_action` | `action`、`args`、公共参数 | 结构化结果/`NEEDS_CONFIRMATION` |
| `xiaoai_confirm` | `confirmToken`、`approve` | `CONFIRMED`/`DENIED`/`EXPIRED` |
| `xiaoai_notify` | `channel`、`text`、`force?` | 实际渠道/是否成功 |
| `xiaoai_memory_read` | `section?`、`limit?` | 条目/分节（root） |
| `xiaoai_memory_mirror` | `text`、`dryRun?` | 镜像结果（实验，root） |
| `xiaoai_ask` | `text`、`sessionId?`、`context?` | 小爱回复 + sessionId |
| `xiaoai_job_status` | `jobId` | 状态/结果 |
| `xiaoai_audit` | `limit?` | 审计摘要 |

公共参数：`context`、`sessionId`、`idempotencyKey`、`dryRun`、`async`、`timeoutMs`、`clientId`。

### 6.3 第一批动作 schema

| 动作 | 输入 | 输出 |
|---|---|---|
| `app.open` | `package` 或 `intentUri` | `opened`、`component` |
| `device.info` | `fields?` | `model`、`android`、`battery`、`charging`、`network`、`foreground?` |
| `alarm.set` | `hour`、`minute`、`label?`、`skipUi?` | `openedUi` / `created` |
| `notification.send` | `title`、`text`、`id?` | `notificationId` |
| `media.control` | `command`、`volume?` | `state` / `volume` |

### 6.4 Manifest/权限清单（Phase 1 新增）

- `POST_NOTIFICATIONS`（已有）
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_SPECIAL_USE`（Android 14+）
- `RECEIVE_BOOT_COMPLETED`
- `WAKE_LOCK`（仅在必要短时使用；默认不持有）
- 前台服务 + boot receiver
- 通知渠道（action service / confirmation）
- 可选：`PACKAGE_USAGE_STATS`（前台应用，需用户授权）
- 可选：`SYSTEM_ALERT_WINDOW`（后台启动 Activity 兜底，谨慎）

## 7. 对 Phase 1 的直接影响

1. `xiaoai_submit`/`xiaoai_ask` 必须返回 `sessionId`，并处理 `SESSION_BUSY`/连接态会话。
2. `xiaoai_notify` 的 island 文案限长；确认不能依赖岛。
3. `xiaoai_action` 的 5 个动作全部走 AndroidExecutor；Shizuku 只做 shell 动作；
   root 只做记忆/截图/settings。
4. `xiaoai_memory_read` 只读 Markdown；不碰 DB。
5. 能力表必须显示权限可用性；没有 root 时 memory 相关直接标不可用。
6. 单 API key + 8770 loopback 默认；LAN 默认关闭。

## 8. 未验证/下一轮

- `alarm.set` 的 `skipUi` 在 HyperOS 是否生效。
- 无 root 时 `media.control` 播放/暂停能做到什么程度。
- `app.open` 在锁屏/后台/FGS 下的实际成功率。
- 超级岛锁屏可见性、勿扰模式、连续提醒频率。
- 前台服务在 HyperOS 的存活率/自启动。
- 小爱记忆云端/本地真相（仅影响可选镜像）。
- `agent.submit` 真实返回结构（需要一次无害 prompt 验证）。

## 9. 产物

- CLI/权限侦察临时文件：`~/xiaoi-plus-dev/tmp/agent-phase0/`（含 APK/dex/加密 DB，
  敏感文件 `chmod 600`；报告完成后可删除）。
- 小爱记忆只读副本：`~/xiaoi-plus-dev/tmp/agent-phase0/memory/`
  （`chmod 600`，含个人数据，验证后删除）。
