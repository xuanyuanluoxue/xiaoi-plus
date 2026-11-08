# UI 重写计划书（0.14，Compose + Miuix）

> 状态：**草案，待评审**
> 日期：2026-11-07
> 目标版本：`0.14.0-ui.1`（versionCode 19）
> 参考：
> - 可行性评估：`~/xiaoi-plus-dev/tmp/miuix-ui-feasibility.md`
> - 页面设计：`~/xiaoi-plus-dev/tmp/ui-page-design.md`
> - 能力清单：[../agent/CAPABILITIES.md](../agent/CAPABILITIES.md)
> - 现有设计系统：[../design-system.md](../design-system.md)
> - 现有 UI 架构：[../ui-architecture.md](../ui-architecture.md)

---

## 1. 目标与非目标

### 1.1 目标

1. 把设置 App 从 4 个 Activity + XML 迁移到 **单 Activity + Compose + Miuix**。
2. 按“两个方向”重做信息架构：
   - **小爱**：注入与强化（模型、提示词、工具、技能、记忆）。
   - **接入**：原生能力 + 借用小爱能力开放 MCP/Skill 给三方。
3. 0.14 只做 **功能 parity**：现有配置、状态、日志、备份、令牌/LAN、8770/8771 行为不变。
4. 保留 Java 核心与现有 JVM 测试；构建、签名、升级路径可复现。
5. 为 0.15（提示词/工具注入）、0.16（技能包/暴露）留出页面与数据模型位置。

### 1.2 非目标

- 不新增提示词、工具注入、技能包、记忆、ASR/TTS、场景路由功能（只做占位）。
- 不改 Xiaoai 注入逻辑（`Hook/Bridge/AgentService`）。
- 不引入 navigation-compose、DI、图片加载库、blur、Monet。
- 不上调 targetSdk；不重打包小爱；不改包名/签名。
- 不做平板双栏、横屏专用布局（0.15 再评估）。

---

## 2. 锁定决策

| # | 决策 | 说明 |
| --- | --- | --- |
| D1 | 路线 1 | Gradle 9.9 + AGP 9.5.1 + Kotlin 2.4.21 + Compose 1.12.2 + Miuix 0.9.4 |
| D2 | 单 APK 起步 | P0 spike 验证 Xposed 注入与体积；失败再拆 |
| D3 | Java 核心保留 | 只重写 UI 层；配置/状态/桥/Agent 逻辑不重写 |
| D4 | 4 个底部 tab | 首页 / 小爱 / 接入 / 我的；能力放接入二级 |
| D5 | 技能双视图 | 一套 `Skill` 数据；小爱页管注入，接入页管暴露 |
| D6 | 保存模型 | 一级主开关即时保存；二级表单显式保存；无全局 FAB |
| D7 | 旧 Config 只读 | 放“我的 > 诊断 > 兼容模式”，0.15 删除 |
| D8 | 0.14 parity | 能力页只读；审计/确认/测试台占位；危险区只做“停用全部” |
| D9 | targetSdk 26 | 不上调；不做 blur/Monet |
| D10 | 8770 无独立开关 | 跟随 `mcpEnabled + token`；LAN 由 `agentLan` 独立控制 |

---

## 3. 技术栈与构建

### 3.1 版本锁定

| 项 | 版本 | 备注 |
| --- | --- | --- |
| Gradle | 9.9.0 | wrapper 固定 |
| AGP | 9.5.1 | 需要 JDK 21 |
| Kotlin | 2.4.21 | Compose 编译器插件同版本 |
| Compose Multiplatform | 1.12.2 | Android 目标 |
| Miuix | 0.9.4 | `miuix-ui` + `miuix-preference` |
| compileSdk | 37 | Miuix AAR `minCompileSdk=37` |
| minSdk / targetSdk | 26 / 26 | 保持不变 |
| JDK | 21（构建） | Java/Kotlin target=11（Compose 1.12 要求；源码不用 11+ 语言特性） |
| build-tools | 37.0.0（Gradle 默认即可） | 不再手工调用 aapt2/d8 |

### 3.2 Gradle 结构

```text
xiaoi-plus/
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle/libs.versions.toml
├─ app/
│  ├─ build.gradle.kts
│  └─ src/main/{java,res,assets}
├─ tests/                     # 保留：纯 JVM 断言，不迁移
│  ├─ run.sh
│  └─ src/
└─ scripts/                   # check-consistency / verify-apk / release 保留并适配
```

