package plus.xiaoi.module.ui.components.glass

import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FULL 分支（API 33+）：miuix-blur 采样 + HWUI 模糊 + RuntimeShader 折射。
 * 本文件的类只会在 [GlassMode.FULL] 时被调用，低版本不会实例化 RuntimeShader。
 *
 * Adapted from LiquidTab (GPL-3.0, big-dictator/LiquidTab)
 * `ManagerGlassBottomBar.kt` / `ManagerLens.kt`。
 */

@Composable
internal fun rememberFullGlassBackdrop(): GlassBackdropSource {
    val backdrop = rememberLayerBackdrop()
    return remember(backdrop) {
        GlassBackdropSource(GlassMode.FULL, miuixBackdrop = backdrop, contentLayer = null)
    }
}

internal fun Modifier.miuixLayerBackdrop(source: GlassBackdropSource): Modifier {
    val backdrop = source.miuixBackdrop as? LayerBackdrop ?: return this
    return layerBackdrop(backdrop)
}

/** 悬浮胶囊本体：模糊 + 折射 + 半透明填充（高光由底栏统一绘制）。 */
@RequiresApi(GlassSupport.FULL_MIN_SDK)
@Composable
internal fun FullGlassBarSurface(
    source: GlassBackdropSource,
    barShape: Shape,
    fill: Color,
    blurRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val backdrop = source.miuixBackdrop as? Backdrop ?: return
    val density = LocalDensity.current
    val radiusPx = with(density) { blurRadius.toPx() }
    val blurEffect = remember(radiusPx) {
        GlassNativeBlur.effect(radiusPx).asComposeRenderEffect()
    }
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { barShape },
            effects = {
                renderEffect = blurEffect
                downscaleFactor = 1
                glassLens(
                    refractionHeight = with(density) { 20.dp.toPx() },
                    refractionAmount = with(density) { 16.dp.toPx() },
                )
            },
            onDrawSurface = { drawRect(fill) },
        ),
    )
}

/**
 * 选中 pill：按下/拖拽时折射增强并放大，静止时是稳定的半透明胶囊。
 * 背景采样直接取页面 backdrop，省掉参考实现里隐藏 tab 行二次采样的复杂度。
 */
@RequiresApi(GlassSupport.FULL_MIN_SDK)
@Composable
internal fun FullGlassPill(
    source: GlassBackdropSource,
    pillShape: Shape,
    fill: Color,
    pressProgress: Float,
    modifier: Modifier = Modifier,
) {
    val backdrop = source.miuixBackdrop as? Backdrop ?: return
    val density = LocalDensity.current
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { pillShape },
            effects = {
                if (pressProgress > 0.01f) {
                    glassLens(
                        refractionHeight = with(density) { 10.dp.toPx() } * pressProgress,
                        refractionAmount = with(density) { 14.dp.toPx() } * pressProgress,
                        depthEffect = true,
                        chromaticAberration = 0.5f,
                    )
                }
            },
            layerBlock = {
                val scale = 1f + 0.08f * pressProgress
                scaleX = scale
                scaleY = scale
            },
            onDrawSurface = { drawRect(fill) },
        ),
    )
}

// ------------------------------------------------------------------ 折射着色器

internal fun BackdropEffectScope.glassLens(
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Float = 0f,
) {
    if (!GlassSupport.isRuntimeShaderSupported()) return
    if (refractionHeight <= 0f || refractionAmount <= 0f) return
    // 0.9.4 的 BackdropEffectScope.padding 是只读的；模糊/折射按当前 padding
    // 采样，边缘由离屏层裁剪兜底。
    val cornerShape = shape as? androidx.compose.foundation.shape.CornerBasedShape ?: return
    val maxRadius = size.minDimension / 2f
    val isLtr = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr
    val radii = floatArrayOf(
        (if (isLtr) cornerShape.topStart else cornerShape.topEnd).toPx(size, this),
        (if (isLtr) cornerShape.topEnd else cornerShape.topStart).toPx(size, this),
        (if (isLtr) cornerShape.bottomEnd else cornerShape.bottomStart).toPx(size, this),
        (if (isLtr) cornerShape.bottomStart else cornerShape.bottomEnd).toPx(size, this),
    )
    val sf = downscaleFactor.coerceAtLeast(1).toFloat()
    val dispersion = chromaticAberration > 0f
    runtimeShaderEffect(
        key = if (dispersion) "GlassLiquidLensDispersion" else "GlassLiquidLens",
        shaderString = if (dispersion) DISPERSION_SHADER else LENS_SHADER,
        uniformShaderName = "content",
    ) {
        setFloatUniform("size", size.width / sf, size.height / sf)
        setFloatUniform("offset", -padding / sf, -padding / sf)
        setFloatUniform("cornerRadii", FloatArray(4) { radii[it].coerceAtMost(maxRadius) / sf })
        setFloatUniform("refractionHeight", refractionHeight / sf)
        setFloatUniform("refractionAmount", -refractionAmount / sf)
        setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
        if (dispersion) setFloatUniform("chromaticAberration", chromaticAberration)
    }
}

