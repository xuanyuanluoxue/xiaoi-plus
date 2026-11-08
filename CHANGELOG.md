# 更新日志

## 未发布 — UI 对齐 + 悬浮液态玻璃底栏 + 预测性返回

- **卡片体系**：设置页与首页改为独立卡片清单（28dp 圆角、20dp 内边距、12dp 间距）；
  行首 48dp 语义彩色圆图标（`XiaoiIconTone`），首页与设置页共用同一套色板。
- **顶栏**：改为状态栏下 48dp 行 + 32sp 左对齐标题；页面级动作收进顶栏，
  不再在内容区另起“说明 + 按钮”工具栏。
- **首页**：状态 Hero（四维徽章 + 绿/黄/红）+ 小爱/接入/今日调用/最近活动/能力/
  配置/关于独立卡；今日调用只查本机 8771，失败保持“未确认”。
- **设置首页**：项目介绍/版本、赞助、作者、致谢、外观、配置管理、诊断、日志、
  关于、开源许可、危险区各自独立卡。
- **预测性返回**：targetSdk 26 → 34，Manifest 开启 `enableOnBackInvokedCallback`；
  二级页由 `PredictiveBackHandler` 驱动缩放/淡出，一级页仍用普通 `BackHandler`。
- **底栏**：`Miuix NavigationBar` 换成悬浮胶囊玻璃底栏（首页 / 小爱 / 接入 / 我的），
  选中 pill 支持点击、按压放大与横向拖拽切换；内容区 `layerBackdrop` 采样，
  一级页底部预留玻璃条高度，二级页隐藏底栏。
- **三级回退**：API 33+ 且 RuntimeShader 可用走 miuix-blur 模糊 + 折射；API 31–32
  走 Compose GraphicsLayer + 平台 RenderEffect 模糊；API 26–30、系统关闭动画或
  用户关闭走同尺寸实心胶囊（主题色），交互与选中态一致。
- **令牌**：`XiaoiColors` / `XiaoiDimens` 新增 `glass*` 令牌；Snackbar 随底栏高度上移。
- **许可**：底栏实现改编自 GPL-3.0 的 LiquidTab，保留来源与许可说明；
  新增 `androidx.graphics:graphics-shapes`（Apache-2.0）。
- 未新增权限；minSdk 仍为 26；targetSdk 升到 34（预测性返回）；版本号未改。
- 已知：`miuix-blur` 的 AAR 声明 minSdk 33，本仓库用 `tools:overrideLibrary`
  放开清单合并，并只在 API 33+ 调用玻璃代码；低版本走实心回退。

## 0.14.1 — 2026-11-08 — 修复启动入口仍指向旧首页

> versionCode 19 → 20。

- **根因**：0.14.0 的 `AndroidManifest.xml` 仍把 `MAIN/LAUNCHER` 放在旧
  `HomeActivity`，`MainActivity` 是 `exported=false`。从桌面图标进入时看到的还是
  旧 XML 首页，Compose UI 只能靠 `--es route` 打开。
- **修复**：`MAIN/LAUNCHER` 移到 `MainActivity`（`exported=true`）；`HomeActivity`
  改为 `exported=false`，只从「我的 › 兼容模式」显式打开。
- **门禁**：`check-consistency.sh` 新增 launcher 检查，启动入口不是
  `.MainActivity` 或未 `exported=true` 时直接失败。
- **验证**：覆盖安装后 `cmd package resolve-activity` 返回
  `plus.xiaoi.module/.MainActivity`；桌面图标启动落在 Compose 首页。

## 0.14.0 — 2026-11-08 — Compose + Miuix UI 重写

> versionCode 18 → 19（A8 定版）。设置页从「4 个 Activity + XML」迁到
> **单 Activity + Compose + Miuix**，4 个 tab、19 条路由；本版只做功能 parity，
> 不新增提示词、工具注入、技能包、记忆或语音路由。
> 构建与签名：`0.14.0` / versionCode 19；R8 后 APK **1.6 MiB**（R8 前 8.4 MiB）；
> LSPosed 注入已在真机复验。8770 端口冲突见 Release Notes 已知限制。

### 本版重点

- **4 tab**：首页 / 小爱 / 接入 / 我的；一级页显示底栏，二级页隐藏底栏并显示返回。
- **首页**：四维状态 Hero、待处理（最多 3 条）、8771/8770 双域入口、运行概况、最近活动；全页只读，按钮只导航。
- **小爱**：主开关即时保存；服务商/模型/端点/密钥显式保存；运行态显示 D3/D4、rev 与运行模型；5 个增强能力只读占位。
- **接入**：8771 控制面与 8770 动作面分开；令牌强度/掩码/轮换；LAN 回退；连接信息；67 条能力 + 5 动作 + 8 + 6 工具只读清单；6 项权限。
- **我的**：本机信息、主题三档、完整备份/脱敏导出/还原/运行时导入、诊断与日志、关于/开源许可、危险区「停用全部」。
- **P1 严格 Miuix**：`Scaffold`、`SmallTopAppBar`、`NavigationBar`（容器）、`SnackbarHost`、
  `Card`、`Button`、`SwitchPreference`、`ArrowPreference`、`BasicComponent`；不再用 Row/Column 模拟。
- **A3 数据层**：`AgentRuntimeStatus`、`BridgeStatus` 实际 bind/LAN、`PermissionProbe`、
  `IssueInputFactory`、`ActivityHub`、`CapabilityRegistry`、`NavStack`；均为只读，不改 8770/8771 行为。
- **主题**：跟随系统/浅色/深色，唯一事实源是 App prefs，不写 `config.json`、不参与导出。
- **日志/关于**：日志级别筛选、跟随、清空、分享与三种空态；关于含版本/作者/GitHub/许可证/致谢/免责。

### P2 视觉修复（A8 前）

- 对比度：输入/搜索 label 改用 `textSecondary`（2.06:1 → 5.01:1）；浅色
  `success/warning/error` 调深，徽章文字 6.08/6.85/6.54:1；Hero UNKNOWN 标签改
  `heroOn`（10.83:1）。
- 筛选 chip 收敛为 `FilterChips`（28dp 视觉 + 48dp 触控，选中 primary 实心）；
  日志 chip 与卡片间补 12dp，删除 `XiaoiChip`。
- 模型页服务商副标题单行省略、去重复“当前/服务商设置”；模型行动作水平居中。
- 8771 统一“控制面”；8770 状态由 `ui/status/AgentStatus.kt` 单点判定，
  Access/Bridge/Diagnostics 同源；未知统一“未确认”。
- `XiaoiButton(SECONDARY)` 改 `chipOkBg/primary`，浅色下可见（7.42:1，深色 8.32:1）。
- 低成本 P2：复制/去设置触控 48dp、About 许可证进入 Licenses、Tools 去重复计数、
  全屏通知副标题缩短、配置管理组名改“从运行中的小爱导入”。

### 验收

- P1 功能验收 41 PASS / 0 FAIL；P2 功能验收 65 PASS / 0 FAIL / 2 BLOCKED
  （弱令牌回退、损坏只读，受“不改用户配置”限制，已给静态证据）。
- 视觉审核 P2：0 P0、5 P1、13 P2；5 条 P1 与低成本 P2 已修，详见
  `docs/ui/reports/P2-VISUAL-FIXES.md`。
