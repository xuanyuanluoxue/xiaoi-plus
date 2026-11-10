package plus.xiaoi.module.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Pause

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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusHero(
    title: String,
    subtitle: String,
    status: DimensionStatus,
    dimensions: List<DimensionState>,
    primaryText: String?,
    onPrimary: (() -> Unit)?,
    onDetail: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = XiaoiTheme.colors
    // 底色只在卡片色上做 6–8% 着色：状态交给图标和文字，不再铺一整块棕/红底。
    val background = when (status) {
        DimensionStatus.ERROR -> lerp(colors.surface2, colors.error, 0.08f)
        DimensionStatus.WAITING -> lerp(colors.surface2, colors.warning, 0.08f)
        DimensionStatus.OK, DimensionStatus.READY ->
            lerp(colors.surface2, colors.primary, 0.06f)
        DimensionStatus.UNKNOWN -> colors.surface2
    }
    val iconTone = when (status) {
        DimensionStatus.ERROR -> XiaoiIconTone.DANGER
        DimensionStatus.WAITING -> XiaoiIconTone.WARNING
        DimensionStatus.OK, DimensionStatus.READY -> XiaoiIconTone.ACCENT
        DimensionStatus.UNKNOWN -> XiaoiIconTone.NEUTRAL
    }
    // 图标和状态一致：暂停/等待是 Pause，失败是 Close，健康才是 Ok。
    val heroIcon = when (status) {
        DimensionStatus.ERROR -> MiuixIcons.Close
        DimensionStatus.WAITING -> MiuixIcons.Pause
        DimensionStatus.OK, DimensionStatus.READY -> MiuixIcons.Ok
        DimensionStatus.UNKNOWN -> MiuixIcons.Info
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = XiaoiTheme.dimens.radiusCard,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = background),
    ) {
        Column(Modifier.padding(XiaoiTheme.dimens.cardPad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                XiaoiRowIcon(icon = heroIcon, tone = iconTone)
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
                // 自然宽度 + 自动换行：三枚徽章一行放得下就一行，不会留下半行空白。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    dimensions.forEach { state ->
                        DimensionBadge(
                            label = state.label,
                            status = state.status,
                            text = state.text,
                        )
                    }
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
