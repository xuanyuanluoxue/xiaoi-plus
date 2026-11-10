package plus.xiaoi.module.ui.components.glass

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import plus.xiaoi.module.Prefs

/**
 * 底栏能力分级。
 *
 * - [FULL]：API 33+ 且 miuix-blur 的 RuntimeShader 可用 → 背景采样 + 模糊 + 折射 + 高光。
 * - [BLUR]：API 31–32 → 平台 RenderEffect 模糊（无折射着色器），低版本不会加载 miuix 着色器类。
 * - [SOLID]：26–30、系统关闭动画、或用户关闭 → 同尺寸实心胶囊。
 */
enum class GlassMode { FULL, BLUR, SOLID }

/**
 * 玻璃底栏能力探测。所有需要 API 33+ 的调用都集中在 [FULL] 分支，
 * 低版本只走到这里做 `SDK_INT` 判断，不会实例化 miuix 的 RuntimeShader。
 *
 * Adapted from LiquidTab (GPL-3.0, big-dictator/LiquidTab) 的分级思路；
 * 本项目的 [BLUR]/[SOLID] 回退与开关为本仓库实现。
 */
object GlassSupport {

    const val FULL_MIN_SDK: Int = 33
    const val BLUR_MIN_SDK: Int = 31

    /**
     * miuix-blur 的 RuntimeShader 能力。
     *
     * <p>库调用放在独立对象里：API < 33 时短路，不会加载/解析 miuix-blur 的类，
     * 避免低版本在类校验阶段碰到 API 33 才有的 RuntimeShader 符号。</p>
     */
    fun isRuntimeShaderSupported(): Boolean =
        Build.VERSION.SDK_INT >= FULL_MIN_SDK && BlurCapability.isSupported()

    private object BlurCapability {
        fun isSupported(): Boolean = top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported()
    }

    /** 是否可以使用完整液态玻璃（任务要求的入口名）。 */
    fun isGlassSupported(): Boolean = isRuntimeShaderSupported()

    /** 系统「移除动画」视为用户要求静态界面。读取失败按开启动画处理。 */
    fun animationsDisabled(context: Context): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    } catch (t: Throwable) {
        false
    }

    /** 用户开关；当前没有外观页入口，默认开启。 */
    fun userEnabled(context: Context): Boolean = try {
        context.getSharedPreferences(Prefs.UI_PREFS, Context.MODE_PRIVATE)
            .getBoolean(Prefs.KEY_GLASS_BOTTOM_BAR, false)
    } catch (t: Throwable) {
        false
    }

    fun resolve(context: Context): GlassMode = resolve(
        sdkInt = Build.VERSION.SDK_INT,
        runtimeShader = isGlassSupported(),
        userEnabled = true, // 用户要求：玻璃默认开启，不提供开关
        animationsDisabled = animationsDisabled(context),
    ).also { android.util.Log.i("GlassSupport", "mode=$it sdk=${Build.VERSION.SDK_INT} shader=${isGlassSupported()} user=${userEnabled(context)} ALL=${context.getSharedPreferences(Prefs.NAME, 0).all} DIR=${context.dataDir} animOff=${animationsDisabled(context)}") }

    /** 纯函数，便于单测按 SDK/开关组合覆盖三分支。 */
    fun resolve(
        sdkInt: Int,
        runtimeShader: Boolean,
        userEnabled: Boolean,
        animationsDisabled: Boolean,
    ): GlassMode = when {
        !userEnabled || animationsDisabled -> GlassMode.SOLID
        runtimeShader && sdkInt >= FULL_MIN_SDK -> GlassMode.FULL
        sdkInt >= BLUR_MIN_SDK -> GlassMode.BLUR
        else -> GlassMode.SOLID
    }

    @Composable
    fun rememberMode(): GlassMode {
        val context = LocalContext.current
        return remember(context) { resolve(context) }
    }
}