// ------------------------------------------------------------------ 高光

/** 与 miuix-blur HighlightStyle 的 LIGHT_REF 对齐。 */
private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f
private const val GRAVITY_DIR_THRESHOLD_SQ = 0.01f

/** 重力旋转后的高光方向（参考实现里的 gravity-rotated specular）。 */
internal data class GlassSpecular(val lightX: Float, val lightY: Float) {
    companion object {
        val Default = GlassSpecular(LIGHT_REF_X, LIGHT_REF_Y)
    }
}

@RequiresApi(GlassSupport.FULL_MIN_SDK)
@Composable
internal fun rememberGlassSpecular(extraDegrees: Float = -45f): GlassSpecular {
    val tilt by rememberDeviceTilt()
    return remember(tilt, extraDegrees) {
        val gx = tilt.gravityX
        val gy = tilt.gravityY
        val magnitudeSq = gx * gx + gy * gy
        val (lx0, ly0) = if (magnitudeSq > GRAVITY_DIR_THRESHOLD_SQ) {
            val inv = 1f / sqrt(magnitudeSq)
            (gx * inv) to (gy * inv)
        } else {
            0f to -1f
        }
        val rad = extraDegrees * PI / 180.0
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        GlassSpecular(
            lightX = LIGHT_REF_X + (c * lx0 - s * ly0),
            lightY = LIGHT_REF_Y + (s * lx0 + c * ly0),
        )
    }
}

// ------------------------------------------------------------------ 着色器源码

private const val SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) { if (coord.y <= 0.0) return radii.y; else return radii.z; }
    else { if (coord.y <= 0.0) return radii.x; else return radii.w; }
}
float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}
float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) return sign(coord) * normalize(max(cornerCoord, 0.0));
    float gradX = step(cornerCoord.y, cornerCoord.x);
    return sign(coord) * float2(gradX, 1.0 - gradX);
}
float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }
"""

private const val LENS_SHADER = """
uniform shader content;
uniform float2 size; uniform float2 offset; uniform float4 cornerRadii;
uniform float refractionHeight; uniform float refractionAmount; uniform float depthEffect;
$SDF
half4 main(float2 coord) {
    float2 halfSize = size * 0.5; float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii); float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) return content.eval(coord); sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    return content.eval(coord + d * grad);
}
"""

private const val DISPERSION_SHADER = """uniform shader content;
uniform float2 size; uniform float2 offset; uniform float4 cornerRadii;
uniform float refractionHeight; uniform float refractionAmount; uniform float depthEffect;
uniform float chromaticAberration;
$SDF
half4 main(float2 coord) {
    float2 halfSize = size * 0.5; float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii); float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) return content.eval(coord); sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity; half4 color = half4(0.0);
    half4 red = content.eval(refractedCoord + dispersedCoord); color.r += red.r/3.5; color.a += red.a/7.0;
    half4 orange = content.eval(refractedCoord + dispersedCoord*0.6666667); color.r += orange.r/3.5; color.g += orange.g/7.0; color.a += orange.a/7.0;
    half4 yellow = content.eval(refractedCoord + dispersedCoord*0.3333333); color.r += yellow.r/3.5; color.g += yellow.g/3.5; color.a += yellow.a/7.0;
    half4 green = content.eval(refractedCoord); color.g += green.g/3.5; color.a += green.a/7.0;
    half4 cyan = content.eval(refractedCoord - dispersedCoord*0.3333333); color.g += cyan.g/3.5; color.b += cyan.b/3.0; color.a += cyan.a/7.0;
    half4 blue = content.eval(refractedCoord - dispersedCoord*0.6666667); color.b += blue.b/3.0; color.a += blue.a/7.0;
    half4 purple = content.eval(refractedCoord - dispersedCoord); color.r += purple.r/7.0; color.b += purple.b/3.0; color.a += purple.a/7.0;
    return color;
}
"""
