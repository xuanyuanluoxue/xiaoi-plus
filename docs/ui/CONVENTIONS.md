# UI 开发规范（Compose / Miuix）

> 状态：**生效**（0.14 起）
> 适用范围：`app/src/main/java/plus/xiaoi/module/ui/**`、`MainActivity.kt`、`capability/**`、`core/**` 的新增 UI 支撑类。
> 关联：[PLAN.md](PLAN.md)、[COMPONENTS.md](COMPONENTS.md)、[SCREENS.md](SCREENS.md)、[BUILD.md](BUILD.md)。

## 1. 总原则

1. **Java 核心不动**：`ConfigRepository/StateModel/BridgeStatus/AgentService/Actions/Mcp` 继续用 Java；Kotlin 只做 UI、导航与展示状态。
2. **单一事实源**：配置写入只走 `ConfigRepository`；状态结论只走 `StateModel/BridgeStatus/ErrorCatalog`；UI 不复制判断。
3. **先组件，后页面**：页面只能引用 [COMPONENTS.md](COMPONENTS.md) 的组件与主题令牌，不允许自写卡片/行/徽章样式。
4. **不做假功能**：0.14 未实现的能力只能显示“规划中”只读行，不给可点击但无效的开关/按钮。
5. **安全默认**：高风险能力默认关闭；危险操作必须二次确认；UI 不显示 token/key 明文。

## 2. 目录与命名

```text
app/src/main/java/plus/xiaoi/module/
├─ MainActivity.kt                 # 唯一 Activity
├─ ui/
│  ├─ nav/Route.kt                 # 路由 sealed interface
│  ├─ nav/NavStack.kt              # 可选：Java 纯逻辑，放 core
│  ├─ theme/XiaoiTheme.kt          # 主题入口
│  ├─ theme/Tokens.kt              # 颜色/尺寸/字体令牌映射
│  ├─ components/                  # 通用组件（见 COMPONENTS.md）
│  ├─ screen/home/                 # HomeScreen + HomeStateHolder
│  ├─ screen/xiaoai/               # XiaoaiScreen + ModelScreen
│  ├─ screen/access/               # Access/Bridge/Token/Connect/Capability
│  ├─ screen/me/                   # Me/Appearance/ConfigManage/Logs/About
│  └─ util/                        # io{}、格式化、剪贴板
└─ core/                           # 既有 Java 核心 + IssueResolver/CapabilityRegistry/NavStack/ActivityFeed
```

命名规则：

| 类型 | 规则 | 示例 |
| --- | --- | --- |
| Screen | `XxxScreen` | `HomeScreen` |
| 状态持有 | `XxxStateHolder` | `HomeStateHolder` |
| 通用组件 | `XiaoiXxx` | `XiaoiCard` |
| 业务组件 | 语义名 | `StatusHero`、`IssueRow` |
| 路由 | `Route.Xxx` | `Route.Home`、`Route.Model` |
| 状态数据 | `XxxUiState` | `ModelUiState` |
| 事件 | `onXxx` | `onSave`、`onRetry` |

包名统一 `plus.xiaoi.module.ui.*`；不要新建 `com.example`、不要按功能复制一套 `common`。

## 3. Kotlin / Compose 规则

- Kotlin/Java `target = 11`（Compose 1.12 要求；AGP 9 不允许 Java/Kotlin target 不一致）；源码仍不使用 Java 11+ 语言特性。
- 只用 Compose + Miuix + 既有 Java 核心；**不引入** navigation-compose、Hilt/Koin、Retrofit、Coil、Accompanist。
- 所有 Composable 默认 **无状态**：参数是 `UiState` + 回调；状态提升到 StateHolder。
- 不在 Composable 里直接读 `ConfigRepository`、`BridgeClient`、`SharedPreferences`；通过 StateHolder 注入数据。
- `@Composable` 函数保持小而纯；长页面拆 `Section`，单文件建议 ≤ 300 行。
- 使用 `remember`/`rememberSaveable` 保存表单草稿；进程死亡恢复必须可用。
- 列表用 `LazyColumn` + 稳定 `key`；不要用 `Column` 渲染 60+ 行。
- 不在 Composable 里做 IO、`Thread.sleep`、`new Thread`、阻塞等待。
- 预览（`@Preview`）可选；不要求每个组件都有，但核心组件建议有。

## 4. 状态与数据流

```text
MainActivity
  └─ XiaoiApp(nav = NavStack, snackbar = SnackbarHostState)
       └─ Screen(state = holder.state, onXxx = holder::onXxx)
            └─ StateHolder
                 ├─ 读：ConfigRepository / StateModel / BridgeStatus / AgentRuntime
                 └─ 写：ConfigRepository.saveQuiet + AgentService.startIfEnabled
```

- StateHolder 用 `mutableStateOf` 暴露只读 `State<T>`；不要暴露可变对象。
- 异步统一走 `ui/util/Executors.kt` 的 `suspend fun <T> io(block: () -> T): T`：
  - 内部使用 `AppExecutors.io` + `suspendCancellableCoroutine`；
  - 禁止在 UI 层直接 `Dispatchers.IO`（保持与 AGENTS.md 一致）。
- 页面刷新：`onResume` / `LaunchedEffect(Unit)` / 手动刷新；不可见时停止轮询。
- 保存：
  - 一级主开关：即时保存；失败回滚并 Snackbar。
  - 二级表单：显式保存/取消；失败保留草稿。
- 草稿：`rememberSaveable` + `DirtyState`；返回时弹“保存/放弃/取消”。
- 外部文件变更：有脏数据 → 黄条“载入文件/保留编辑”；无脏数据 → 自动重载。

## 5. 导航

