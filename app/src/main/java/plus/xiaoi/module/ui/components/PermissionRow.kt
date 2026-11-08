package plus.xiaoi.module.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Text

/** 权限状态；无标准 API 时必须用 UNKNOWN →「未确认」。 */
enum class PermissionStatus { GRANTED, DENIED, UNKNOWN, NOT_APPLICABLE }

/** 56dp 权限行：状态徽章 + 可选「去设置」。 */
@Composable
fun PermissionRow(
    name: String,
    status: PermissionStatus,
    detail: String?,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = XiaoiTheme.colors
    val (label, tone) = when (status) {
        PermissionStatus.GRANTED -> "已授权" to XiaoiTone.SUCCESS
        PermissionStatus.DENIED -> "未授权" to XiaoiTone.ERROR
        PermissionStatus.UNKNOWN -> "未确认" to XiaoiTone.NEUTRAL
        PermissionStatus.NOT_APPLICABLE -> "不需要" to XiaoiTone.NEUTRAL
    }
    BasicComponent(
        modifier = modifier.heightIn(min = 56.dp),
        title = name,
        titleColor = BasicComponentDefaults.titleColor(color = colors.text),
        summary = detail,
        summaryColor = BasicComponentDefaults.summaryColor(color = colors.textSecondary),
        endActions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                XiaoiBadge(label, tone)
                if (onOpenSettings != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "去设置",
                        color = colors.primary,
                        style = XiaoiTheme.typography.body,
                        modifier = Modifier
                            .clickable(onClick = onOpenSettings)
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
            }
        },
    )
}
