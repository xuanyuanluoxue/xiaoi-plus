package plus.xiaoi.module.ui.screen.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean
import plus.xiaoi.module.AppConfig
import plus.xiaoi.module.AppExecutors
import plus.xiaoi.module.BridgeClient
import plus.xiaoi.module.ConfigRepository
import plus.xiaoi.module.Json
import plus.xiaoi.module.StatusCache
import plus.xiaoi.module.StatusResolver
import plus.xiaoi.module.agent.AgentRuntimeStatus
import plus.xiaoi.module.core.ActivityFeed
import plus.xiaoi.module.core.ActivityHub
import plus.xiaoi.module.core.BridgeStatus
import plus.xiaoi.module.core.IssueInputFactory
import plus.xiaoi.module.core.IssueResolver
import plus.xiaoi.module.core.PermissionProbe
import plus.xiaoi.module.core.StateModel
import plus.xiaoi.module.ui.components.DimensionState
import plus.xiaoi.module.ui.components.IssueSeverity
import plus.xiaoi.module.ui.components.IssueUi
import plus.xiaoi.module.ui.components.XiaoiTone

/** 双域入口卡：8771 与 8770 各自一份，绝不互相代替判断。 */
internal data class DomainCardUi(
    val title: String,
    val route: String,
    val status: String,
    val tone: XiaoiTone,
    val port: String,
    val bind: String,
    val note: String,
)

/** 只读键值行（运行概况）。 */
internal data class ValueUi(val label: String, val value: String, val mono: Boolean = false)

/** 最近活动一行；只做展示，不含令牌/密钥。 */
internal data class ActivityUi(
    val time: String,
    val domain: String,
    val level: ActivityFeed.Level,
    val text: String,
)

/** 待处理项 + 导航目标；组件只接收 IssueUi，不带业务路由。 */
internal data class HomeIssue(val ui: IssueUi, val route: String)

internal data class HomeUiState(
    val loading: Boolean = true,
    val heroTitle: String = "待确认",
    val heroSub: String = "正在读取本机配置与运行快照",
    val dimensions: List<DimensionState> = emptyList(),
    val cta: HomeCta? = null,
    val issues: List<HomeIssue> = emptyList(),
    val issueTotal: Int = 0,
    val xiaoai: DomainCardUi = DomainCardUi(
        title = "小爱 · 8771", route = "Access", status = "未确认",
        tone = XiaoiTone.NEUTRAL, port = "—", bind = "未确认", note = "",
    ),
    val agent: DomainCardUi = DomainCardUi(
        title = "接入 · 8770", route = "Bridge", status = "未确认",
        tone = XiaoiTone.NEUTRAL, port = "—", bind = "未确认", note = "",
    ),
    val bridgeRows: List<ValueUi> = emptyList(),
    val agentRows: List<ValueUi> = emptyList(),
    val activities: List<ActivityUi> = emptyList(),
    /** 今日调用量；null 表示未开启 MCP / 无令牌 / 查询失败，不伪造 0。 */
    val usageThird: Long? = null,
    val usageOfficial: Long? = null,
)

/**
 * 首页只读状态持有者：进入页面读一次配置 + 快照，之后每 5 秒重读。
 *
 * <p>配置读取在 [AppExecutors.io]，不阻塞主线程；判读全部交给
 * StateModel / IssueInputFactory / IssueResolver。未知事实保持“未确认/—”，
 * 不把 0 当成离线，也不把旧快照当成在线。</p>
 */
internal class HomeStateHolder(context: Context) {

    private val app: Context = context.applicationContext ?: context
    private val busy = AtomicBoolean(false)

    var state by mutableStateOf(HomeUiState())
        private set

    fun refresh() {
        if (!busy.compareAndSet(false, true)) return
        AppExecutors.io {
            val next = try {
                read()
            } catch (t: Throwable) {
                HomeUiState(
                    loading = false,
                    heroTitle = "未确认",
                    heroSub = "读取本机状态失败，请稍后重试",
                    cta = HomeCta("查看诊断", "Diagnostics"),
                )
            }
            AppExecutors.ui {
                state = next
                busy.set(false)
            }
        }
    }

