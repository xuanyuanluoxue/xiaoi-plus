package plus.xiaoi.module.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.HorizontalDivider

/**
 * 脏数据条：非 dirty 时完全不占位；保存中禁用两个按钮。
 * 只负责展示与回调，状态由调用方持有。
 */
@Composable
fun XiaoiDirtyBar(
    dirty: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!dirty) return
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = XiaoiTheme.colors.line)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XiaoiTextButton(
                text = "放弃",
                enabled = !saving,
                onClick = onDiscard,
            )
            Spacer(Modifier.width(12.dp))
            Spacer(Modifier.weight(1f))
            XiaoiButton(
                text = "保存",
                onClick = onSave,
                enabled = !saving,
                loading = saving,
            )
        }
    }
}
