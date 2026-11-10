package plus.xiaoi.module.ui.screen.access

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.agent.AgentRuntimeStatus
import plus.xiaoi.module.core.BridgePolicy
import plus.xiaoi.module.core.BridgeStatus
import plus.xiaoi.module.core.CapabilityRegistry
import plus.xiaoi.module.core.IssueResolver
import plus.xiaoi.module.ui.components.PortRow
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiButton
import plus.xiaoi.module.ui.components.XiaoiButtonStyle
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiCardGroup
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiIconTone
import plus.xiaoi.module.ui.components.XiaoiInfoRow
import plus.xiaoi.module.ui.components.XiaoiLoadingSkeleton
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.components.XiaoiValueRow
import plus.xiaoi.module.ui.status.agentPortUi
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Unlock
import top.yukonga.miuix.kmp.icon.extended.WorldClock

/**
 * 接入一级：先回答“谁可以连进来、连的是哪个通道、安不安全”。
 * 只读事实来自 [BridgeStatus] / [AgentRuntimeStatus] 与 config.json；
 * 8770 没有独立开关，页面不提供启停控件。
 */
@Composable
fun AccessScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val colors = XiaoiTheme.colors
    val loaded = rememberAccessConfig()
    val bridge = rememberBridgeSnapshot()
    val agent = rememberAgentSnapshot()
    val facts = loaded.value

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 112.dp)
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        if (facts == null) {
            XiaoiLoadingSkeleton(height = 96.dp)
            Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
            XiaoiLoadingSkeleton(height = 160.dp)
            return@Column
        }
        val cfg = facts.config
        if (cfg == null) {
            XiaoiCard {
                Text("配置读取失败", color = colors.text, style = XiaoiTheme.typography.rowTitle)
                Spacer(Modifier.height(6.dp))
                Text(
                    "无法确认令牌与局域网状态，通道信息按实际快照显示。",
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.body,
                )
                Spacer(Modifier.height(12.dp))
                XiaoiButton(
                    text = "重试",
                    style = XiaoiButtonStyle.SECONDARY,
                    onClick = { loaded.reload() },
                )
            }
            Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
        }

        if (cfg != null && (facts.readOnly || facts.skeleton)) {
            XiaoiCard {
                Text(
                    if (facts.readOnly) "配置只读" else "尚未初始化配置",
                    color = colors.text,
                    style = XiaoiTheme.typography.rowTitle,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    facts.loadError.ifEmpty {
                        "当前只能查看通道事实，令牌与局域网修改不可用。"
                    },
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.body,
                )
            }
            Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
        }

        val mcpEnabled = cfg?.mcpEnabled == true
        val token = cfg?.mcpToken.orEmpty()
        val lanRequested = cfg?.let { it.mcpLan || it.agentLan } == true
        val strength = tokenStrengthOf(token)
        val now = System.currentTimeMillis()
        val bridgeState = bridgeStateText(mcpEnabled, bridge, now)
        val agentState = agentPortUi(mcpEnabled, agent)

        XiaoiGroupHeader("通道")
        PortCard(
            name = "8771 控制面",
            port = bridge.port.takeIf { it > 0 },
            bind = "绑定 · " + bindDetail(bridge.bindHost, bridge.lanEnabled),
            lanText = when (bridge.lanEnabled) {
                true -> "局域网"
                false -> "仅本机"
                null -> "未确认"
            },
            running = bridge.port > 0 && bridge.fresh(now),
            stateText = bridgeState,
            stateTone = when (bridgeState) {
                "运行中" -> XiaoiTone.SUCCESS
                "等待上报", "等待小爱" -> XiaoiTone.WARNING
                else -> XiaoiTone.NEUTRAL
            },
            onClick = { onNavigate("Bridge") },
        )
        Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
        PortCard(
            name = "8770 动作面",
            port = agent.port.takeIf { it > 0 },
            bind = "绑定 · " + if (agent.known()) {
                bindDetail(agent.bindHost, agent.bindHost == "0.0.0.0")
            } else {
                "未确认"
            },
            lanText = if (!agent.known()) {
                "未确认"
            } else if (agent.bindHost == "0.0.0.0") {
                "局域网"
            } else if (agent.bindHost == "127.0.0.1") {
                "仅本机"
            } else {
                "未确认"
            },
            running = agent.running,
            stateText = agentState.text,
            stateTone = agentState.tone,
            onClick = { onNavigate("Bridge") },
        )

        XiaoiGroupHeader("令牌与安全")
        XiaoiCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("令牌强度", color = colors.text, style = XiaoiTheme.typography.rowTitle)
                Spacer(Modifier.weight(1f))
                XiaoiBadge(
                    text = tokenStrengthText(strength),
                    tone = when (strength) {
                        IssueResolver.TokenStrength.STRONG -> XiaoiTone.SUCCESS
                        IssueResolver.TokenStrength.WEAK -> XiaoiTone.WARNING
                        IssueResolver.TokenStrength.NONE -> XiaoiTone.NEUTRAL
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            if (!mcpEnabled) {
                Text(
                    "MCP 未开启：8771 与 8770 都不会启动；开关在小爱页。",
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.caption,
                )
            } else if (lanFallback(token, lanRequested)) {
                Text(
                    "令牌不足 32 位：局域网请求已回退仅本机。",
                    color = colors.warning,
                    style = XiaoiTheme.typography.caption,
                )
            }
        }
        Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
        XiaoiPreferenceCard {
            XiaoiValueRow("令牌", maskedToken(token), mono = true)
            XiaoiValueRow("8771 实际监听", bridgeBindText(bridge))
            XiaoiValueRow("8770 实际监听", agentBindText(agent))
        }

        XiaoiGroupHeader("入口")
        XiaoiCardGroup {
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "端口与服务",
                    subtitle = "8771/8770 只读详情",
                    onClick = { onNavigate("Bridge") },
                    icon = MiuixIcons.Link,
                    iconTone = XiaoiIconTone.BLUE,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "令牌与局域网",
                    subtitle = "令牌掩码/生成、8771 与 8770 LAN",
                    onClick = { onNavigate("Token") },
                    icon = MiuixIcons.Lock,
                    iconTone = XiaoiIconTone.CYAN,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "连接信息",
                    subtitle = "URL、adb forward、客户端 JSON 与 Skill",
                    onClick = { onNavigate("Connect") },
                    icon = MiuixIcons.WorldClock,
                    iconTone = XiaoiIconTone.BLUE,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "能力清单",
                    subtitle = "${CapabilityRegistry.all().size} 条能力 · 搜索/筛选/详情",
                    onClick = { onNavigate("Capabilities") },
                    icon = MiuixIcons.ListView,
                    iconTone = XiaoiIconTone.GREEN,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "工具清单",
                    subtitle = "5 个动作 · 8771 8 个 · 8770 6 个",
                    onClick = { onNavigate("Tools") },
                    icon = MiuixIcons.Tasks,
                    iconTone = XiaoiIconTone.ORANGE,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "权限",
                    subtitle = "通知、精确闹钟、全屏、电池、usage、通知监听",
                    onClick = { onNavigate("Permissions") },
                    icon = MiuixIcons.Unlock,
                    iconTone = XiaoiIconTone.RED,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "8770 跟随 MCP 开关与令牌，没有独立启停；局域网由 agentLan 单独控制。",
            color = colors.textHint,
            style = XiaoiTheme.typography.caption,
        )
    }
}

