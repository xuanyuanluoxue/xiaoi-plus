# UI 组件契约（Compose / Miuix）

> 状态：**生效**（0.14 起）
> 规则：页面只能使用本文件列出的组件；新增/修改组件先改本文件。
> 关联：[CONVENTIONS.md](CONVENTIONS.md)、[SCREENS.md](SCREENS.md)、[design-system.md](../design-system.md)。

## 0. 通用约定

- **Miuix 优先**：有现成组件就直接用，不用 Row/Column 模拟；自定义只保留 Miuix 没有的业务组件。
- 组件默认尺寸（Card 16dp、Button 40dp、TopAppBar 52dp、NavigationBar 默认）不再覆盖；颜色/字号令牌可以按品牌映射。
- 所有组件无状态：参数 = 数据 + 回调；状态提升到 StateHolder。
- 所有尺寸/颜色/字体来自 `XiaoiTheme`；组件内部不得出现裸色值。
- 触控目标 ≥48dp；图标 24dp；行高遵循令牌。
- 组件不接受 `Context` 做业务操作；需要剪贴板/Intent 时由调用方传 lambda。
- 语义：`Modifier.semantics { }` 只加必要信息；不要重复朗读。
- 组件文件放 `ui/components/`；一个文件一个组件，文件名 = 组件名。

## 1. 主题与骨架

### XiaoiTheme

```kotlin
@Composable
fun XiaoiTheme(
    mode: ThemeMode,                 // FOLLOW_SYSTEM / LIGHT / DARK
    content: @Composable () -> Unit
)
```

- 用现有 19 个颜色令牌构造 light/dark `MiuixTheme`。
- 提供 `XiaoiTheme.colors`、`.dimens`、`.typography`。
- 不启用 Monet。

### Miuix Scaffold

直接使用 `top.yukonga.miuix.kmp.basic.Scaffold`：

```kotlin
Scaffold(
    topBar = { XiaoiTopBar(...) },
    bottomBar = { XiaoiBottomBar(...) },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    containerColor = XiaoiTheme.colors.bg,
) { padding -> ... }
```

- 不再手写 `Column + Box(weight)` 骨架。
- `SnackbarHostState.showSnackbar()` 统一提示；不使用自绘 Snackbar/Toast。

### XiaoiTopBar（紧凑大标题）

```kotlin
@Composable
fun XiaoiTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
)
```

- 状态栏下 48dp 行；标题 32sp 左对齐；返回 48dp；页面动作与标题同排。
- 对齐参考实现 `ManagerCompactTopBar`；不做折叠大标题的空白行。
- 页面级动作用 `XiaoiPageActions` 发布到顶栏，不在内容区另起工具栏。

### XiaoiBottomBar（Miuix NavigationBar）

```kotlin
@Composable
fun XiaoiBottomBar(current: MainTab, onSelect: (MainTab) -> Unit)
```

- 容器用 Miuix `NavigationBar`；图标来自 `miuix-icons`（Home / Mic / Link / Settings）。
- item 自绘：Miuix `NavigationBarItem` 会给未选中内容强制乘 0.4 alpha，
  纯黑在浅色底上最高也只有 2.82:1，达不到 3:1。自绘 item 用 Miuix
  `Icon`/`Text` + `selectable(Role.Tab)`，48dp 高、24dp 图标、Caption 文字。
- 选中 `primary`，未选中 `textSecondary`；不叠 alpha。
- 只在二级页隐藏；不悬浮、不加背景块。例外记录在 `design-system.md` §9.2。

## 2. 基础组件

### XiaoiCard

```kotlin
@Composable
fun XiaoiCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
)
```

- Miuix `Card`，圆角 28dp、内边距 20dp；偏好行用 `XiaoiPreferenceCard`（不加内边距）。
- 多张独立卡用 `XiaoiCardGroup` 包裹，间距 12dp。
- 可点击时提供 ripple + `Role.Button`；不可点击不加。

### XiaoiButton

```kotlin
enum class XiaoiButtonStyle { PRIMARY, SECONDARY, TEXT, DANGER_TEXT }

@Composable
fun XiaoiButton(
    text: String,
    onClick: () -> Unit,
    style: XiaoiButtonStyle = XiaoiButtonStyle.PRIMARY,
    enabled: Boolean = true,
    loading: Boolean = false
)
```

- 高 48dp；圆角 16dp；主按钮 `primary/on_primary`；次按钮 `chipOkBg/primary`
  （浅色 `#DCE9F7/#2F4A6B`，深色 `#2E3444/#C7D2FE`），不能白底白卡。
- 实测对比度：次按钮浅色 **7.42:1**、深色 **8.32:1**（P2 视觉修复只改 SECONDARY，
  PRIMARY/TEXT/DANGER_TEXT 不动）。
