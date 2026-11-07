# 能力清单（Capability Inventory）

> 状态：**草案，待评审**（2026-11-07）
> 目的：把“这个项目能让用户做到什么”拆成可核对的条目，每条都写清
> **实现方式、作用域、执行通道、现状与风险**，避免把“能做”和“已做”混在一起。
> 关联：[PLAN.md](PLAN.md)、[PHASE0-REPORT.md](PHASE0-REPORT.md)、[PHASE1.md](PHASE1.md)。

## 0. 字段说明

| 字段 | 取值 |
| --- | --- |
| 作用域 | **小爱**＝改变小爱自身（模型/提示词/记忆/语音/UI）；**系统**＝操作手机与其他 App；**桥**＝外部 Agent 接入 |
| 通道 | **小爱内置接口**（CLI / `agent.submit` / 控制面）、**LSPosed hook**、**普通 Android API**、**Shizuku**、**root**、**ADB**、**用户授权服务**（通知监听/无障碍/MediaProjection） |
| 状态 | ✅ 已实现并真机验证；🧪 部分实现/实验；📋 计划但未实现；⛔ 不做或不可行 |
| 风险 | 低 / 中 / 高；高危能力默认关闭，且不进入自动执行 |

> 说明：**ADB 不是用户运行时通道**。它只用于开发调试、CI 真机脚本，以及一次性
> 启动 Shizuku 服务；普通用户日常使用不依赖 ADB。

## 1. 通道总表

| 通道 | 前置条件 | 能做什么 | 不能做什么 | 稳定性 | 风险 |
| --- | --- | --- | --- | --- | --- |
| 小爱内置接口 | 小爱进程运行；Bearer token | `session.open/close`、`agent.submit`、`asr.*`、`tts.*`、`consent.respond`、`framework.schema` | 没有本地设备动作；`device.*` 是多设备互联 facade | 中（版本绑定） | 中（会话/隐私） |
| LSPosed hook | LSPosed + root + 小爱版本白名单 | 改请求/响应、模型配置、vision、统计、控制面 | 改不了加密记忆 DB、改不了小爱技能/签名 | 中（升级易碎） | 中（可能破坏主流程） |
| 普通 Android API | 装 APK + 运行时权限 | 设备信息、打开 App、通知、App 内闹钟、音量、媒体键 | 后台启动受限；读通知/设置/截图/UI 自动化都不行 | 高 | 低 |
| Shizuku | 一次性 ADB 启动服务 + 用户授权 | shell 级系统命令：`settings`、`am start`、`screencap`、`cmd media_session`、`pm` | 读不了 `/data/data`；不能改小爱私有数据 | 中 | 中 |
| root | KernelSU/Magisk 给本 App 授权 | 任意系统命令、读 `/data/data`、小爱记忆 Markdown、重启/关机 | 不修复错误 SELinux 配置；不绕过系统策略 | 中 | 高 |
| ADB | PC + USB/无线调试授权 | 安装、日志、`dumpsys`、真机自动化、启动 Shizuku | 不是用户日常路径；重启后常掉 | 高（开发） | 高（若暴露） |
| 用户授权服务 | 用户手动开启 | 通知读取、无障碍点击/读屏、MediaProjection 截图录屏 | 用户关闭即失效；商店/系统限制多 | 中 | 高 |

## 2. 能力清单 A：作用到小爱自身（作用域＝小爱）

