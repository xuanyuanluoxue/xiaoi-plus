package plus.xiaoi.module.ui.screen.access

import androidx.compose.runtime.Composable
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder

/** 能力清单占位（A6 替换）：67 条只读、可搜索可筛选。 */
@Composable
fun CapabilitiesScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    XiaoiPlaceholder(
        title = "能力清单",
        note = "67 条能力的搜索、状态/域筛选与详情跳转将在 P2 接入；只读，无试运行按钮。",
    )
}
