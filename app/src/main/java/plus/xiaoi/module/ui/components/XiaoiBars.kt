package plus.xiaoi.module.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.core.NavStack
import plus.xiaoi.module.ui.components.glass.GlassBackdropSource
import plus.xiaoi.module.ui.components.glass.GlassBottomBar
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 页面内容顶部需要为空出来的高度：状态栏 + Miuix 紧凑顶栏 + 8dp 呼吸感。
 *
 * <p>由 MainActivity 注入。页面把它作为**滚动内容内部**的顶部留白（`padding` 在
 * `verticalScroll` 之后、或 LazyColumn 的 `contentPadding`），所以滚动时内容会从
 * 顶栏下方穿过并由渐变模糊带处理。</p>
 */
val LocalXiaoiTopBarInset = staticCompositionLocalOf { 0.dp }

/** 页面顶部留白；未注入时按状态栏 + Miuix 折叠高度回退。 */
@Composable
fun xiaoiTopBarInset(): Dp {
    val injected = LocalXiaoiTopBarInset.current
    if (injected > 0.dp) return injected
    return WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        TopAppBarDefaults.CollapsedHeight + 8.dp
}

/**
 * 顶栏：Miuix 官方 [SmallTopAppBar]，背景透明，让下面的渐变模糊带透出来。
 *
 * <p>标题居中、二级页左侧返回按钮、右侧页面动作，全部由组件库负责；
 * 本函数只做透明背景与主题色的接线。</p>
 */
@Composable
fun XiaoiTopBar(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = XiaoiTheme.colors
    SmallTopAppBar(
        title = title,
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        titleColor = colors.text,
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(MiuixIcons.Back, contentDescription = "返回", tint = colors.text)
                }
            }
        },
        actions = actions,
    )
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