    private fun read(): HomeUiState {
        val repo = ConfigRepository.get(app)
        val config = repo.load()
        val readOnly = repo.isReadOnly()
        val now = System.currentTimeMillis()
        val bridge = BridgeStatus.get()
        val agent = AgentRuntimeStatus.get()
        val permissions = PermissionProbe.capture(app)

        val facts = IssueInputFactory.ConfigFacts.from(config, !readOnly, readOnly)
        facts.probe = cachedProbe(config)

        val result = HomeFacts.resolve(facts, bridge, now)
        val allIssues = IssueInputFactory.issues(facts, bridge, agent, permissions, now)
        val issues = IssueResolver.limit(allIssues, MAX_ISSUES).map { issue ->
            HomeIssue(
                ui = IssueUi(
                    id = issue.id,
                    title = issue.title,
                    actionText = issue.actionText,
                    severity = severityOf(issue.severity),
                ),
                route = issue.route,
            )
        }

        val heroSub = if (bridge.updatedAt > 0) {
            result.heroSub() + " · 最近上报 " + HomeFacts.clock(bridge.updatedAt)
        } else {
            result.heroSub()
        }

        val xiaoaiStatus = when {
            !facts.configValid -> "未确认" to XiaoiTone.NEUTRAL
            bridge.connected && bridge.fresh(now) -> "在线" to XiaoiTone.SUCCESS
            bridge.connected -> "等待" to XiaoiTone.WARNING
            !facts.mcpEnabled -> "已停止" to XiaoiTone.NEUTRAL
            else -> "等待" to XiaoiTone.WARNING
        }
        val xiaoaiNote = when {
            !facts.configValid -> "配置损坏，无法确认通道"
            bridge.connected && bridge.fresh(now) -> "leader 已连接"
            bridge.connected -> "超过 30 秒未上报，不确认在线"
            !facts.mcpEnabled -> "8771 控制面已关闭；不影响 8770 判断"
            else -> "等待小爱启动并上报"
        }
        val agentStatus = when {
            !facts.configValid -> "未确认" to XiaoiTone.NEUTRAL
            agent.running -> "运行中" to XiaoiTone.SUCCESS
            agent.failed() -> "启动失败" to XiaoiTone.ERROR
            agent.known() -> "已停止" to XiaoiTone.NEUTRAL
            !facts.mcpEnabled -> "已停止" to XiaoiTone.NEUTRAL
            else -> "未确认" to XiaoiTone.NEUTRAL
        }
        val agentNote = when {
            !facts.configValid -> "配置损坏，无法确认动作面"
            agent.running && agent.lanEnabled -> "局域网可访问"
            agent.running -> "仅本机可访问"
            agent.failed() -> agent.lastError
            agent.known() -> "已停止，未收到本次启动事实"
            !facts.mcpEnabled -> "MCP 关闭时不会启动"
            else -> "尚未收到启动事实"
        }

        val usage = readUsage(config, bridge)

        return HomeUiState(
            loading = false,
            heroTitle = result.heroTitle(),
            heroSub = heroSub,
            dimensions = HomeFacts.dimensions(result),
            cta = HomeFacts.cta(result),
            issues = issues,
            issueTotal = allIssues.size,
            xiaoai = DomainCardUi(
                title = "小爱 · 8771",
                route = "Access",
                status = xiaoaiStatus.first,
                tone = xiaoaiStatus.second,
                port = if (bridge.connected && bridge.port > 0) bridge.port.toString() else "—",
                bind = bindText(bridge.bindHost, bridge.lanEnabled),
                note = xiaoaiNote,
            ),
            agent = DomainCardUi(
                title = "接入 · 8770",
                route = "Bridge",
                status = agentStatus.first,
                tone = agentStatus.second,
                port = if (agent.running) agent.port.toString() else "—",
                bind = when {
                    !agent.running -> "未确认"
                    agent.lanEnabled -> "局域网"
                    else -> "仅本机"
                },
                note = agentNote,
            ),
            bridgeRows = listOf(
                ValueUi(
                    "leader 端口",
                    if (bridge.connected && bridge.port > 0) bridge.port.toString() else "—",
                    mono = true,
                ),
                ValueUi("绑定地址", bridge.bindHost.ifBlank { "未确认" }, mono = true),
                ValueUi("局域网", lanText(bridge.lanEnabled)),
                ValueUi("最后更新", HomeFacts.stamp(bridge.updatedAt)),
            ),
            agentRows = listOf(
                ValueUi("端口", if (agent.running) agent.port.toString() else "—", mono = true),
                ValueUi("启动时间", HomeFacts.stamp(agent.startedAt)),
                ValueUi(
                    "最后错误",
                    when {
                        agent.lastError.isNotEmpty() -> agent.lastError
                        agent.known() -> "无"
                        else -> "未确认"
                    },
                ),
            ),
            usageThird = usage.first,
            usageOfficial = usage.second,
            activities = ActivityHub.recent(MAX_ACTIVITY).map { event ->
                ActivityUi(
                    time = HomeFacts.clock(event.timestamp),
                    domain = domainLabel(event.domain),
                    level = event.level,
                    text = event.text,
                )
            },
        )
    }

