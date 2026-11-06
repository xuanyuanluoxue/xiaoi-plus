# 0.12.0 工作包与子 Agent 指令

> 上游：[PLAN.md](PLAN.md)
> 原则：**一个工作包一个子 Agent**；每个包给死范围、验收标准、必跑测试、禁止改的文件。
> 所有包都必须保持：Java 1.8、无 Gradle、无第三方运行时依赖、手写 XML View、`R.*` 资源、
> `AppExecutors` 后台任务、`BaseActivity.showMessage()` 提示、失败即降级。

## 通用子 Agent 合同

每个开发 Agent 的指令都必须包含以下段落，缺一不可：

```text
你是 xiaoi-plus 的 <WPx> 开发 Agent。仓库：/home/chatxavier/code/xiaoi-plus。

背景：<为什么做这个包，上游决策>
目标：<一句话可判定的目标>
范围（必须做）：<逐条>
非范围（禁止做）：<逐条；特别列出禁止顺手改的文件/功能>
必须阅读：<文件列表>
实现约束：Java 1.8 / 无 Gradle / 无第三方依赖 / 手写 View / R.* / AppExecutors /
          BaseActivity.showMessage / 失败即降级 / 不把密钥写进日志
验收标准：<可测试、可判定>
必须跑的测试：<命令>
输出：<改了哪些文件、为什么、测试结果、未覆盖项、风险>
完成定义：<构建/单测/一致性/真机项>
禁止：不要改本包以外的文件；不要提交；不要发 Release；不要写“大概/可能”。
```

---

## WP1：控制面 Service + UID + 配置拉取

**目标**：App 暴露 `BridgeControlService`，小爱进程通过 Binder 拉配置、注册回调、上报状态/日志；
彻底移除 App → HTTP 配置推送的入口。

**范围**
- 新增 `control/BridgeControlService.java`（App 进程）
- 新增 `control/BridgeControlBinder.java`（Binder 方法 + UID 校验）
- 新增 `control/UidGuard.java`（voiceassist UID/包名校验）
- 新增 `control/InstanceRecord.java` / `control/BridgeRegistry.java`（App 侧实例表）
- 新增 `core/ControlKeys.java`（Bundle key 常量，纯 JVM）
- `AndroidManifest.xml` 注册 service（显式 ComponentName，exported）
- App 侧读取私有 config.json，`pullConfig()` 返回 JSON 快照 + rev
- 小爱侧 `BridgeControlClient`（绑定、重连、超时、死亡回调）
- `register()` 携带 `legacyJson`（桥读取 target/module prefs 的 0.11 快照）
- `reportState()` / `appendLogs()` 最小实现，App 先落内存/日志
- **本包是增量**：保留旧 `BridgePusher`/`/config` 推送路径，控制面通过 feature flag
  `controlPlaneEnabled` 逐步启用；WP3 才切换主路径，WP4 才删除旧路径

**非范围**
- 不删除 `BridgePusher`/`/config`（WP4）
- 不改状态 UI（WP6）
- 不改 ConfigActivity 布局（WP5）
- 不实现 HMAC/Provider/配对码

**验收**
- 第三方测试 App bind 被 `SecurityException` 拒绝
- `adb shell`（UID 2000）调用被拒绝
- voiceassist 能 bind、pullConfig 拿到 config.json 内容
- App 能通过 callback 发 `reload`，桥收到并重新 pull
- App force-stop 后桥重连有退避，不崩溃、不刷日志
- feature flag 关闭时，旧推送路径行为与 beta.4 一致（回归）

**测试**
- 纯 JVM：`ControlKeys`、UID 允许集合策略、registry 状态机
- 真机：主用户/工作资料/双开 UID、force-stop、低内存
- `./tests/run.sh`、`./build.sh`、`./scripts/check-consistency.sh`

**风险**
- MIUI 后台 bind 限制；Service 拉起 App 失败 → 需真机验证，备选是 Provider（仅兼容）
- Binder 传 apiKey/token 必须只在同机内核，不落日志

**规模**：L

---

## WP2：配置单一事实源 + 私有文件 + 迁移

**目标**：`config.json` 成为 App 私有目录下的唯一事实源，带 `rev`；target prefs 只做降级缓存；
旧配置升级不丢，损坏不静默覆盖。

