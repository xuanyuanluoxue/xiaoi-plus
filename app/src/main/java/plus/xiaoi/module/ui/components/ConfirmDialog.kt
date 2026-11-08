package plus.xiaoi.module.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 确认弹窗：默认焦点在「取消」，危险操作由调用方传 danger=true。
 * 更强的确认（勾选/倒计时）由调用方在 message 外自行叠加，组件不硬编码。
 */
@Composable
fun XiaoiConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    secondaryNote: String? = null,
) {
    val colors = XiaoiTheme.colors
    WindowDialog(
        show = show,
        title = title,
        titleColor = if (danger) colors.error else colors.text,
        summary = message,
        summaryColor = colors.textSecondary,
        backgroundColor = colors.surface2,
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (secondaryNote != null) {
                Text(
                    text = secondaryNote,
                    color = colors.textHint,
                    style = XiaoiTheme.typography.caption,
                )
                Spacer(Modifier.height(12.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                XiaoiTextButton("取消", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                XiaoiTextButton(
                    text = confirmText,
                    danger = danger,
                    onClick = onConfirm,
                )
            }
        }
    }
}
