package plus.xiaoi.module.ui.components.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.core.NavStack

/**
 * 底栏 4 个 tab：顺序与 [NavStack.MainTab] 声明顺序一致。
 * 图标统一 24dp 实心/同笔触，不混用 Miuix 的线性与工程内线性 `ic_home`。
 *
 * Adapted from LiquidTab (GPL-3.0, big-dictator/LiquidTab) 的选中 pill 结构；
 * 图标矢量取自本仓库 archive/liquid-nav-2026-11-08 的 XiaoiNavIcons。
 */
internal data class GlassTab(
    val tab: NavStack.MainTab,
    val label: String,
    val icon: ImageVector,
)

internal val GlassTabs: List<GlassTab> = listOf(
    GlassTab(NavStack.MainTab.HOME, "首页", GlassNavIcons.Home),
    GlassTab(NavStack.MainTab.XIAOAI, "小爱", GlassNavIcons.Xiaoai),
    GlassTab(NavStack.MainTab.ACCESS, "接入", GlassNavIcons.Access),
    GlassTab(NavStack.MainTab.ME, "我的", GlassNavIcons.Me),
)

internal object GlassNavIcons {

    val Home: ImageVector by lazy {
        icon(
            "GlassNav.Home",
            "M12 3.2 21 10.4v10.4h-6.4v-6.2H9.4v6.2H3V10.4Z",
        )
    }

    /** 四角弧形星芒，代表小爱/智能。 */
    val Xiaoai: ImageVector by lazy {
        icon(
            "GlassNav.Xiaoai",
            "M12 2.5C12.6 7.2 16.8 11.4 21.5 12 16.8 12.6 12.6 16.8 12 21.5 " +
                "11.4 16.8 7.2 12.6 2.5 12 7.2 11.4 11.4 7.2 12 2.5Z",
        )
    }

    /** 两段链节，代表接入/连接。 */
    val Access: ImageVector by lazy {
        icon(
            "GlassNav.Access",
            "M4 12c0-2.2 1.8-4 4-4h3v2H8c-1.1 0-2 .9-2 2s.9 2 2 2h3v2H8c-2.2 0-4-1.8-4-4Z" +
                "M20 12c0-2.2-1.8-4-4-4h-3v2h3c1.1 0 2 .9 2 2s-.9 2-2 2h-3v2h3c2.2 0 4-1.8 4-4Z" +
                "M8 11h8v2H8Z",
        )
    }

    /** 头肩剪影，代表我的。 */
    val Me: ImageVector by lazy {
        icon(
            "GlassNav.Me",
            "M12 3.5a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z" +
                "M4.5 20.5c0-3.6 3.4-6.2 7.5-6.2s7.5 2.6 7.5 6.2v.5h-15Z",
        )
    }

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()
}
