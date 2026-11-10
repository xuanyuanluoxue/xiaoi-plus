package plus.xiaoi.module.ui.screen.xiaoai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import plus.xiaoi.module.ConfigRepository
import plus.xiaoi.module.StatusCache
import plus.xiaoi.module.StatusResolver
import plus.xiaoi.module.agent.AgentRuntimeStatus
import plus.xiaoi.module.core.ActivityFeed
import plus.xiaoi.module.core.ActivityHub
import plus.xiaoi.module.core.BridgeStatus
import plus.xiaoi.module.core.StateModel
import plus.xiaoi.module.ui.components.DimensionState
import plus.xiaoi.module.ui.components.userDimensions

/** 小爱一级页的只读投影；所有字段都是事实或“未确认”，不做乐观伪造。 */
internal data class XiaoaiUiState(
    val loading: Boolean = true,
    val readOnly: Boolean = false,
    val loadError: String = "",
    val enabled: Boolean = false,
    val saving: Boolean = false,
    val providerName: String = "",
    val modelId: String = "",
    val endpoint: String = "",
    val keySet: Boolean = false,
    val heroTitle: String = "未确认",
    val heroSub: String = "",
    val dimensions: List<DimensionState> = emptyList(),
    val bridgeLine: String = "未确认",
    val appliedLine: String = "未确认",
    val lastReport: String = "—",
    val runtimeModelLine: String = "未确认",
    val lastError: String = "",
    val lastErrorAt: Long = 0L,
)

/**
 * 小爱页状态：读 [ConfigRepository] + [StateModel] + [BridgeStatus]，写只做「启用/暂停」。
 * 主开关即时保存；失败回滚到文件事实并用 Snackbar 说明。
 */
internal class XiaoaiStateHolder(private val context: Context) {

    var state by mutableStateOf(XiaoaiUiState())
        private set

    /** 读文件与运行快照；IO 线程执行，返回后由调用方在主线程写状态。 */
    suspend fun refresh() {
        // 保存期间不要把乐观开关状态覆盖回文件旧值。
        if (state.saving) return
        val next = runCatching { xiaoaiIo { read() } }.getOrNull() ?: return
        state = next.copy(saving = state.saving)
    }

    /**
     * 主开关：即时保存。
     * 先乐观显示目标值；README 级约束是失败必须回滚，因此任何异常都重新读文件。
     */
    suspend fun setEnabled(enable: Boolean, onMessage: (String) -> Unit) {
        if (state.saving || state.readOnly) return
        state = state.copy(enabled = enable, saving = true)
        val result = runCatching {
            xiaoaiIo {
                val repo = ConfigRepository.get(context)
                val cfg = repo.load()
                if (repo.isReadOnly()) {
                    return@xiaoaiIo "配置已损坏，保存被阻止"
                }
                if (cfg.enabled == enable) return@xiaoaiIo ""
                cfg.enabled = enable
                val invalid = repo.validate(cfg)
                if (invalid != null) return@xiaoaiIo "未保存：$invalid"
                try {
                    repo.save(cfg)
                    ""
                } catch (t: Throwable) {
                    "保存失败，已回滚"
                }
            }
        }
        val message = result.getOrElse { "保存失败，已回滚" }
        state = state.copy(saving = false)
        refresh()
        if (message.isNotEmpty()) {
            onMessage(message)
        } else {
            onMessage(
                if (enable) "已启用，等待小爱应用" else "已暂停：配置全部保留",
            )
        }
    }

