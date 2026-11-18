package plus.xiaoi.module.ui.components.glass

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import plus.xiaoi.module.core.NavStack
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 玻璃底栏的固定参数；颜色/尺寸优先走 [XiaoiTheme] 令牌。 */
internal object GlassDefaults {
    val BlurRadius: Dp = 16.dp
    val PillCornerPercent: Int = 50
}

/** 一级页需要为悬浮底栏预留的高度（不含系统手势条 inset，Scaffold 已单独处理）。 */
@Composable
fun rememberGlassBarReservedHeight(): Dp = with(XiaoiTheme.dimens) {
    glassBarHeight + glassBarMargin + glassBarGap
}

/**
 * 悬浮液态玻璃底栏：4 个 tab（首页 / 小爱 / 接入 / 我的），选中 pill 支持点击、
 * 按压放大与横向拖拽切换。
 *
 * 三级回退由 [GlassMode] 决定：
 * - FULL：miuix-blur 采样 + HWUI 模糊 + 折射着色器（[FullGlassBarSurface]）；
 * - BLUR：Compose GraphicsLayer + 平台 BlurEffect（[BlurGlassBarSurface]）；
 * - SOLID：同尺寸实心胶囊，交互完全一致。
 *
 * Adapted from LiquidTab (GPL-3.0, big-dictator/LiquidTab)
 * `ManagerGlassBottomBar.kt`；回退分支与 Xiaoi 令牌接线为本仓库实现。
 */
