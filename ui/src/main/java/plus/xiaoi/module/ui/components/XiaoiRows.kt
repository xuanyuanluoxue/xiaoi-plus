package plus.xiaoi.module.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.Text

/**
 * 72dp 信息行：有动作时用 Miuix ArrowPreference，无动作时用 BasicComponent。
 * [icon] 只给入口行用（去某个页面），状态/值行保持纯文字。
 */
@Composable
fun XiaoiInfoRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    iconTone: XiaoiIconTone = XiaoiIconTone.NEUTRAL,
    badge: String? = null,
    badgeTone: XiaoiTone = XiaoiTone.NEUTRAL,
) {
    val startAction: (@Composable () -> Unit)? = if (icon != null) {
        { XiaoiRowIcon(icon, iconTone) }
    } else {
        null
    }
    val endActions: @Composable RowScope.() -> Unit = if (badge != null) {
        {
            XiaoiBadge(badge, badgeTone)
            Spacer(Modifier.width(8.dp))
        }
    } else {
        {}
    }
    if (onClick != null) {
        ArrowPreference(
            title = title,
            summary = subtitle,
            startAction = startAction,
            endActions = endActions,
            onClick = onClick,
        )
    } else {
        BasicComponent(
            title = title,
            summary = subtitle,
            startAction = startAction,
            endActions = endActions,
        )
    }
}

/** 只读值行：Miuix BasicComponent + 右侧值。 */
@Composable
fun XiaoiValueRow(
    label: String,
    value: String,
    mono: Boolean = false,
    icon: ImageVector? = null,
    iconTone: XiaoiIconTone = XiaoiIconTone.NEUTRAL,
) {
    val colors = XiaoiTheme.colors
    BasicComponent(
        title = label,
        startAction = if (icon != null) {
            { XiaoiRowIcon(icon, iconTone) }
        } else {
            null
        },
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
fun XiaoiCopyRow(
    title: String,
    value: String,
    icon: ImageVector? = null,
    iconTone: XiaoiIconTone = XiaoiIconTone.NEUTRAL,
    onCopy: () -> Unit,
) {
    val colors = XiaoiTheme.colors
    BasicComponent(
        title = title,
        summary = value,
        startAction = if (icon != null) {
            { XiaoiRowIcon(icon, iconTone) }
        } else {
            null
        },
        endActions = {
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "复制",
                    color = colors.primary,
                    style = XiaoiTheme.typography.body,
                )
            }
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
    icon: ImageVector? = null,
    iconTone: XiaoiIconTone = XiaoiIconTone.NEUTRAL,
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = title,
        summary = subtitle,
        enabled = enabled,
        startAction = if (icon != null) {
            { XiaoiRowIcon(icon, iconTone) }
        } else {
            null
        },
    )
}
