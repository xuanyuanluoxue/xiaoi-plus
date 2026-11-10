package plus.xiaoi.module.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import plus.xiaoi.module.ThemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 语义颜色令牌，与 res/values/colors.xml、values-night/colors.xml 对齐。 */
data class XiaoiColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val text: Color,
    val textSecondary: Color,
    val textHint: Color,
    val primary: Color,
    val primaryPressed: Color,
    val onPrimary: Color,
    val chipBg: Color,
    val chipOkBg: Color,
    val success: Color,
    val warning: Color,
    val warningBg: Color,
    val error: Color,
    val errorBg: Color,
    val iconTile: Color,
    /** Hero 内文字；对应 design-system §2 的 hero-on。 */
    val heroOn: Color,
    // 入口圆图标：全站统一一个强调色，红色只留给危险操作；状态仍由徽章文字表达。
    val iconAccent: Color,
    val iconSuccess: Color,
    val iconWarning: Color,
    val iconDanger: Color,
    // 悬浮玻璃底栏（仅 ui/components/glass 使用）。
    /** 模糊层之上的半透明填充；不透明度保证标签 ≥4.5:1。 */
    val glassFill: Color,
    /** SOLID 回退的实心胶囊容器（主题色）。 */
    val glassSolid: Color,
    /** SOLID 胶囊上的图标/文字。 */
    val glassOnSolid: Color,
    /** 选中 pill 的叠加色。 */
    val glassPill: Color,
    /** 玻璃边缘高光/描边。 */
    val glassEdge: Color,
)

/** 尺寸令牌；页面不写裸 dp。 */
data class XiaoiDimens(
    val pagePad: Dp = 16.dp,
    val pagePadTop: Dp = 12.dp,
    val cardPad: Dp = 20.dp,
    val cardGap: Dp = 12.dp,
    val navHeight: Dp = 80.dp,
    val navItemHeight: Dp = 64.dp,
    val radiusCard: Dp = 28.dp,
    val radiusInput: Dp = 12.dp,
    val radiusButton: Dp = 16.dp,
    val rowInfo: Dp = 72.dp,
    val rowValue: Dp = 56.dp,
    val rowSwitch: Dp = 56.dp,
    val touch: Dp = 48.dp,
    val iconCircle: Dp = 48.dp,
    val iconGlyph: Dp = 26.dp,
    // 悬浮玻璃底栏尺寸；页面不写裸 dp。
    val glassBarHeight: Dp = 58.dp,
    val glassBarMargin: Dp = 10.dp,
    val glassBarGap: Dp = 8.dp,
    val glassBarInset: Dp = 6.dp,
    val glassBarHorizontal: Dp = 16.dp,
    val glassBarMaxWidth: Dp = 380.dp,
    val glassTabIcon: Dp = 22.dp,
)

/** 字号层级；颜色由调用方显式传入，避免 Miuix 默认黑字在深色下不可读。 */
data class XiaoiTypography(
    val display: TextStyle = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
    val title: TextStyle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    val hero: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    val rowTitle: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    val body: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    val caption: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    val mono: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace),
)

