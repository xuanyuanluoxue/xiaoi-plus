package plus.xiaoi.module.ui.screen.access

import androidx.compose.runtime.Composable
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder

/** 工具清单占位（A6 替换）：8771 8 个 + 8770 6 个。 */
@Composable
fun ToolsScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    XiaoiPlaceholder(
        title = "工具清单",
        note = "8771 八个与 8770 六个工具的名称/描述/风险/确认策略将在 P2 接入；只读。",
    )
}