- 单 Activity；`NavStack` 只用 push/pop/switchTab，无 per-tab 栈。
- 路由用 `sealed interface Route`，参数只放 id/枚举，不放 token/key。
- 一级页显示底栏；二级页隐藏底栏；返回后恢复。
- 一级根页返回：非首页 → 首页；首页 → 退出。
- 深链只接受显式 Intent extras：`TAB` / `PAGE` / `FOCUS`；白名单外回首页；不执行任何动作。
- 二级编辑页拦截系统返回：脏 → 保存/放弃/取消。

## 6. 错误与提示

- 统一 `SnackbarHostState`；禁止系统 Toast；禁止在页面内自建底部提示。
- 错误文案来自 `ErrorCatalog` 或固定字符串；**不拼接异常 message**（避免泄露路径/密钥）。
- 成功提示要具体：“已保存，等待小爱应用”“端点可用 · 123ms”“已复制，含令牌，勿外传”。
- 失败不静默：行内错误 + Snackbar/Inline banner；保留用户输入。
- 禁止把 apiKey/token/确认 token/对话正文写入日志或 UI 状态快照。

## 7. 资源与令牌

- 所有用户可见文本放 `res/values/strings.xml`，Compose 用 `stringResource(R.string.xxx)`；带参数的用 `stringResource(id, args)`。
- 颜色只引用 `XiaoiTheme.colors.*`；**禁止** `Color(0xFF...)` 出现在 `ui/` 下。
- 尺寸只引用 `XiaoiTheme.dimens.*`；页面级布局常量可局部定义，但圆角/行高/页面边距必须用令牌。
- 字体只引用 `XiaoiTheme.typography.*`；数字/端口/日志用 `Mono`。
- 禁止裸 `#RRGGBB`、`getIdentifier`、硬编码中文（除调试日志）。
- 新增颜色/尺寸必须先更新 `docs/design-system.md` + `Tokens.kt`，再使用。

## 8. 无障碍

- 触控目标 ≥ 48dp；导航项 ≥ 64dp。
- 装饰性图标 `contentDescription = null`；承载语义的图标必须有文字或 `contentDescription`。
- 状态不能只靠颜色：徽章必须有文字；错误行必须有级别文字。
- 四维徽章提供 `stateDescription`；`SwitchRow` 整行 `toggleable(role = Role.Switch)`，内部 Switch 不重复响应。
- 字体缩放 1.0/1.3/2.0 不裁切；行高用 `heightIn(min=...)`，不写死 `height`。
- 尊重系统“移除动画”；不做自动播放动画。

## 9. 性能

- 首页 5 秒 tick 只在 `RESUMED` 时运行；统计 15 秒节流。
- 列表 `key` 稳定；`derivedStateOf` 避免整页重组。
- 不在 Composable 中创建大对象/正则/JSON 解析；放 StateHolder。
- 图片/图标使用现有矢量或 Miuix 图标；不引入图片加载库。
- 首屏骨架 150ms 延迟出现，避免闪烁。

## 10. 测试

- 纯逻辑（`IssueResolver`、`CapabilityRegistry`、`NavStack`、`PermissionProbe`、格式化）用 Java 写，进 `tests/run.sh`。
- Compose UI 0.14 不做 instrumentation 测试；用真机截图矩阵 + `uiautomator dump` 冒烟。
- 每个页面至少覆盖：正常、空、错误/离线、加载 四种状态（能静态复现的用调试开关）。
- 修改核心逻辑必须补 JVM 断言；只改 UI 不强制新增断言，但不能让现有断言失败。

## 11. Git / Worktree / 提交

- 每个工作包一个分支：`feat/ui-<wp>`（如 `feat/ui-p0`、`feat/ui-home`）。
- worktree 统一放 `~/xiaoi-plus-dev/worktrees/<wp>`，**不要**散落在 `~/code`；合并后立即删除。
- 提交信息用 conventional commits：`feat(ui): ...`、`fix(ui): ...`、`docs(ui): ...`。
- 一个提交只做一件事；不提交 `build/`、`.gradle/`、`local.properties`、密钥、截图大文件。
- 合并前必须：`./gradlew :app:assembleDebug`、`tests/run.sh`、`scripts/check-consistency.sh` 通过。
- 不直接在 `main` 上开发；不 `git push --force`。

## 12. Definition of Done

一个页面/组件完成需同时满足：

- [ ] 使用规范组件与令牌，无裸色值/硬编码中文。
- [ ] 正常/空/错/加载状态齐全；无假按钮。
- [ ] 深浅色截图；字体缩放 1.3 不裁切。
- [ ] 关键操作有确认/回滚/错误提示。
- [ ] 不泄露 key/token；日志脱敏。
- [ ] 构建、单测、一致性门禁通过。
- [ ] 文档（SCREENS/COMPONENTS/CHANGELOG）同步。

## 13. 禁止清单

1. 不引入 Compose 之外的 UI 框架、DI、图片库、navigation-compose。
2. 不在 `ui/` 里写 `new Thread`、`Thread.sleep`、阻塞 IO。
3. 不用系统 Toast；不吞异常；不把异常原文直接展示。
4. 不新增第二个强调色、第二种卡片/按钮/行样式。
5. 不做 blur、Monet、折叠大标题（minSdk 26 不成立）。
6. 不上调 targetSdk；不改包名/签名。
7. 不给“规划中”功能做可点击假开关。
8. 不在页面里复制 `StateModel` 的判断逻辑。
9. 不把 token/key 放进路由参数、日志、截图、`contentDescription`。
10. 不跨工作包改别人的文件；需要改组件契约先改 COMPONENTS.md 并通知主 Agent。
