package plus.xiaoi.module.ui.screen.me

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.Prefs
import plus.xiaoi.module.ThemeMode
import plus.xiaoi.module.ui.components.FilterChips
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiSwitchRow
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

/**
 * 外观（SCREENS.md §10）。
 *
 * 三档主题即时生效；唯一事实源是 App prefs（ThemeManager），
 * 不写 config.json，也不参与备份/导出/分享。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun AppearanceScreen(
    themeMode: Int,
    onThemeChange: (Int) -> Unit,
    onMessage: (String) -> Unit,
) {
    val colors = XiaoiTheme.colors
    val context = LocalContext.current
    var glassEnabled by remember {
        mutableStateOf(
            context.getSharedPreferences(Prefs.UI_PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(Prefs.KEY_GLASS_BOTTOM_BAR, false),
        )
    }
    val current = when (themeMode) {
        ThemeMode.LIGHT -> ThemeMode.LIGHT
        ThemeMode.DARK -> ThemeMode.DARK
        else -> ThemeMode.SYSTEM
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 112.dp)
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        XiaoiCard {
            Text("主题", color = colors.text, style = XiaoiTheme.typography.title)
            Spacer(Modifier.height(4.dp))
            Text(
                "选择后立即生效；切换时会重建界面，未保存的编辑不会丢。",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
            Spacer(Modifier.height(16.dp))
            FilterChips(
                options = listOf("跟随系统", "浅色", "深色"),
                selected = when (current) {
                    ThemeMode.LIGHT -> "浅色"
                    ThemeMode.DARK -> "深色"
                    else -> "跟随系统"
                },
                onSelect = {
                    onThemeChange(
                        when (it) {
                            "浅色" -> ThemeMode.LIGHT
                            "深色" -> ThemeMode.DARK
                            else -> ThemeMode.SYSTEM
                        },
                    )
                },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                when (current) {
                    ThemeMode.LIGHT -> "当前：浅色（不随系统深色模式变化）"
                    ThemeMode.DARK -> "当前：深色（不随系统浅色模式变化）"
                    else -> "当前：跟随系统（系统切换深浅色时同步变化）"
                },
                color = colors.textHint,
                style = XiaoiTheme.typography.caption,
            )
        }

        XiaoiGroupHeader("底栏")
        XiaoiPreferenceCard {
            XiaoiSwitchRow(
                title = "玻璃底栏",
                subtitle = "关闭后使用同尺寸实心胶囊；系统移除动画时也会自动回退",
                checked = glassEnabled,
                onCheckedChange = { on ->
                    glassEnabled = on
                    context.getSharedPreferences(Prefs.UI_PREFS, android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean(Prefs.KEY_GLASS_BOTTOM_BAR, on).apply()
                    (context as? Activity)?.recreate()
                },
            )
        }

        XiaoiGroupHeader("说明")
        XiaoiCard {
            Text(
                "主题只影响这台手机上的界面配色：",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "· 不写入 config.json\n" +
                    "· 不参与备份、脱敏导出或分享\n" +
                    "· 不影响模型、MCP 令牌或 8771/8770 通道",
                color = colors.textHint,
                style = XiaoiTheme.typography.caption,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