- 19 条路由可达；二级页无底栏；`tests/run.sh`、`check-consistency.sh`、
  `verify-apk.sh` 通过（P2 验收时 versionCode 18、R8 关闭）。
- 浅色 12 页、深色 8 页、字号 1.3 四页截图；手机未新增 PNG。

### 兼容/升级

- 同包名同签名覆盖安装，`versionCode 18 → 19`；`config.json` v3 与密钥/令牌保留。
- 主题、导出、分享、诊断、8771/8770 行为与 0.13 一致；`POST /config` 仍固定 410。
- 旧 `HomeActivity`/`ConfigActivity` 作为 0.14 过渡入口保留（兼容模式可看只读文件信息），
  **0.15 删除**；新功能以 `MainActivity` 的 4 tab 为准。
- 降级仍受“私有配置 + 新 UI”限制：降回 0.13 前先做完整备份并关闭 MCP/LAN。

### 已知限制

- R8/shrink、CI 与发布脚本的 0.14 适配在 P3（A8）收尾；设备断开，LSPosed/R8 真机回归
  以 A8 报告为准。APK 体积目标 ≤6MB，未确认前不作承诺。
- 日志只有级别筛选，**没有域筛选**；Connect 是整页二级页，不是 BottomSheet。
- 能力清单/工具/权限只读；审计、确认队列、测试台 0.15 提供。
- 增强能力（提示词/工具/技能/记忆/语音）仍是只读占位，没有开关或执行入口。
- 弱令牌回退与配置损坏只读只做了静态证据，发布前建议用备份配置补测。

## 0.13.0-agent.2 — 2026-11-07 — alarm.set 语义修复

> 修复 0.13.0-agent.1 中 `alarm.set` 在 HyperOS 上“返回成功但闹钟不存在”的问题。
> versionCode 17 → 18。

### 变化

- `alarm.set` 新增 `mode`：`auto`（默认）/ `clock` / `app`。
  - `auto`：先尝试系统时钟 App；被后台启动限制拦截时，自动改用 App 内闹钟。
  - `clock`：只用系统时钟 App，失败如实返回错误。
  - `app`：用 `AlarmManager.setAlarmClock` 注册精确闹钟，到点由
    `AgentAlarmReceiver` 发高优先级通知并响闹钟铃声（不在时钟 App 列表里）。
- 返回值不再只有 `scheduled`，新增 `kind`（clock/app）、`verified`、`exact`、`triggerAt`：
  - 时钟 App 路径用 `getNextAlarmClock()` 二次确认，确认不到就返回
    `scheduled=false`，不伪报成功。
  - App 内闹钟路径区分精确/非精确；没有精确闹钟权限时退化为非精确并明确 warning。
- 新增 `SCHEDULE_EXACT_ALARM` / `USE_FULL_SCREEN_INTENT` 权限与 `agent_alarm` 通知渠道。
- 真机验证（M332BF / HyperOS / Android 17）：
  `mode=app` 返回 `exact=true, verified=true`，`dumpsys alarm` 可见精确闹钟；
  到点通知渠道 `agent_alarm`（importance=4，闹钟铃声）与标题均出现。
  `mode=clock` 在后台被系统拒绝（`PERMISSION_DENIED`），`mode=auto` 正确回退并带 warning。
- JVM 单测 1453 → 1478 断言（新增 `AlarmTimesTest` 与 schema 校验）。

### 兼容/升级

- 直接覆盖安装，同包名同签名，配置保留。
- `alarm.set` 旧参数（`hour`/`minute`/`label`/`skipUi`）保持可用；旧客户端不传
  `mode` 时按 `auto` 处理。
- 时钟 App 路径行为与 0.13.0-agent.1 相同，不再把“已提交”说成“已创建”。


## 未发布 — Agent WP1.5：幂等结果持久化

- 幂等缓存写入 App 私有 `files/agent_idempotency.json`：服务/进程重启后同一
  `idempotencyKey` 仍返回首次结果，不重复执行副作用；24 小时过期，最多 500 条。
- 快照只含 key / action / 参数 hash / 过期时间 / 结果信封；`token`、`apiKey`、
  `context` 等敏感键递归剔除。损坏或超限（256KB）时按空缓存启动并记录日志。
- 确认 token 仍只存内存（一次性、60 秒），**重启失效是安全设计**；审计仍为内存环形缓冲。
- JVM 单测 1328 → 1445 断言（新增 `AgentIdempotencyPersistenceTest`）。

## 未发布 — Agent WP1.4：8770 独立局域网开关

- 配置新增 `mcp.agentLan`（默认 false，不升 schema）；`mcp.lan`（8771）语义不变，
  8770/8771 共用同一个 `mcpToken`。
- 8770 只有显式开启且 token 为 32 位十六进制时才绑 `0.0.0.0`；弱 token 自动回退
  `127.0.0.1`，`/health` 与通知如实显示实际绑定。
- 设置页新增「Agent 动作面允许局域网访问」开关（与 8771 独立确认）；打开时提示
  设备动作风险，空/弱 token 可一键生成并开启。
- 配置页地址提示、JSON 速览、复制连接信息与 Skill 同时包含 8771/8770；
  复制内容带 8770 工具清单与 adb forward/同一 Wi-Fi 前置条件。
- JVM 单测 1296 → 1328 断言（新增 `AgentLanPolicyTest` 与 `ConfigCodec` 用例）。
- 真机 `ss -ltn` / 电脑经手机 IP 访问 8770 待设备可用后补测。

## 0.13.0-agent.1 — 2026-11-06 — Agent Action Plane

> **首个 0.13 测试版**（versionCode 17）。在 0.12 控制面/私有配置/四维状态之上，
> 增加 App 进程内的 Agent 动作面：不依赖小爱对话进程也能执行设备动作。

### 本版重点

- 新增 App 前台服务 + `127.0.0.1:8770` MCP 动作面；小爱不运行时也可用
- 第一批动作：`device.info`、`app.open`、`alarm.set`、`notification.send`、`media.control`
- `xiaoai_confirm`：60 秒一次性确认 token；危险动作不再直接执行
- `xiaoai_notify`：超级岛提醒，失败降级普通通知（只提醒，不能批准危险动作）
- `xiaoai_ask`：代理 8771 小爱，返回干净文本 + `sessionId`
- `idempotencyKey` 24 小时幂等；结果持久化到 `files/agent_idempotency.json`，
  服务重启后同 key 返回首次结果
- `xiaoai_audit`：脱敏审计（参数名/哈希/结果/耗时，不含参数值/token/context）
- 8770 LAN 独立开关 `mcp.agentLan`，默认关闭；只有 32 位十六进制 token 才绑
  `0.0.0.0`，弱 token 自动回退 loopback
- 开机自启：重启后 8770 自动恢复（需系统允许自启动/通知）
- 新增 `skill/xiaoi-agent`：其他 Agent 克隆仓库后可直接按文档接入 8770

### 兼容/升级

- 直接覆盖安装，同包名同签名，versionCode 16 → 17；0.12 配置保留
- `config.json` 新增 `mcp.agentLan`（缺字段按 false），schema 仍为 v3
- 8771 的旧 MCP 工具、`/health`、`/logs`、`/stats`、`/cli` 行为不变
- 8770 与 8771 共用同一个 `mcpToken`；不新增第二套 key

### 已知限制

