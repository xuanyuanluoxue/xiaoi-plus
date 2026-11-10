package plus.xiaoi.module.ui.screen.access

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import plus.xiaoi.module.Bridge
import plus.xiaoi.module.Lan
import plus.xiaoi.module.Mcp
import plus.xiaoi.module.agent.AgentHttpServer
import plus.xiaoi.module.agent.AgentLanPolicy
import plus.xiaoi.module.agent.AgentMcp
import plus.xiaoi.module.core.BridgePolicy
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiCodeBlock
import plus.xiaoi.module.ui.components.XiaoiCopyRow
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiLoadingSkeleton
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiTextButton
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.components.XiaoiValueRow
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text
import plus.xiaoi.module.Json

/**
 * 连接信息：8771/8770 的 loopback / 局域网地址、adb forward、客户端 JSON 与 Skill 文本。
 * 内容可能包含令牌，页面明确提示“勿截图外传”；复制提示不包含令牌本身。
 */
@Composable
fun ConnectScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = XiaoiTheme.colors
    val loaded = rememberAccessConfig()
    val bridge = rememberBridgeSnapshot()
    val agent = rememberAgentSnapshot()
    val facts = loaded.value
    var lanIp by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        lanIp = withContext(Dispatchers.IO) { Lan.ipv4() }
    }

    if (facts == null) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = XiaoiTheme.dimens.pagePad)
                .padding(top = XiaoiTheme.dimens.pagePadTop),
        ) {
            XiaoiLoadingSkeleton(height = 88.dp)
            Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
            XiaoiLoadingSkeleton(height = 220.dp)
        }
        return
    }

    val cfg = facts.config
    val token = cfg?.mcpToken.orEmpty()
    val mcpEnabled = cfg?.mcpEnabled == true
    val bridgePort = bridge.port.takeIf { it > 0 } ?: Bridge.PORT_START
    val agentPort = agent.port.takeIf { it > 0 } ?: AgentHttpServer.DEFAULT_PORT
    val mcpLanEffective = mcpEnabled && BridgePolicy.bindHost(
        cfg?.mcpLan == true,
        mcpEnabled,
        token,
    ) == "0.0.0.0"
    val agentLanEffective = mcpEnabled && AgentLanPolicy.lanEffective(cfg?.agentLan == true, token)
    val lanReady = lanIp.isNotEmpty()

    val mcpLocal = "http://127.0.0.1:$bridgePort/mcp"
    val agentLocal = "http://127.0.0.1:$agentPort/mcp"
    val mcpLan = if (mcpLanEffective && lanReady) "http://$lanIp:$bridgePort/mcp" else ""
    val agentLan = if (agentLanEffective && lanReady) "http://$lanIp:$agentPort/mcp" else ""

    val clientJson = buildClientJson(mcpLan.ifEmpty { mcpLocal }, agentLan.ifEmpty { agentLocal }, token)
    val skillText = buildSkillText(mcpLan.ifEmpty { mcpLocal }, agentLan.ifEmpty { agentLocal })

    fun copy(text: String, message: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        try {
            clipboard?.setPrimaryClip(ClipData.newPlainText("xiaoi connect", text))
            onMessage(message)
        } catch (t: Throwable) {
            onMessage("复制失败")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        XiaoiCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                XiaoiBadge("含令牌", XiaoiTone.WARNING)
                Spacer(Modifier.weight(1f))
                Text(
                    "勿截图外传",
                    color = colors.warning,
                    style = XiaoiTheme.typography.caption,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "连接信息里的 Bearer 令牌可直接访问手机接口；只发给你信任的客户端。",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
        }

        XiaoiGroupHeader("本机地址")
        XiaoiPreferenceCard {
            XiaoiCopyRow("8771 控制面", mcpLocal) {
                copy(mcpLocal, "已复制 8771 本机地址")
            }
            XiaoiValueRow("MCP 路径", "/mcp", mono = true)
            XiaoiCopyRow("8770 动作面", agentLocal) {
                copy(agentLocal, "已复制 8770 本机地址")
            }
        }
        Text(
            if (bridge.port > 0) {
                "8771 使用控制面实际上报端口 $bridgePort。"
            } else {
                "8771 端口未上报，按默认 $bridgePort 生成；启动后以实际端口为准。"
            },
            color = colors.textHint,
            style = XiaoiTheme.typography.caption,
            modifier = Modifier.padding(top = XiaoiTheme.dimens.cardGap, start = 4.dp),
        )

        XiaoiGroupHeader("局域网地址")
        val showLanRows = mcpEnabled && lanReady && (mcpLan.isNotEmpty() || agentLan.isNotEmpty())
        if (showLanRows) {
            XiaoiPreferenceCard {
                if (mcpLan.isNotEmpty()) {
                    XiaoiCopyRow("8771 局域网", mcpLan) {
                        copy(mcpLan, "已复制 8771 局域网地址")
                    }
                } else {
                    XiaoiValueRow("8771 局域网", "未开放")
                }
                if (agentLan.isNotEmpty()) {
                    XiaoiCopyRow("8770 局域网", agentLan) {
                        copy(agentLan, "已复制 8770 局域网地址")
                    }
                } else {
                    XiaoiValueRow("8770 局域网", "未开放")
                }
                XiaoiValueRow("本机 IP", lanIp, mono = true)
            }
        } else {
            XiaoiCard {
                when {
                    !mcpEnabled -> Text(
                        "MCP 未开启：两个通道都不会监听，以下地址暂不可用。",
                        color = colors.textSecondary,
                        style = XiaoiTheme.typography.body,
                    )

                    !lanReady -> Text(
                        "未检测到局域网 IPv4；连上 Wi-Fi 后重新进入本页。",
                        color = colors.textSecondary,
                        style = XiaoiTheme.typography.body,
                    )

                    else -> Text(
                        "未请求局域网，或令牌不足 32 位已回退仅本机。",
                        color = colors.textSecondary,
                        style = XiaoiTheme.typography.body,
                    )
                }
                if (!lanReady && mcpEnabled) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "本机 IP：未检测到",
                        color = colors.textHint,
                        style = XiaoiTheme.typography.caption,
                    )
                }
            }
        }

        XiaoiGroupHeader("adb forward")
        XiaoiPreferenceCard {
            val mcpForward = "adb forward tcp:$bridgePort tcp:$bridgePort"
            val agentForward = "adb forward tcp:$agentPort tcp:$agentPort"
            XiaoiCopyRow("8771", mcpForward) { copy(mcpForward, "已复制 adb forward 命令") }
            XiaoiCopyRow("8770", agentForward) { copy(agentForward, "已复制 adb forward 命令") }
        }
        Text(
            "只有本机地址需要 adb forward；局域网地址与电脑在同一 Wi-Fi 时可直接访问。",
            color = colors.textHint,
            style = XiaoiTheme.typography.caption,
            modifier = Modifier.padding(top = XiaoiTheme.dimens.cardGap, start = 4.dp),
        )

        XiaoiGroupHeader("客户端 JSON")
        XiaoiCodeBlock(
            text = clientJson,
            maxLines = 12,
            onCopy = { copy(clientJson, "已复制客户端 JSON（含令牌，勿外传）") },
        )

        XiaoiGroupHeader("Skill 文本")
        XiaoiCodeBlock(
            text = skillText,
            maxLines = 14,
            onCopy = { copy(skillText, "已复制 Skill 文本") },
        )

        if (token.trim().isEmpty()) {
            Spacer(Modifier.height(12.dp))
            XiaoiCard {
                Text(
                    "当前没有令牌：所有 MCP 请求都会 401。",
                    color = colors.warning,
                    style = XiaoiTheme.typography.body,
                )
                Spacer(Modifier.height(4.dp))
                XiaoiTextButton("去令牌页生成") { onNavigate("Token") }
            }
        }
    }
}

