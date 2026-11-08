package plus.xiaoi.module.ui.screen.me

import androidx.compose.runtime.Composable
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder

/**
 * 外观占位（A7 替换）：主题三档即时生效，不写入 config.json。
 * 签名保留 themeMode/onThemeChange，接入时不需要改 MainActivity。
 */
@Composable
fun AppearanceScreen(
    themeMode: Int,
    onThemeChange: (Int) -> Unit,
    onMessage: (String) -> Unit,
) {
    XiaoiPlaceholder(
        title = "外观",
        note = "主题三档（跟随系统/浅色/深色）即时生效将在 P2 接入；不写入 config.json、不参与导出。",
    )
}