- root 兜底需要 KernelSU/Magisk 给本 App 授权；未授权时 `su` 被拒绝，
  `PERMISSION_DENIED`，会提示去授权或改用 Shizuku
- `alarm.set` 在 HyperOS 后台启动时可能被系统拒绝（`PERMISSION_DENIED`）；
  `startActivity` 成功也不代表闹钟已创建，返回 `requested=true, scheduled=false`
- `media.control` 的 play/pause 依赖 MediaSession；无活跃会话时只返回“已派发” warning
- 确认 token 不持久化（重启失效，安全设计）；审计仍为内存环形缓冲
- 8770 LAN 使用同一个 token，尚无独立 scope/限流；公共 Wi-Fi 建议关闭

### 升级方式

```bash
adb install -r xiaoi-plus-0.13.0-agent.1.apk
```

升级后打开一次 App，确认 MCP 开关与 token；需要 8770 局域网时在配置页单独开启
「Agent 动作面允许局域网访问」。

## 0.12.0-beta.1 — 2026-11-06 — WP1~WP7 核心重构 + 发布门禁

> **首个 0.12 测试版（versionCode 16）**。这是破坏性重构后的回归版本：
> 配置移入 App 私有目录、控制面改为 bound Service、数据面删除 HTTP 推送与 `POST /config`。
>
> 交流群：QQ **1121057362**（问题反馈 / 版本适配 / 使用交流）

### 本版重点 / 破坏性变更 / 升级方式

- **重点**：控制面改为 App 内 bound Service（UID 白名单），配置以私有 `config.json` 为唯一事实源，
  状态按 D1 配置 / D2 端点 / D3 已应用 / D4 运行时四维展示，不再伪造“已确认”。
- **破坏性**：`POST /config` 返回 `410 Gone`，App 不再向 8771~8775 推送配置；
  旧脚本/自动化必须改在手机 App 保存配置。
- **破坏性**：`config.json` 从 `Android/data/...` 移到 `/data/data/plus.xiaoi.module/files/`；
  首次打开新版本会读取旧配置，确认后迁移，baseUrl/model/key/token 不丢。
- **安全**：LAN 只接受 32 hex（128 bit）token；远程 MCP 只能改 `model`/`marker` 和已有服务商选择，
  不能写 `baseUrl`/`apiKey`/`bridge`/`lan`；短 token 自动回退仅本机。
- **升级方式**：直接覆盖安装即可（同包名同签名，versionCode 15 → 16）；
  升级后先打开一次 App 确认旧配置迁移结果，再到配置页检查 MCP 开关与端点。
- **降级警告**：降回 0.11 会重新开启 MCP 且读不到私有配置文件；降级前请先在 App 内关闭 MCP、
  清空 token，并保留完整备份。


- 新增 `core/BridgePolicy`（纯 JVM）：`shouldListen` / `bindHost` / `tokenStrongEnoughForLan`
  （32 hex 仅预留，WP4 才启用；本包 LAN 行为不变）
- 新增 `bridge/LeaderElection`：小爱各进程共享 `files/xiaoi_bridge.lock`，
  `RandomAccessFile + FileChannel.tryLock()` 选出全机唯一 leader；进程死亡由内核释放锁，
  其他进程每 10s 重试接管；同 JVM `OverlappingFileLockException` 安全降级
- 新增 `bridge/BridgeLifecycle`：幂等 `start/apply/stop`；MCP 关闭或 token 为空时
  `stopServer` + 释放 leader 锁，并且不再绑定 8771~8775；配置变化统一走 `apply()`，
  删除 `Bridge.rebindIfNeeded` 双路径
- `Bridge.install` 不再无条件 `startServer`：先起控制面，再由生命周期决定 leader；
  `Bridge.stopServer()` 关闭 server、清会话、`sStarted=false`、`sPort=0`、`sBoundLan=false`
- 非 leader 进程的 `Bridge.port()` 恒为 0；只有 leader 上报真实端口
- 所有小爱进程都参与控制面与 leader 选举（`Hook` 加载即启动），不依赖「主进程」命名；
  无 facade 的进程（`:core`）先等 30s，让带 facade/CLI 的进程优先当 leader
- 生命周期每 10s 主动 `pullConfig` 兜底：MCP 关闭期间 App 重新开启也能生效（无需重启小爱）
- 关闭判定按 `cfg_rev` 取新：控制面配置 rev 高时以控制面为准；rev 相同以 target prefs 为准，
  避免 leader 释放锁后其他进程用陈旧内存状态误接管
- 新增纯 JVM `BridgePolicyTest` / `LeaderElectionTest`（含跨进程 kill 后接管验证）

### WP1 控制面

- 新增 `control/BridgeControlService`（App 进程 bound Service，显式 ComponentName，
  无 intent-filter）：小爱进程通过 Binder 拉取 `config.json`、注册 callback、
  上报运行时状态与日志
- 每个 transaction 首行用 `Binder.getCallingUid()` + `getPackagesForUid` 校验，
  只允许 `com.miui.voiceassist`；其他 UID 抛 `SecurityException` 并记拒绝日志
- 配置读取放到 HandlerThread；同一 JSON（SHA-256 相同）只 `Hook.applyConfig` 一次
- 新增 `bridge/BridgeControlClient`：bind 失败/服务死亡按 2s→5s→10s→20s→30s 退避重试，
  不崩溃、不刷日志；拿不到 Context 时后台重试
- `core/ControlKeys`、`control/UidGuard`、`control/BridgeRegistry`、
  `control/ControlLogRing` 均为纯 JVM，新增对应单测
- App 侧控制面日志走 logcat tag `xiaoi-control`（App 进程没有 XposedBridge，
  不能复用会静默失败的 `Logs`）
- `config.json` 不存在时 `pullConfig` 返回错误，不用默认骨架覆盖 beta.4 运行态配置
- `control_plane` 模块 prefs 开关（默认开）；关闭后行为与 beta.4 一致
- WP1 阶段旧 `BridgePusher` / `/config` 未删除；WP4 已移除（见下文）
- WP3 补充：控制面下发携带 `cfg_rev`；每个进程每 10s 主动 pull 一次作为兜底

### WP2 配置单一事实源

- `config.json` 移到 App 私有目录 `getFilesDir()/config.json`；不再写 `Android/data`，
  其他应用/adb 无法直接读写密钥
- schema 升到 v3，新增 `rev`；每次 App 保存 `rev` 自增，target/module prefs 只写
  legacy 兼容键 + `cfg_rev`（降级缓存，不再作为事实源）
- 原子写改为 tmp + fsync + rename，**不先删除目标**；写失败保留旧文件
- 解析失败：保留原文件、写 `config.json.corrupt.<ts>`、进入只读态；保存被阻止并提示
  “配置文件损坏，已保留原文件；请先恢复/重置”，绝不静默覆盖
- beta.4 外部 `config.json` 首次加载一次性迁移到私有目录；私有文件已存在时旧文件
  归档为 `config.json.legacy.<ts>`，不覆盖
- 首次安装/无文件时只返回内存骨架（`skeleton=true`、`rev=0`），不再自动从运行时导入并写盘；
  手动“从运行中小爱导入”保留
- `Config.forTarget()` 不再把 `marker/bridge` 硬编码为 true；target prefs 缺失键按默认值处理
- 分享仍只写 external `exports/`；`file_paths.xml` 不再暴露整个私有/外部 files 目录
- 新增纯 JVM `core/ConfigStore` + `ConfigStoreTest`；`ConfigCodecTest` 覆盖 v3/rev/v2 迁移