| ID | 能力 | 实现方式 | 通道 | 状态 | 风险/备注 |
| --- | --- | --- | --- | --- | --- |
| A1 | 模型重定向 | hook `qm.a` 的 `getBaseUrl` / `getApiKey` / `getModel` | LSPosed hook | ✅ | 只改请求目标；版本白名单 |
| A2 | 模型标识 | hook `sm.a.getModelProviderId` → `X-Model-Provider-Id: xiaoi-plus` | LSPosed hook | ✅ | 走传输层，不占提示词 |
| A3 | 图片理解 | hook `getSupportsVision` 返回 true，保留原图 | LSPosed hook | ✅ | 上游模型需支持视觉 |
| A4 | 模型/服务商切换 | App 配置 + 控制面 reload；MCP `xiaoai_switch_model` | 内置控制面 + MCP | ✅ | MCP 只能改 model/marker；端点/key 只能 App 内改 |
| A5 | 配置热更新 | 控制面 facade + 文件监听；小爱进程 reload | 内置控制面 | ✅ | 30 秒无上报回退“等待上报” |
| A6 | 调用统计 | hook `qm.i/j/b`、`um.h` 计数 | LSPosed hook | ✅ | 只计数，不含内容 |
| A7 | 运行日志 | 内存环形 300 条 + logcat + 落盘 + 控制面上报 | 内置控制面 | ✅ | 脱敏；不记 key/token/正文 |
| A8 | 系统提示词**追加** | after hook `buildOpenAIRequest` / `buildResponsesRequest`，在原生 system 后追加 | LSPosed hook | 📋 | 占上下文；与原生规则冲突；每请求只注入一次 |
| A9 | 系统提示词**替换** | 同上，覆盖 `instructions` / system message | LSPosed hook | 📋 | 高风险：可能破坏小爱工具协议与安全策略 |
| A10 | 动态上下文注入 | 变量 `{time}` `{battery}` `{app}` `{model}` 拼进 A8 | hook + 普通 API | 📋 | 隐私与 token 成本；默认关闭 |
| A11 | 工具注入（function calling） | `ActionDescriptor` → OpenAI `tools`；解析 `tool_calls` → `ActionRegistry`；回填 `tool` 消息 | hook + 8770 | 📋 | 需真机确认请求体保留 `tools`；流式拼装；危险动作要确认 |
| A12 | 轻量工具协议 | system 约定输出 `<xiaoi_action>{json}</xiaoi_action>`；响应流解析并执行 | hook | 📋 | 模型可能不遵守；只作 fallback |
| A13 | Skill 包 | `skill.json`（prompt / tools 白名单 / triggers / examples），App 安装启停，按场景注入 | hook + 配置 | 📋 | 提示冲突、技能越权；白名单 + 风险分级 |
| A14 | 记忆只读注入 | root 读 `files/memory/MEMORY.md`、`outline.md`，摘要注入 system | root | 📋 | 需 root；记忆里的指令性文本不可信 |
| A15 | 记忆写入/镜像 | root 追加 Markdown（实验），不碰加密 DB/向量索引 | root | 🧪 规划 | SELinux owner/context；云端真相未知；默认关 |
| A16 | 多轮会话 | `xiaoai_ask` 返回并复用 `sessionId` | 小爱内置 CLI | ✅ | 会话归属连接；`SESSION_BUSY` |
| A17 | 让小爱执行任务 | `xiaoai_submit` → CLI `agent.submit` | 小爱内置 CLI | ✅ | 黑盒：实际技能取决于小爱 |
| A18 | 代小爱确认授权 | CLI `consent.respond`（技术上可调用） | 小爱内置 CLI | ⛔ | 属于用户对小爱的授权，项目不做代签 |
| A19 | ASR 增强 | hook 识别结果：热词/纠错/前置改写 | LSPosed hook | 📋 | 版本绑定强 |
| A20 | TTS 增强 | hook 合成文本后处理；或改用系统 TTS | LSPosed hook | 📋 | 音色仍受小爱限制 |
| A21 | 超级岛提醒 | 焦点通知 + `miui.focus.param`；`xiaoai_notify` | 普通 API（8770/8771） | ✅ | ≤22 字符、6 秒、无 actions |
| A22 | 岛交互/按钮 | 需小爱 RemoteViews / actions | 未开放 | ⛔ | 当前 payload 无 actions，不能批准危险动作 |
| A23 | 输出前缀 | 响应构造器改写 delta | LSPosed hook | ⛔ 禁用 | 多进程重复注入；模型会模仿历史 |
| A24 | 配置读取 | `xiaoai_config` 脱敏返回 | 内置控制面 | ✅ | 不返回 key/token 明文 |
| A25 | 读小爱记忆 DB | `claw_memory*.db` 加密/自定义格式 | root 也需解密 | ⛔ | 格式未知；不碰 |
| A26 | 改小爱技能/资源/签名 | 重打包 APK / 改技能库 | — | ⛔ | 违反“不修改不重打包小爱”原则；破坏升级与完整性 |

## 3. 能力清单 B：作用到系统与其他应用（作用域＝系统）

