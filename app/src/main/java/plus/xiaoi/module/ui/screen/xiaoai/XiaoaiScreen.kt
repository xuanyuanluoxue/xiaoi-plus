package plus.xiaoi.module.ui.screen.xiaoai

import androidx.compose.runtime.Composable
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder

/** 小爱一级页占位（A5 替换）：状态卡、当前模型、运行态、增强能力。 */
@Composable
fun XiaoaiScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    XiaoiPlaceholder(
        title = "小爱",
        note = "状态卡、当前模型、运行态与增强能力将在 P2 接入；主开关即时保存、表单显式保存。",
    )
}
