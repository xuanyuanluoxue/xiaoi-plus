package plus.xiaoi.module.ui.screen.home

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import plus.xiaoi.module.core.BridgeStatus
import plus.xiaoi.module.core.IssueInputFactory
import plus.xiaoi.module.core.StateModel
import plus.xiaoi.module.ui.components.DimensionState
import plus.xiaoi.module.ui.components.userDimensions

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

    /** 三枚用户能理解的事实；措辞与徽章映射见 [userDimensions]。 */
    fun dimensions(result: StateModel.Result): List<DimensionState> = userDimensions(result)

    /**
     * 每种状态都有唯一主 CTA：配置问题去配置，运行/通道问题去只读详情。
     * 只有 D4 IN_USE 时 Hero 才显示「第三方模型已生效」，由 StateModel 决定。
     */
    fun cta(result: StateModel.Result): HomeCta = when {
        result.d1 == StateModel.ConfigState.INVALID ->
            HomeCta("查看配置管理", "ConfigManage")

        result.d1 == StateModel.ConfigState.NOT_CONFIGURED ->
            HomeCta("去配置模型", "Model")

        result.d1 == StateModel.ConfigState.PAUSED ->
            HomeCta("去小爱页开启", "Xiaoai")

        result.d2 == StateModel.EndpointState.AUTH ||
            result.d2 == StateModel.EndpointState.MODEL ||
            result.d2 == StateModel.EndpointState.ENDPOINT ->
            HomeCta("检查模型配置", "Model")

        result.verified() ->
            HomeCta("查看小爱", "Xiaoai")

        result.d3 == StateModel.AppliedState.WAITING ||
            result.d3 == StateModel.AppliedState.STALE ->
            HomeCta("查看接口状态", "Bridge")

        else -> HomeCta("查看接口状态", "Bridge")
    }

    /** 运行概况时间戳；0/负数 = 没有事实，显示“未确认”。 */
    fun stamp(ms: Long): String =
        if (ms <= 0) "未确认" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    /** 最近活动时间：只显示时分秒，日期变化由列表顺序表达。 */
    fun clock(ms: Long): String =
        if (ms <= 0) "—" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