- 单 `:app` 模块；不拆 `:core`，避免大范围移动 Java 文件。
- `tests/run.sh` 继续用 javac 编译 Java 核心；Gradle 只负责 Android APK。
- `libs/xposed-stub-src` 用 `compileOnly files(...)` 或 `compileOnly project`。

### 3.3 构建迁移的坑（必须逐项处理）

| 项 | 处理 |
| --- | --- |
| Manifest `package` | 删除，改为 `namespace = "plus.xiaoi.module"` |
| `colors.xml` 的 `<add-resource>` | 全部删除（overlay 模式遗留；Gradle 会报重复定义） |
| `assets/xposed_init` | 保持原样；R8 不能删 assets |
| R8 keep | `-keep class plus.xiaoi.module.Hook { *; }`；反射/入口类保留 |
| 签名 | 复用 `keystore/xiaoi.keystore` 与现有口令来源；`versionCode` 仍以 Manifest 为准 |
| versionCode | 18 → 19；只在 Manifest 改（或迁到 Gradle 并单一来源） |
| 多 dex | minSdk 26 原生支持；AGP 自动分包 |
| `check-consistency.sh` | 适配：保留 R 引用/线程/提示检查；增加 Compose 目录检查 |
| `verify-apk.sh` | 保留；仍校验包名/versionCode/证书指纹 |
| CI | 安装 SDK 37 + JDK 21；Gradle 缓存；保留 `tests/run.sh` |

### 3.4 体积与启动预算

| 指标 | 目标 |
| --- | --- |
| APK（R8） | ≤ 6 MB |
| APK（不 R8） | ≤ 10 MB（仅 debug） |
| 冷启动 | 不超过当前基线 + 300ms |
| 首页首帧 | ≤ 500ms（有缓存时） |
| 方法数 | 允许 multidex；不追求单 dex |

---

## 4. 信息架构

### 4.1 导航

```text
MainActivity（单 Activity）
├─ 首页      [home]      看哪里不对 + 下一步点哪
├─ 小爱      [xiaoi]     模型/端点/密钥/启用 + 未来提示词/工具/技能/记忆
├─ 接入      [access]    8771/8770/令牌/LAN/连接信息/能力清单/停用全部
└─ 我的      [me]        本机/外观/配置管理/诊断与日志/关于/危险区
```

- 底部导航只在一级页显示；二级页隐藏，返回后恢复。
- 单栈 `NavStack`：`push/pop/switchTab`；无 per-tab 栈。
- 一级根页返回：非首页 → 首页；首页 → 退出。
- 二级编辑页有 `DirtyState`，返回时弹“保存/放弃/取消”。
- 深链只用显式 Intent extras：`TAB` / `PAGE` / `FOCUS`，白名单枚举；不执行任何动作。

### 4.2 页面树

```text
首页
├─ 待处理详情（BottomSheet，可选）
└─ 最近活动 → 我的/日志

小爱
├─ 模型与服务商
│  ├─ 服务商编辑（Dialog/全屏）
│  ├─ 模型编辑（Dialog）
│  └─ 管理服务商与模型（BottomSheet）
├─ 系统提示词（0.15）
├─ 工具注入（0.15）
├─ 技能包（0.16）
├─ 记忆（0.15+）
└─ 语音与路由（0.16+）

接入
├─ 端口与服务（只读）
├─ 令牌与局域网（表单）
├─ 连接信息（BottomSheet）
├─ 能力清单
│  ├─ 动作详情
│  ├─ 工具清单
│  └─ 权限矩阵
├─ 审计（0.15）
├─ 确认队列（0.15）
└─ 测试台（0.15）

我的
├─ 外观
├─ 配置管理（备份/还原/导出/分享/导入/损坏恢复）
├─ 诊断与权限
├─ 日志（全屏）
├─ 关于（含开源许可）
├─ 兼容模式（只读，默认折叠）
└─ 危险区（停用全部）
```

### 4.3 首页 5 块

