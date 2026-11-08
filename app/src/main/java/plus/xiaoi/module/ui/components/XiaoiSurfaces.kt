package plus.xiaoi.module.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Notes

/** Miuix Card：使用组件默认圆角/颜色；自定义内容加 16dp 内边距。 */
@Composable
fun XiaoiCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

/** Preference 卡片：ArrowPreference / SwitchPreference 自带内边距，不再额外加。 */
@Composable
fun XiaoiPreferenceCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column { content() }
    }
}

/** 主按钮：48dp 高、16dp 圆角。 */
@Composable
fun XiaoiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            color = XiaoiTheme.colors.primary,
            contentColor = XiaoiTheme.colors.onPrimary,
            disabledColor = XiaoiTheme.colors.chipBg,
            disabledContentColor = XiaoiTheme.colors.textHint,
        ),
    ) {
        Text(
            text = text,
            style = XiaoiTheme.typography.rowTitle,
        )
    }
}

/** 文字按钮：用 Miuix TextButton；透明底，仅保留文字色，48dp 触控。 */
@Composable
fun XiaoiTextButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = XiaoiTheme.colors
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minHeight = XiaoiTheme.dimens.touch,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.textButtonColors(
            color = Color.Transparent,
            textColor = if (danger) colors.error else colors.primary,
            disabledColor = Color.Transparent,
            disabledTextColor = colors.textHint,
        ),
        textStyle = XiaoiTheme.typography.body,
    )
}

@Composable
fun XiaoiGroupHeader(text: String) {
    Text(
        text = text,
        color = XiaoiTheme.colors.textSecondary,
        style = XiaoiTheme.typography.caption,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

enum class XiaoiTone { NEUTRAL, SUCCESS, WARNING, ERROR, INFO }

@Composable
fun XiaoiBadge(text: String, tone: XiaoiTone = XiaoiTone.NEUTRAL) {
    val colors = XiaoiTheme.colors
    val (bg, fg) = when (tone) {
        XiaoiTone.SUCCESS -> colors.success.copy(alpha = 0.14f) to colors.success
        XiaoiTone.WARNING -> colors.warningBg to colors.warning
        XiaoiTone.ERROR -> colors.errorBg to colors.error
        XiaoiTone.INFO -> colors.chipOkBg to colors.primary
        XiaoiTone.NEUTRAL -> colors.chipBg to colors.textSecondary
    }
    Box(
        Modifier
            .background(bg, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = fg, style = XiaoiTheme.typography.caption, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun XiaoiChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = XiaoiTheme.colors
    Box(
        Modifier
            .heightIn(min = XiaoiTheme.dimens.touch)
            .background(if (selected) colors.chipOkBg else colors.chipBg, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) colors.primary else colors.textSecondary,
            style = XiaoiTheme.typography.caption,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
fun XiaoiEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = MiuixIcons.Notes,
) {
    val colors = XiaoiTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = colors.textHint,
        )
        Spacer(Modifier.height(12.dp))
        Text(title, color = colors.text, style = XiaoiTheme.typography.rowTitle)
        Spacer(Modifier.height(4.dp))
        Text(
            description,
            color = colors.textSecondary,
            style = XiaoiTheme.typography.caption,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun XiaoiStatTile(label: String, value: String?) {
    Column(
        Modifier
            .background(XiaoiTheme.colors.surface, RoundedCornerShape(XiaoiTheme.dimens.radiusCard))
            .padding(16.dp),
    ) {
        Text(label, color = XiaoiTheme.colors.textSecondary, style = XiaoiTheme.typography.caption)
        Spacer(Modifier.height(6.dp))
        Text(
            value ?: "—",
            color = XiaoiTheme.colors.text,
            style = XiaoiTheme.typography.display,
        )
    }
}

@Composable
fun XiaoiDivider() {
    HorizontalDivider(color = XiaoiTheme.colors.line)
}

@Composable
fun XiaoiHeaderRow(title: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, color = XiaoiTheme.colors.text, style = XiaoiTheme.typography.hero)
        trailing()
    }
}
