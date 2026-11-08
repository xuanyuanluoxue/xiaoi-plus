package plus.xiaoi.module.ui.components.glass

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import kotlin.math.min

/**
 * MIUIX 连续曲率（squircle）胶囊，与注入 View 的底栏使用同一条曲线。
 *
 * Adapted from LiquidTab (GPL-3.0, big-dictator/LiquidTab)。
 */
internal fun Path.addBottomBarSquircle(
    width: Float,
    height: Float,
    radius: Float,
    smoothing: Float,
) {
    val strength = smoothing.coerceIn(-1f, 1f)
    val cornerRadius = radius.coerceAtLeast(0f).coerceAtMost(min(width, height) * 0.5f)
    if (cornerRadius <= 0f) {
        addRect(Rect(0f, 0f, width, height))
        return
    }
    if (strength < 0f) {
        val androidPath = RoundedPolygon(
            vertices = floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height),
            rounding = CornerRounding(cornerRadius, -strength),
            centerX = width * 0.5f,
            centerY = height * 0.5f,
        ).toPath()
        addPath(androidPath.asComposePath())
        return
    }
    val extent = (radius * (1f + 0.2f * strength)).coerceAtMost(min(width, height) * 0.5f)
    if (extent <= 0f) {
        addRect(Rect(0f, 0f, width, height))
        return
    }
    val controlRatio = 0.448f - 0.182f * strength
    val control = extent * controlRatio
    moveTo(extent, 0f)
    lineTo(width - extent, 0f)
    cubicTo(width - control, 0f, width, control, width, extent)
    lineTo(width, height - extent)
    cubicTo(width, height - control, width - control, height, width - extent, height)
    lineTo(extent, height)
    cubicTo(control, height, 0f, height - control, 0f, height - extent)
    lineTo(0f, extent)
    cubicTo(0f, control, control, 0f, extent, 0f)
    close()
}

/**
 * 四角同半径的 squircle。`smoothing` 参与 equals/hashCode，避免 Compose
 * 在只改平滑度时复用旧 Outline。
 *
 * Adapted from LiquidTab (GPL-3.0, big-dictator/LiquidTab)。
 */
internal class BottomBarSquircleShape(
    corner: CornerSize,
    private val smoothing: Float,
) : CornerBasedShape(
    topStart = corner,
    topEnd = corner,
    bottomEnd = corner,
    bottomStart = corner,
) {
    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline = Outline.Generic(
        Path().apply {
            addBottomBarSquircle(
                width = size.width,
                height = size.height,
                radius = minOf(topStart, topEnd, bottomEnd, bottomStart),
                smoothing = smoothing,
            )
        },
    )

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = BottomBarSquircleShape(topStart, smoothing)

    override fun equals(other: Any?): Boolean =
        other is BottomBarSquircleShape &&
            topStart == other.topStart && smoothing == other.smoothing

    override fun hashCode(): Int = 31 * topStart.hashCode() + smoothing.hashCode()
}
