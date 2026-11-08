package plus.xiaoi.module.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.core.NavStack
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.icon.extended.Settings

/** 顶栏：直接用 Miuix SmallTopAppBar，不模拟。 */
@Composable
fun XiaoiTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = XiaoiTheme.colors
    SmallTopAppBar(
        title = title,
        color = colors.bg,
        titleColor = colors.text,
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, contentDescription = "返回", tint = colors.text)
                }
            }
        },
        actions = actions,
        defaultWindowInsetsPadding = false,
    )
}

/** 底部导航：容器用 Miuix NavigationBar；item 自绘以绕开 0.4 alpha 的对比度上限。 */
@Composable
fun XiaoiBottomBar(current: NavStack.MainTab, onSelect: (NavStack.MainTab) -> Unit) {
    val colors = XiaoiTheme.colors
    val items = listOf(
        Triple(NavStack.MainTab.HOME, "首页", MiuixIcons.Home),
        Triple(NavStack.MainTab.XIAOAI, "小爱", MiuixIcons.Mic),
        Triple(NavStack.MainTab.ACCESS, "接入", MiuixIcons.Link),
        Triple(NavStack.MainTab.ME, "我的", MiuixIcons.Settings),
    )
    NavigationBar(
        color = colors.bg,
        showDivider = true,
    ) {
        items.forEach { (tab, label, icon) ->
            XiaoiNavItem(
                selected = tab == current,
                onClick = { onSelect(tab) },
                icon = icon,
                label = label,
            )
        }
    }
}

/**
 * Miuix NavigationBarItem 会强制给未选中内容乘 0.4 alpha；在本项目浅色底上最高只能到
 * 2.82:1，不满足 3:1。这里保留 NavigationBar 容器与 Miuix 图标/文字，只自绘 item。
 */
@Composable
private fun RowScope.XiaoiNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
) {
    val colors = XiaoiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val content = if (selected) colors.primary else colors.textSecondary
    Column(
        modifier = Modifier
            .weight(1f)
            .height(XiaoiTheme.dimens.navItemHeight)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = content,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = content,
            style = XiaoiTheme.typography.caption,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