| 块 | 内容 | 数据来源 |
| --- | --- | --- |
| 状态总览 | 四维徽章、Hero 标题/副行、最近上报、主按钮 | `StateModel.Result`、`StatusCache`、`BridgeStatus` |
| 待处理 | 最多 3 条 issue，每条一个 CTA | 新增 `IssueResolver` + `PermissionProbe` + `AgentRuntime` |
| 双域入口 | 小爱卡（模型/启用）、接入卡（通道在线数/动作数） | `AppConfig`、`BridgeStatus`、`AgentRuntime`、`CapabilityRegistry` |
| 运行概况 | 今日三方/官方、8771/8770 端口与绑定 | `BridgeClient.stats`、`BridgeStatus`、`AgentRuntime` |
| 最近活动 | 最近 3 条语义事件 | 新增 `ActivityFeed`（合并 App 进程与 8771 镜像） |

首页禁止：输入框、开关、保存、模型选择、令牌/LAN/主题/备份。
首页允许：校验模型、切换模型（只导航）、超级岛、复制连接信息（可选）。

### 4.4 我的 5 块 + 危险区

| 块 | 内容 |
| --- | --- |
| 本机与版本 | App 版本/构建、设备、Android、Root（已安装/未检测到）、LSPosed（已连接/未确认） |
| 外观 | 跟随系统/浅色/深色，即时生效，不写 config |
| 配置管理 | 备份（含密钥）、还原、导出（脱敏）、分享（脱敏）、运行时导入、损坏恢复 |
| 诊断与日志 | 日志、权限与诊断、服务状态、复制诊断信息、清空本机日志 |
| 关于 | 关于、开源许可、致谢、检查更新、作者/GitHub、免责声明 |
| 危险区（折叠） | 0.14 只做“停用全部对外能力”；重置/清除数据 0.15 |

### 4.5 域页面（摘要）

**小爱一级**：状态卡（主开关即时保存）、当前模型卡、运行态卡、增强能力（规划中只读）、诊断入口。
**接入一级**：8771/8770 通道状态、令牌与安全、连接信息、能力与工具、审计/确认（占位）、停用全部。
**能力清单**：搜索 + 域/状态筛选；行 = 标题 + ID + 通道 + 风险/状态；详情 = 作用/通道/前置/schema/示例/配置入口。
**日志**：级别/域筛选、跟随、清空本机缓冲、分享；三种空态。

### 4.6 术语

| 术语 | 含义 |
| --- | --- |
| 能力桥（8771） | 小爱进程内的数据面，`xiaoai_*` 工具 |
| 动作面（8770） | App 进程前台服务，`xiaoai_action` 等 |
| 设备动作 | `ActionRegistry` 的 `app.open` 等 |
| 工具 | MCP `tools/list` 的 `xiaoai_*` |
| 技能包 | `skill.json` 注入小爱（0.16） |
| Skill 文档 | 给外部 Agent 的连接说明 |
| 四维状态 | 配置 / 端点 / 应用 / 运行 |
| 确认 token | 60 秒一次性确认令牌 |

---

## 5. 数据与状态

### 5.1 新增只读接口（UI 支撑，不是新功能）

| 接口 | 用途 | 实现位置 |
| --- | --- | --- |
| `AgentRuntime.snapshot()` | 8770 运行/端口/绑定/启动时间/最后错误 | `agent/`，Java |
| `BridgeStatus.lan/bindHost` | 8771 实际绑定，而不是请求值 | `core/BridgeStatus.java` |
| `PermissionProbe` | 通知/精确闹钟/全屏/电池优化 | 新增 Java 类 |
| `IssueResolver` | 首页待处理，纯函数可单测 | 新增 Java 类 |
| `ActivityFeed` | 最近语义事件，合并 8770 与 8771 | 新增 Java 类 |
| `CapabilityRegistry` | 能力清单静态数据 + 运行时状态 | 新增 Java 类 |
| `NavStack` | 单栈导航状态，纯逻辑 | 新增 Java 类 |

### 5.2 CapabilityRegistry

```text
CapabilityDescriptor（静态）
  id, domain, title, summary, channel, risk,
  requires[], exposedTo[], deepLink, schemas[], source

CapabilityState（运行时）
  id, status(AVAILABLE|LIMITED|PERMISSION_MISSING|UNAVAILABLE|PLANNED|DISABLED|UNSUPPORTED),
  detail, lastCheckedAt
```