private fun buildClientJson(mcpUrl: String, agentUrl: String, token: String): String =
    "{\"mcpServers\":{\"xiaoi-plus\":{\"url\":\"$mcpUrl\",\"headers\":{\"Authorization\":\"Bearer $token\"}}," +
        "\"xiaoi-agent\":{\"url\":\"$agentUrl\",\"headers\":{\"Authorization\":\"Bearer $token\"}}}}"

private fun buildSkillText(mcpUrl: String, agentUrl: String): String {
    val sb = StringBuilder()
    sb.append("# 小爱同学plus MCP\n\n")
    sb.append("## 8771 控制面\n\n")
    sb.append("端点：").append(mcpUrl).append("（streamable-http，Bearer token）\n\n")
    sb.append("工具：\n")
    sb.append(toolLines(Mcp.toolsList()))
    sb.append("\n## 8770 Agent 动作面\n\n")
    sb.append("端点：").append(agentUrl).append("（streamable-http，Bearer token）\n\n")
    sb.append("工具：\n")
    sb.append(toolLines(AgentMcp("0").toolsList()))
    return sb.toString()
}

private fun toolLines(table: Map<String, Any?>): String {
    val tools = table["tools"] as? List<*> ?: return ""
    val sb = StringBuilder()
    for (item in tools) {
        val map = item as? Map<*, *> ?: continue
        val name = Json.optString(map, "name", "")
        if (name.isEmpty()) continue
        val desc = Json.optString(map, "description", "")
        sb.append("- ").append(name)
        if (desc.isNotEmpty()) sb.append("：").append(desc)
        sb.append('\n')
    }
    return sb.toString()
}