### WP4 数据面加固（破坏性）

- **`POST /config` 改为 `410 Gone`**：不读取/解析 body、不写 prefs、不热更新；GET 仍 405。
  旧 App/脚本/自动化必须改用手机 App 保存配置。
- 删除 `BridgePusher`、`BridgePusherTest` 与 `BridgeClient` 的 8771~8775 端口扫描：
  App 不再向未认领端口发送 Bearer。leader 端口经控制面登记到纯 JVM `core/BridgeEndpoint`，
  `BridgeClient.discover()` 只读该值（0 = 无 leader）。
- 新增 `core/RateLimiter`：`/health`、`/island` 按来源 IP 30 次/10s，超限 429；
  `/mcp` 有 Bearer，不做全局限流。
- `Http` 有界读取：单行上限 8192 字符、header 最多 128 行；超限抛 IOException，不无限读。
  数据面线程池改为有界 `ThreadPoolExecutor`（core=4/max=8/queue=32），队列满回 503。
- `BridgePolicy.bindHost` 收紧：LAN 仅当 token 为 32 位 hex 才绑 `0.0.0.0`；
  短 token/非 hex 静默回退 loopback（MCP 本机仍可用），日志提示「LAN 需要 32 hex token」。
- `xiaoai_switch_model` 只接受 `model`/`marker`；远程传 `baseUrl`/`apiKey`/`bridge`/`lan`
  返回 `isError=true`，文案明确“端点和密钥只能在手机 App 设置”。工具 schema 同步删除这些参数。
- `Bridge.HOST.writeConfig` 强制沿用当前 lan/bridge/token，不接受远程改数据面开关。
- leader 资格 = `hasFacade() || hasCli()`：没有 facade/CLI 的进程（如 `:core`）不再接管，
  避免“端口能连但工具不可用”；有 CLI 无 facade 的进程仍可接管。
- App 侧：保存后显示「已保存，等待小爱应用」并通过控制面 reload；删除
  `pushSync/pushAsync/pending`、`lastPush` 与“令牌不一致”等假推送状态。
- `/health` 只接受 Bearer，不再回退模块 prefs 的旧 token；`/health`、`/island` 加 429 限流。
- 契约基线：`config_auth_probe` 期望 410，`mcp_tools_list` 删除 switch_model 的写参数。
- 文档/Release Notes：`/config` 410、LAN 强 token、远程写拒绝均为破坏性变更；
  降级到 0.11 前请先关闭 MCP/清空 token。

### WP5 配置页机械拆分（无行为变化）

- `ConfigActivity` 从 1616 行降到 914 行，只保留 `cfg`/`dirty`/`rendering`/`statusState`/
  `savedWaiting`/`ui` 状态、生命周期与 `save()`/`collect()`/`render()`/`checkModelNow()` 编排
- 新增 `ui/config/ConfigDialogs`：对话框容器、管理行、输入框、错误行等纯 UI 工具
- 新增 `ui/config/StatusSection`：状态条、推送行、文件冲突条、运行时冲突条
- 新增 `ui/config/McpSection`：MCP 开关联动视图、令牌、URL/JSON 速览、
  复制连接信息/Skill、端口探测
- 新增 `ui/config/EditSection`：从运行中小爱导入、脱敏导出、完整备份、还原、分享；
  SAF 结果仍由 Activity 的 `onActivityResult` 转发
- 新增 `ui/config/ModelEditor`：服务商/模型管理列表与添加/重命名/删除对话框
- 新增 `ui/config/AppearanceSection`：主题 chips
- 不改 `activity_config.xml`、字符串、资源 ID、点击行为、校验与导入导出逻辑；
  `AppConfig`/`ConfigRepository`/`ConfigCodec`/Bridge/MCP 均未改动
- 单测 543 断言全绿；`./build.sh` 与 `./scripts/check-consistency.sh` 通过；
  真机对比配置页顶部/MCP/底部/管理/备份与主题切换，布局与文案一致

### WP6 状态模型 + 主页/配置页/日志页

- 新增纯 JVM `core/StateModel`：D1 配置（未配置/暂停/就绪/损坏）、D2 端点
  （未校验/校验中/可用/密钥无效/模型不存在/端点不可达/未知）、D3 已应用
  （未连接/等待/已应用/超过 30s 未上报）、D4 运行时（未知/在用/模型不一致/禁用/缺密钥）
- 只有 D4 `IN_USE` 能显示「已确认」；`ModelValidator` 成功只更新 D2，
  主页显示「端点可用」，不再调用 `renderStatus(c, true, model)` 伪造运行态
- 新增纯 JVM `core/BridgeStatus`：保存 leader 最近一次 register/reportState 快照
  （model/enabled/hasKey/appliedRev/port/updatedAt/runtimeKnown）；超过 30s 未更新
  自动显示「等待上报/未确认」，服务销毁时清空
- 控制面协议新增 `KEY_CFG_REV`：`register`/`reportState` 上报已加载 rev；
  `InstanceRecord`/`BridgeRegistry` 保存 cfgRev，`BridgeStatus` 由 leader 更新
- `BridgeControlService` 新增静态 `logDump()`/`lastError()`/`clearLogs()`；
  `onDestroy` 清理内存日志与运行态快照
- `BridgeControlClient.pushLogs()`：每次 pull 后把 `Logs.dump(80, lastSeq)` 增量
  经 `C2S_APPEND_LOGS` 送到 App；失败静默，不影响配置/状态链路
- 日志页改为读控制面内存镜像：不再检查 MCP 开关/token，不再访问 `/logs`；
  MCP 关闭时仍可查看，空态区分「等待小爱上报」与「筛选无结果」
- 主页最后错误改为 `BridgeControlService.lastError()`；调用量仍可用 `/stats`，
  但不再参与 D4/「已确认」
- 配置页推送行改为 D3：已应用 / 已保存等待应用 / 未连接 / 超 30s；不再出现
  「推送失败/令牌不一致」；运行时冲突改为读 `BridgeStatus`
- 暂停允许编辑：删除 chips/管理/密钥的禁用与触摸吞噬；暂停只停重定向，
  配置可继续修改并保存
- `StatusResolver` 修正：启用但无密钥返回「未配置」，不再把缺密钥说成「已启用」
- 新增 `StateModelTest`（四维组合）、`BridgeStatusTest`；`BridgeRegistryTest` 增加 cfgRev；
  纯 JVM 单测、`./build.sh`、`./scripts/check-consistency.sh` 全部通过

### WP7 UX 安全 + 错误目录 + 导入/导出

- 导入/还原改为「覆盖前确认」：先解析/校验/脱敏检查，再展示服务商数、当前服务商、
  模型、端点 host、Key/Token 是否设置、启用状态、version/rev；取消不改变当前编辑
- 生成 MCP 令牌前确认旧客户端会立即 401；有未保存修改时复制连接信息/Skill 先提示
  「复制的是草稿」，取消不复制；生成后提示「保存后生效」
- 打开局域网先确认公共 Wi-Fi/公司网络风险；空令牌自动生成，弱令牌提示会回退仅本机
  并让用户选择「生成并开启」；URL/JSON 速览在弱令牌时明确显示实际仅本机
