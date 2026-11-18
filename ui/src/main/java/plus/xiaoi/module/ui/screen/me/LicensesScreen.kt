package plus.xiaoi.module.ui.screen.me

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiCardGroup
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.theme.XiaoiTheme
import plus.xiaoi.module.ui.components.xiaoiTopBarInset
import top.yukonga.miuix.kmp.basic.Text

/** 开源许可（SCREENS.md §14）：保留三组版权声明，不做网络请求。 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun LicensesScreen(onMessage: (String) -> Unit) {
    val colors = XiaoiTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = xiaoiTopBarInset(), bottom = 112.dp)
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        XiaoiCard {
            Text("本项目", color = colors.text, style = XiaoiTheme.typography.title)
            Spacer(Modifier.height(6.dp))
            Text(
                "小爱同学plus 以 GPL-3.0 分发。你可以运行、研究、修改与再分发，" +
                    "再分发时需保留同样的许可证与版权声明。",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
        }

        XiaoiGroupHeader("第三方组件")
        XiaoiCardGroup {
            XiaoiCard {
                LicenseBlock(
                    title = "Miuix · Apache-2.0",
                    body = "© compose-miuix-ui contributors\n" +
                        "本项目的设置页使用其 Compose 组件与设计令牌。" +
                        "Apache-2.0 允许商用与修改，需保留版权与许可声明。",
                )
            }
            XiaoiCard {
                LicenseBlock(
                    title = "LiquidTab · GPL-3.0",
                    body = "底栏玻璃效果的实现参考并改编自 big-dictator/LiquidTab" +
                        "（GPL-3.0）；改编文件保留原始版权与许可头。" +
                        "本项目同样以 GPL-3.0 分发。",
                )
            }
            XiaoiCard {
                LicenseBlock(
                    title = "AndroidX Graphics Shapes · Apache-2.0",
                    body = "© The Android Open Source Project\n" +
                        "用于底栏的连续曲率（squircle）外形计算。",
                )
            }
            XiaoiCard {
                LicenseBlock(
                    title = "Kyant0 / AndroidLiquidGlass · Apache-2.0",
                    body = "液态玻璃的折射与高光渲染思路参考 Kyant0 的 Backdrop" +
                        "（AndroidLiquidGlass）；本项目未整体打包该库。",
                )
            }
            XiaoiCard {
                LicenseBlock(
                    title = "Kotlin / Compose / AndroidX · 各自许可证",
                    body = "Kotlin © JetBrains s.r.o.（Apache-2.0）\n" +
                        "Compose Multiplatform © JetBrains s.r.o.（Apache-2.0）\n" +
                        "AndroidX © The Android Open Source Project（Apache-2.0）",
                )
            }
            XiaoiCard {
                LicenseBlock(
                    title = "致谢",
                    body = "OpenClaw · KernelSU Next · Miuix\n" +
                        "以上项目仅在本机运行时互操作，本项目不重打包或分发其代码。",
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "完整许可证文本随源码仓库分发（LICENSE）。本页只做摘要，不替代许可证原文。",
            color = colors.textHint,
            style = XiaoiTheme.typography.caption,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LicenseBlock(title: String, body: String) {
    val colors = XiaoiTheme.colors
    Text(title, color = colors.text, style = XiaoiTheme.typography.rowTitle)
    Spacer(Modifier.height(4.dp))
    Text(body, color = colors.textSecondary, style = XiaoiTheme.typography.body)
}
