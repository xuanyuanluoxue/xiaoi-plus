package plus.xiaoi.module.ui.screen.me

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.R
import plus.xiaoi.module.ui.components.XiaoiButton
import plus.xiaoi.module.ui.components.XiaoiButtonStyle
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiTextButton
import plus.xiaoi.module.ui.components.xiaoiTopBarInset
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

/** 爱发电主页；换地址只改这一处。 */
private const val AFDIAN_URL = "https://afdian.com/a/chatxavier"

/**
 * 赞助支持：爱发电（推荐）+ 微信收款码。
 *
 * <p>不写入配置、不参与备份；赞助与否不影响任何功能。二维码是静态图片，
 * 只在关于/赞助页展示，不上传到任何服务。</p>
 */
@Composable
fun SponsorScreen(onMessage: (String) -> Unit) {
    val colors = XiaoiTheme.colors
    val context = LocalContext.current

    fun copyLink() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("afdian", AFDIAN_URL))
            onMessage("已复制爱发电链接")
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
            Text(
                "如果这个项目帮到了你",
                color = colors.text,
                style = XiaoiTheme.typography.title,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "可以请我喝杯咖啡。赞助不影响任何功能，量力而行就好。",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
        }

        XiaoiGroupHeader("爱发电")
        XiaoiCard {
            Text(
                "推荐用爱发电：一次性和包月都支持，链接长期有效。",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
            Spacer(Modifier.height(12.dp))
            XiaoiButton(
                text = "打开爱发电",
                style = XiaoiButtonStyle.PRIMARY,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(AFDIAN_URL)),
                        )
                    } catch (t: Throwable) {
                        onMessage("无法打开浏览器，请复制链接")
                    }
                },
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                XiaoiTextButton(text = "复制链接", onClick = { copyLink() })
            }
            Spacer(Modifier.height(2.dp))
            Text(
                AFDIAN_URL,
                color = colors.textHint,
                style = XiaoiTheme.typography.mono,
            )
        }

        XiaoiGroupHeader("微信")
        XiaoiCard {
            Text(
                "微信扫一扫，或长按识别二维码。",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.sponsor_wechat),
                    contentDescription = "微信收款二维码",
                    modifier = Modifier
                        .width(300.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "感谢每一位支持者。",
            color = colors.textHint,
            style = XiaoiTheme.typography.caption,
        )
    }
}