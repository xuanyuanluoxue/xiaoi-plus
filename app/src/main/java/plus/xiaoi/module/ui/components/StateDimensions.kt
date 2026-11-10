package plus.xiaoi.module.ui.components

import plus.xiaoi.module.core.StateModel

/**
 * 用户视角的三枚状态：配置 / 模型服务 / 小爱。
 *
 * <p>内部仍由 [StateModel] 的 D1~D4 决定；这里把「小爱是否已加载」(D3) 与
 * 「小爱是否正在用」(D4) 合并成一枚「小爱」，不再把「已应用 / 运行时」这种
 * 工程词暴露给用户。首页与小爱页共用本函数，保证两处措辞一致。</p>
 */
fun userDimensions(result: StateModel.Result): List<DimensionState> = listOf(
    DimensionState("配置", configStatus(result.d1), configText(result.d1)),
    DimensionState("模型服务", endpointStatus(result.d2), endpointText(result.d2)),
    DimensionState("小爱", xiaoaiStatus(result), xiaoaiText(result)),
)

private fun configStatus(state: StateModel.ConfigState): DimensionStatus = when (state) {
    StateModel.ConfigState.READY -> DimensionStatus.OK
    StateModel.ConfigState.PAUSED,
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
    StateModel.EndpointState.UNKNOWN -> "未校验"
}

/** 小爱是否用上了：先说过期，再说运行态，最后才是「已加载但未确认在用」。 */
private fun xiaoaiStatus(result: StateModel.Result): DimensionStatus = when {
    result.d3 == StateModel.AppliedState.STALE -> DimensionStatus.WAITING
    result.d4 == StateModel.RuntimeState.IN_USE -> DimensionStatus.OK
    result.d4 == StateModel.RuntimeState.MODEL_MISMATCH ||
        result.d4 == StateModel.RuntimeState.DISABLED ||
        result.d4 == StateModel.RuntimeState.NO_KEY -> DimensionStatus.WAITING
    result.d3 == StateModel.AppliedState.WAITING -> DimensionStatus.WAITING
    else -> DimensionStatus.UNKNOWN
}

private fun xiaoaiText(result: StateModel.Result): String = when {
    result.d3 == StateModel.AppliedState.STALE -> "上报超时"
    result.d4 == StateModel.RuntimeState.IN_USE -> "生效中"
    result.d4 == StateModel.RuntimeState.MODEL_MISMATCH -> "待应用"
    result.d4 == StateModel.RuntimeState.DISABLED -> "未启用"
    result.d4 == StateModel.RuntimeState.NO_KEY -> "缺密钥"
    result.d3 == StateModel.AppliedState.WAITING -> "等待加载"
    result.d3 == StateModel.AppliedState.APPLIED -> "已加载"
    else -> "未确认"
}
