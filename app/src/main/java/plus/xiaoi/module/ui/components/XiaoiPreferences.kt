package plus.xiaoi.module.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Icon

/**
 * HyperOS 风格的分组列表：组标题 + 圆角卡。
 *
 * <p>与 XiaomiHelper 的 `itemPreferenceGroup` 对齐：同组行共用一张卡、行内自带左右
 * 内边距，组与组之间留 [XiaoiGroupHeader] 的 24dp 间距；不再额外画分隔线。</p>
 */
@Composable
fun XiaoiPreferenceGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (title != null) XiaoiGroupHeader(title)
    XiaoiPreferenceCard(modifier) { content() }
}

/**
 * 行首图标：32dp 圆角方块 + 20dp 图标，对应 HyperOS 设置列表的彩色入口块。
 * 只用于“去哪/是什么”的入口行；状态行和值行不加图标，避免视觉噪音。
 */
@Composable
fun XiaoiRowIcon(icon: ImageVector) {
    val colors = XiaoiTheme.colors
    Box(
        modifier = Modifier
            .padding(end = 14.dp)
            .size(32.dp)
            .background(colors.iconTile, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 页面级动作（顶栏 actions）的发布口；由 [plus.xiaoi.module.MainActivity] 提供容器。 */
class XiaoiPageActionsState {
    var actions: (@Composable RowScope.() -> Unit)? by mutableStateOf(null)
        internal set
}

val LocalXiaoiPageActions = compositionLocalOf<XiaoiPageActionsState?> { null }

/**
 * 二级页把自己的动作放进顶栏，而不是在内容区再拉一条“说明 + 按钮”工具栏。
 *
 * <p>在页面根部调用一次即可；页面离开组合时自动清空，避免回到一级页仍显示旧动作。</p>
 */
@Composable
fun XiaoiPageActions(content: @Composable RowScope.() -> Unit) {
    val state = LocalXiaoiPageActions.current ?: return
    SideEffect { state.actions = content }
    DisposableEffect(state) {
        onDispose { state.actions = null }
    }
}
