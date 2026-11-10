package plus.xiaoi.module.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Icon

/**
 * 入口图标语义：ACCENT 承担所有「去哪 / 是什么」的入口；WARNING/DANGER 只给
 * 需要提醒或破坏性的操作。颜色不承载状态——状态由徽章文字与维度色表达，
 * 这样页面不会出现 8 种彩色圆各说各话的情况。
 */
enum class XiaoiIconTone { NEUTRAL, ACCENT, WARNING, DANGER }

/** 圆底色：一个强调色 + 两个语义色；NEUTRAL 走主题中性色。 */
@Composable
fun XiaoiIconTone.containerColor(): Color {
    val colors = XiaoiTheme.colors
    return when (this) {
        XiaoiIconTone.ACCENT -> colors.iconAccent
        XiaoiIconTone.WARNING -> colors.iconWarning
        XiaoiIconTone.DANGER -> colors.iconDanger
        XiaoiIconTone.NEUTRAL -> colors.iconTile
    }
}

/** 圆内图形色：强调/语义色底一律白字；中性底用主题次要文字色。 */
@Composable
private fun XiaoiIconTone.glyphColor(): Color {
    val colors = XiaoiTheme.colors
    return if (this == XiaoiIconTone.NEUTRAL) colors.textSecondary else Color.White
}

/**
 * 48dp 彩色圆形图标，对应参考截图的入口样式。
 * 只用于“去哪/是什么”的入口行；状态行和值行不加图标，避免视觉噪音。
 */
@Composable
fun XiaoiRowIcon(icon: ImageVector, tone: XiaoiIconTone = XiaoiIconTone.NEUTRAL) {
    Box(
        modifier = Modifier
            .padding(end = 14.dp)
            .size(XiaoiTheme.dimens.iconCircle)
            .background(tone.containerColor(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tone.glyphColor(),
            modifier = Modifier.size(XiaoiTheme.dimens.iconGlyph),
        )
    }
}

/**
 * 独立卡片列表：页面不再用“组标题 + 共享大卡”，每个入口各自一张卡，
 * 卡间距统一为 [plus.xiaoi.module.ui.theme.XiaoiDimens.cardGap]。
 */
@Composable
fun XiaoiCardGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(XiaoiTheme.dimens.cardGap),
        content = content,
    )
}

/** 过渡组件：仍在用“组标题 + 共享卡”的旧页面先保留，设置页改造完成后删除。 */
@Composable
fun XiaoiPreferenceGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (title != null) XiaoiGroupHeader(title)
    XiaoiPreferenceCard(modifier) { content() }
}

/** 页面级动作（顶栏 actions）的发布口；由 MainActivity 提供容器。 */
class XiaoiPageActionsState {
    var actions: (@Composable RowScope.() -> Unit)? by mutableStateOf(null)
        internal set
}

val LocalXiaoiPageActions = compositionLocalOf<XiaoiPageActionsState?> { null }

/**
 * 二级页把自己的动作放进顶栏，而不是在内容区再拉一条“说明 + 按钮”工具栏。
 * 页面离开组合时自动清空，避免回到一级页仍显示旧动作。
 */
@Composable
fun XiaoiPageActions(content: @Composable RowScope.() -> Unit) {
    val state = LocalXiaoiPageActions.current ?: return
    SideEffect { state.actions = content }
    DisposableEffect(state) {
        onDispose { state.actions = null }
    }
}
