package plus.xiaoi.module.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy

/** 代码/JSON 块：Mono 12，超长内部滚动；复制由调用方给 Snackbar。 */
@Composable
fun XiaoiCodeBlock(
    text: String,
    maxLines: Int = 12,
    onCopy: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = XiaoiTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface2, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        if (onCopy != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = MiuixIcons.Copy,
                        contentDescription = "复制",
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Column(
            Modifier
                .heightIn(max = (maxLines * 20).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = text,
                color = colors.text,
                style = XiaoiTheme.typography.mono,
            )
        }
    }
}
