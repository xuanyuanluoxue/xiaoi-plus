package plus.xiaoi.module.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiButton
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

/** P1 占位页：保留导航骨架，真实内容在 P2 接入。 */
@Composable
fun PlaceholderScreen(
    title: String,
    description: String,
    legacyLabel: String,
    onOpenLegacy: () -> Unit,
) {
    val colors = XiaoiTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop),
    ) {
        XiaoiCard {
            XiaoiBadge("P2 接入", XiaoiTone.INFO)
            Spacer(Modifier.height(12.dp))
            Text(title, color = colors.text, style = XiaoiTheme.typography.title)
            Spacer(Modifier.height(6.dp))
            Text(description, color = colors.textSecondary, style = XiaoiTheme.typography.body)
            Spacer(Modifier.height(16.dp))
            XiaoiButton(legacyLabel, onOpenLegacy)
        }
    }
}
