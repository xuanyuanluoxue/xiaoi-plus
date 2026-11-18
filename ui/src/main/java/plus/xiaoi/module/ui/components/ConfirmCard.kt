package plus.xiaoi.module.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

/**
 * 行内确认卡：用于危险区默认折叠后展开的二次确认。
 * 比弹窗更轻，但仍要求显式点「确认」；不代替 XiaoiConfirmDialog 的强确认。
 */
@Composable
fun ConfirmCard(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    secondaryNote: String? = null,
) {
    val colors = XiaoiTheme.colors
    XiaoiCard(modifier = modifier) {
        Text(
            text = title,
            color = if (danger) colors.error else colors.text,
            style = XiaoiTheme.typography.rowTitle,
        )
        Spacer(Modifier.height(6.dp))
        Text(message, color = colors.textSecondary, style = XiaoiTheme.typography.body)
        if (secondaryNote != null) {
            Spacer(Modifier.height(6.dp))
            Text(secondaryNote, color = colors.textHint, style = XiaoiTheme.typography.caption)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            XiaoiTextButton("取消", onClick = onDismiss)
            Spacer(Modifier.width(12.dp))
            XiaoiTextButton(
                text = confirmText,
                danger = danger,
                onClick = onConfirm,
            )
        }
    }
}