| ID | 能力 | 实现方式 | 通道 | 状态 | 风险/备注 |
| --- | --- | --- | --- | --- | --- |
| B1 | 设备信息 | `Build` / `BatteryManager` / `ConnectivityManager` | 普通 API | ✅ | 字段可缺省，不伪造 |
| B2 | 打开应用 | `getLaunchIntentForPackage` + `startActivity` | 普通 API | ✅ | HyperOS 后台启动可能不显示 |
| B3 | 打开应用（兜底） | `monkey -p <pkg> ... 1` | root | 🧪 | 固定模板 + 包名白名单；本机未授权 |
| B4 | 发通知 | `NotificationManager` + `agent_action` 渠道 | 普通 API | ✅ | `risk=confirm`；需 `POST_NOTIFICATIONS` |
| B5 | App 内闹钟 | `AlarmManager.setAlarmClock` + `AgentAlarmReceiver` | 普通 API | ✅ | 精确、免 Doze；不在系统时钟列表 |
| B6 | 时钟 App 闹钟 | `AlarmClock.ACTION_SET_ALARM` + `getNextAlarmClock` 核对 | 普通 API | 🧪 | HyperOS 后台返回 `PERMISSION_DENIED` |
| B7 | 音量 | `AudioManager.setStreamVolume` | 普通 API | ✅ | 勿扰/设备策略可能拒绝 |
| B8 | 播放/暂停/切歌 | `dispatchMediaKeyEvent` | 普通 API / root | 🧪 | 依赖当前 MediaSession |
| B9 | 截图 | `screencap`；或 MediaProjection | root / Shizuku / 用户授权 | 📋 | 隐私；需存储或流式编码 |
| B10 | 设置读写 | `settings get/put` | Shizuku / root（部分 `WRITE_SETTINGS`） | 📋 | 危险键需白名单（如 ADB 开关） |
| B11 | 前台应用 | `UsageStatsManager` | 用户授权 usage access | 📋 | 需手动授权；或 root/Shizuku |
| B12 | 应用列表 | `PackageManager` | 普通 API | 📋 | targetSdk 26 当前基本不过滤；升级 target 后需 `QUERY_ALL_PACKAGES` |
| B13 | 安装/卸载 | `PackageInstaller` Intent；或 `pm install/uninstall` | 普通 Intent / root / Shizuku | 📋 | 高风险；默认只走用户确认 |
| B14 | 读通知 | `NotificationListenerService` | 用户授权 | 📋 | 隐私极高；默认关，不做正文落盘 |
| B15 | 取消/回复通知 | listener + `RemoteInput` | 用户授权 | 📋 | 同上 |
| B16 | 剪贴板 | `ClipboardManager` | 普通 API | 📋 | 后台读取受限；隐私 |
| B17 | 联系人 | `ContactsContract` | 运行时权限 | 📋 | 隐私；只读优先 |
| B18 | 日历 | `CalendarContract` | 运行时权限 | 📋 | 读写分开授权 |
| B19 | 提醒/待办 | `CalendarContract` 或借小爱技能 | 普通 API / 内置 CLI | 📋 | 语义交给小爱更准 |
| B20 | 电话/短信 | `Telephony` / `SmsManager` | 运行时权限 | ⛔ 不建议 | 高风险、审核与误触代价大 |
| B21 | 文件读写 | SAF / App 私有目录；或 root | 普通 API / root | 📋 | 范围化，不做任意路径 |
| B22 | WiFi/蓝牙/数据/飞行模式 | `svc` / `cmd` / 系统 API | Shizuku / root | 📋 | 普通 App 无法直接切换 |
| B23 | 亮度/勿扰/锁屏 | `Settings.System` / `DevicePolicyManager` / `cmd` | `WRITE_SETTINGS` / Shizuku / root | 📋 | 勿扰需 policy access |
| B24 | 定位 | `LocationManager` | 运行时权限 | 📋 | 隐私；默认关 |
| B25 | 重启/关机 | `reboot` / `svc power` | root | 📋 | 危险；必须确认 |
| B26 | UI 自动化（点击/滑动/读屏） | `AccessibilityService` | 用户授权 | ⛔ 不建议 | 权限过大、脆弱、易被滥用 |
| B27 | 文本注入 | 无障碍 / `input text` | 用户授权 / Shizuku / root | ⛔ 不建议 | 同上 |
| B28 | 任意 shell | `su -c` / Shizuku shell | root / Shizuku | ⛔ 明确不做 | 注入面；只保留固定命令族 |
| B29 | 录屏/投屏 | `MediaProjection` | 用户授权 | 📋 | 隐私；每次授权 |
| B30 | 米家 IoT / 账号态 | 米家云 / 小米账号 | 生态独占 | ⛔ | 需开放平台授权，不在本项目范围 |
| B31 | 支付/账号/风控 | — | — | ⛔ | 不做 |

## 3.5 能力清单 C：桥与外部 Agent（跨作用域）

| ID | 能力 | 实现方式 | 通道 | 状态 | 风险/备注 |
| --- | --- | --- | --- | --- | --- |
| C1 | 外部 Agent 接入 | `POST /mcp`（JSON-RPC 2.0）+ Bearer token | 8770 / 8771 | ✅ | 默认仅本机；LAN 需 32 hex token |
| C2 | 让小爱执行一句话任务 | `xiaoai_submit` → CLI `agent.submit` | 小爱内置 CLI | ✅ | 需要小爱进程；黑盒技能 |
| C3 | 与小爱多轮对话 | `xiaoai_ask` + `sessionId` | 小爱内置 CLI | ✅ | 返回干净文本 + sessionId |
| C4 | 读写/查看配置 | `xiaoai_config`、`xiaoai_status`、`xiaoai_logs`、`xiaoai_stats` | 控制面 | ✅ | 脱敏；不含 key/token |
| C5 | 热切换模型 | `xiaoai_switch_model` | 控制面 | ✅ | 只允许 model/marker |
| C6 | 触发超级岛 | `xiaoai_island` / `xiaoai_notify` | 8771 / 8770 | ✅ | 只提醒，不能批准 |
| C7 | 执行设备动作 | `xiaoai_action` + `ActionRegistry` | 8770 | ✅ | 5 个动作；按权限显示可用性 |
| C8 | 危险动作确认 | `xiaoai_confirm` + 60 秒一次性 token | 8770 | ✅ | 重启失效；不持久化 |
| C9 | 幂等与审计 | `idempotencyKey` 24h；`xiaoai_audit` | 8770 | ✅ | 结果落盘；审计仍在内存 |
| C10 | 原始 CLI 透传 | `xiaoai_cli` | 小爱内置 CLI | 🧪 | 只用于诊断；不暴露任意 shell |

