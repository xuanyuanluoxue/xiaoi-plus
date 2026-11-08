package plus.xiaoi.module.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text

/** 72dp 信息行；整行可点，右侧箭头只在有动作时出现。 */
@Composable
fun XiaoiInfoRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = XiaoiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(XiaoiTheme.dimens.rowInfo)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = colors.text,
                style = XiaoiTheme.typography.rowTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.caption,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Text("›", color = colors.textHint, style = XiaoiTheme.typography.hero)
        }
    }
}

/** 56dp 只读值行。 */
@Composable
fun XiaoiValueRow(label: String, value: String, mono: Boolean = false) {
    val colors = XiaoiTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().height(XiaoiTheme.dimens.rowValue),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = colors.textSecondary,
            style = XiaoiTheme.typography.body,
            modifier = Modifier.width(88.dp),
        )
        Text(
            value,
            color = colors.text,
            style = if (mono) XiaoiTheme.typography.mono else XiaoiTheme.typography.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 56dp 开关行：整行 toggleable，Switch 不重复响应。 */
@Composable
fun XiaoiSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val colors = XiaoiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(XiaoiTheme.dimens.rowSwitch)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, style = XiaoiTheme.typography.rowTitle)
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.caption,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}
