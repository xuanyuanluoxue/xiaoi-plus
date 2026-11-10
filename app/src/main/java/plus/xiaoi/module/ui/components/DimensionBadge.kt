package plus.xiaoi.module.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

/** 四维状态；UNKNOWN 只能显示「未确认」，不能伪造健康。 */
enum class DimensionStatus { READY, OK, WAITING, ERROR, UNKNOWN }

/** 单枚维度徽章：28dp，单行省略；stateDescription 供 TalkBack 朗读。 */
@Composable
fun DimensionBadge(
    label: String,
    status: DimensionStatus,
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = XiaoiTheme.colors
    // 正常/已确认 = 中性底（不再用绿色）；只有等待（琥珀）和失败（红）才着色。
    // 半透明叠加：无论 Hero 卡片是什么底色，徽章都能看出边界（不再靠和卡片撞色的 chipBg）。
    val background = when (status) {
        DimensionStatus.READY, DimensionStatus.OK ->
            colors.textSecondary.copy(alpha = 0.16f)

        DimensionStatus.UNKNOWN -> colors.textSecondary.copy(alpha = 0.10f)
        DimensionStatus.WAITING -> colors.warning.copy(alpha = 0.16f)
        DimensionStatus.ERROR -> colors.error.copy(alpha = 0.16f)
    }
    val labelColor = when (status) {
        DimensionStatus.READY, DimensionStatus.OK -> colors.heroOn
        DimensionStatus.WAITING -> colors.warning
        DimensionStatus.ERROR -> colors.error
        DimensionStatus.UNKNOWN -> colors.textSecondary
    }
    Box(
        modifier = modifier
            .height(28.dp)
            .background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp)
            .semantics { stateDescription = "$label：$text" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = labelColor, fontWeight = FontWeight.Medium)) { append(label) }
                append("  ")
                append(text)
            },
            color = colors.heroOn,
            style = XiaoiTheme.typography.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
