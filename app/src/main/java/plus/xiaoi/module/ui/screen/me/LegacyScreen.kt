package plus.xiaoi.module.ui.screen.me

import androidx.compose.runtime.Composable
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder

/** 兼容模式占位（A7 替换，只读）：config.json 路径、脱敏 JSON、损坏文件列表。 */
@Composable
fun LegacyScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    XiaoiPlaceholder(
        title = "兼容模式",
        note = "config.json 路径/最后加载、脱敏 JSON 与 .corrupt/.legacy 列表将在 P2 接入；禁止保存/导入/修改。",
    )
}
