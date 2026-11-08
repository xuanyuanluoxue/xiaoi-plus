package plus.xiaoi.module.ui.components.glass

import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * BLUR 分支（API 31–32）：不碰 miuix-blur 的 RuntimeShader，改用 Compose
 * [GraphicsLayer] 记录页面内容，再用平台 BlurEffect 采样到底栏区域。
 * 只有 [GlassMode.BLUR] 才会调用本文件，API 26–30 不会走到这里。
 *
 * Adapted from LiquidTab (GPL-3.0, big-dictator/LiquidTab) 的 Backdrop 采样思路；
 * 平台 RenderEffect 版本是本仓库针对 minSdk 26 的回退实现。
 */

/** 在内容根节点上记录整棵页面到 [layer]，并正常绘制自身。 */
internal fun Modifier.recordContentBackdrop(layer: GraphicsLayer): Modifier =
    drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }

/**
 * 把 [layer] 中底栏所在区域画进一个离屏 GraphicsLayer，并加平台模糊。
 *
 * @param offsetInParent 底栏左上角在内容坐标系里的偏移（px）。
 */
@RequiresApi(GlassSupport.BLUR_MIN_SDK)
@Composable
internal fun BlurGlassBarSurface(
    source: GlassBackdropSource,
    barShape: Shape,
    fill: Color,
    offsetInParent: Offset,
    blurRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val layer = source.contentLayer ?: return
    val density = LocalDensity.current
    val radiusPx = with(density) { blurRadius.toPx() }
    val effect = remember(radiusPx) { BlurEffect(radiusPx, radiusPx, TileMode.Clamp) }
    Box(
        modifier
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                clip = true
                shape = barShape
                renderEffect = effect
            }
            .drawWithContent {
                if (offsetInParent != Offset.Unspecified) {
                    translate(-offsetInParent.x, -offsetInParent.y) { drawLayer(layer) }
                }
                drawRect(fill)
            },
    )
}
