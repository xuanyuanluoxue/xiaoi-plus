package plus.xiaoi.module.ui.screen.me

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiCardGroup
import plus.xiaoi.module.ui.components.XiaoiCopyRow
import plus.xiaoi.module.ui.components.XiaoiIconTone
import plus.xiaoi.module.ui.components.XiaoiInfoRow
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.theme.XiaoiTheme
import plus.xiaoi.module.ui.components.xiaoiTopBarInset
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Update

private const val REPO = "https://github.com/xuanyuanluoxue/xiaoi-plus"

@Composable
fun AboutScreen(onNavigate: (String) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val colors = XiaoiTheme.colors
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrDefault("?")

    fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            onMessage("没有可用浏览器")
        }
    }

    fun copy(text: String, label: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            onMessage("已复制$label")
        } catch (t: Throwable) {
            onMessage("复制失败")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = xiaoiTopBarInset(), bottom = 112.dp)
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        XiaoiCard {
            Text("小爱同学plus", color = colors.text, style = XiaoiTheme.typography.title)
            Spacer(Modifier.height(4.dp))
            Text("v$version", color = colors.textSecondary, style = XiaoiTheme.typography.caption)
            Spacer(Modifier.height(8.dp))
            Text(
                "模型重定向 / 8771 控制面 / 8770 动作面；仅在自己设备生效。",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
        }

        Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))

        XiaoiCardGroup {
            XiaoiPreferenceCard {
                XiaoiCopyRow(
                    "作者",
                    "轩辕落雪",
                    onCopy = { copy("轩辕落雪", "作者名") },
                    icon = MiuixIcons.Community,
                    iconTone = XiaoiIconTone.ACCENT,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    "GitHub",
                    "xuanyuanluoxue/xiaoi-plus",
                    onClick = { open(REPO) },
                    icon = MiuixIcons.Link,
                    iconTone = XiaoiIconTone.ACCENT,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    "许可证",
                    "GPL-3.0 · 查看全部开源许可",
                    onClick = { onNavigate("Licenses") },
                    icon = MiuixIcons.File,
                    iconTone = XiaoiIconTone.ACCENT,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    "致谢",
                    "OpenClaw · KernelSU Next · Miuix",
                    icon = MiuixIcons.Favorites,
                    iconTone = XiaoiIconTone.ACCENT,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    "检查更新",
                    "打开 GitHub Releases",
                    onClick = { open("$REPO/releases") },
                    icon = MiuixIcons.Update,
                    iconTone = XiaoiIconTone.ACCENT,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    "项目主页",
                    "README、构建说明与已知限制",
                    onClick = { open(REPO) },
                    icon = MiuixIcons.Refresh,
                    iconTone = XiaoiIconTone.ACCENT,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "免责声明：本项目不修改、不重打包、不分发小爱同学的任何代码或资源；" +
                "仅通过 LSPosed 在自己设备上做运行时互操作。",
            color = colors.textHint,
            style = XiaoiTheme.typography.caption,
        )
        Spacer(Modifier.height(24.dp))
    }
}
