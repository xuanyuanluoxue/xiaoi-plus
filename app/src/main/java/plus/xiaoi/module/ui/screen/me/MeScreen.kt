package plus.xiaoi.module.ui.screen.me

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.R
import plus.xiaoi.module.ThemeMode
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiChip
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiInfoRow
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiTextButton
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun MeScreen(
    themeMode: Int,
    onThemeChange: (Int) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLegacyHome: () -> Unit,
    onOpenLegacyConfig: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = XiaoiTheme.colors
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrDefault("?")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        XiaoiCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_xiaoi),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("小爱同学plus", color = colors.text, style = XiaoiTheme.typography.hero)
                    Text(
                        "v$version · ${Build.MODEL}",
                        color = colors.textSecondary,
                        style = XiaoiTheme.typography.caption,
                    )
                    Text(
                        "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
                        color = colors.textHint,
                        style = XiaoiTheme.typography.caption,
                    )
                }
            }
        }

        XiaoiGroupHeader("外观")
        XiaoiCard {
            Text("主题", color = colors.text, style = XiaoiTheme.typography.rowTitle)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                XiaoiChip("跟随系统", themeMode == ThemeMode.SYSTEM) {
                    onThemeChange(ThemeMode.SYSTEM)
                }
                Spacer(Modifier.size(8.dp))
                XiaoiChip("浅色", themeMode == ThemeMode.LIGHT) {
                    onThemeChange(ThemeMode.LIGHT)
                }
                Spacer(Modifier.size(8.dp))
                XiaoiChip("深色", themeMode == ThemeMode.DARK) {
                    onThemeChange(ThemeMode.DARK)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "只影响本机界面，不写入 config.json、不随配置导出",
                color = colors.textHint,
                style = XiaoiTheme.typography.caption,
            )
        }

        XiaoiGroupHeader("设置")
        XiaoiPreferenceCard {
            XiaoiInfoRow("日志", "查看控制面与小爱上报的运行日志", onOpenLogs)
            XiaoiInfoRow("关于", "版本、GitHub、许可证与致谢", onOpenAbout)
        }

        XiaoiGroupHeader("P1 预览")
        XiaoiCard {
            Text(
                "单 Activity + 4 tab + Miuix 主题 + 基础组件。首页/小爱/接入将在 P2 接入。",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                XiaoiTextButton("旧版首页") { onOpenLegacyHome() }
                Spacer(Modifier.size(8.dp))
                XiaoiTextButton("旧版配置") { onOpenLegacyConfig() }
                Spacer(Modifier.weight(1f))
                XiaoiTextButton("提示") { onMessage("P1 地基：日志与关于已迁移") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
