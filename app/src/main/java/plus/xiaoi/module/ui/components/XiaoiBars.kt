package plus.xiaoi.module.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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

/**
 * 顶栏：与参考实现的 `ManagerCompactTopBar` 对齐——状态栏下 48dp 行、
 * 32sp 标题、返回按钮与页面动作同排；不做折叠大标题的空白行。
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
            .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)
            .height(40.dp),
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

/** 顶栏占用的竖向高度（状态栏 inset + 40dp 标题栏）：滚动内容用它做顶部内边距。 */
@androidx.compose.runtime.Composable
fun xiaoiTopBarHeight(): androidx.compose.ui.unit.Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 40.dp