**范围**
- `ConfigRepository`：私有 `getFilesDir()/config.json`；原子写（tmp + fsync + rename，不先删目标）
- `ConfigCodec`：schema v3（`rev`）；`isSkeleton` 仅内存/迁移标记；未知字段保留
- 新增 `core/ConfigStore.java` / `core/ConfigMigration.java`（纯 JVM 读写/迁移/校验）
- 解析失败：保留原文件、写 `.corrupt.<ts>`、UI 只读态（WP7 接 UI）
- 首次升级：私有文件不存在时，用控制面 `legacyJson` 展示“采用旧配置”
- 删除 `Config.forTarget()` 的 `marker/bridge` 硬编码；target prefs 读取解析全部键
- `ConfigSync` 改为“监听私有文件 + 通知桥 reload”；旧 `pushAsync` 标记为 legacy，
  仅在 `controlPlaneEnabled=false` 时保留（WP3 切换后删除）
- `ConfigRepository.writePrefs` 只写 legacy 兼容键，带 `cfgRev`
- `config.json` 不再写 `Android/data`
- **过渡策略**：WP2 与 WP3 必须同一发布列车；如果 WP2 单独合并，保留旧 HTTP 推送，
  通过 `controlPlaneEnabled` flag 控制，避免热更新中断

**非范围**
- 不改 MCP 工具（WP4）
- 不改主页状态（WP6）

**验收**
- 新装：默认骨架有明确标记，不会被当成真实配置
- beta.4 只有 target/module prefs、没有 config.json → 升级后能“采用旧配置”，不丢 key/token
- 手动写坏 config.json → 保留原文件 + `.corrupt`，UI 提示，不覆盖
- 保存后 `rev` 递增；桥拉到的 `rev` 与 App 一致
- MCP-only（无 baseUrl/model）仍能保存和读取 token/lan/bridge
- 旧 `config.json` v2 可迁移；未知字段保留；降级可读

**测试**
- 纯 JVM：`ConfigStore` 原子写/损坏/迁移/未知字段/rev/skeleton
- 真机：beta.4 升级、清数据、恢复备份、外部改坏文件

**规模**：L

---

## WP3：Bridge 生命周期 + leader 选举 + 关闭即停

**目标**：MCP 关闭时彻底关闭数据面；全机只有一个 HTTP 监听；重启小爱后状态保持；
不依赖主进程/ facade 所在进程。

**范围**
- 新增 `core/BridgePolicy.java`（纯 JVM：shouldListen/bindHost/token 长度/版本支持）
- 新增 `bridge/BridgeLifecycle.java`（start/stop/rebind/takeover）
- 新增 `bridge/LeaderElection.java`（bind 8771~8775，30s 重试）
- `Hook`：版本未知不启动桥；只在配置加载后尝试 leader；其他进程只 hook
- `Bridge`：`mcp.enabled=false` → close server、清会话、`sStarted=false`
- `Config.forTarget()` 读取 `bridge`/`marker`（不再硬编码）
- leader 通过控制面上报 port/instance；App 不再扫描端口
- 数据面 `BridgeServer` 核心拆到 `core/`（HTTP 解析/路由/鉴权可测），Android 只接线
- **切换配置传输**：`controlPlaneEnabled=true` 后，`ConfigSync`/`ConfigRepository` 不再走
  旧 HTTP 推送；保留旧路径一个版本作为回滚开关
- 与 WP4 同一发布列车；WP3 单独合并时旧推送仍可用

**非范围**
- 不改 MCP 工具参数（WP4）
- 不改状态 UI（WP6）

**验收**
- `ss -ltn` 在关闭后无 8771~8775；重启小爱仍无
- 三进程共存时只有一个监听；leader 被杀后 30s 内接管
- 只启动 `:core` 时也能接管（不依赖主进程）
- 版本未知/不在白名单时不监听、不 hook 错误方法
- App 不在线时控制面不可用 → 数据面不启动或按 provisioned 策略安全降级

**测试**
- 纯 JVM：`BridgePolicy` 组合、leader 状态机、HTTP 核心
- 真机：只主进程 / 只 :core / 三进程、force-stop、杀 leader、版本未知