- 静态数据只录入 CAPABILITIES 中 ✅/🧪 + 5 个动作 + 14 个工具；📋 用 `PLANNED`；⛔ 用 `UNSUPPORTED`。
- 🧪 映射为 `LIMITED`（部分可用/实验），不要标成“需授权”；`Counts.partial` 单独计数。
- 运行时状态由 `RuntimeProbe` 合并，**不要把 status 写死在 descriptor 里**。
- Registry 用 Java 实现，继续被 `tests/run.sh` 覆盖；不引入 Kotlin-only 逻辑到核心。

### 5.3 Skill 模型（为 0.16 预留）

```text
Skill {
  id, name, description,
  prompt,
  tools: [action ids],
  triggers: [...],
  exposure: local|lan|mcp,
  permissions, risk, examples
}
```

- 只存一份；小爱页编辑注入部分，接入页编辑暴露部分。
- 0.14 不创建、不安装、不暴露；只在设计/数据层预留。

### 5.4 刷新策略

- 首页：`onResume` + 前台 5 秒 tick；统计 15 秒节流；不可见停止。
- 小爱/接入：`onResume` + 保存后 + 手动刷新。
- 端口页：2 秒轮询（仅该页）。
- 日志：跟随开关 2 秒。
- 所有 IO 走 `AppExecutors.io`；Compose 用 `LaunchedEffect`/`produceState` 调仓库。

### 5.5 呈现规则

| 情况 | 规则 |
| --- | --- |
| 加载 | 有缓存显示缓存 + “刷新中”；无缓存骨架，150ms 后出现 |
| 8771 离线 | 内容可读；统计“—”；不显示 0 |
| 8770 未启动 | 动作清单仍在；标“不可用”；不提供执行 |
| 小爱未运行 | 8771“小爱未运行”；8770 仍可“运行中”；不合并 |
| 权限缺失 | 行内 warning + 去授权；不阻塞浏览 |
| 配置只读 | 顶部红条；保存禁用；只允许导出/恢复 |
| 统计未知 | “—” + 原因；不用 0 代替未知 |

---

## 6. 设计系统与组件

### 6.1 令牌

- 颜色：沿用 `docs/design-system.md` 的 19 个语义令牌；不新增强调色。
- 字体：28/22/20/16/14/12 + Mono；中文 `sans-serif`；不引入字体文件。
- 间距：4dp 栅格；页面 16；卡片内边距 20；卡间距 24/12。
- 圆角：卡片 24、输入 12、按钮 16、胶囊 999、FAB 18。
- 行高：信息 72 / 输入开关 56 / 次级 48；触控 ≥48。
- Hero 底色：success/warning/error 12% 透明；未配置 `surface`。

### 6.2 Miuix 组件映射

| Miuix | 用途 | 覆盖 |
| --- | --- | --- |
| `TopAppBar` | 一级/二级顶栏 | 高 56dp（默认 52） |
| `NavigationBar` | 4 tab | 高 80dp 含安全区 |
| `Card` | 卡片 | 圆角 24dp（默认 16） |
| `Button` | 主/次按钮 | 高 48dp（默认 40） |
| `Switch` | 主开关/LAN | 整行 toggleable |
| `TextField` | 输入 | 高 44dp、圆角 12dp（默认 16） |
| `Dialog` / `BottomSheet` | 确认/连接信息 | 圆角 24dp |
| `Snackbar` | 全局提示 | 替代 Toast |

### 6.3 自研组件

`StatusHero`、`DimensionBadge`、`StatusBadge`、`RiskBadge`、`IssueRow`、`StatTile`、`PortRow`、`InfoRow`、`SwitchRow`、`ValueRow`、`CapabilityRow`、`CodeBlock`、`PermissionRow`、`EmptyState`、`DirtyBar`、`ConfirmCard`、`FilterChips`、`SearchField`、`LoadingSkeleton`。

### 6.4 无障碍与适配

- 所有触控 ≥48dp；图标装饰性 `contentDescription=null`；承载语义的图标必须有文字。
- 四维徽章 `stateDescription`，不能只靠颜色。
- `SwitchRow` 整行 toggleable，内部 Switch 不重复响应。
- 字体缩放 1.0/1.3/2.0 不裁切；行高用 `heightIn`，不写死。
- 深浅色两套截图；不做阴影/渐变/描边分层。

---

## 7. 迁移映射

### 7.1 旧 Config 9 组

