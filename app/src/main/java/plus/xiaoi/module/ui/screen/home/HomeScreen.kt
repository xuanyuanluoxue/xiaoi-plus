package plus.xiaoi.module.ui.screen.home

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.HomeActivity
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiButton
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

/**
 * 首页占位（A4 替换）。签名固定：路由 + 统一提示。
 * P2 期间保留旧版首页入口，避免功能不可达；其余页面不再提供旧版入口。
 */
@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = XiaoiTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop),
    ) {
        XiaoiCard {
            XiaoiBadge("P2 待接入", XiaoiTone.INFO)
            Spacer(Modifier.height(12.dp))
            Text("状态总览", color = colors.text, style = XiaoiTheme.typography.title)
            Spacer(Modifier.height(6.dp))
            Text(
                "四维状态、待处理、双域入口、运行概况与最近活动将在 P2 接入。",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "调试入口：--es route Home / Model / Bridge / Token / Connect / Capabilities / Tools / Permissions / Appearance / ConfigManage / Diagnostics / Legacy / Licenses",
                color = colors.textHint,
                style = XiaoiTheme.typography.caption,
            )
        }
        Spacer(Modifier.height(12.dp))
        XiaoiButton(
            text = "打开旧版首页",
            onClick = {
                try {
                    context.startActivity(Intent(context, HomeActivity::class.java))
                } catch (t: Throwable) {
                    onMessage("无法打开旧版首页")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "旧版入口只在 P2 过渡期保留；首页本身不再承载配置。",
            color = colors.textHint,
            style = XiaoiTheme.typography.caption,
        )
    }
}