- 配置损坏首次发现时弹恢复入口：恢复最新 `.legacy` 备份 / 重建默认配置 / 取消；
  原文件与 `.corrupt` 保留在私有目录，取消后保存继续被阻止
- 新增纯 JVM `core/ErrorCatalog`（8 类错误 title + action）；主页超级岛按
  无令牌/MCP 未开/小爱未连/控制面过期/端口不可达区分原因
- 主页只读信息行与关于页许可证/致谢隐藏不可点的行尾箭头；完整备份导出提示用完删除
- 新增 `ErrorCatalogTest`；`ConfigStoreTest` 增加 list/moveCorruptAside/restoreFrom；
  单测 664 断言全绿，`./build.sh` 与 `./scripts/check-consistency.sh` 通过

### WP8 发布门禁

- 新增 `scripts/verify-apk.sh`：校验 APK 已签名、包名 `plus.xiaoi.module`、
  versionCode 与 manifest 一致、签名证书 SHA-256 命中固定指纹；失败 exit 1
- 新增 `scripts/expected-signer.sha256`：固定正式签名证书指纹
  （`d7dd34b2e654562954c991730ba6ba88955ea1fcce9e13ae949f74b8b213d19b`，
  0.11.0-beta.4 ~ 0.12.0 同一证书；只是指纹，不含私钥/口令）
- `scripts/verify-apk.sh --self-test`：不依赖设备验证门禁本身——正式签名 APK 通过、
  未签名 APK 被拒、临时密钥签名因指纹不符被拒，临时文件自动清理
- `scripts/release.sh` 构建后、归档前强制校验；未签名/错签名直接退出，不生成 `dist/` 与 notes；
  `--allow-new-key` 只跳过指纹比对，仍校验已签名 + 包名 + versionCode
- CI：单测后新增 `scripts/check-consistency.sh`；签名后新增 APK 校验。
  `signed=true` 必须命中固定指纹；`signed=false` 只做基础校验并打 `::warning::`
- CI：tag 构建仅在 `signed=true` 时创建 GitHub Release；无正式密钥的 tag 仍上传 artifact，
  但跳过 Release 并输出 warning/notice
- 修复 `TestMain` 中 `UsageCounterTest` / `LanTest` 的嵌套 try/catch，
  任一测试套件异常都会计数并继续执行后续套件
- 本版为 beta：需要真机 P0 矩阵（控制面拒绝第三方、关闭即停、LAN 弱 token、远程写拒绝、
  beta.4 升级不丢配置、降级警告）通过后才考虑 0.12.0 正式版

## 0.11.0-beta.4 — 2026-11-05

> **测试版修复**：局域网/本机都能稳定轮换 MCP 令牌，不再出现「改了令牌推不进去、桥仍认旧令牌」。
>
> 交流群：QQ **1121057362**（问题反馈 / 版本适配 / 使用交流）

- `/config` 推送支持**多令牌尝试**：先试新 token，失败后自动回退上一次成功推送的 token，
  推送成功后桥内存切到新 token
- `ConfigRepository` 写 prefs 改为 `commit()` 并记录失败，保证轮换时 prefs 镜像先落盘
- Token 轮换真机验证：生成新令牌 → 保存 → 旧 token 在两端口均 401，新 token 均 200
- 新增 BridgePusher 轮换用例；JVM 单测 416 断言

## 0.11.0-beta.3 — 2026-11-05

> **测试版新能力**：MCP 支持同一局域网直连，不再必须 adb forward。
> 默认仍只绑 loopback，局域网必须显式开启且设置 token。
>
> 交流群：QQ **1121057362**（问题反馈 / 版本适配 / 使用交流）

### 局域网 MCP
- 设置页新增「允许局域网访问」开关：开启后桥监听 `0.0.0.0:8771~8775`，
  同一 Wi-Fi 的电脑可直接连 `http://<手机IP>:8771/mcp`
- 安全约束：`token` 为空时开关不生效（仍只绑 loopback）；MCP/桥开关关闭时同样只绑 loopback；
  `POST /config` 与 `POST /island` 即使开启局域网也只接受本机请求
- 开关热生效：保存后立即重绑，无需重启小爱；关闭后回到仅本机
- 配置页显示局域网 IP 与两个 URL（局域网 / 本机），复制连接信息、Skill 文案按模式生成
- `config.json` 新增 `mcp.lan`；`xiaoai_config` 返回 `lan` 字段

### 真机验收
- USB 真机（HyperOS + LSPosed）：局域网 `health`、`tools/list`（8 个）、
  `initialize`、`ping`、`xiaoai_status`、`xiaoai_cli`、`/logs`、`/stats` 均通过
- 无 token / 错误 token → 401；局域网 `POST /config`、`POST /island` → 403
- 关闭局域网开关后端口立即回到 `127.0.0.1`，局域网连接失败；重新开启恢复
- 清空 token 后即使开关为开也回到 loopback（安全降级）

## 0.11.0-beta.2 — 2026-11-05

> **测试版修复**：真机验收发现的主页状态滞后、MCP 速览工具缺失、令牌明文显示。

- 主页回到前台重新探测运行时：暂停/恢复、MCP 开关、模型切换立即反映
- 主页副行「未确认」不再被截断
- MCP JSON 速览的工具清单从 `Mcp.toolsList()` 单一来源生成（补 `xiaoai_stats`），
  endpoint 显示实际端口
- MCP 令牌默认掩码显示，避免截图/旁观泄露
- 真机验收：主页/配置/日志/关于、深浅主题、暂停/恢复、备份对话框、
  MCP health/tools/stats 均通过（USB adb）

## 0.11.0-beta.1 — 2026-11-05

> **测试版**：工程化收敛后的第一个稳定候选。接口与配置格式仍可能在小版本内调整，
> 升级前请先看本页与 Release Notes。
>
> 交流群：QQ **1121057362**（问题反馈 / 版本适配 / 使用交流）

### 信任修复
- 主页探测同时读取运行时 `enabled`：暂停后不再显示“可用”
- 状态缓存加 10 分钟 TTL，过期回到「已启用 / 待校验」
- 主页副行区分「已确认 / 未确认（小爱未运行或 MCP 未开启）」，并提示运行时与文件模型不一致
- 「输出前缀」整卡隐藏：多进程重复注入未解决前不再暴露假功能
- 日志页：/logs 失败四分类（MCP 未开 / 无令牌 / 小爱未运行 / 读取失败），点按去配置或重试；跟随最新改为真实轮询
- 导出拆分：脱敏导出只用于分享，含密钥备份可恢复；导入检测 `***` 并阻断
- MCP 复制内容从 `Mcp.toolsList()` 单一来源生成（补 `xiaoai_stats`），端口改为实际探测

### 图标
- 全套 24dp 单色矢量：home/settings/logs/info/kernel/device/refresh/license/tag/key/model/link/chevron
- 导航改用 `setImageTintList`，品牌笑脸不再被染成色块
- launcher 改 adaptive-icon（含 monochrome）

### 工程
- 颜色令牌 32 → 19，新增 `dimens.xml`、`PageTitle` 样式
- 新增 `BridgeClient`、`AppExecutors`、`StatusResolver`、`ThemeManager`
- 构建顺序改为 aapt2 → R → javac；去除 `getIdentifier`


## 未发布 — 信任修复与图标

