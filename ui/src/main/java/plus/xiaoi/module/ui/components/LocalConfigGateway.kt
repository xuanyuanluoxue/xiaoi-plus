package plus.xiaoi.module.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import plus.xiaoi.module.core.config.ConfigGateway

/**
 * 配置读写端口的 Compose 注入点（WP1a）。
 *
 * <p>由 `XiaoiApp` 在进程级创建并 provide；页面/StateHolder 只依赖
 * [ConfigGateway]，不再调用配置文件适配器的静态入口。</p>
 *
 * <p>默认实现故意抛错：缺少注入属于接线 bug，不能静默回落到真实文件。</p>
 */
val LocalConfigGateway = staticCompositionLocalOf<ConfigGateway> {
    error("ConfigGateway 未提供：请在 XiaoiApp 的 CompositionLocalProvider 中注入")
}