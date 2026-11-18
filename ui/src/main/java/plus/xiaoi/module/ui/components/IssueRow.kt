package plus.xiaoi.module.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.icon.extended.Info

/** 待处理严重度；只用于图标与颜色，不允许只靠颜色表达。 */
enum class IssueSeverity { INFO, WARN, ERROR }

/** 首页待处理行的 UI 数据；id 用于去重，不做动作。 */
data class IssueUi(
    val id: String,
    val title: String,
    val actionText: String,
    val severity: IssueSeverity,
)

/** 64dp 待处理行：整行可点，不提供「忽略/关闭」。 */
@Composable
fun IssueRow(issue: IssueUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = XiaoiTheme.colors
    val (icon, tint, desc) = when (issue.severity) {
        IssueSeverity.INFO -> Triple(MiuixIcons.Info, colors.textSecondary, "提示")
        IssueSeverity.WARN -> Triple(MiuixIcons.Help, colors.warning, "注意")
        IssueSeverity.ERROR -> Triple(MiuixIcons.Close, colors.error, "错误")
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(issue.title, color = colors.text, style = XiaoiTheme.typography.rowTitle)
            Spacer(Modifier.height(2.dp))
            Text(
                issue.actionText,
                color = colors.primary,
                style = XiaoiTheme.typography.body,
            )
        }
    }
}
