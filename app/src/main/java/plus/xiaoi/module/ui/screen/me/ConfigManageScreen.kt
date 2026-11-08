package plus.xiaoi.module.ui.screen.me

import androidx.compose.runtime.Composable
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder

/** 配置管理占位（A7 替换）：备份/还原/脱敏导出/导入/损坏恢复。 */
@Composable
fun ConfigManageScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    XiaoiPlaceholder(
        title = "配置管理",
        note = "备份（含密钥确认）、还原、脱敏导出/分享、运行时导入与损坏恢复将在 P2 接入。",
    )
}
