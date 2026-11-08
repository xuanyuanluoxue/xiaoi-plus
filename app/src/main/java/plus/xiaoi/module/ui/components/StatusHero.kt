package plus.xiaoi.module.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

/** 四维状态输入；与 DimensionBadge 一一对应。 */
data class DimensionState(
    val label: String,
    val status: DimensionStatus,
    val text: String,
)

/**
 * 首页状态总览：背景按四维状态取色（12%）。四维 2×2，主按钮最多一个。
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
        dimensions.any { it.status == DimensionStatus.ERROR } -> colors.error
        dimensions.any { it.status == DimensionStatus.WAITING } -> colors.warning
        dimensions.isNotEmpty() && dimensions.all {
            it.status == DimensionStatus.OK || it.status == DimensionStatus.READY
        } -> colors.success
        else -> null
    }
    val background = tone?.copy(alpha = 0.12f) ?: colors.surface
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(title, color = colors.heroOn, style = XiaoiTheme.typography.hero)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = colors.textSecondary, style = XiaoiTheme.typography.caption)
        }
        if (dimensions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
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
