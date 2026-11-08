package plus.xiaoi.module.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup

/**
 * 搜索框：Miuix TextField + 搜索/清除图标。
 * 无焦点时背景仍由 Miuix 默认容器色决定，不额外覆盖。
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = XiaoiTheme.colors
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 44.dp),
        label = placeholder,
        useLabelAsPlaceholder = true,
        singleLine = true,
        textStyle = XiaoiTheme.typography.body.copy(color = colors.text),
        // 搜索占位同样显式取 textSecondary，避免默认 #A9A9A9 在灰底上只有 2.06:1。
        colors = TextFieldDefaults.textFieldColors(labelColor = colors.textSecondary),
        leadingIcon = {
            Icon(
                imageVector = MiuixIcons.Basic.Search,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = MiuixIcons.Basic.SearchCleanup,
                        contentDescription = "清除",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    )
}
