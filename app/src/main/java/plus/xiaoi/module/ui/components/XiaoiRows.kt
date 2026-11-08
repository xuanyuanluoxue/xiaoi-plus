package plus.xiaoi.module.ui.components

import androidx.compose.runtime.Composable
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
