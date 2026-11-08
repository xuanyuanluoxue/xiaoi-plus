package plus.xiaoi.module.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show

/**
 * 表单输入：44dp 起，Miuix TextField；错误态在下方用 error 文字说明。
 * 密钥字段默认掩码，显示/隐藏只在本次组合内生效，不写入任何状态日志。
 */
@Composable
fun XiaoiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isSecret: Boolean = false,
    error: String? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = XiaoiTheme.colors
    var revealed by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.heightIn(min = 44.dp),
            label = if (value.isEmpty() && placeholder.isNotEmpty()) placeholder else label,
            useLabelAsPlaceholder = value.isEmpty() && placeholder.isNotEmpty(),
            enabled = enabled,
            singleLine = true,
            textStyle = XiaoiTheme.typography.body.copy(color = colors.text),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (isSecret && !revealed) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            trailingIcon = if (isSecret) {
                {
                    IconButton(
                        onClick = { revealed = !revealed },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (revealed) MiuixIcons.Hide else MiuixIcons.Show,
                            contentDescription = if (revealed) "隐藏" else "显示",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            } else {
                null
            },
        )
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, color = colors.error, style = XiaoiTheme.typography.caption)
        }
    }
}
