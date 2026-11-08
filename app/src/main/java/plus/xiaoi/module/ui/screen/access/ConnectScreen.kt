package plus.xiaoi.module.ui.screen.access

import androidx.compose.runtime.Composable
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder

/**
 * 连接信息占位（A6 替换）。
 * SCREENS.md 定义为 BottomSheet；A6 接入时由接入页拉起，路由保留以便调试与深链。
 */
@Composable
fun ConnectScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    XiaoiPlaceholder(
        title = "连接信息",
        note = "8771/8770 URL、客户端 JSON、Skill 文本与复制将在 P2 以 BottomSheet 形式接入；内容含令牌。",
    )
}
