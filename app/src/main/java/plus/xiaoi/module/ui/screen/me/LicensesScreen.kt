package plus.xiaoi.module.ui.screen.me

import androidx.compose.runtime.Composable
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder

/** 开源许可占位（A7 替换）：GPL-3.0、Apache-2.0（Miuix）与 AndroidX/Kotlin。 */
@Composable
fun LicensesScreen(
    onMessage: (String) -> Unit,
) {
    XiaoiPlaceholder(
        title = "开源许可",
        note = "GPL-3.0、Apache-2.0（Miuix）与 Kotlin/Compose/AndroidX 版权声明将在 P2 接入。",
    )
}
