package plus.xiaoi.module.ui.screen.access

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import plus.xiaoi.module.AppConfig
import plus.xiaoi.module.ConfigRepository
import plus.xiaoi.module.agent.AgentRuntimeStatus
import plus.xiaoi.module.core.BridgeStatus
import plus.xiaoi.module.core.IssueInputFactory
import plus.xiaoi.module.core.IssueResolver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 接入域只读事实：一次读取 config.json，附带损坏/只读/骨架标记。
 * 页面不直接缓存成配置事实；需要刷新时调用 [AccessConfigHolder.reload]。
 */
internal data class AccessConfig(
    val config: AppConfig?,
    val readOnly: Boolean,
    val loadError: String,
    val skeleton: Boolean,
)

/** 加载结果持有者；`value == null` 表示仍在读取。 */
@Stable
internal class AccessConfigHolder(
    private val state: MutableState<AccessConfig?>,
    private val trigger: MutableState<Int>,
) {
    val value: AccessConfig? get() = state.value

    fun reload() {
        trigger.value = trigger.value + 1
    }
}

/** 在 IO 线程读配置，避免主线程文件 I/O；失败按“不可写”处理，不伪造默认值。 */
@Composable
internal fun rememberAccessConfig(): AccessConfigHolder {
    val context = LocalContext.current
    val state = remember { mutableStateOf<AccessConfig?>(null) }
    val trigger = remember { mutableStateOf(0) }
    LaunchedEffect(trigger.value) {
        state.value = withContext(Dispatchers.IO) {
            runCatching {
                val repo = ConfigRepository.get(context)
                val cfg = repo.loadForUi()
                AccessConfig(
                    config = cfg,
                    readOnly = repo.isReadOnly(),
                    loadError = repo.loadError(),
                    skeleton = repo.isSkeleton(),
                )
            }.getOrElse {
                AccessConfig(null, true, "配置读取失败", true)
            }
        }
    }
    return AccessConfigHolder(state, trigger)
}

/** 8771 快照是进程内静态事实；页面可见时轮询，不触发网络请求。 */
@Composable
internal fun rememberBridgeSnapshot(): BridgeStatus.Snapshot {
    var snapshot by remember { mutableStateOf(BridgeStatus.get()) }
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = BridgeStatus.get()
            delay(1500)
        }
    }
    return snapshot
}

/** 8770 快照同上；与服务生命周期一致，App 被杀后自然回到未知。 */
@Composable
internal fun rememberAgentSnapshot(): AgentRuntimeStatus.Snapshot {
    var snapshot by remember { mutableStateOf(AgentRuntimeStatus.get()) }
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = AgentRuntimeStatus.get()
            delay(1500)
        }
    }
    return snapshot
}

/** 令牌强度复用核心判定，不在页面重写规则。 */
internal fun tokenStrengthOf(token: String): IssueResolver.TokenStrength =
    IssueInputFactory.tokenStrength(token)

internal fun tokenStrengthText(strength: IssueResolver.TokenStrength): String = when (strength) {
    IssueResolver.TokenStrength.NONE -> "未设置（NONE）"
    IssueResolver.TokenStrength.WEAK -> "弱令牌（WEAK）"
    IssueResolver.TokenStrength.STRONG -> "强令牌（STRONG）"
}

/** 只展示首尾各 4 位；短令牌只显示掩码，不泄露长度以外的信息。 */
internal fun maskedToken(token: String): String {
    val t = token.trim()
    if (t.isEmpty()) return "未设置"
    if (t.length <= 8) return "•".repeat(t.length)
    return t.take(4) + "…" + t.takeLast(4)
}

/** 绑定地址翻译；空串/null 一律“未确认”，不猜 loopback。 */
internal fun bindLabel(host: String?, lan: Boolean?): String {
    val h = host?.trim().orEmpty()
    if (h.isEmpty()) return "未确认"
    return when {
        lan == true || h == "0.0.0.0" -> "局域网"
        h == "127.0.0.1" || h == "::1" -> "仅本机"
        else -> h
    }
}

internal fun bindDetail(host: String?, lan: Boolean?): String {
    val h = host?.trim().orEmpty()
    if (h.isEmpty()) return "未确认"
    val label = bindLabel(h, lan)
    return "$label · $h"
}

/** 8771 状态：只看 8771 自己的快照与 MCP 开关，不从 8770 推断。 */
internal fun bridgeStateText(
    mcpEnabled: Boolean,
    snapshot: BridgeStatus.Snapshot,
    now: Long,
): String = when {
    !mcpEnabled -> "未开启"
    snapshot.port > 0 && snapshot.fresh(now) -> "运行中"
    snapshot.port > 0 -> "等待上报"
    else -> "等待小爱"
}

/** 8770 状态：有事实才说运行/停止/失败，从未上报则“未确认”。 */
internal fun agentStateText(
    mcpEnabled: Boolean,
    snapshot: AgentRuntimeStatus.Snapshot,
): String = when {
    !mcpEnabled -> "未开启"
    snapshot.running -> "运行中"
    snapshot.known() && snapshot.failed() -> "启动失败"
    snapshot.known() -> "已停止"
    else -> "未确认"
}

/** 时间戳：0 表示没有事实，显示 “—”，不补当前时间。 */
internal fun formatTime(at: Long): String {
    if (at <= 0L) return "—"
    return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(at))
}

internal fun formatClock(at: Long): String {
    if (at <= 0L) return "—"
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
}
