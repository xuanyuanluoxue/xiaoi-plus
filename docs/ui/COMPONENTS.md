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

### XiaoiTopBar（Miuix SmallTopAppBar wrapper）

```kotlin
@Composable
fun XiaoiTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
)
```

- 内部直接用 `SmallTopAppBar`；标题居中、默认高 52dp。
- 返回用 `IconButton + Icon(MiuixIcons.Back)`；不再用字符 `‹`。
- 颜色传 `bg/text`；不覆盖组件尺寸/圆角。

### XiaoiBottomBar（Miuix NavigationBar）

```kotlin
@Composable
fun XiaoiBottomBar(current: MainTab, onSelect: (MainTab) -> Unit)
```

- 内部直接用 `NavigationBar` + `NavigationBarItem`；图标来自 `miuix-icons`
  （Home / Mic / Link / Settings）。
- 选中态、图标大小、行高由 Miuix 组件决定；不再自绘文字 tab。
- 只在二级页隐藏；不悬浮、不加背景块。

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

- 使用 Miuix `Card` 默认圆角 16dp；不覆盖颜色/圆角。
- 自定义内容用 `XiaoiCard`（16dp 内边距）；偏好列表用 `XiaoiPreferenceCard`（不加内边距）。
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

- 高 48dp；圆角 16dp；主按钮 `primary/on_primary`；次按钮 `surface_2/text`。
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

- 高 44dp；圆角 12dp；背景 `surface_2`；聚焦 1dp `primary`；错误 1dp `error`。
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

### XiaoiInfoRow

```kotlin
@Composable
fun XiaoiInfoRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
)
```

- 72dp；标题 16/22/500；副行 12/16；箭头 20dp。
- 空副行不留空高；点击区域整行。

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
    description: String? = null,
    icon: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null
)
```

- 图标 48dp `text_hint`；标题 16；说明 12；可选次按钮。

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

- 56dp；状态徽章 + “去设置”；无标准 API 时显示“未确认”。

### FilterChips

```kotlin
@Composable
fun FilterChips(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
)
```

- 28dp；横向滚动；选中 `primary` 15% 底 + `primary` 文字。

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

- 44dp；搜索图标；清除按钮；无焦点时背景 `surface_2`。

## 4. 组件验收

- [ ] 参数与本文一致；没有额外必需的 Context/Repository。
- [ ] 深浅色截图；字体缩放 1.3 不裁切。
- [ ] 触控 ≥48dp；TalkBack 可读。
- [ ] 无裸色值/硬编码中文/固定高度卡死文字。
- [ ] 不用 Toast、不吞异常、不写日志敏感信息。
- [ ] 修改契约时同步更新本文件与 `docs/design-system.md`。