**规模**：M

---

## WP4：数据面加固 + `/config` 410 + MCP 写权限

**目标**：数据面只服务外部 MCP/CLI；不再接收配置；LAN 强制强 token；危险 MCP 写参数被拒绝。

**范围**
- `/config` 返回 `410 Gone`，不解析 body
- 删除 `BridgePusher`、`BridgeClient.discover` 及对应测试/基线
- `Mcp.xiaoai_switch_model`：只允许 `model`/`marker`/已有 provider 选择；
  `baseUrl`/`apiKey`/`bridge`/`lan` 明确报错
- `Mcp` 工具描述/instructions 更新；删除“所有调用都在 loopback”等过期文案
- LAN：token 必须 32 hex；短 token 只允许 loopback；开 LAN 时提示轮换
- `/cli`：LAN 可用但必须 Bearer；MCP `xiaoai_cli` 同样
- HTTP：有界队列、最大连接数、header 行/总长度上限、所有端点限流
- `/health` 只接受 Bearer，不再作为身份依据
- `/island` 保留 loopback-only 兼容；App 走控制面
- `Http.reason(403)` 等补齐

**非范围**
- 不改控制面（WP1）
- 不改状态 UI（WP6）

**验收**
- 旧 App/脚本 POST `/config` → 410，body 不进入解析/日志
- 远程 `xiaoai_switch_model` 带 `baseUrl`/`apiKey`/`bridge`/`lan` → `isError=true`
- LAN 6 位 token → 拒绝/强制轮换；32 hex → 正常
- 并发 100 个慢连接不拖垮 `/health`；超长 header 被拒
- `ss`/契约：MCP 关闭后无监听

**测试**
- 纯 JVM：Mcp 参数拒绝、Http 上限/限流、token 长度策略
- 契约：`/config` 410、LAN 403、弱 token、MCP 写拒绝
- 删除旧 `BridgePusherTest`/`BridgeClientTest` 或改造成新路径

**规模**：M

---

## WP5：ConfigActivity 机械拆分（无行为变化）

**目标**：在改状态/错误之前，把 1600+ 行页面拆成可维护 section；**不改任何行为/文案/布局**。

**范围**
- 抽 `ui/config/StatusSection`、`ModelSection`、`McpSection`、`EditSection`、`AppearanceSection`、`PushController`
- 只移动代码，不改 ID、点击、校验、文案
- 保留 `ConfigActivity` 作为 shell；`render()` 委托给 sections
- 更新 AOCI/README 的 UI 架构说明

**非范围**
- 不改状态模型（WP6）
- 不改错误文案/确认框（WP7）
- 不引入新依赖/架构

**验收**
- 截图/交互与 beta.4 一致
- 460 断言全绿；`build.sh`/一致性通过
- 无新增 `getIdentifier`、裸 Thread、Toast、未引用 drawable

**规模**：M

---

## WP6：状态模型 + 主页/配置页/日志页

**目标**：四维状态分离；校验模型不再伪造“已确认”；暂停可编辑；日志/诊断走控制面。

**范围**
- 新增 `core/StateModel.java`（D1~D4 + 迁移，纯 JVM）
- `HomeActivity`：只从控制面拿 D3/D4；删除 `renderStatus(c,true,...)` 伪造
- `ConfigActivity`：保存只依赖文件写入；显示“已保存，等待小爱应用”；暂停允许编辑
- `LogsActivity`：通过控制面拿日志；不再要求 MCP 开启
- `ModelValidator`：只更新 D2；“端点可用”与“已确认”分开
- `StatusResolver`/`AppStatus`：删除“无 key 也 ENABLED”的歧义
- 图标/死箭头：不可点行去掉箭头或补行为（仅限本包涉及页）

**非范围**
- 不改控制面协议（WP1）
- 不改配置迁移（WP2）

**验收**
- 校验模型成功 → 只显示“端点可用”，D4 仍为“未确认”
- 保存时桥不在线 → “已保存，等待小爱应用”，不显示“推送失败/令牌不一致”
- 暂停时能改服务商/模型/密钥
- 日志页在 MCP 关闭时仍能读（控制面可用）
- D1~D4 组合有单测；UI 文案与状态矩阵一致