- `DANGER_TEXT` 只用于确认框内的危险按钮（error 文字）。
- loading 时禁用并显示 16dp 进度；不要改变宽度导致跳动。

### XiaoiTextField

```kotlin
@Composable
fun XiaoiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    isSecret: Boolean = false,
    error: String? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text
)
```

- 高 44dp；圆角 12dp；背景 Miuix 默认容器；聚焦 1dp `primary`；错误 1dp `error`。
- label/placeholder 显式传 `textSecondary`（Miuix 默认浅灰在灰底上仅 2.06:1，
  显式后 **5.01:1**）。
- 密钥字段默认掩码，右侧“显示/隐藏”仅本次；不把明文写入状态日志。

### XiaoiSwitchRow

```kotlin
@Composable
fun XiaoiSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true
)
```

- 高 56dp；整行 `toggleable(role = Role.Switch)`；内部 Switch `onCheckedChange = null`。
- 高风险开关由调用方先弹确认，再调用 `onCheckedChange`。

### XiaoiInfoRow / XiaoiRowIcon

```kotlin
@Composable
fun XiaoiInfoRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    iconTone: XiaoiIconTone = XiaoiIconTone.NEUTRAL,
    badge: String? = null,
    badgeTone: XiaoiTone = XiaoiTone.NEUTRAL
)

@Composable
fun XiaoiRowIcon(icon: ImageVector, tone: XiaoiIconTone = XiaoiIconTone.NEUTRAL)
```

- 72dp；左 48dp 语义色圆 + 26dp 白色图形；右侧徽章 + chevron。
- `XiaoiIconTone`：GREEN/BLUE/ORANGE/PINK/RED/CYAN/PURPLE/INDIGO/NEUTRAL，
  颜色只表达“是什么”，状态仍用徽章文字。
- 首页用彩色圆；设置页保留彩色圆但同一入口固定同一语义色。
- 空副行不留空高；点击区域整行。

### XiaoiCopyRow

```kotlin
@Composable
fun XiaoiCopyRow(title: String, value: String, onCopy: () -> Unit)
```

- 用 Miuix `BasicComponent`；副行显示要复制的值，右侧“复制”文字。
- “复制”最小触控 48×48dp，字号不变（P2 视觉修复）。
- 不用 `ArrowPreference`：复制不是跳转，不显示 chevron。
- 复制内容必须与副行展示值一致；提示由调用方 `showSnackbar`。

### XiaoiValueRow

```kotlin
@Composable
fun XiaoiValueRow(
    label: String,
    value: String,
    mono: Boolean = false,
    onCopy: (() -> Unit)? = null
)
```

- 56dp；左标签 88dp；右值 14sp；可选复制图标。
- 长值省略中间/尾部；复制时调用方给 Snackbar。

### XiaoiGroupHeader

```kotlin
@Composable
fun XiaoiGroupHeader(text: String)
```

- 12sp `text_secondary`；上 24dp / 下 8dp；不加线。

### XiaoiBadge

```kotlin
enum class XiaoiBadgeTone { NEUTRAL, SUCCESS, WARNING, ERROR, INFO }

@Composable
fun XiaoiBadge(
    text: String,
    tone: XiaoiBadgeTone = XiaoiBadgeTone.NEUTRAL,
    icon: (@Composable () -> Unit)? = null
)
```

- 24dp 高；12sp；必须有文字；不允许只靠颜色。

### XiaoiStatTile

```kotlin
@Composable
fun XiaoiStatTile(
    label: String,
    value: String?,          // null = 未知，显示“—”
    modifier: Modifier = Modifier
)
```

- 背景 `surface`；内边距 16dp；标签 Caption；数字 28sp Mono/600。
- 未知必须显示“—”，不能显示 0。

### XiaoiEmptyState

```kotlin
@Composable
fun XiaoiEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = MiuixIcons.Notes
)
```

- 图标 48dp `text_hint`，用 `miuix-icons`，不允许用文字标点占位。
- 标题 16；说明 12 居中；上下 48dp。
- 可选次按钮由页面在空态下方自行放置（P2 再扩展）。

### XiaoiCodeBlock

```kotlin
@Composable
fun XiaoiCodeBlock(
    text: String,
    maxLines: Int = 12,
    onCopy: (() -> Unit)? = null
)
```

- 背景 `surface_2`；圆角 12dp；内边距 12dp；Mono 11/12。
- 最大高度后内部滚动；复制按钮 24dp。

### XiaoiDirtyBar

```kotlin
@Composable
fun XiaoiDirtyBar(
    dirty: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit
)
```

- 高 56dp；顶部 1dp `line`；左侧“放弃”文字按钮；右侧“保存”主按钮。
- 非 dirty 时隐藏；保存中禁用。

### XiaoiLoadingSkeleton