| 旧分组 | 新位置 |
| --- | --- |
| 顶部冲突条 | 小爱/模型编辑页 + 小爱一级运行态 |
| 状态条 + 推送行 | 首页状态总览 + 小爱一级状态卡 |
| 总开关 | 小爱一级主开关 |
| 模型/服务商/密钥/校验 | 小爱 > 模型与服务商 |
| 输出前缀（隐藏） | 不迁入可见页面 |
| MCP/8771/8770/LAN/token/URL/JSON | 接入一级 + 令牌与局域网 + 端口与服务 + 连接信息 |
| 配置导入导出备份还原分享 | 我的 > 配置管理 |
| 外观/主题 | 我的 > 外观 |
| 底部导航 + 脏标记/FAB | 新 4 tab + 二级 DirtyBar |

### 7.2 旧 Home

| 旧元素 | 新位置 |
| --- | --- |
| Hero 状态 | 首页状态总览 |
| 今日三方/官方 | 首页运行概况 |
| 校验模型/切换模型/超级岛 | 首页快捷条 |
| 当前模型/接口地址 | 小爱一级 + 模型页 |
| 内核/Android/Root/版本 | 我的 > 本机与诊断 |
| 异常单行 | 首页待处理 |
| 底部导航 | Miuix NavigationBar |

### 7.3 Logs / About / Nav / BaseActivity

- Logs → 我的 > 日志（全屏二级）。
- About → 我的 > 关于 + 开源许可。
- `Nav.go`/`FLAG_ACTIVITY_REORDER_TO_FRONT` → `NavStack`。
- `BaseActivity.showMessage` → 全局 `SnackbarHost`。
- `ThemeManager` 保留；`XiaoiTheme` 读取；`MainActivity` 重建。

### 7.4 保存与草稿

- 主开关即时保存；失败回滚开关 + Snackbar。
- 二级表单显式保存/取消；失败保留草稿。
- 旋转/进程死亡：`rememberSaveable` 保存草稿；恢复后仍 dirty。
- 外部文件变更：有脏数据时黄条“载入文件/保留编辑”；无脏数据自动重载。

---

## 8. 工作包与排期

> 估算为人日，含真机验证；可按阶段单独发布。

### P0 Spike（3–5 天）——先验证最大未知数

| # | 任务 | 产出 | 验收 |
| --- | --- | --- | --- |
| P0.1 | 新建 Gradle 工程（分支 `feat/ui-compose`，worktree 放 `~/xiaoi-plus-dev/worktrees/`） | 可 `assembleDebug` | 构建成功 |
| P0.2 | 迁 namespace、删 `add-resource`、签名/versionCode、assets/xposed_init | 可覆盖安装 | 包名/签名/versionCode 正确 |
| P0.3 | 引入 Compose + Miuix，新增一个 `SpikeActivity`（Miuix Card/Switch） | 页面可打开 | 深浅色正常 |
| P0.4 | LSPosed 注入与模型重定向回归 | 真机验证 | 小爱仍走第三方模型 |
| P0.5 | 体积/冷启动/内存基线；观察小爱进程类加载 | 数据记录 | APK ≤10MB（debug）；无崩溃/冲突 |
| P0.6 | Go/No-Go 评审 | 决策记录 | 通过则进 P1；失败回退路线 2 或拆 APK |

**风险闸门**：如果 Xposed 注入出现 Compose 类冲突或小爱启动异常，立即暂停并评估拆 APK。

### P1 地基（5–7 天）

| # | 任务 | 验收 |
| --- | --- | --- |
| P1.1 | `MainActivity` + `NavStack` + 4 tab + 顶栏/底栏 | 4 tab 可切换；返回规则正确 |
| P1.2 | `XiaoiTheme` + 令牌 + 排版 + 深浅色 | 主题即时切换；无裸色值 |
| P1.3 | 基础组件：Card/Button/TextField/InfoRow/SwitchRow/StatTile/Badge/EmptyState/CodeBlock/DirtyBar | 组件在 Logs/About 页可用 |
| P1.4 | SnackbarHost + `showMessage` 桥 | 旧调用可迁移；无 Toast |
| P1.5 | 日志页 + 关于页 | 功能 parity；三种空态 |
| P1.6 | `PermissionProbe` / `IssueResolver` / `NavStack` 单测 | `tests/run.sh` 全绿 |
| P1.7 | 深链 extras + 通知点入 | 8770 通知 → 接入；未知 → 首页 |

### P2 页面（10–15 天）