**测试**
- 纯 JVM：StateModel 全组合、StatusResolver、ModelValidator 只改 D2
- 真机：暂停/恢复、桥不在线、校验成功/失败、日志页

**规模**：L

---

## WP7：UX 安全 + 错误目录 + 导入/导出

**目标**：危险操作可理解、可恢复；配置文件损坏有明确出口；去掉误导性文案。

**范围**
- 导入/还原：覆盖前确认 + 变更摘要（模型/端点/token 是否设置）
- 生成/轮换 token：确认“旧客户端立即失效”；复制前提示“先保存”
- LAN 开关：保存后生效的明确提示 + 公共 Wi-Fi 风险确认；显示实际绑定地址
- 清空密钥：提供“暂停并清空”路径
- 配置文件损坏：顶部红色提示 + “恢复 .bak / 重新开始”
- 错误目录：把 `health 401/小爱未运行/MCP 未开/无 token` 分开
- 脱敏导出/完整备份：导出后提示删除；`ShareProvider` 只暴露必要路径
- 关于页构建日期/commit 真实化；不可点箭头清理；`strings.xml` 迁移本包新增文案

**非范围**
- 不改安全协议（WP1/WP4）
- 不做全量 i18n/大改视觉

**验收**
- 导入覆盖有确认和 diff；取消不改变当前编辑
- 生成 token 有确认；未保存复制有提示
- 配置文件损坏有可行动出口；不会静默回默认
- 错误提示能区分至少 4 种原因
- 新增文案全部走 `strings.xml`（本包范围）

**规模**：M

---

## WP8：发布门禁 + 测试 + 真机矩阵 + beta

**目标**：把 0.12.0-beta.1 安全地发出去；门禁能阻止未签名/未测试产物。

**范围**
- `scripts/verify-apk.sh`：校验签名证书 SHA-256（支持 `KEYSTORE_FILE`/pinned cert）
- `release.sh`：构建后强制 verify；失败不归档
- CI：增加 `check-consistency.sh`；tag 构建 `signed=true` 才建 Release
- 测试：补 P0 纯 JVM + 真机矩阵；修复 `TestMain` 嵌套 try
- 文档：README/security/integration/architecture/HANDOVER/CHANGELOG 更新
- 版本：`0.12.0-beta.1`（versionCode 递增）；tag + pre-release
- 真机矩阵：见 PLAN §6 + 安全评审清单

**非范围**
- 不修功能 bug（回到对应 WP）

**验收**
- 无 secrets 的 tag 构建不创建 Release
- 正式签名指纹与 beta.4 一致
- 单测/一致性/构建全绿
- 真机 P0 矩阵通过：控制面拒绝第三方、关闭即停、LAN 弱 token、远程写拒绝、
  beta.4 升级不丢配置、降级警告
- Release Notes 明确测试版、破坏性变更（`/config` 410、配置私有化）、QQ 群

**规模**：M

---

## 依赖与合并顺序

```text
WP1 控制面 ──┬── WP2 配置 ──┬── WP6 状态/UI ── WP7 UX ── WP8 发布
             ├── WP3 生命周期 ── WP4 数据面 ──┘
             └── WP5 机械拆分（必须在 WP6 前）
```

- WP1 与 WP2 可以并行接口设计，但 WP2 的 `pullConfig` 依赖 WP1 的 Service。
- WP3 依赖 WP1 的 register/report；WP4 依赖 WP3 的 leader。
- WP5 必须在 WP6 前完成；WP7 在 WP6 后。
- 每个包独立 commit；合并前跑 `tests/run.sh` + `build.sh` + `check-consistency.sh`。
- 任何包不得跨包改文件；需要跨包改动时，拆成新包或回到主 Agent 重新排期。

## 子 Agent 交付格式

```text
1. 改动文件列表（含新增/删除）
2. 每个文件改了什么、为什么
3. 验收标准逐条自检（通过/未通过/未覆盖）
4. 测试命令与结果
5. 未覆盖项/风险/需要主 Agent 决策
6. 没有做的事（明确列出非范围）
```

主 Agent 审核通过后才合并；安全相关包（WP1/WP3/WP4）必须再过一次独立复核。
