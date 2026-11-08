package plus.xiaoi.module.ui.status

import plus.xiaoi.module.agent.AgentRuntimeStatus
import plus.xiaoi.module.ui.components.XiaoiTone

/**
 * 8770 动作面状态的唯一 UI 判定：Access / Bridge / Diagnostics 共用。
 *
 * 只读 [AgentRuntimeStatus.get] 的快照与 [AgentRuntimeStatus.Snapshot.known]，
 * 不扫端口、不猜 loopback；未知状态统一显示「未确认」。
 */
enum class AgentPortState { RUNNING, STOPPED, FAILED, DISABLED, UNKNOWN }

data class AgentPortUi(
    val state: AgentPortState,
    val text: String,
    val tone: XiaoiTone,
)

fun agentPortUi(
    mcpEnabled: Boolean,
    snapshot: AgentRuntimeStatus.Snapshot,
): AgentPortUi {
    val state = when {
        !mcpEnabled -> AgentPortState.DISABLED
        snapshot.running -> AgentPortState.RUNNING
        snapshot.known() && snapshot.failed() -> AgentPortState.FAILED
        snapshot.known() -> AgentPortState.STOPPED
        else -> AgentPortState.UNKNOWN
    }
    val (text, tone) = when (state) {
        AgentPortState.RUNNING -> "运行中" to XiaoiTone.SUCCESS
        AgentPortState.STOPPED -> "已停止" to XiaoiTone.NEUTRAL
        AgentPortState.FAILED -> "启动失败" to XiaoiTone.ERROR
        AgentPortState.DISABLED -> "未开启" to XiaoiTone.NEUTRAL
        AgentPortState.UNKNOWN -> "未确认" to XiaoiTone.NEUTRAL
    }
    return AgentPortUi(state, text, tone)
}
