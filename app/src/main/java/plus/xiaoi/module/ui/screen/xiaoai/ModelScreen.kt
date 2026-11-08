package plus.xiaoi.module.ui.screen.xiaoai

import androidx.compose.runtime.Composable
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder

/** 模型与服务商占位（A5 替换）：0.14 唯一可编辑的模型表单。 */
@Composable
fun ModelScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    XiaoiPlaceholder(
        title = "模型与服务商",
        note = "服务商/模型 chips、端点、密钥、校验与显式保存将在 P2 接入；校验只更新 D2，不改 D4。",
    )
}
