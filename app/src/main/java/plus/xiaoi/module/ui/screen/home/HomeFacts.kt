package plus.xiaoi.module.ui.screen.home

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import plus.xiaoi.module.core.BridgeStatus
import plus.xiaoi.module.core.IssueInputFactory
import plus.xiaoi.module.core.StateModel
import plus.xiaoi.module.ui.components.DimensionState
import plus.xiaoi.module.ui.components.DimensionStatus

/** 首页主按钮：只做导航，不执行动作。 */
internal data class HomeCta(val text: String, val route: String)

/**
 * 首页的纯展示映射：把四维结果翻译成徽章文案与 CTA，不重新判断状态。
 *
 * <p>StateModel.Input 的填充与 {@link IssueInputFactory#from} 保持一致；
 * 后者只返回 IssueResolver.Input，不暴露 StateModel.Result，所以首页显式构造
 * 一次输入来取 Hero 标题/副行。任何判断仍由 StateModel 完成。</p>
 */
internal object HomeFacts {

    fun resolve(
        facts: IssueInputFactory.ConfigFacts,
        bridge: BridgeStatus.Snapshot,
        now: Long,
    ): StateModel.Result {
        val connected = bridge.connected && bridge.port > 0
        val input = StateModel.Input()
        input.hasProvider = facts.hasProvider
        input.hasModel = facts.hasModel
        input.hasKey = facts.hasKey
        input.hasBase = facts.hasBase
        input.enabled = facts.enabled
        input.configInvalid = !facts.configValid
        input.probe = facts.probe ?: StateModel.Probe.UNKNOWN
        input.bridgeConnected = connected
        input.fileRev = facts.rev
        input.appliedRev = bridge.appliedRev
        input.lastReportAt = bridge.updatedAt
        input.now = now
        input.runtimeKnown = bridge.runtimeKnown
        input.runtimeEnabled = bridge.enabled
        input.runtimeHasKey = bridge.hasKey
        input.runtimeModel = bridge.model
        input.fileModel = facts.model ?: ""
        return StateModel.resolve(input)
    }

    /** 四维徽章：D1 配置 / D2 端点 / D3 已应用 / D4 运行时。 */
    fun dimensions(result: StateModel.Result): List<DimensionState> = listOf(
        DimensionState("D1 配置", configStatus(result.d1), configText(result.d1)),
        DimensionState("D2 端点", endpointStatus(result.d2), endpointText(result.d2)),
        DimensionState("D3 已应用", appliedStatus(result.d3), appliedText(result.d3)),
        DimensionState("D4 运行时", runtimeStatus(result.d4), runtimeText(result.d4)),
    )

    /**
     * 每种状态都有唯一主 CTA：配置问题去配置，运行/通道问题去只读详情。
     * 只有 D4 IN_USE 时 Hero 才显示「已确认」，由 StateModel 决定。
     */
    fun cta(result: StateModel.Result): HomeCta = when {
        result.d1 == StateModel.ConfigState.INVALID ->
            HomeCta("查看配置管理", "ConfigManage")

        result.d1 == StateModel.ConfigState.NOT_CONFIGURED ->
            HomeCta("去配置模型", "Model")

        result.d1 == StateModel.ConfigState.PAUSED ->
            HomeCta("去小爱页启用", "Xiaoai")

        result.d2 == StateModel.EndpointState.AUTH ||
            result.d2 == StateModel.EndpointState.MODEL ||
            result.d2 == StateModel.EndpointState.ENDPOINT ->
            HomeCta("检查模型配置", "Model")

        result.verified() ->
            HomeCta("查看小爱", "Xiaoai")

        result.d3 == StateModel.AppliedState.WAITING ||
            result.d3 == StateModel.AppliedState.STALE ->
            HomeCta("查看端口与服务", "Bridge")

        else -> HomeCta("查看端口与服务", "Bridge")
    }

    private fun configStatus(state: StateModel.ConfigState): DimensionStatus = when (state) {
        StateModel.ConfigState.READY -> DimensionStatus.OK
        StateModel.ConfigState.PAUSED -> DimensionStatus.WAITING
        StateModel.ConfigState.NOT_CONFIGURED -> DimensionStatus.WAITING
        StateModel.ConfigState.INVALID -> DimensionStatus.ERROR
    }

    private fun configText(state: StateModel.ConfigState): String = when (state) {
        StateModel.ConfigState.READY -> "就绪"
        StateModel.ConfigState.PAUSED -> "已暂停"
        StateModel.ConfigState.NOT_CONFIGURED -> "未配置"
        StateModel.ConfigState.INVALID -> "损坏"
    }

    private fun endpointStatus(state: StateModel.EndpointState): DimensionStatus = when (state) {
        StateModel.EndpointState.OK -> DimensionStatus.OK
        StateModel.EndpointState.CHECKING -> DimensionStatus.WAITING
        StateModel.EndpointState.AUTH,
        StateModel.EndpointState.MODEL,
        StateModel.EndpointState.ENDPOINT -> DimensionStatus.ERROR
        StateModel.EndpointState.UNKNOWN -> DimensionStatus.UNKNOWN
    }

    private fun endpointText(state: StateModel.EndpointState): String = when (state) {
        StateModel.EndpointState.OK -> "可用"
        StateModel.EndpointState.CHECKING -> "校验中"
        StateModel.EndpointState.AUTH -> "密钥无效"
        StateModel.EndpointState.MODEL -> "模型不存在"
        StateModel.EndpointState.ENDPOINT -> "不可达"
        StateModel.EndpointState.UNKNOWN -> "未确认"
    }

    private fun appliedStatus(state: StateModel.AppliedState): DimensionStatus = when (state) {
        StateModel.AppliedState.APPLIED -> DimensionStatus.OK
        StateModel.AppliedState.WAITING,
        StateModel.AppliedState.STALE -> DimensionStatus.WAITING
        StateModel.AppliedState.UNKNOWN -> DimensionStatus.UNKNOWN
    }

    private fun appliedText(state: StateModel.AppliedState): String = when (state) {
        StateModel.AppliedState.APPLIED -> "已应用"
        StateModel.AppliedState.WAITING -> "等待应用"
        StateModel.AppliedState.STALE -> "上报超时"
        StateModel.AppliedState.UNKNOWN -> "未确认"
    }

    private fun runtimeStatus(state: StateModel.RuntimeState): DimensionStatus = when (state) {
        StateModel.RuntimeState.IN_USE -> DimensionStatus.OK
        StateModel.RuntimeState.MODEL_MISMATCH,
        StateModel.RuntimeState.DISABLED,
        StateModel.RuntimeState.NO_KEY -> DimensionStatus.WAITING
        StateModel.RuntimeState.UNKNOWN -> DimensionStatus.UNKNOWN
    }

    private fun runtimeText(state: StateModel.RuntimeState): String = when (state) {
        StateModel.RuntimeState.IN_USE -> "已确认"
        StateModel.RuntimeState.MODEL_MISMATCH -> "待应用"
        StateModel.RuntimeState.DISABLED -> "未启用"
        StateModel.RuntimeState.NO_KEY -> "缺密钥"
        StateModel.RuntimeState.UNKNOWN -> "未确认"
    }

    /** 运行概况时间戳；0/负数 = 没有事实，显示“未确认”。 */
    fun stamp(ms: Long): String =
        if (ms <= 0) "未确认" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    /** 最近活动时间：只显示时分秒，日期变化由列表顺序表达。 */
    fun clock(ms: Long): String =
        if (ms <= 0) "—" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
