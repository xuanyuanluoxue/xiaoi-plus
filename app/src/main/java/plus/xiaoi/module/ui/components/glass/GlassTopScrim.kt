package plus.xiaoi.module.ui.components.glass

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.progressiveBlur

/** 渐变模糊带向顶栏下方延伸的高度（模糊由强到消失）。 */
private val ScrimFade = 18.dp

/** 顶栏本体高度：状态栏 + Miuix 紧凑顶栏。页面顶部留白 = 该值 + 8dp。 */
@Composable
fun rememberTopBarHeight(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        TopAppBarDefaults.CollapsedHeight

/** 渐变模糊带总高度：顶栏 + 向下渐隐。 */
@Composable
fun rememberTopScrimHeight(): Dp = rememberTopBarHeight() + ScrimFade

/**
 * 顶栏渐变模糊带：铺在官方 [top.yukonga.miuix.kmp.basic.SmallTopAppBar] 下面、
 * 页面内容上面。
 *
 * <p>FULL（API 33+）：用 `miuix-blur` 的 `progressiveBlur` 对页面内容做
 * 由强到弱的真实高斯模糊，再叠一层主题色纵向渐变保证标题可读；
 * 其余能力等级退回同形状的主题色渐变，不假装模糊。</p>
 */
@Composable
fun GlassTopScrim(
    backdrop: GlassBackdropSource,
    modifier: Modifier = Modifier,
) {
    val colors = XiaoiTheme.colors
    if (backdrop.mode == GlassMode.FULL && Build.VERSION.SDK_INT >= GlassSupport.FULL_MIN_SDK) {
        FullGlassTopScrim(
            source = backdrop,
            tint = colors.bg,
            modifier = modifier,
        )
    } else {
        Box(
            modifier
                .fillMaxWidth()
                .height(rememberTopScrimHeight())
                .drawWithCache {
                    val brush = topTintBrush(tint = colors.bg, height = size.height)
                    onDrawBehind { drawRect(brush) }
                },
        )
    }
}

@RequiresApi(GlassSupport.FULL_MIN_SDK)
@Composable
private fun FullGlassTopScrim(
    source: GlassBackdropSource,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val backdrop = source.miuixBackdrop as? Backdrop ?: return
    val density = LocalDensity.current
    val radiusPx = with(density) { 22.dp.toPx() }
    val blurEffect = remember(radiusPx) {
        GlassNativeBlur.effect(radiusPx).asComposeRenderEffect()
    }
    // 模糊从顶栏底部开始减弱，到渐隐区结束：顶部最强，底部完全清晰。
    val gradient = remember {
        ProgressiveBlur(angle = 90f, startFraction = 0.08f, endFraction = 0.96f, curve = 1.15f)
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(rememberTopScrimHeight())
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = {
                    progressiveBlur(radiusPx, radiusPx, gradient)
                    // 采样层边缘外扩，避免高斯核在上下边界被 CLAMP 截断出现硬边。
                    padding = GlassNativeBlur.padding(radiusPx, padding)
                },
                progressiveGradient = gradient,
                onDrawSurface = {
                    drawRect(topTintBrush(tint = tint, height = size.height))
                },
            ),
    )
}

/** 主题色纵向渐变：顶部最实、顶栏底部开始变淡、渐隐区结束处完全透明。 */
private fun topTintBrush(
    tint: Color,
    height: Float,
): Brush {
    // 顶栏本体约占 52dp / (52+18)dp = 74%，模糊带只在最后 26% 渐隐。
    val barEdge = (height * 0.74f).coerceIn(0f, height)
    return Brush.verticalGradient(
        0f to tint.copy(alpha = 0.88f),
        (barEdge * 0.55f / height).coerceIn(0f, 1f) to tint.copy(alpha = 0.80f),
        (barEdge / height).coerceIn(0f, 1f) to tint.copy(alpha = 0.55f),
        1f to Color.Transparent,
        startY = 0f,
        endY = height,
    )
}
