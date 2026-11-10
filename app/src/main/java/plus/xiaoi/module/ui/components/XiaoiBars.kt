package plus.xiaoi.module.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import plus.xiaoi.module.core.NavStack
import plus.xiaoi.module.ui.components.glass.GlassBackdropSource
import plus.xiaoi.module.ui.components.glass.GlassBottomBar
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/** 顶栏标题行高度（不含状态栏 inset）：顶栏本体、模糊带、页面顶部留白共用这一处。 */
val XiaoiTopBarHeight: Dp = 44.dp

/**
 * 顶栏：与参考实现的 `ManagerCompactTopBar` 对齐——状态栏下 [XiaoiTopBarHeight] 行、
 * 26sp 标题、返回按钮与页面动作同排；不做折叠大标题的空白行。
 */
@Composable
fun XiaoiTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = XiaoiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 8.dp)
            .height(XiaoiTopBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(MiuixIcons.Back, contentDescription = "返回", tint = colors.text)
            }
        }
        Text(
            text = title,
            color = colors.text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            content = actions,
        )
    }
}

/**
 * 底部导航：悬浮液态玻璃底栏，替换原来的 Miuix NavigationBar。
 *
 * <p>能力分级、采样与回退都在 `ui/components/glass`：FULL/BLUR 采样页面，
 * SOLID 用同尺寸实心胶囊；本函数只负责 tab 数据与路由回调。</p>
 */
@Composable
fun XiaoiBottomBar(
    current: NavStack.MainTab,
    onSelect: (NavStack.MainTab) -> Unit,
    backdrop: GlassBackdropSource,
    contentCoordinates: LayoutCoordinates?,
    modifier: Modifier = Modifier,
) {
    GlassBottomBar(
        current = current,
        onSelect = onSelect,
        backdrop = backdrop,
        modifier = modifier,
        contentCoordinates = contentCoordinates,
    )
}

/** 顶栏占用的竖向高度（状态栏 inset + [XiaoiTopBarHeight]）：滚动内容用它做顶部内边距。 */
@Composable
fun xiaoiTopBarHeight(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + XiaoiTopBarHeight

/**
 * 当前页面的滚动位置（像素）：MainActivity 提供，各页用 [xiaoiScrollState] /
 * [rememberXiaoiListState] 写入。0 表示还在顶部，大于 8dp 表示内容已经滚起来。
 */
val LocalXiaoiScrollY = staticCompositionLocalOf<MutableIntState> { mutableIntStateOf(0) }

/** 可滚动页面的 [ScrollState]：滚动量变化时上报给顶栏，驱动「实色 → 半透明模糊」。 */
@Composable
fun xiaoiScrollState(): ScrollState {
    val st = rememberScrollState()
    val scrollY = LocalXiaoiScrollY.current
    LaunchedEffect(st.value) { scrollY.intValue = st.value }
    return st
}

/**
 * 可滚动列表的 [LazyListState]：第一项还在顶部时报像素偏移，
 * 已经滚到第二项以后报一个足够大的哨兵值（顶栏只关心「是否 > 8dp」）。
 */
@Composable
fun rememberXiaoiListState(): LazyListState {
    val st = rememberLazyListState()
    val scrollY = LocalXiaoiScrollY.current
    LaunchedEffect(st.firstVisibleItemIndex, st.firstVisibleItemScrollOffset) {
        scrollY.intValue = if (st.firstVisibleItemIndex == 0) st.firstVisibleItemScrollOffset else 10000
    }
    return st
}
