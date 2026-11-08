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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.R
import plus.xiaoi.module.core.NavStack
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.icon.extended.Settings

/**
 * 顶栏：Miuix [TopAppBar] 的「返回行 + 大标题」布局，与 XiaomiHelper 的
 * HyperXPage 一致；页面级动作走 `actions`，不再在内容区另起一条工具栏。
 */
@Composable
fun XiaoiTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = XiaoiTheme.colors
    TopAppBar(
        title = "",
        titleColor = colors.text,
        largeTitle = title,
        largeTitleColor = colors.text,
        color = colors.bg,
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
    NavigationBar(
        color = colors.bg,
        showDivider = true,
    ) {
        XiaoiNavItem(
            selected = current == NavStack.MainTab.HOME,
            onClick = { onSelect(NavStack.MainTab.HOME) },
            icon = null,
            painter = painterResource(R.drawable.ic_home),
            label = "首页",
        )
        XiaoiNavItem(
            selected = current == NavStack.MainTab.XIAOAI,
            onClick = { onSelect(NavStack.MainTab.XIAOAI) },
            icon = MiuixIcons.Mic,
            label = "小爱",
        )
        XiaoiNavItem(
            selected = current == NavStack.MainTab.ACCESS,
            onClick = { onSelect(NavStack.MainTab.ACCESS) },
            icon = MiuixIcons.Link,
            label = "接入",
        )
        XiaoiNavItem(
            selected = current == NavStack.MainTab.ME,
            onClick = { onSelect(NavStack.MainTab.ME) },
            icon = MiuixIcons.Settings,
            label = "我的",
        )
    }
}

/**
 * Miuix NavigationBarItem 会强制给未选中内容乘 0.4 alpha；在本项目浅色底上最高只能到
 * 2.82:1，不满足 3:1。这里保留 NavigationBar 容器与 Miuix 图标/文字，只自绘 item。
 *
 * <p>首页用工程内已有的 24dp 线性 `ic_home`，与 Miuix 的线性 Mic/Link/Settings
 * 保持同一笔触；`ic_xiaoi` 是品牌色实心块，不适合放进底栏。</p>
 */
@Composable
private fun RowScope.XiaoiNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    painter: Painter? = null,
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
        if (painter != null) {
            Icon(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = content,
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = content,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = content,
            style = XiaoiTheme.typography.caption,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