### 产品信任
- 主页探测同时读取运行时 `enabled`：暂停后不再显示“可用”
- 状态缓存加 10 分钟 TTL，过期回到「已启用 / 待校验」
- 主页副行区分「已确认 / 未确认（小爱未运行或 MCP 未开启）」，并提示运行时与文件模型不一致
- 「输出前缀」整卡隐藏：多进程重复注入未解决前不再暴露假功能
- 日志页：/logs 失败四分类（MCP 未开 / 无令牌 / 小爱未运行 / 读取失败），点按去配置或重试；跟随最新改为真实轮询
- 导出拆分：脱敏导出只用于分享，含密钥备份可恢复；导入检测 `***` 并阻断
- MCP 复制内容从 `Mcp.toolsList()` 单一来源生成（补 `xiaoai_stats`），端口改为实际探测

### 图标
- 导航/信息行/关于页换用单色矢量图标，导航改为 `setImageTintList`
- 新增 `ic_home/settings/logs/info/kernel/device/license/tag/refresh/chevron` 等
- launcher 改 adaptive-icon（含 monochrome），品牌图标不再兼任 24dp 导航

### 测试
- JVM 新增：脱敏文件识别、工具清单单一来源断言


## 0.10.0 — 2026-11-04

### P3b 设计系统（第二批）

- 颜色令牌 32 → 19：删除 card/card_primary/card_subtle/input_bg/divider/hairline/
  surface/surface_2(旧)/hero_*/nav_bg/pressed/stroke/icon_tile_stroke 等别名，
  统一为 `bg / surface / surface_2 / line / text* / primary* / chip* / success /
  warning / warning_bg / error / error_bg / icon_tile`
- 新增 `dimens.xml`（页面/卡片/导航/FAB/字号令牌），页面边距、导航高度、FAB 尺寸、
  卡片内边距改用令牌
- 新增 `Widget.XiaoiPlus.PageTitle`，四页标题同一写法
- 日志错误行改用 `error_bg` 令牌（去掉 Java 里的 `0x1AC43D3D`）
- 真机验证：深浅两档、四个页面、标题/卡片/导航/FAB 均正常

### P3a 设计系统（第一批）

- 主页数字砖改成两个真实指标「今日三方 / 今日官方」（28sp 单行），
  删除恒为 0 的「日志条数」
- FAB 干净态改为次按钮（浅底主色字），不再用 35% 透明导致白字不可读
- 对话框主题补齐 values-night；管理/导出统一走同一套 `Theme.XiaoiPlus.Dialog`
- 删除 `values-v31` Monet 覆盖：深浅统一品牌色，避免浅色动态色、深色静态紫
- 系统 Toast 全部换成应用内提示条 `BaseActivity.showMessage()`；门禁禁止 `Toast.makeText`
- 一致性门禁新增 Toast 检查

### P0 工程地基

- `build.sh` 顺序改为 **aapt2 → 生成 R → javac**；业务代码 22 处
  `getIdentifier` 全部改为 `R.id/R.color/R.style/R.layout`，资源改名编译期报错
- 删除无引用资源：`activity_settings.xml`、`bg_accent`、`bg_card_primary`、
  `bg_card_subtle`、`bg_chip_off`
- `AndroidManifest` 显式声明 `targetSdk=26`，Gradle IDE 元数据同步（不再一边 26 一边 35）
- 新增 `scripts/check-consistency.sh`（版本/SDK/R/死资源），接入 `check-all` 与 `pre-push`

### P2 数据通路统一

- 新增 `BridgeClient`（纯 JVM）：端口发现（认 `port=`）、鉴权头、MCP 取文本、
  logs/stats/island 全部收敛；Home/Logs/ConfigSync 不再各写 HttpURLConnection
- 新增 `AppExecutors`：App 进程统一 3 个 IO 线程 + 主线程回调，替换 12 处裸 `new Thread`
- 修复 `ConfigRepository.pushAsync` 创建线程但从未 `start()` 的静默 bug
- 一致性门禁新增：禁止 App 层裸 `new Thread`、禁止额外的 HTTP 客户端
- 新增 `BridgeClientTest` 13 断言

### P1 主题单源

- 新增 `ThemeManager` / `ThemeMode`：主题只存 App prefs，`config.json` 的
  `themeMode` 读时忽略、写时不再输出（消除双源）
- `BaseActivity.onResume` 比对实际生效模式，不一致就 `recreate()`；
  被 `REORDER_TO_FRONT` 复用的旧页面也会立即切换，不再一页深一页浅
- `ConfigSync` / `save()` 不再回写主题；切主题不再需要点保存
- 配置页切主题时未保存的编辑随 Bundle 恢复，不再静默丢弃
- 新增 `ThemeModeTest`（11 断言）；JVM 总计 **353 断言**


## 0.9.0 — 2026-11-04

### 调用量统计（三方 / 官方）

- 在**真实模型客户端**上计数，一次模型调用记一次：`qm.i` OpenAIClient、
  `qm.j` ResponsesClient、`qm.b` AnthropicClient → 三方；`um.h` TaiyiLlmClient
  （mimo / 本地 AI）→ 官方。不挂 `qm.c`（DynLlmClient）与 `ft.j`（埋点包装），
  避免同一次调用被记两次
- 主对话才计入使用量（带 tools，或消息数 ≥6）；记忆抽取、意图分类、标题生成等
  子调用单独记在 `sub` 里，避免一次闲聊被算成三五次
- 账本是小爱进程 `files/xiaoi_usage.log` 的追加日志（一行一次调用，O_APPEND 跨进程
  安全），进程重启不丢、7 天后自动清理；App 读不到小爱私有目录，统一经桥取数
- 新增 `GET /stats?days=1..90`（Bearer 鉴权）与 MCP 工具 `xiaoai_stats`
- 主页「今日调用 · 三方/官方」显示当天两个数字（经桥实时取数），不再显示日志条数

### 暂停语义修复

- 此前 `enabled=false` 只停掉标记注入，`sm.a` 的 baseUrl / apiKey / model getter
  仍在覆盖，实际仍走三方；现在暂停时不改写这三个 getter，请求回到小爱原生模型
  （与配置页「关闭后小爱使用原生模型」的承诺一致）

### 测试

- 新增 `UsageCounterTest`（33 断言）：主对话判据、行编码/清洗、按天聚合、窗口过滤、
  超期清理；JVM 总计 **342 断言** 全绿
- 契约新增 `/stats` 鉴权与形状、`xiaoai_stats` 可调用，工具数 7 → 8

## 0.8.1 — 2026-11-04

### config.json 成为事实源（文件优先）

- 新增 `ConfigSync`：App 进程内 FileObserver 监听 `Android/data/plus.xiaoi.module/files/config.json`，
  改动 1~2 秒内自动应用（写 prefs 镜像 → 推小爱）。直接编辑文件即生效，不再依赖点保存
- 启动引导：config.json 缺失或仍是默认骨架（无密钥）时，自动从运行中小爱导入真实
  model/baseUrl 生成，**不再用默认值覆盖运行时**
- 运行时变更（MCP switch_model 等）不自动写回文件；配置页提供「从运行中小爱导入」
  手动回流，文件永远优先、不被静默覆盖
- 硬保护：**无密钥的配置绝不推送**，从机制上根除「保存冲掉生效配置」

### 显式 enabled 字段（暂停不再清空配置）

