package plus.xiaoi.module.ui.screen.access

import androidx.compose.runtime.Composable
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder

/** 接入一级页占位（A6 替换）：通道状态、令牌、连接信息、能力、停用全部。 */
@Composable
fun AccessScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    XiaoiPlaceholder(
        title = "接入",
        note = "8771/8770 通道、令牌与安全、连接信息、能力与工具将在 P2 接入；8770 没有独立开关。",
    )
}
