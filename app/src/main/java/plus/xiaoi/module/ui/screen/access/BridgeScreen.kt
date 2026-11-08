package plus.xiaoi.module.ui.screen.access

import androidx.compose.runtime.Composable
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder

/** 端口与服务占位（A6 替换，只读）：8771/8770 分开显示。 */
@Composable
fun BridgeScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    XiaoiPlaceholder(
        title = "端口与服务",
        note = "8771 的端口/绑定/最后上报/rev/工具数，8770 的端口/绑定/启动时间/最后错误将在 P2 接入。",
    )
}