```kotlin
@Composable
fun XiaoiLoadingSkeleton(
    height: Dp,
    modifier: Modifier = Modifier
)
```

- 卡片形状；150ms 延迟出现；不使用旋转 spinner。

### XiaoiConfirmDialog

```kotlin
@Composable
fun XiaoiConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    secondaryNote: String? = null
)
```

- 圆角 24dp；标题 20；正文 14；默认焦点“取消”；危险按钮用 error 文字。
- 需要更强确认时由调用方叠加勾选/倒计时，不在组件内硬编码。

### XiaoiBottomSheet

```kotlin
@Composable
fun XiaoiBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
)
```

- 顶部圆角 24dp；背景 `surface_2`；最大高度 85%；拖拽关闭。

## 3. 业务组件

### StatusHero

```kotlin
@Composable
fun StatusHero(
    title: String,
    subtitle: String,
    dimensions: List<DimensionState>,
    primaryText: String?,
    onPrimary: (() -> Unit)?,
    onDetail: (() -> Unit)? = null
)
```

- 高约 160–180dp；背景按状态 12% 透明；文字 `hero_on`。
- 四维徽章 2×2；每枚有文字 + 状态描述。

### DimensionBadge

```kotlin
enum class DimensionStatus { READY, OK, WAITING, ERROR, UNKNOWN }

@Composable
fun DimensionBadge(label: String, status: DimensionStatus, text: String)
```

- 28dp 高；标签 12sp；`stateDescription` 必填。

### IssueRow

```kotlin
@Composable
fun IssueRow(
    issue: IssueUi,
    onClick: () -> Unit
)
```

- 64dp；severity 图标 20dp；标题 16；动作 14 `primary`；整行可点。
- 不提供“忽略/关闭”。

### PortRow

```kotlin
@Composable
fun PortRow(
    name: String,
    port: Int?,
    bind: String?,           // “仅本机” / “局域网”
    running: Boolean,
    onClick: (() -> Unit)?
)
```

- 56dp；状态点 + 名称 + Mono 端口 + 绑定；右侧箭头。
- 8771/8770 分开；不合并成“小爱未运行”。

### CapabilityRow

```kotlin
@Composable
fun CapabilityRow(
    id: String,
    title: String,
    channel: String,
    status: CapabilityStatus,
    risk: CapabilityRisk,
    onClick: () -> Unit
)
```

- 72dp；标题 + `id · channel`；右侧风险/状态徽章 + 箭头。

### PermissionRow

```kotlin
@Composable
fun PermissionRow(
    name: String,
    status: PermissionStatus,
    detail: String?,
    onOpenSettings: (() -> Unit)?
)
```

- 56dp；状态徽章 + “去设置”；“去设置”最小触控 48×48dp。
- 无标准 API 时显示“未确认”，不当成拒绝。

### FilterChips

```kotlin
@Composable
fun FilterChips(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
)
```

- 28dp 视觉 + 48dp 触控；FlowRow 自动换行，不横向裁切。
- 未选中 `chipBg` + `textSecondary`；选中 `primary` 实心 + `onPrimary`。
- 全站唯一筛选 chip 视觉；日志、外观、能力页共用。
- `XiaoiChip` 已删除（0.14 P2 视觉修复），不要再新增第二套筛选 chip。
- 日志页 chip 行与下方卡片之间固定 12dp，避免卡片压住 chip 下缘。

### SearchField

```kotlin
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onClear: () -> Unit
)
```

- 44dp；搜索图标；清除按钮；label/placeholder 显式 `textSecondary`。

### AgentStatus / agentPortUi

```kotlin
enum class AgentPortState { RUNNING, STOPPED, FAILED, DISABLED, UNKNOWN }

fun agentPortUi(
    mcpEnabled: Boolean,
    snapshot: AgentRuntimeStatus.Snapshot
): AgentPortUi
```

- 8770 动作面状态的**唯一 UI 判定**；Access / Bridge / Diagnostics / Home 共用。
- 只读 `AgentRuntimeStatus` 快照与 `known()`，不扫端口、不猜 loopback。
- 文案固定：运行中 / 已停止 / 启动失败 / 未开启 / 未确认；未知统一“未确认”。
- 不允许页面各自拼一套 `PortState`；新增 8770 状态展示先改本组件。

## 4. 组件验收

- [ ] 参数与本文一致；没有额外必需的 Context/Repository。
- [ ] 深浅色截图；字体缩放 1.3 不裁切。
- [ ] 触控 ≥48dp；TalkBack 可读。
- [ ] 无裸色值/硬编码中文/固定高度卡死文字。
- [ ] 不用 Toast、不吞异常、不写日志敏感信息。
- [ ] 修改契约时同步更新本文件与 `docs/design-system.md`。