@Composable
fun GlassBottomBar(
    current: NavStack.MainTab,
    onSelect: (NavStack.MainTab) -> Unit,
    backdrop: GlassBackdropSource,
    modifier: Modifier = Modifier,
    contentCoordinates: LayoutCoordinates? = null,
) {
    val colors = XiaoiTheme.colors
    val dimens = XiaoiTheme.dimens
    val mode = backdrop.mode
    val glassy = mode != GlassMode.SOLID
    val isDark = colors.bg.luminance() < 0.5f

    val selectedIndex = GlassTabs.indexOfFirst { it.tab == current }.coerceAtLeast(0)
    val barShape = remember { BottomBarSquircleShape(CornerSize(percent = 50), smoothing = -1f) }
    val pillShape = remember { RoundedCornerShape(percent = GlassDefaults.PillCornerPercent) }

    val fill = if (glassy) colors.glassFill else colors.glassSolid
    val selectedColor = if (glassy) colors.primary else colors.glassOnSolid
    val unselectedColor = if (glassy) {
        colors.textSecondary
    } else {
        colors.glassOnSolid.copy(alpha = 0.78f)
    }
    val pillFill = if (glassy) colors.glassPill else colors.glassOnSolid.copy(alpha = 0.16f)

    val density = LocalDensity.current
    var barPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    val barOffset = remember(barPositionInRoot, contentCoordinates) {
        runCatching {
            val origin = contentCoordinates?.takeIf { it.isAttached }?.positionInRoot()
            if (origin == null) Offset.Zero else barPositionInRoot - origin
        }.getOrDefault(Offset.Zero)
    }

    var pressedIndex by remember { mutableStateOf<Int?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex) {
        if (!dragging) dragIndex = selectedIndex.toFloat()
    }
    val pressProgress by animateFloatAsState(
        targetValue = if (dragging || pressedIndex != null) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "glassBarPress",
    )
    val settledIndex by animateFloatAsState(
        targetValue = if (dragging) dragIndex else selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "glassBarSelection",
    )

    val specular = if (mode == GlassMode.FULL && Build.VERSION.SDK_INT >= 33) {
        rememberGlassSpecular()
    } else {
        null
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.glassBarHorizontal)
            .padding(bottom = dimens.glassBarMargin),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = dimens.glassBarMaxWidth)
                .fillMaxWidth()
                .height(dimens.glassBarHeight)
                .onGloballyPositioned { barPositionInRoot = it.positionInRoot() }
                // RenderNode 阴影只在 API 31+ 使用；26–30 用描边区分层次，避免平台差异。
                .then(
                    if (Build.VERSION.SDK_INT >= 31) {
                        Modifier.dropShadow(
                            shape = barShape,
                            shadow = Shadow(
                                radius = 12.dp,
                                color = Color.Black,
                                alpha = if (isDark) 0.36f else 0.16f,
                            ),
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            val inset = dimens.glassBarInset
            val tabWidth = (maxWidth - inset * 2) / GlassTabs.size
            val tabWidthPx = with(density) { tabWidth.toPx() }
            val pillWidth = tabWidth - 4.dp
            val pillHeight = dimens.glassBarHeight - inset

            // 表面：玻璃 / 模糊 / 实心。运行时 mode 不会改变，分支稳定。
            when {
                mode == GlassMode.FULL && Build.VERSION.SDK_INT >= 33 -> {
                    FullGlassBarSurface(
                        source = backdrop,
                        barShape = barShape,
                        fill = fill,
                        blurRadius = GlassDefaults.BlurRadius,
                        modifier = Modifier.matchParentSize(),
                    )
                }

                mode == GlassMode.BLUR && Build.VERSION.SDK_INT >= 31 -> {
                    BlurGlassBarSurface(
                        source = backdrop,
                        barShape = barShape,
                        fill = fill,
                        offsetInParent = barOffset,
                        blurRadius = GlassDefaults.BlurRadius,
                        modifier = Modifier.matchParentSize(),
                    )
                }

                else -> {
                    Box(
                        Modifier
                            .matchParentSize()
                            .drawWithCache {
                                val path = Path().apply {
                                    addBottomBarSquircle(size.width, size.height, size.minDimension / 2f, -1f)
                                }
                                onDrawWithContent {
                                    drawPath(path, fill)
                                }
                            },
                    )
                }
            }

            // 选中 pill。
            Box(
                Modifier
                    .offset {
                        val insetPx = with(density) { (inset + 2.dp).toPx() }
                        val insetTop = with(density) { (inset / 2).toPx() }
                        IntOffset(
                            x = insetPx.roundToInt() + (settledIndex * tabWidthPx).roundToInt(),
                            y = insetTop.roundToInt(),
                        )
                    }
                    .width(pillWidth)
                    .height(pillHeight)
                    .graphicsLayer {
                        if (mode == GlassMode.BLUR || mode == GlassMode.SOLID) {
                            val scale = 1f + 0.06f * pressProgress
                            scaleX = scale
                            scaleY = scale
                        }
                    }
                    .then(
                        if (mode == GlassMode.FULL && Build.VERSION.SDK_INT >= 33) {
                            Modifier
                        } else {
                            Modifier.drawWithCache {
                                val path = Path().apply {
                                    addBottomBarSquircle(
                                        size.width,
                                        size.height,
                                        size.minDimension / 2f,
                                        -1f,
                                    )
                                }
                                onDrawWithContent { drawPath(path, pillFill) }
                            }
                        },
                    ),
            ) {
                if (mode == GlassMode.FULL && Build.VERSION.SDK_INT >= 33) {
                    FullGlassPill(
                        source = backdrop,
                        pillShape = pillShape,
                        fill = pillFill,
                        pressProgress = pressProgress,
                        blurRadius = GlassDefaults.BlurRadius,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // 边缘高光：FULL 跟随重力方向，其余模式用主题描边。
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val path = Path().apply {
                            addBottomBarSquircle(size.width, size.height, size.minDimension / 2f, -1f)
                        }
                        val stroke = Stroke(width = 1.dp.toPx())
                        val light = specular
                        onDrawWithContent {
                            drawContent()
                            if (light != null) {
                                val dx = light.lightX - 0.5f
                                val dy = light.lightY - 0.7f
                                val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                                val ux = dx / length
                                val uy = dy / length
                                val reach = size.maxDimension * 0.58f
                                val center = Offset(size.width * 0.5f, size.height * 0.5f)
                                val brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = if (isDark) 0.05f else 0.10f),
                                        Color.White.copy(alpha = if (isDark) 0.42f else 0.68f),
                                        Color.White.copy(alpha = if (isDark) 0.08f else 0.16f),
                                        Color.Transparent,
                                    ),
                                    start = center - Offset(ux * reach, uy * reach),
                                    end = center + Offset(ux * reach, uy * reach),
                                )
                                drawPath(path, brush, style = stroke)
                            } else {
                                drawPath(path, colors.glassEdge, style = stroke)
                            }
                        }
                    },
            )

            // Tab 内容 + 点击/拖拽。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = inset, vertical = 2.dp)
                    .pointerInput(tabWidthPx, GlassTabs.size) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = true },
                            onDragCancel = {
                                dragging = false
                                dragIndex = selectedIndex.toFloat()
                            },
                            onDragEnd = {
                                val target = dragIndex
                                    .roundToInt()
                                    .coerceIn(0, GlassTabs.lastIndex)
                                dragging = false
                                dragIndex = target.toFloat()
                                onSelect(GlassTabs[target].tab)
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            dragIndex = (dragIndex + dragAmount / tabWidthPx)
                                .coerceIn(0f, GlassTabs.lastIndex.toFloat())
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassTabs.forEachIndexed { index, tab ->
                    GlassTabItem(
                        tab = tab,
                        selected = index == selectedIndex,
                        selectedColor = selectedColor,
                        unselectedColor = unselectedColor,
                        onClick = { onSelect(tab.tab) },
                        onPressedChange = { pressed ->
                            if (pressed) {
                                pressedIndex = index
                            } else if (pressedIndex == index) {
                                pressedIndex = null
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.GlassTabItem(
    tab: GlassTab,
    selected: Boolean,
    selectedColor: Color,
    unselectedColor: Color,
    onClick: () -> Unit,
    onPressedChange: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) { onPressedChange(pressed) }
    val contentColor = if (selected) selectedColor else unselectedColor
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            ),
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            modifier = Modifier.size(XiaoiTheme.dimens.glassTabIcon),
            tint = contentColor,
        )
        Text(
            text = tab.label,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
