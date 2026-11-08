package plus.xiaoi.module.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import plus.xiaoi.module.core.NavStack
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
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

/** 底部导航：直接用 Miuix NavigationBar + NavigationBarItem。 */
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
            NavigationBarItem(
                selected = tab == current,
                onClick = { onSelect(tab) },
                icon = icon,
                label = label,
            )
        }
    }
}