@Composable
private fun PortCard(
    name: String,
    port: Int?,
    bind: String,
    lanText: String,
    running: Boolean,
    stateText: String,
    stateTone: XiaoiTone,
    onClick: () -> Unit,
) {
    val colors = XiaoiTheme.colors
    XiaoiPreferenceCard {
        PortRow(
            name = name,
            port = port,
            bind = bind,
            running = running,
            onClick = onClick,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XiaoiBadge(text = stateText, tone = stateTone)
            Spacer(Modifier.weight(1f))
            Text(
                "LAN · $lanText",
                color = colors.textHint,
                style = XiaoiTheme.typography.caption,
            )
        }
    }
}

private fun lanFallback(token: String, requested: Boolean): Boolean {
    if (!requested) return false
    val t = BridgePolicy.normalize(token)
    return t.isNotEmpty() && !BridgePolicy.tokenStrongEnoughForLan(t)
}

private fun bridgeBindText(snapshot: BridgeStatus.Snapshot): String {
    if (snapshot.port <= 0) return "未确认"
    return bindDetail(snapshot.bindHost, snapshot.lanEnabled)
}

private fun agentBindText(snapshot: AgentRuntimeStatus.Snapshot): String {
    if (!snapshot.known()) return "未确认"
    return bindDetail(snapshot.bindHost, snapshot.bindHost == "0.0.0.0")
}