- 配置新增 `enabled`：暂停 = enabled=false（保留 model/baseUrl/apiKey），恢复 = true
- 推送协议、prefs、MCP `xiaoai_config`/`mergeConfig` 全链路贯通
- 测试：JVM 188 断言（含 enabled 合并与黄金串）

### 模型选择重写

- 修复 v2 配置页无「添加模型」入口、新服务商模型列表永远为空的根问题
- 选择对话框（含显示名）/ 添加模型（ID+显示名、去重）/ 管理对话框点选删除
- 删除当前激活模型自动回退列表首项

### 输出前缀（区分三方模型是否生效）

- 配置新增 `markerTemplate` 模板（`{model}` 占位符），配置页新增开关与输入框
- 响应侧注入已逆向定位并跑通（hook `sm.b$c/d` 构造器改写 delta，前缀必然出现在
  回复开头），但小爱多进程架构下同一条回复会被多次注入（实测 2~5 次），
  **暂禁用**，代码保留待单点 hook 方案；模板写入后模型会从历史学到前缀格式
- 逆向结论：`qm.j`/`qm.i` 的 build\*Request 返回 kotlinx JsonObject（非 org.json），
  直接 cast 会 CCE 被吞

### 界面修复

- 底部导航单源化（`nav_bar.xml` 共享布局 + `BaseActivity.setupNav`），
  修复跨页图标/文字乱切与配置页导航缺图标
- 配置页重写：修深色输入框白底、导航裁切、脏标记误报、外观卡运行时注入
- 主页 Hero 重设计：去「能力桥/重定向」chips，新增状态副行
  「模型重定向 · <小爱侧真实生效模型>」；状态改「已激活/未激活」
- MCP 板块去「能力桥」叫法，新增终端风格 JSON 字段速览（endpoint/auth/
  protocol/model/redirect/tools/bind，token 只显示是否设置）

### 模型校验（真实探针）

- 新增 `ModelValidator`（纯 JVM，可桌面单测）：向 baseUrl 发 max_tokens=1 的
  chat/completions，区分 可用 / 密钥无效 / 模型不存在 / 端点不可达 / 超时 / 非兼容端点
- 配置页「模型状态」行：页面打开、文件变更、保存、导入后自动校验（30s 节流），
  可点按手动重试；结果防陈旧（序列号只采纳最新一次）
- JVM 测试 +12 断言（本地 HTTP 桩覆盖 200/401/404/400/非 JSON/不可达/空参数）

### 配置文件与 UI 同步

- 外部编辑 `config.json` 后，配置页自动重载重绘并重新校验（此前需退出重进）

### P2 同步内核

- 推送改为「认端口 + 全端口聚合」：新增纯 JVM `BridgePusher`，`/health` 必须含
  `port=` 才认领（8770 冒名服务会被跳过），对每个已知桥 POST /config 并聚合结果
- 无密钥时按字段推送（marker/markerTemplate/bridge/token）：改前缀、开关、暂停
  不再被“密钥为空”整体拦掉
- 冲突基线与 onResume 兜底、推送失败记 `sync_pending` 下次启动重试、监听器注销
- 测试 +51（BridgePusherTest：正常桥/冒名/401/不可达/部分成功/载荷分组）

### P3 配置页 UI 重排

- 状态条 7 状态（未配置/待校验/校验中/可用/密钥无效/模型不存在/端点不可达/已暂停）
- 模型卡改 chips：服务商/模型点击即选；「管理」对话框统一 选/重命名/删模型/删服务商
- 保存显示真实推送结果（已推送 · N 个进程 / 失败原因 + 重试），不再无条件成功
- 冲突条：外部改文件且有未保存输入时提示，点按才载入；enabled=false 整体屏蔽点击
- MCP 终端 JSON 保留并标明「文件里的期望配置」

### P4 导入导出与密钥边界

- 导出默认脱敏，含密钥需二次确认；分享默认脱敏
- 导入走 ConfigCodec 校验，非法配置不覆盖当前编辑状态

### P1 数据层定稿

- 抽出 `ConfigCodec`（纯 JVM）：parse / toJson / normalize / validate / defaults，
  `ConfigRepository` 只保留 IO 与副作用
- 补上此前缺失的语义：悬空 activeProviderId/activeModelId 自动修正、
  字段去空白、模型去重、schema 版本保留、未知顶层字段原样写回
- 校验新增防呆：暂停（enabled=false）合法；「密钥误填成 MCP Token」明确报错
- 配置层测试从 0 到 53 断言（配置层总断言 253）；用真机 config.json 验证幂等

### P0 收口

- 修升级丢密钥：`ConfigRepository.defaults()` 从旧 prefs 回填端点/模型/密钥
- 修前缀重启失效：`Bridge.writeTargetPrefs()` 补写 `marker_tpl`（此前漏写，
  推送后当前进程有效、小爱重启后失效）
- 删除死代码 `SettingsActivity`（572 行、第三份 prefs 写入逻辑）及清单声明
- 文档纠偏：`ui-architecture.md` 去掉不存在的「打开配置文件/默认脱敏」，
  README 说明 Android 13+ 文件管理器可能看不到 `Android/data`

### 契约测试

- 端口发现改为 `/health` 自证身份（8770 被其它服务占用且会返回
  `ok facade=true`，此前会误拨）；基线更新（含 enabled 字段）

## 0.8.0 — 2026-11-03

> 地基版本：无新面向用户的功能，聚焦可测试性与一处真实 bug 修复。

### 修复：token 轮换死锁

- 症状：在设置页更换「能力桥 Token」后，推送永远 401 且静默失败；小爱 prefs 里
  留着旧 token，重启也无解——包括 MCP 客户端在内全部被锁在门外。
- 根因（两半）：`/config` 只认内存里的旧 token；且全量替换语义要求重发整份配置，
  而推送端只有新 token 和它自己那份（可能为默认值的）配置。
- 修复：`/config` 鉴权放宽为「内存 token 或模块 prefs 当前 token」匹配其一；
  `POST /config` 改为合并语义——**缺失的字段保持当前值**，换 token 只需推
  `{"token":"..."}`，不会再把小爱侧正在生效的模型配置冲掉。
- 兼容性：v1 设置页 / v2 配置页都全量发送全部字段，行为不变。

### 重构：能力桥拆出纯 JVM 层（行为不变）

- 新增 `Json`（自写 JSON 解析/写出，紧凑输出与 org.json 逐字节一致）、
  `Http`（HTTP 线协议 + Bearer 匹配）、`Mcp`（JSON-RPC 2.0 / MCP 工具表/工具执行，
  通过 `Mcp.Host` 接口隔离 Android 依赖）。
- `Bridge.java` 从 1071 行降到约 700 行，只剩 Android 接线（Xposed hook、
  CLI binder 反射、prefs 写入、socket 生命周期、鉴权策略）。
- `Logs` 去掉 org.json 依赖、Context 改反射判定，可进桌面测试。

### 测试地基（从 0 到 1）

- `tests/run.sh`：零依赖桌面测试（javac + java，无 JUnit / 无 Gradle / 无 android.jar），
  覆盖 Json / Http / Mcp / Logs 共 186 个断言，含 JSON-RPC 黄金串（逐字节锁定 v0.7 输出）。
