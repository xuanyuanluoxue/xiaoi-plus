package plus.xiaoi.module.ui.screen.access

import androidx.compose.runtime.Composable
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder

/** 令牌与局域网占位（A6 替换）：弱令牌回退仅本机。 */
@Composable
fun TokenScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    XiaoiPlaceholder(
        title = "令牌与局域网",
        note = "令牌生成/掩码/复制、8771/8770 局域网与强度提示将在 P2 接入；不足 32 位自动回退仅本机。",
    )
}
