package plus.xiaoi.module.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight

/**
 * 56dp 端口行：状态点 + 名称 + Mono 端口 + 绑定。
 * 8771/8770 各自一行，不合并判断。
 */
@Composable
fun PortRow(
    name: String,
    port: Int?,
    bind: String?,
    running: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = XiaoiTheme.colors
    BasicComponent(
        modifier = modifier.heightIn(min = 56.dp),
        title = name,
        titleColor = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(color = colors.text),
        summary = bind,
        summaryColor = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.summaryColor(
            color = colors.textSecondary,
        ),
        startAction = {
            Box(
                Modifier
                    .padding(end = 12.dp)
                    .size(8.dp)
                    .background(if (running) colors.success else colors.textHint, CircleShape),
            )
        },
        endActions = {
            Text(
                text = port?.toString() ?: "—",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.mono,
            )
            if (onClick != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = MiuixIcons.Basic.ArrowRight,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        onClick = onClick,
    )
}