## 4. 按通道看“能不能”

| 能力族 | 小爱内置接口 | 普通 API | Shizuku | root | ADB |
| --- | --- | --- | --- | --- | --- |
| 换模型 / 改提示词 / 工具注入 | 不能（只有 agent.submit） | 不能 | 不能 | 不能 | 不能（需 hook） |
| 让小爱执行它的技能 | ✅ `agent.submit` | 不能 | 不能 | 不能 | 不能 |
| 设备信息 / 打开 App / 通知 / 闹钟 / 音量 | 不能 | ✅ | ✅ | ✅ | ✅（开发） |
| 截图 / 改设置 / 命令 | 不能 | 不能 | ✅ | ✅ | ✅（开发） |
| 读小爱记忆 | 不能（CLI 无记忆方法） | 不能 | 不能（`/data/data` 不可读） | ✅ | 不能（普通 adb 也读不了） |
| 读通知 / UI 自动化 | 不能 | 需用户授权 | 不能 | 部分（`dumpsys`/`input`） | ✅（开发） |
| 米家 / 小米账号 | 不能（黑盒技能） | 不能 | 不能 | 不能 | 不能 |

## 5. 风险分级与默认开关

| 级别 | 能力 | 默认 | 保护 |
| --- | --- | --- | --- |
| 低 | 设备信息、音量、App 内闹钟、通知 | 开 | 审计 |
| 中 | 打开 App、危险通知、时钟 App 闹钟 | 开 | 通知走 `confirm`/60 秒 token；App 受后台限制 |
| 高 | root、Shizuku、LAN、通知读取、截图 | **关** | 显式开关 + 用户授权 + 审计 |
| 极高 | 无障碍、任意 shell、电话/短信、重启 | **不做** | — |

## 6. 建议路线

**P0（先做，收益最大）**

1. A8 系统提示词追加：配置输入框 + 两个 hook 注入 + 变量 + 去重。
2. A11 工具注入 MVP：只接 `device.info` / `app.open` / `media.control(volume)`，
   验证小爱请求体是否保留 `tools`。
3. A13 Skill 包 MVP：`skill.json` + 2 个内置包（早间管家、专注模式）。

**P1（补系统能力）**

4. Shizuku 执行器：设置读写、截图、`am start`、`pm`（免 root）。
5. root 执行器扩展：记忆只读、设置、截图、重启（保持固定命令族）。
6. 前台应用 / 应用列表 / 日历 / 联系人（普通权限，按需申请）。

**P2（体验与稳定）**

7. 场景路由 + 官方回退（A4 扩展）。
8. ASR/TTS 增强（A19/A20）。
9. 通知读取（B14）——单独评审，默认关。

**不做**：A18 代签、A22 岛按钮、A23 输出前缀、A25 记忆 DB、B26/B27 无障碍与注入、
B28 任意 shell、B30 米家、B31 支付。

## 7. 代码与文档映射

| 能力族 | 主要实现 |
| --- | --- |
| 模型/提示词/vision/统计 | `app/src/main/java/plus/xiaoi/module/Hook.java` |
| 8771 工具（submit/status/config/switch_model/logs/stats/island） | `Mcp.java`、`Bridge*.java` |
| 8770 工具（action/capabilities/confirm/notify/ask/audit） | `agent/AgentMcp.java` |
| 5 个动作 schema | `agent/Actions.java`、`agent/ActionRegistry.java` |
| Android 执行器 | `agent/AndroidExecutor.java` |
| root 执行器与固定命令 | `agent/RootExecutor.java`、`agent/RootCommands.java` |
| 闹钟接收与渠道 | `agent/AgentAlarmReceiver.java`、`agent/AlarmTimes.java` |
| 设备动作面生命周期 | `agent/AgentService.java`、`agent/AgentHttpServer.java` |
| 外部 Agent 接入文档 | `skill/xiaoi-agent/SKILL.md`、`skill/xiaoi-mcp/SKILL.md` |
| 侦察与权限矩阵 | `docs/agent/PHASE0-REPORT.md` |
