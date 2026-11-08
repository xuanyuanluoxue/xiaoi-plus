package plus.xiaoi.module.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.core.NavStack
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

/** 顶栏：一级 56dp；二级带返回。不做折叠大标题。 */
@Composable
fun XiaoiTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = XiaoiTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier.width(48.dp).height(48.dp).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("‹", color = colors.text, style = XiaoiTheme.typography.title)
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = title,
                color = colors.text,
                style = XiaoiTheme.typography.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    }
}

/** 底部 4 tab；一级页显示，二级页隐藏。 */
@Composable
fun XiaoiBottomBar(current: NavStack.MainTab, onSelect: (NavStack.MainTab) -> Unit) {
    val colors = XiaoiTheme.colors
    val tabs = listOf(
        NavStack.MainTab.HOME to "首页",
        NavStack.MainTab.XIAOAI to "小爱",
        NavStack.MainTab.ACCESS to "接入",
        NavStack.MainTab.ME to "我的",
    )
    Column(Modifier.fillMaxWidth().background(colors.bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Row(
            modifier = Modifier.fillMaxWidth().height(80.dp).selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            tabs.forEach { (tab, label) ->
                val selected = tab == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(80.dp)
                        .selectable(
                            selected = selected,
                            onClick = { onSelect(tab) },
                            role = Role.Tab,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .width(18.dp)
                            .height(3.dp)
                            .background(if (selected) colors.primary else Color.Transparent,
                                RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = label,
                        color = if (selected) colors.primary else colors.textSecondary,
                        style = XiaoiTheme.typography.caption,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** 轻量 Snackbar；由 MainActivity 控制自动消失。 */
@Composable
fun XiaoiSnackbar(message: String) {
    val colors = XiaoiTheme.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.surface2, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(message, color = colors.text, style = XiaoiTheme.typography.body)
        }
    }
}
