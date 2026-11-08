package plus.xiaoi.module.ui.components.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

/**
 * 内容区背景采样句柄。
 *
 * Adapted from LiquidTab (GPL-3.0, big-dictator/LiquidTab) 的 Backdrop 接线方式。
 *
 * - FULL 持有一个 miuix `Backdrop`（用 `Any` 隔离，避免低版本加载 miuix 着色器类）；
 * - BLUR 持有一个 Compose [GraphicsLayer]，底栏用它配合平台 RenderEffect 做模糊；
 * - SOLID 不采样。
 */
@Stable
class GlassBackdropSource internal constructor(
    val mode: GlassMode,
    internal val miuixBackdrop: Any?,
    internal val contentLayer: GraphicsLayer?,
) {
    companion object {
        internal val Solid = GlassBackdropSource(GlassMode.SOLID, null, null)
    }
}

/** 按能力创建采样句柄；FULL 的 miuix 调用只在该分支执行。 */
@Composable
fun rememberGlassBackdrop(mode: GlassMode): GlassBackdropSource = when (mode) {
    GlassMode.FULL -> rememberFullGlassBackdrop()
    GlassMode.BLUR -> GlassBackdropSource(
        mode = GlassMode.BLUR,
        miuixBackdrop = null,
        contentLayer = rememberGraphicsLayer(),
    )

    GlassMode.SOLID -> GlassBackdropSource.Solid
}

/**
 * 内容区接线：FULL/ BLUR 在这里挂采样，SOLID 原样返回。
 * 调用方只需要把本 Modifier 加在页面内容根节点上。
 */
internal fun Modifier.glassBackdropSource(source: GlassBackdropSource): Modifier =
    when (source.mode) {
        GlassMode.FULL -> miuixLayerBackdrop(source)
        GlassMode.BLUR -> recordContentBackdrop(source.contentLayer ?: return this)
        GlassMode.SOLID -> this
    }