    private fun read(): XiaoaiUiState {
        val repo = ConfigRepository.get(context)
        val cfg = repo.load()
        val readOnly = repo.isReadOnly()
        val loadError = repo.loadError()
        val now = System.currentTimeMillis()
        val snap = BridgeStatus.get()
        val agent = AgentRuntimeStatus.get()
        val fileModel = cfg.model()
        val cacheProbe = if (readOnly) {
            StatusResolver.Probe.UNKNOWN
        } else {
            StatusCache.probe(
                context,
                StatusCache.fingerprint(cfg.baseUrl(), cfg.apiKey(), fileModel),
            )
        }
        val input = StateModel.Input().apply {
            hasProvider = cfg.activeProvider() != null
            hasModel = cfg.activeModel()?.id?.isNotEmpty() == true
            hasKey = cfg.apiKeyOfActive().isNotEmpty()
            hasBase = cfg.baseUrl().isNotEmpty()
            enabled = cfg.enabled
            configInvalid = readOnly
            probe = cacheProbe.toStateProbe()
            bridgeConnected = snap.connected
            fileRev = cfg.rev
            appliedRev = snap.appliedRev
            lastReportAt = snap.updatedAt
            this.now = now
            runtimeKnown = snap.runtimeKnown
            runtimeEnabled = snap.enabled
            runtimeHasKey = snap.hasKey
            runtimeModel = snap.model
            this.fileModel = fileModel
        }
        val result = StateModel.resolve(input)
        val provider = cfg.activeProvider()
        val hubError = ActivityHub.recent(ActivityHub.CAPACITY)
            .lastOrNull { it.level == ActivityFeed.Level.ERROR }
        val agentError = agent.lastError
        val lastError: String
        val lastErrorAt: Long
        if (hubError != null && (agentError.isEmpty() || hubError.timestamp >= agent.updatedAt)) {
            lastError = hubError.text
            lastErrorAt = hubError.timestamp
        } else if (agentError.isNotEmpty()) {
            lastError = agentError
            lastErrorAt = agent.updatedAt
        } else {
            lastError = ""
            lastErrorAt = 0L
        }
        return XiaoaiUiState(
            loading = false,
            readOnly = readOnly,
            loadError = loadError,
            enabled = cfg.enabled,
            providerName = provider?.name.orEmpty(),
            modelId = fileModel,
            endpoint = cfg.baseUrl(),
            keySet = cfg.apiKeyOfActive().isNotEmpty(),
            heroTitle = result.heroTitle(),
            heroSub = result.heroSub(),
            dimensions = userDimensions(result),
            bridgeLine = when {
                !snap.connected -> "小爱任务接口未连接（未确认）"
                snap.fresh(now) -> "小爱任务接口已连接 · 端口 ${snap.port}"
                else -> "小爱任务接口已连接，但超过 30 秒未上报"
            },
            appliedLine = when (result.d3) {
                StateModel.AppliedState.APPLIED -> "已加载 rev ${snap.appliedRev}"
                StateModel.AppliedState.WAITING ->
                    "等待加载 · 文件 rev ${cfg.rev} / 小爱 rev ${snap.appliedRev}"
                StateModel.AppliedState.STALE -> "超过 30 秒未上报，不确认已加载"
                StateModel.AppliedState.UNKNOWN -> "未确认"
            },
            lastReport = formatClock(snap.updatedAt),
            runtimeModelLine = runtimeModelLine(snap, fileModel),
            lastError = lastError,
            lastErrorAt = lastErrorAt,
        )
    }

    private fun runtimeModelLine(snap: BridgeStatus.Snapshot, fileModel: String): String {
        if (!snap.connected) return "未确认（小爱未连接）"
        if (!snap.runtimeKnown) return "等待小爱上报运行模型"
        if (snap.model.isEmpty()) return "小爱未上报运行模型"
        if (snap.model == fileModel) return "小爱在用 ${snap.model}"
        return "小爱在用 ${snap.model} · 文件 ${fileModel.ifEmpty { "未选择" }}"
    }

    private fun StatusResolver.Probe.toStateProbe(): StateModel.Probe = when (this) {
        StatusResolver.Probe.OK -> StateModel.Probe.OK
        StatusResolver.Probe.AUTH -> StateModel.Probe.AUTH
        StatusResolver.Probe.MODEL -> StateModel.Probe.MODEL
        StatusResolver.Probe.ENDPOINT -> StateModel.Probe.ENDPOINT
        StatusResolver.Probe.UNKNOWN -> StateModel.Probe.UNKNOWN
    }
}
