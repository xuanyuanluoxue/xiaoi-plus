package plus.xiaoi.module.ui.screen.access

import androidx.compose.runtime.Composable
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder

/** 权限矩阵占位（A6 替换）：无标准 API 时显示「未确认」。 */
@Composable
fun PermissionsScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    XiaoiPlaceholder(
        title = "权限",
        note = "通知、精确闹钟、全屏通知、电池优化等权限矩阵将在 P2 接入；未确认不当作拒绝。",
    )
}