    /** 今日调用量：只查本机 8771；失败/未配置保持 null，首页显示“未确认”。 */
    private fun readUsage(config: AppConfig, bridge: BridgeStatus.Snapshot): Pair<Long?, Long?> {
        if (!config.mcpEnabled) return null to null
        val token = config.mcpToken.orEmpty().trim()
        if (token.isEmpty() || bridge.port <= 0) return null to null
        return try {
            val body = BridgeClient.stats(bridge.port, token, 1) ?: return null to null
            val root = Json.read(body) as? Map<*, *> ?: return null to null
            val today = root["today"] as? Map<*, *> ?: return null to null
            Json.optLong(today, "third", 0L) to Json.optLong(today, "official", 0L)
        } catch (t: Throwable) {
            null to null
        }
    }

    /** 只读校验缓存；配置指纹不匹配/过期时 StatusCache 自己返回 UNKNOWN。 */
    private fun cachedProbe(config: AppConfig): StateModel.Probe {
        val provider = config.activeProvider() ?: return StateModel.Probe.UNKNOWN
        val model = config.activeModel() ?: return StateModel.Probe.UNKNOWN
        val cached = StatusCache.probe(
            app,
            StatusCache.fingerprint(provider.baseUrl, provider.apiKey, model.id),
        )
        return when (cached) {
            StatusResolver.Probe.OK -> StateModel.Probe.OK
            StatusResolver.Probe.AUTH -> StateModel.Probe.AUTH
            StatusResolver.Probe.MODEL -> StateModel.Probe.MODEL
            StatusResolver.Probe.ENDPOINT -> StateModel.Probe.ENDPOINT
            StatusResolver.Probe.UNKNOWN -> StateModel.Probe.UNKNOWN
        }
    }

    private fun severityOf(severity: IssueResolver.Severity): IssueSeverity =
        when (severity) {
            IssueResolver.Severity.ERROR -> IssueSeverity.ERROR
            IssueResolver.Severity.WARN -> IssueSeverity.WARN
            IssueResolver.Severity.INFO -> IssueSeverity.INFO
        }

    /** 实际绑定优先看 lanEnabled；旧版桥不上报时回退 bindHost，仍未知就写“未确认”。 */
    private fun bindText(host: String, lan: Boolean?): String {
        if (lan == true) return "局域网"
        if (lan == false) return "仅本机"
        val value = host.trim()
        if (value.isEmpty()) return "未确认"
        return when (value) {
            "0.0.0.0", "::" -> "局域网"
            "127.0.0.1", "::1", "localhost" -> "仅本机"
            else -> value
        }
    }

    private fun lanText(lan: Boolean?): String = when (lan) {
        true -> "已开启"
        false -> "仅本机"
        null -> "未确认"
    }

    private fun domainLabel(domain: ActivityFeed.Domain): String = when (domain) {
        ActivityFeed.Domain.XIAOAI -> "小爱"
        ActivityFeed.Domain.AGENT -> "动作面"
        ActivityFeed.Domain.ACCESS -> "接入"
    }

    private companion object {
        const val MAX_ISSUES = 3
        const val MAX_ACTIVITY = 5
    }
}
