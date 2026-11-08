package plus.xiaoi.module.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text

/** 统一卡片：24dp 圆角、surface、20dp 内边距、无阴影。 */
@Composable
fun XiaoiCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(XiaoiTheme.dimens.cardPad)) { content() }
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
        minHeight = 48.dp,
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

/** 文字按钮：用于卡片内次级动作。 */
@Composable
fun XiaoiTextButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = text,
        color = XiaoiTheme.colors.primary,
        style = XiaoiTheme.typography.body,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
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
            .background(if (selected) colors.chipOkBg else colors.chipBg, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
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
fun XiaoiEmptyState(title: String, description: String, modifier: Modifier = Modifier) {
    val colors = XiaoiTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("·", color = colors.textHint, style = XiaoiTheme.typography.display)
        Spacer(Modifier.height(8.dp))
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
    Box(Modifier.fillMaxWidth().height(1.dp).background(XiaoiTheme.colors.line))
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
