package plus.xiaoi.module.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok

/** 四维状态输入；与 DimensionBadge 一一对应。 */
data class DimensionState(
    val label: String,
    val status: DimensionStatus,
    val text: String,
)

/**
 * 首页状态 Hero：独立卡片 + 语义色圆图标（绿/黄/红/灰），保留四维徽章。
 * 只负责展示；CTA 由调用方决定。
 */
@Composable
fun StatusHero(
    title: String,
    subtitle: String,
    dimensions: List<DimensionState>,
    primaryText: String?,
    onPrimary: (() -> Unit)?,
    onDetail: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = XiaoiTheme.colors
    val tone: Color? = when {
        dimensions.any { it.status == DimensionStatus.ERROR } -> colors.iconRed
        dimensions.any { it.status == DimensionStatus.WAITING } -> colors.iconOrange
        dimensions.isNotEmpty() && dimensions.all {
            it.status == DimensionStatus.OK || it.status == DimensionStatus.READY
        } -> colors.iconGreen
        else -> null
    }
    val background = (tone ?: colors.textSecondary).copy(alpha = 0.14f)
    val iconTone = when {
        dimensions.any { it.status == DimensionStatus.ERROR } -> XiaoiIconTone.RED
        dimensions.any { it.status == DimensionStatus.WAITING } -> XiaoiIconTone.ORANGE
        tone != null -> XiaoiIconTone.GREEN
        else -> XiaoiIconTone.NEUTRAL
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = XiaoiTheme.dimens.radiusCard,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = background),
    ) {
        Column(Modifier.padding(XiaoiTheme.dimens.cardPad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                XiaoiRowIcon(
                    icon = if (iconTone == XiaoiIconTone.NEUTRAL) MiuixIcons.Info else MiuixIcons.Ok,
                    tone = iconTone,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = colors.text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (subtitle.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            subtitle,
                            color = colors.textSecondary,
                            style = XiaoiTheme.typography.body,
                        )
                    }
                }
            }
            if (dimensions.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                dimensions.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { state ->
                            DimensionBadge(
                                label = state.label,
                                status = state.status,
                                text = state.text,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (onPrimary != null && primaryText != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    XiaoiButton(primaryText, onPrimary, style = XiaoiButtonStyle.PRIMARY)
                    if (onDetail != null) {
                        Spacer(Modifier.width(8.dp))
                        XiaoiTextButton("查看详情", onClick = onDetail)
                    }
                }
            } else if (onDetail != null) {
                Spacer(Modifier.height(8.dp))
                XiaoiTextButton("查看详情", onClick = onDetail)
            }
        }
    }
}