| # | 任务 | 验收 |
| --- | --- | --- |
| P2.1 | 首页 5 块 + `ActivityFeed` + `AgentRuntime` | 五种状态截图；无配置控件 |
| P2.2 | 小爱一级 + 模型与服务商 | 开关即时保存；表单显式保存；校验/冲突/损坏 |
| P2.3 | 接入一级 + 端口/令牌/连接信息 | 8771/8770 分开；弱令牌回退；复制含提示 |
| P2.4 | 能力清单 + 详情 + 工具清单 + 权限矩阵 | 只读；筛选；schema/示例 |
| P2.5 | 我的 5 块 + 危险区 | 备份/还原/脱敏/导入；停用全部 |
| P2.6 | 兼容模式（只读） | 默认折叠；无保存入口 |
| P2.7 | 删除旧 Activity/layout（保留兼容视图） | 无两套可编辑入口 |

### P3 收尾（5–8 天）

| # | 任务 | 验收 |
| --- | --- | --- |
| P3.1 | R8 + shrinkResources + keep 规则 + multidex | Release APK ≤6MB；Hook 正常 |
| P3.2 | CI：SDK 37 + JDK 21 + Gradle 缓存 + 签名 | CI 全绿；Release 资产可验签 |
| P3.3 | `check-consistency.sh`/`verify-apk.sh` 适配 | 门禁全绿 |
| P3.4 | 截图矩阵 + 功能回归 | 4 tab × 2 主题 × 状态；配置/日志/8770/8771 正常 |
| P3.5 | 文档：README/design-system/ui-architecture/CHANGELOG/HANDOVER | 与实现一致 |
| P3.6 | 发布 `0.14.0-ui.1` | prerelease；资产指纹正确 |

**总计：23–35 人日。**

---

## 9. 测试与验收

### 9.1 自动

- `tests/run.sh`：Java 核心 + `IssueResolver` + `CapabilityRegistry` + `NavStack` + `PermissionProbe` 纯逻辑。
- `check-consistency.sh`：R 引用、无裸色值、无线程违规、drawable 引用、版本号。
- `verify-apk.sh`：包名/versionCode/证书指纹。
- CI：`assembleRelease` + 单测 + 门禁 + 签名校验。

### 9.2 手工/脚本

- 截图矩阵：首页/小爱/模型/接入/能力/我的/日志 × 浅深 × 正常/空/错。
- 功能回归：
  - 配置保存、暂停/启用、模型校验、服务商/模型 CRUD；
  - 8771 启停、8770 启停、令牌轮换、LAN 开关、复制连接信息；
  - 通知/闹钟/音量/打开 App（8770 5 个动作）；
  - 备份/还原/脱敏导出/导入/损坏恢复；
  - 日志筛选/跟随/清空/分享；
  - 深链（通知点入）、主题切换、旋转/进程死亡草稿恢复；
  - LSPosed 注入、模型重定向、图片理解。
- 性能：冷启动、首帧、APK 体积、内存（首页/配置页）。

### 9.3 0.14 验收清单

- [ ] 4 tab 导航与返回规则正确；二级页隐藏底栏。
- [ ] 首页 5 块齐全；无配置控件；四维状态只有 D4 能显示“已确认”。
- [ ] 待处理最多 3 条；0 条显示“一切正常”；每条有 CTA。
- [ ] 小爱主开关即时保存；模型页显式保存；冲突/损坏/只读正确。
- [ ] 接入 8771/8770 分开；弱令牌回退；复制提示含令牌。
- [ ] 能力清单只读；5 动作 + 8 + 6 工具详情可打开。
- [ ] 我的 5 块 + 危险区；停用全部列出影响；无重置/清除。
- [ ] 旧 Config 不可编辑；兼容模式只读。
- [ ] 深浅色、字体缩放 1.0/1.3/2.0、TalkBack 关键项通过。
- [ ] APK ≤6MB；LSPosed/模型重定向/8770/8771 回归通过。
- [ ] 无假开关/无空按钮/无“规划中”可点击但无效果。

---

## 10. 风险与回退

