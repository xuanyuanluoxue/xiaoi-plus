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
import plus.xiaoi.module.agent.Actions
import plus.xiaoi.module.core.StateModel
import plus.xiaoi.module.ui.components.PortRow
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiLoadingSkeleton
import plus.xiaoi.module.ui.components.XiaoiPageActions
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.components.XiaoiValueRow
import plus.xiaoi.module.ui.status.agentPortUi
import plus.xiaoi.module.ui.theme.XiaoiTheme
import plus.xiaoi.module.ui.components.xiaoiTopBarInset
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Notes

/**
 * 端口与服务（只读）：8771/8770 分开渲染，未知字段显示“未确认/—”。
 * 不提供重启/停止按钮，避免页面越过服务生命周期。
 */
@Composable
fun BridgeScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val colors = XiaoiTheme.colors
    val loaded = rememberAccessConfig()
    val bridge = rememberBridgeSnapshot()
    val agent = rememberAgentSnapshot()
    val facts = loaded.value

    XiaoiPageActions {
        IconButton(onClick = { onNavigate("Logs") }) {
            Icon(MiuixIcons.Notes, contentDescription = "查看日志", tint = colors.text)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = xiaoiTopBarInset(), bottom = 112.dp)
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        if (facts == null) {
            XiaoiLoadingSkeleton(height = 180.dp)
            Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
            XiaoiLoadingSkeleton(height = 180.dp)
            return@Column
        }
        val mcpEnabled = facts.config?.mcpEnabled == true
        val now = System.currentTimeMillis()
        val agentState = agentPortUi(mcpEnabled, agent)

        Text(
            "只读快照，不重启服务",
            color = colors.textSecondary,
            style = XiaoiTheme.typography.caption,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        XiaoiGroupHeader("8771 控制面")
        XiaoiCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("状态", color = colors.text, style = XiaoiTheme.typography.rowTitle)
                Spacer(Modifier.weight(1f))
                XiaoiBadge(
                    text = bridgeStateText(mcpEnabled, bridge, now),
                    tone = when (bridgeStateText(mcpEnabled, bridge, now)) {
                        "运行中" -> XiaoiTone.SUCCESS
                        "等待上报", "等待小爱" -> XiaoiTone.WARNING
                        else -> XiaoiTone.NEUTRAL
                    },
                )
            }
        }
        Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
        XiaoiPreferenceCard {
            PortRow(
                name = "监听端口",
                port = bridge.port.takeIf { it > 0 },
                bind = bindDetail(bridge.bindHost, bridge.lanEnabled),
                running = bridge.port > 0 && bridge.fresh(now),
                onClick = null,
            )
            XiaoiValueRow("实际绑定", bindDetail(bridge.bindHost, bridge.lanEnabled))
            XiaoiValueRow("局域网", bridgeLanText(bridge))
            XiaoiValueRow("最后连接", formatTime(bridge.lastConnectedAt))
            XiaoiValueRow("最后断开", formatTime(bridge.lastDisconnectedAt))
            XiaoiValueRow("最后上报", formatTime(bridge.updatedAt))
            XiaoiValueRow("上报新鲜度", freshnessText(bridge, now))
            XiaoiValueRow("已应用 rev", if (bridge.appliedRev > 0) bridge.appliedRev.toString() else "—")
            XiaoiValueRow("运行模型", bridge.model.ifEmpty { "—" })
        }
        Text(
            "端口/绑定来自控制面的实际上报；旧版小爱未上报时保持“未确认”。",
            color = colors.textHint,
            style = XiaoiTheme.typography.caption,
            modifier = Modifier.padding(top = XiaoiTheme.dimens.cardGap, start = 4.dp),
        )

        XiaoiGroupHeader("8770 动作面")
        XiaoiCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("状态", color = colors.text, style = XiaoiTheme.typography.rowTitle)
                Spacer(Modifier.weight(1f))
                XiaoiBadge(text = agentState.text, tone = agentState.tone)
            }
        }
        Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
        XiaoiPreferenceCard {
            PortRow(
                name = "监听端口",
                port = agent.port.takeIf { it > 0 },
                bind = if (agent.known()) {
                    bindDetail(agent.bindHost, agent.bindHost == "0.0.0.0")
                } else {
                    "未确认"
                },
                running = agent.running,
                onClick = null,
            )
            XiaoiValueRow(
                "实际绑定",
                if (agent.known()) {
                    bindDetail(agent.bindHost, agent.bindHost == "0.0.0.0")
                } else {
                    "未确认"
                },
            )
            XiaoiValueRow(
                "局域网",
                if (!agent.known()) "未确认" else if (agent.bindHost == "0.0.0.0") "局域网" else "仅本机",
            )
            XiaoiValueRow("启动时间", formatTime(agent.startedAt))
            XiaoiValueRow("最后错误", if (agent.known()) agent.lastError.ifEmpty { "无" } else "未确认")
            XiaoiValueRow("动作数", Actions.builtins().size.toString())
            XiaoiValueRow("状态来源", if (agent.known()) "本机运行快照" else "未确认")
        }
        Text(
            "8770 跟随 MCP 开关与令牌；没有独立启停。",
            color = colors.textHint,
            style = XiaoiTheme.typography.caption,
            modifier = Modifier.padding(top = XiaoiTheme.dimens.cardGap, start = 4.dp),
        )

        if (!mcpEnabled) {
            XiaoiCard(modifier = Modifier.padding(top = XiaoiTheme.dimens.cardGap)) {
                Text(
                    "MCP 未开启，两个通道均不会启动。",
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.body,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private fun bridgeLanText(snapshot: plus.xiaoi.module.core.BridgeStatus.Snapshot): String =
    when (snapshot.lanEnabled) {
        true -> "局域网"
        false -> "仅本机"
        null -> "未确认"
    }

private fun freshnessText(snapshot: plus.xiaoi.module.core.BridgeStatus.Snapshot, now: Long): String {
    if (snapshot.port <= 0 || snapshot.updatedAt <= 0L) return "—"
    return if (snapshot.fresh(now)) "30 秒内" else "超过 ${StateModel.STALE_MS / 1000} 秒未上报"
}
