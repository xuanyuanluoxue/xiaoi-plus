package plus.xiaoi.module.ui.screen.me

import androidx.compose.runtime.Composable
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder

/** 诊断占位（A7 替换）：权限/服务/诊断信息/兼容模式入口。 */
@Composable
fun DiagnosticsScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    XiaoiPlaceholder(
        title = "诊断",
        note = "权限矩阵、服务状态、复制诊断信息、清空日志与兼容模式入口将在 P2 接入；Root/LSPosed 不撒谎。",
    )
}