| 风险 | 概率 | 影响 | 缓解 |
| --- | --- | --- | --- |
| Miuix AAR 与 AGP/D8 不兼容 | 中 | 阻断 | P0 先验证；必要时降级 Miuix 版本或只借设计 |
| Compose 类进入 Xposed 注入进程 | 中 | 小爱异常 | Compose 只挂 MainActivity；P0 注入实测；失败拆 APK |
| APK 体积/启动超预算 | 中 | 体验 | R8 + shrink；基线对比；移除 icons/blur/nav |
| targetSdk 26 + Compose insets | 中 | 布局错位 | 手动 insets；P1 真机验证 |
| Miuix 0.x API 变更 | 中 | 维护 | 锁定 0.9.4；封装组件，不散用 |
| 构建不可复现 | 中 | CI/发布 | lockfile/依赖锁定；Gradle cache；固定 JDK/SDK |
| 两套保存管线 | 低 | 数据覆盖 | 旧 Config 只读；统一 `ConfigRepository` |
| 草稿丢失 | 中 | 用户输入 | `rememberSaveable` + DirtyState 测试 |
| 页面范围蔓延 | 高 | 延期 | 严格 parity；0.15 功能只做占位 |

**回退策略**：
- 整个重写在独立分支/标签；`0.13.0-agent.2` 保持可发布。
- P0 失败 → 回退路线 2（Java/XML 视觉重写），不继续投入。
- 单 APK 失败 → 拆“模块壳 + 设置 App”，P0 决策记录留档。

---

## 11. 发布计划

| 版本 | 内容 |
| --- | --- |
| `0.14.0-ui.1` | Compose/Miuix UI + 新 IA；功能 parity |
| `0.14.0-ui.2` | 真机回归修复；性能/体积优化 |
| `0.15.0` | 系统提示词 + 工具注入 |
| `0.16.0` | 技能包 + 接入暴露（MCP/Skill） |

发布门禁：
- `tests/run.sh`、`check-consistency.sh`、`verify-apk.sh` 全绿。
- LSPosed 注入、模型重定向、8770/8771 真机回归通过。
- APK ≤6MB；签名指纹不变；versionCode 递增。
- Release 资产 `.sha256` 与 APK 匹配。

---

## 12. 未决问题

| # | 问题 | 当前建议 | 决定时间 |
| --- | --- | --- | --- |
| 1 | 单 APK 还是拆 APK | P0 后决定 | P0 结束 |
| 2 | 是否保留“高级模式”可编辑旧页 | 不保留；只读兼容 | P1 前 |
| 3 | 首页是否加“今日动作数” | 0.14 不加；0.15 评估 | P3 |
| 4 | 是否做平板双栏 | 0.14 不做 | 0.15 |
| 5 | 是否引入 `miuix-icons` | 不引入；保留现有矢量 | P3 |
| 6 | 是否加应用内更新 | 不做；跳 GitHub Releases | 0.15 |

---

## 13. 附录

### 13.1 代码映射

| 能力 | 文件 |
| --- | --- |
| 模型/提示词/vision/统计 | `app/src/main/java/plus/xiaoi/module/Hook.java` |
| 8771 工具 | `Mcp.java`、`Bridge*.java` |
| 8770 工具 | `agent/AgentMcp.java` |
| 5 个动作 | `agent/Actions.java`、`agent/ActionRegistry.java` |
| Android 执行器 | `agent/AndroidExecutor.java` |
| root 执行器 | `agent/RootExecutor.java`、`agent/RootCommands.java` |
| 闹钟 | `agent/AgentAlarmReceiver.java`、`agent/AlarmTimes.java` |
| 状态 | `core/StateModel.java`、`core/BridgeStatus.java`、`StatusCache.java` |
| 配置 | `ConfigRepository.java`、`AppConfig.java`、`ConfigCodec.java` |
| 主题 | `ThemeManager.java`、`Prefs.java` |
| 旧 UI | `HomeActivity/ConfigActivity/LogsActivity/AboutActivity`、`ui/config/*` |

### 13.2 相关文档

- [能力清单](../agent/CAPABILITIES.md)
- [设计系统](../design-system.md)
- [UI 架构](../ui-architecture.md)
- [Agent 计划](../agent/PLAN.md)
- [Phase 1](../agent/PHASE1.md)
- [交接说明](../../HANDOVER.md)

### 13.3 变更控制

- 本文件是 0.14 UI 重写的唯一计划源；范围变更先改本文件并记录原因。
- 任何新增页面/开关必须回答：属于小爱、接入、我的哪一处？是否 0.14 parity？是否有数据来源？
- 任何“未来功能”在 0.14 只能以只读占位出现，不能有假开关。
