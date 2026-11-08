package plus.xiaoi.module.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

/**
 * 横向筛选 chips（全站唯一筛选视觉）：视觉 28dp，触控目标 48dp。
 * 未选中 = chipBg + textSecondary；选中 = primary 实心 + onPrimary，深浅色都清楚。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterChips(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = XiaoiTheme.colors
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Tab) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .background(
                            if (isSelected) colors.primary else colors.chipBg,
                            RoundedCornerShape(999.dp),
                        )
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option,
                        color = if (isSelected) colors.onPrimary else colors.textSecondary,
                        style = XiaoiTheme.typography.caption,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