private val LightColors = XiaoiColors(
    bg = Color(0xFFF7F8FA),
    surface = Color(0xFFF1F2F5),
    surface2 = Color(0xFFFFFFFF),
    line = Color(0xFFE6E9EE),
    text = Color(0xFF111318),
    textSecondary = Color(0xFF5F6772),
    textHint = Color(0xFF6B7280),
    primary = Color(0xFF2F4A6B),
    primaryPressed = Color(0xFF263D59),
    onPrimary = Color(0xFFFFFFFF),
    chipBg = Color(0xFFE4EBF3),
    chipOkBg = Color(0xFFDCE9F7),
    // 浅色状态色同时作为徽章文字：保证在各自 10–14% 淡色底上 ≥4.5:1。
    success = Color(0xFF1B5E43),
    warning = Color(0xFF7A4A00),
    warningBg = Color(0xFFFFF3D6),
    error = Color(0xFF9E2B2B),
    errorBg = Color(0x1AC43D3D),
    iconTile = Color(0xFFDCE9F7),
    heroOn = Color(0xFF16202E),
    iconAccent = Color(0xFF3D4F8F),
    iconSuccess = Color(0xFF1B5E43),
    iconWarning = Color(0xFF8A5200),
    iconDanger = Color(0xFF9E2B2B),
    glassFill = Color(0xFFFFFFFF).copy(alpha = 0.15f),
    glassSolid = Color(0xFF2F4A6B),
    glassOnSolid = Color(0xFFFFFFFF),
    glassPill = Color(0xFF2F4A6B).copy(alpha = 0.14f),
    glassEdge = Color(0xFFFFFFFF).copy(alpha = 0.72f),
)

private val DarkColors = XiaoiColors(
    bg = Color(0xFF0B0B0D),
    surface = Color(0xFF161618),
    surface2 = Color(0xFF1F1F23),
    line = Color(0xFF2A2A2E),
    text = Color(0xFFF2F3F5),
    textSecondary = Color(0xFFA0A4AB),
    textHint = Color(0xFF8E9299),
    primary = Color(0xFFC7D2FE),
    primaryPressed = Color(0xFFA9B8F0),
    onPrimary = Color(0xFF1A1B1E),
    chipBg = Color(0xFF26262B),
    chipOkBg = Color(0xFF2E3444),
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFBBF24),
    warningBg = Color(0xFF2E2511),
    error = Color(0xFFF87171),
    errorBg = Color(0x2EF87171),
    iconTile = Color(0xFF26262B),
    heroOn = Color(0xFFE8ECF8),
    iconAccent = Color(0xFF4A5FA8),
    iconSuccess = Color(0xFF2E7D5B),
    iconWarning = Color(0xFF8A5A16),
    iconDanger = Color(0xFFA84545),
    glassFill = Color(0xFF161618).copy(alpha = 0.12f),
    glassSolid = Color(0xFFC7D2FE),
    glassOnSolid = Color(0xFF1A1B1E),
    glassPill = Color(0xFFC7D2FE).copy(alpha = 0.22f),
    glassEdge = Color(0xFFFFFFFF).copy(alpha = 0.20f),
)

private val LocalXiaoiColors = staticCompositionLocalOf { LightColors }
private val LocalXiaoiDimens = staticCompositionLocalOf { XiaoiDimens() }
private val LocalXiaoiTypography = staticCompositionLocalOf { XiaoiTypography() }

object XiaoiTheme {
    val colors: XiaoiColors
        @Composable @ReadOnlyComposable get() = LocalXiaoiColors.current

    val dimens: XiaoiDimens
        @Composable @ReadOnlyComposable get() = LocalXiaoiDimens.current

    val typography: XiaoiTypography
        @Composable @ReadOnlyComposable get() = LocalXiaoiTypography.current
}

@Composable
fun XiaoiTheme(mode: Int = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    val palette = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            background = palette.bg,
            onBackground = palette.text,
            surface = palette.surface2,
            onSurface = palette.text,
            surfaceVariant = palette.surface,
            onSurfaceVariantSummary = palette.textSecondary,
            outline = palette.line,
            dividerLine = palette.line,
            error = palette.error,
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            background = palette.bg,
            onBackground = palette.text,
            surface = palette.surface2,
            onSurface = palette.text,
            surfaceVariant = palette.surface,
            onSurfaceVariantSummary = palette.textSecondary,
            outline = palette.line,
            dividerLine = palette.line,
            error = palette.error,
        )
    }
    MiuixTheme(colors = scheme) {
        CompositionLocalProvider(
            LocalXiaoiColors provides palette,
            LocalXiaoiDimens provides XiaoiDimens(),
            LocalXiaoiTypography provides XiaoiTypography(),
            content = content,
        )
    }
}
