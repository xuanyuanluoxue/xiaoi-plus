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
    val tone = when (status) {
        DimensionStatus.READY, DimensionStatus.OK -> colors.success
        DimensionStatus.WAITING -> colors.warning
        DimensionStatus.ERROR -> colors.error
        DimensionStatus.UNKNOWN -> colors.textSecondary
    }
    // UNKNOWN 的淡灰底上 textSecondary 只有 3.8:1，标签改用 heroOn；其余状态色已按 ≥4.5:1 调深。
    val labelColor = if (status == DimensionStatus.UNKNOWN) colors.heroOn else tone
    Box(
        modifier = modifier
            .height(28.dp)
            .background(tone.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
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
