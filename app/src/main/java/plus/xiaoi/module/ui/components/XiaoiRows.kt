package plus.xiaoi.module.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.Text

/** 72dp 信息行：有动作时用 Miuix ArrowPreference，无动作时用 BasicComponent。 */
@Composable
fun XiaoiInfoRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    if (onClick != null) {
        ArrowPreference(
            title = title,
            summary = subtitle,
            onClick = onClick,
        )
    } else {
        BasicComponent(
            title = title,
            summary = subtitle,
        )
    }
}

/** 只读值行：Miuix BasicComponent + 右侧值。 */
@Composable
fun XiaoiValueRow(label: String, value: String, mono: Boolean = false) {
    val colors = XiaoiTheme.colors
    BasicComponent(
        title = label,
        endActions = {
            Text(
                text = value,
                color = colors.textSecondary,
                style = if (mono) XiaoiTheme.typography.mono else XiaoiTheme.typography.body,
            )
        },
    )
}

/** 复制行：不带导航箭头；右侧明确写“复制”，复制内容与展示值一致。 */
@Composable
fun XiaoiCopyRow(title: String, value: String, onCopy: () -> Unit) {
    val colors = XiaoiTheme.colors
    BasicComponent(
        title = title,
        summary = value,
        endActions = {
            Text(
                text = "复制",
                color = colors.primary,
                style = XiaoiTheme.typography.body,
                modifier = Modifier
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        },
    )
}

/** 开关行：直接用 Miuix SwitchPreference，整行可点由组件负责。 */
@Composable
fun XiaoiSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = title,
        summary = subtitle,
        enabled = enabled,
    )
}
