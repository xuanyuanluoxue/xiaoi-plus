package plus.xiaoi.module.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import plus.xiaoi.module.application.runtime.AppRuntime

/**
 * UI 运行时端口的 Compose 注入点（WP4）。
 *
 * <p>由 `MainActivity.onCreate` 创建并传给 `XiaoiApp`；
 * 页面不再直接调用 AgentService/PermissionProbe/StatusCache/ConfigSync。</p>
 */
val LocalAppRuntime = staticCompositionLocalOf<AppRuntime> {
    error("AppRuntime 未提供：请在 XiaoiApp 的 CompositionLocalProvider 中注入")
}