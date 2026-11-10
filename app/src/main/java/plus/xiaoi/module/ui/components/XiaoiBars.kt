package plus.xiaoi.module.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import plus.xiaoi.module.core.NavStack
import plus.xiaoi.module.ui.components.glass.GlassBackdropSource
import plus.xiaoi.module.ui.components.glass.GlassBottomBar

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
