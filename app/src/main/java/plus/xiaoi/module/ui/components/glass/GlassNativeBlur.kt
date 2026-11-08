package plus.xiaoi.module.ui.components.glass

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.util.LruCache
import kotlin.math.ceil
import kotlin.math.max

/**
 * 共享 GPU 材质：色彩矩阵 + 高斯模糊，直接复用 HWUI 的 RenderEffect，
 * 不做 CPU 模糊、时间混合或源坐标量化。
 *
 * Adapted from LiquidTab (GPL-3.0, big-dictator/LiquidTab)
 * `rendering/NativeBackdropBlur.kt`。
 */
internal object GlassNativeBlur {

    private data class Key(val radius: Float, val saturation: Float)
    private val effects = LruCache<Key, RenderEffect>(24)

    @Synchronized
    fun effect(radiusPx: Float, saturation: Float = 1.5f): RenderEffect {
        val radius = if (radiusPx.isFinite()) radiusPx.coerceAtLeast(0f) else 0f
        val key = Key(radius, saturation)
        effects.get(key)?.let { return it }
        val color = RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) }),
        )
        val result = if (radius > 0f) {
            RenderEffect.createBlurEffect(radius, radius, color, Shader.TileMode.CLAMP)
        } else {
            color
        }
        effects.put(key, result)
        return result
    }

    fun padding(radiusPx: Float, minimumPx: Float): Float =
        max(minimumPx, ceil(radiusPx.coerceAtLeast(0f) * 3f))
}
