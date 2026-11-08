package plus.xiaoi.module.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Notes

/** Miuix Card：使用组件默认圆角/颜色；自定义内容加 16dp 内边距。 */
@Composable
fun XiaoiCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        Card(modifier = modifier.fillMaxWidth(), onClick = onClick) {
            Column(Modifier.padding(16.dp), content = content)
        }
    } else {
        Card(modifier = modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), content = content)
        }
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

/** 按钮风格；与 COMPONENTS.md §2 对齐。 */
enum class XiaoiButtonStyle { PRIMARY, SECONDARY, TEXT, DANGER_TEXT }

/** 主/次按钮：48dp 高、16dp 圆角；TEXT/DANGER_TEXT 走 Miuix TextButton。 */
@Composable
fun XiaoiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: XiaoiButtonStyle = XiaoiButtonStyle.PRIMARY,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = XiaoiTheme.colors
    if (style == XiaoiButtonStyle.TEXT || style == XiaoiButtonStyle.DANGER_TEXT) {
        XiaoiTextButton(
            text = text,
            modifier = modifier,
            enabled = enabled && !loading,
            danger = style == XiaoiButtonStyle.DANGER_TEXT,
            onClick = onClick,
        )
        return
    }
    val primaryStyle = style == XiaoiButtonStyle.PRIMARY
    val container = if (primaryStyle) colors.primary else colors.chipOkBg
    val content = if (primaryStyle) colors.onPrimary else colors.primary
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        minHeight = XiaoiTheme.dimens.touch,
        cornerRadius = XiaoiTheme.dimens.radiusButton,
        colors = ButtonDefaults.buttonColors(
            color = container,
            contentColor = content,
            disabledColor = colors.chipBg,
            disabledContentColor = colors.textHint,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                size = 16.dp,
                strokeWidth = 2.dp,
                colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = content),
            )
            Spacer(Modifier.width(8.dp))
        }
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