- `scripts/contract-test.sh`：真机契约测试一条命令化（重启小爱 → 日志断言 →
  端口发现 → forward → 免鉴权/鉴权/MCP 全链路），支持 `--save-baseline` / `--check`
  基线对比（归一化版本/端口等噪声，屏蔽 baseUrl 等隐私值）。
- CI（build.yml）新增 JVM 测试步骤，先测试后构建。

### 文档对齐

- 版本号唯一来源改为明确指向 `AndroidManifest.xml`（`scripts/version.sh` /
  `release.sh` / `docs/release.md` 原先指向 build.gradle.kts，照做不生效）；
  `app/build.gradle.kts` 降级为 IDE 元数据壳并加文件头说明。
- CHANGELOG 补 0.7.0；integration.md 工具表补齐 7 个 MCP 工具；
  architecture/feature-candidates 同步 v0.5+ 的请求头标识语义与桥端点现状。

## 0.7.0 — 2026-11-03

### 设置页重构为四页结构

- 四页导航（Home / Config / Settings / Logs）+ 固定底部导航，About 独立成页
- 深色三档：跟随系统 / 浅色 / 深色，`values-night` 全量补齐；主题入口与 BaseActivity 修正
- 配置仓库 v2（`ConfigRepository`）：设置页与主页共用同一读写路径，预填与回显一致
- 设置页按 PM 方案重排分组；命名统一为「小爱同学plus」
- 主页新增版本动态显示、设备信息卡、最后错误行、桥端口 chip
- 修复：日志页崩溃、日志滚动 id、导航图标不统一
- 文档：新增远程调试指南（`docs/remote-debug.md`）与文档索引（`docs/README.md`）

## 0.6.0 — 2026-11-03

### 超级岛单次提示

- 逆向 `gp1/c`（FocusNotificationV3）与 `om/s`（焦点通知）：channel `super_island`、
  extras `miui.focus.param`（`{"param_v2":{...}}`）与 `miui.focus.pics`
- 新增 `Island.showOnce(model)`：首次走第三方模型时发一次焦点通知，6 秒后自动消失
- 新增 MCP 工具 `xiaoai_island`（`force` 可重复触发，便于验证）
- 已确认：SystemUI 收到完整 focusExtra（NotifySyncService 日志可见），通知正常投递、
  显示为「小爱+ / 第三方模型 …」卡片
- 未达成：HyperOS 的 `DynamicIslandEventCoordinator` 对当前 payload 报 `checkError`，
  未折叠成胶囊岛——缺少小爱自身的 RemoteViews（`miui.focus.rv*`）布局；下一步可复用其
  `om.t.i.layout_island_task`

## 0.5.0 — 2026-11-03

### 模型标识改走请求头（不再注入提示词）

- 移除 `instructions` / system 提示里的 `[model=...]` 规则：不再占上下文、不再依赖模型服从
- 改为 hook `sm.a.getModelProviderId()`：小爱原生的 `X-Model-Provider-Id` 头写成 `xiaoi-plus`
- 请求体只保留上游需要的 `model` 字段；标识留在传输层，模型完全看不到
- 真机验证：`模型标识已改走请求头 X-Model-Provider-Id=xiaoi-plus`；回复正文无前缀
- 注意：旧会话里模型可能模仿历史回复的前缀，这是上下文残留，不是模块注入

### 移除

- `applyMarkerResponses` / `applyMarkerChat`：不再改写请求体
- 请求 hook 保留为空实现，作为后续图文分流的接入点

## 0.4.0 — 2026-11-03

### 运行日志

- 新增 `Logs`：300 条内存环形缓冲 + logcat + 滚动落盘 `files/xiaoi_plus.log`（256KB 上限，保留 `.1`）
- 只记录 hook 成败、模型名、端口、调用摘要；**不记录 apiKey / token / 对话正文**
- MCP 新增 `xiaoai_logs(limit, since)`，可按 seq 增量拉

### MCP 配置与切模

- 新增 `xiaoai_config`：读当前生效的 model / baseUrl / marker / bridge / 密钥是否已设置
- 新增 `xiaoai_switch_model`：热切换模型，可只传 `model` 复用现有端点与 key；也可一并改 baseUrl/apiKey
- 目标：日常运维不再打开模块设置页或 LSPosed 管理器

### 跨进程配置热读（修 bug）

- `Config.current()`：800ms 节流 + prefs mtime 检查，任一进程改 prefs，其他进程 1 秒内跟上
- 此前 `POST /config` 只热更新承载桥的那个进程，首发回复仍带旧模型标记；现在切换无需重启小爱

## 0.3.0 — 2026-11-03

### 图片理解

- hook `sm.f.getSupportsVision()` 恒为 `true`：模型名不在小爱已知视觉白名单时，截图/图片不再被替换成「当前模型不支持查看图片」占位文本
- 图文改走第三方模型（实测支持视觉），不再回退原模型——回退只换 model 名、端点仍是第三方，会 404

### MCP 端点

- 能力桥新增 `POST /mcp`：标准 MCP Streamable HTTP（协议 `2025-03-26`）、JSON-RPC 2.0、无会话
- 工具：`xiaoai_status`、`xiaoai_submit`（自动 `session.open`）、`xiaoai_cli`
- 与 `/cli` 共用 Bearer token 与 loopback 限制；`GET /mcp` 返回 405
- 桥线程池 4 → 8，避免 `agent.submit` 等 LLM 回复时互相饿死

### 其他

- `chat/completions` 链路同样注入 `[model=<id>]` 标记规则（此前只有 Responses 链路）

## 0.2.0 — 2026-11-03

- 设置页改为 Shizuku 风格 UI + Monet 动态取色 + 配置预填
- 版本号改为从 AndroidManifest 读取（CI 与 release 脚本统一）

## 0.1.0 — 2026-11-03

首个可安装的预览版。0.x 阶段接口与 hook 目标都可能变化，升级前请看 Release Notes。

### 模型重定向

- hook 小爱配置对象的 `getBaseUrl` / `getApiKey`，把主对话请求指向自建 OpenAI 兼容端点（BYOK）
- 「端点」和「模型名」都非空才启用；任一为空时记录「配置为空」并跳过，小爱保持原行为
- 仅对兼容性矩阵中已验证的小爱版本生效（当前 `8.2.65.4714`），其他版本直接 no-op，避免升级后 bootloop

### 输出标记

- 向 Responses API 请求的 `instructions` 追加格式规则，回复开头显示 `[model=<id>]`
- 设置页可开关，默认开启；只影响提示词，不改上游返回内容
- 标记用于一眼确认请求确实走了自己的端点

### 本地能力桥（实验性，默认关闭）

- 在小爱进程内监听 `127.0.0.1:8770`，提供 `GET /health` 与 `/cli` 命令通道
- Bearer token 鉴权；token 留空时拒绝除首次配置外的所有请求
- 只绑定回环地址，不对局域网或公网暴露；需要用户显式打开

### 配置推送

- 设置页保存后，通过环回 `POST /config` 把配置推送给正在运行的小爱进程
- 小爱收到后热更新内存配置并幂等重装 hook，无需强制停止再打开
- 小爱未运行或模块未启用时只写本地，下次启动读取；设置页给出明确提示
- 首次配置允许在小爱尚未持有 token 时从 loopback 推送，之后必须带 Bearer

### 稳定性

- 所有 hook 均用 try/catch 隔离，异常只写日志，不抛回小爱主流程
- 未命中白名单、配置缺失、桥启动失败都按降级处理
