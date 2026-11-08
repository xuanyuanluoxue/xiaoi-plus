package plus.xiaoi.module.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight

/** 能力状态；PLANNED/UNSUPPORTED 不得显示成「可用」。 */
enum class CapabilityStatus {
    AVAILABLE, LIMITED, PERMISSION_MISSING, UNAVAILABLE, PLANNED, DISABLED, UNSUPPORTED
}

/** 能力风险等级，仅用于展示，不阻塞点击。 */
enum class CapabilityRisk { LOW, MEDIUM, HIGH, CRITICAL }

/** 72dp 能力行：标题 + `id · channel`；右侧风险/状态徽章 + 箭头。 */
@Composable
fun CapabilityRow(
    id: String,
    title: String,
    channel: String,
    status: CapabilityStatus,
    risk: CapabilityRisk,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = XiaoiTheme.colors
    val (statusText, statusTone) = when (status) {
        CapabilityStatus.AVAILABLE -> "可用" to XiaoiTone.SUCCESS
        CapabilityStatus.LIMITED -> "部分可用" to XiaoiTone.WARNING
        CapabilityStatus.PERMISSION_MISSING -> "需授权" to XiaoiTone.WARNING
        CapabilityStatus.PLANNED -> "规划中" to XiaoiTone.INFO
        CapabilityStatus.DISABLED, CapabilityStatus.UNAVAILABLE -> "不提供" to XiaoiTone.NEUTRAL
        CapabilityStatus.UNSUPPORTED -> "不支持" to XiaoiTone.NEUTRAL
    }
    val riskText = when (risk) {
        CapabilityRisk.LOW -> "低"
        CapabilityRisk.MEDIUM -> "中"
        CapabilityRisk.HIGH -> "高"
        CapabilityRisk.CRITICAL -> "极高"
    }
    val riskTone = when (risk) {
        CapabilityRisk.LOW -> XiaoiTone.NEUTRAL
        CapabilityRisk.MEDIUM -> XiaoiTone.INFO
        CapabilityRisk.HIGH, CapabilityRisk.CRITICAL -> XiaoiTone.ERROR
    }
    BasicComponent(
        modifier = modifier.heightIn(min = 72.dp),
        title = title,
        titleColor = BasicComponentDefaults.titleColor(color = colors.text),
        summary = "$id · $channel",
        summaryColor = BasicComponentDefaults.summaryColor(color = colors.textSecondary),
        endActions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                XiaoiBadge(riskText, riskTone)
                Spacer(Modifier.width(6.dp))
                XiaoiBadge(statusText, statusTone)
                Spacer(Modifier.width(6.dp))
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
