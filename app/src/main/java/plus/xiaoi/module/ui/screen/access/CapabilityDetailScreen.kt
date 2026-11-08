package plus.xiaoi.module.ui.screen.access

import androidx.compose.runtime.Composable
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder

/**
 * 能力详情占位（A6 替换）。
 * capabilityId 为 null 表示路由未携带参数；A6 通过 `CapabilityDetail:<id>` 导航，
 * MainActivity 只负责把冒号后的参数透传，不在壳里保存选择状态。
 */
@Composable
fun CapabilityDetailScreen(
    capabilityId: String?,
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    XiaoiPlaceholder(
        title = "能力详情",
        note = "作用、实现方式、前置条件、暴露面与 schema 将在 P2 接入；当前参数：${capabilityId ?: "未指定"}。",
    )
}
