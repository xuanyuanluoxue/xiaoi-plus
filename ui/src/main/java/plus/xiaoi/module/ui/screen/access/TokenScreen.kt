package plus.xiaoi.module.ui.screen.access

import plus.xiaoi.module.core.BridgeSnapshot
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import plus.xiaoi.module.core.AgentLanPolicy
import plus.xiaoi.module.core.BridgePolicy
import plus.xiaoi.module.core.IssueResolver
import plus.xiaoi.module.ui.components.LocalAppRuntime
import plus.xiaoi.module.ui.components.LocalConfigGateway
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiButton
import plus.xiaoi.module.ui.components.XiaoiButtonStyle
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiCardGroup
import plus.xiaoi.module.ui.components.XiaoiConfirmDialog
import plus.xiaoi.module.ui.components.XiaoiDirtyBar
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiIconTone
import plus.xiaoi.module.ui.components.XiaoiLoadingSkeleton
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiSwitchRow
import plus.xiaoi.module.ui.components.XiaoiTextField
import plus.xiaoi.module.ui.components.XiaoiTextButton
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.components.XiaoiValueRow
import plus.xiaoi.module.ui.theme.XiaoiTheme
import plus.xiaoi.module.ui.components.xiaoiTopBarInset
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Unlock
import top.yukonga.miuix.kmp.icon.extended.WorldClock
import java.security.SecureRandom

private enum class LanTarget { MCP, AGENT }

/**
 * 令牌与局域网：令牌掩码/显示/复制/生成；8771 与 8770 的 LAN 分开控制。
 * 显式保存；弱令牌 + LAN 允许请求，但明确提示实际会回退仅本机。
 */
@Composable
fun TokenScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val configGateway = LocalConfigGateway.current
    val appRuntime = LocalAppRuntime.current
    val colors = XiaoiTheme.colors
    val loaded = rememberAccessConfig()
    val bridge = rememberBridgeSnapshot()
    val agent = rememberAgentSnapshot()
    val facts = loaded.value
    val scope = rememberCoroutineScope()

    var tokenDraft by remember { mutableStateOf<String?>(null) }
    var mcpLanDraft by remember { mutableStateOf<Boolean?>(null) }
    var agentLanDraft by remember { mutableStateOf<Boolean?>(null) }
    var noAuthDraft by remember { mutableStateOf<Boolean?>(null) }
    var weakDraft by remember { mutableStateOf<Boolean?>(null) }
    var revealed by remember { mutableStateOf(false) }
    var rotateDialog by remember { mutableStateOf(false) }
    var noAuthDialog by remember { mutableStateOf(false) }
    var lanDialog by remember { mutableStateOf<LanTarget?>(null) }
    var saving by remember { mutableStateOf(false) }

    if (facts == null) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = XiaoiTheme.dimens.pagePad)
                .padding(top = XiaoiTheme.dimens.pagePadTop),
        ) {
            XiaoiLoadingSkeleton(height = 120.dp)
            Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
            XiaoiLoadingSkeleton(height = 180.dp)
        }
        return
    }

    val cfg = facts.config
    val baseToken = cfg?.mcpToken.orEmpty()
    val token = tokenDraft ?: baseToken
    val strength = tokenStrengthOf(token)
    val mcpLan = mcpLanDraft ?: cfg?.mcpLan ?: false
    val agentLan = agentLanDraft ?: cfg?.agentLan ?: false
    val noAuth = noAuthDraft ?: cfg?.mcpNoAuth ?: false
    val weak = weakDraft ?: cfg?.mcpWeakToken ?: false
    val mcpEnabled = cfg?.mcpEnabled == true
    val writable = cfg != null && !facts.readOnly && !facts.skeleton
    val dirty = tokenDraft != null ||
        (mcpLanDraft != null && mcpLanDraft != cfg?.mcpLan) ||
        (agentLanDraft != null && agentLanDraft != cfg?.agentLan) ||
        (noAuthDraft != null && noAuthDraft != cfg?.mcpNoAuth) ||
        (weakDraft != null && weakDraft != cfg?.mcpWeakToken)

    fun copyToken() {
        if (token.isEmpty()) {
            onMessage("没有可复制的令牌")
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        try {
            clipboard?.setPrimaryClip(ClipData.newPlainText("xiaoi token", token))
            onMessage(if (dirty) "已复制草稿令牌（含令牌，勿外传）" else "已复制令牌（含令牌，勿外传）")
        } catch (t: Throwable) {
            onMessage("复制失败")
        }
    }

    fun save() {
        if (!writable) {
            onMessage(if (facts.readOnly) "配置只读，不能保存" else "尚未初始化配置，不能保存")
            return
        }
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val loaded = configGateway.loadState()
                    if (loaded.readOnly) {
                        throw IllegalStateException("配置只读，已拒绝写入")
                    }
                    val fresh = loaded.config
                    fresh.mcpToken = token
                    fresh.mcpLan = mcpLan
                    fresh.agentLan = agentLan
                    fresh.mcpNoAuth = noAuth
                    fresh.mcpWeakToken = weak
                    val saved = configGateway.saveIfRev(fresh, fresh.rev)
                    when {
                        saved.conflict() -> throw IllegalStateException("配置已被其他页面修改，请重试")
                        !saved.ok() -> throw IllegalStateException(saved.error)
                    }
                    // 保存后按新配置重绑设备操作接口；小爱任务接口由小爱重新加载配置后生效。
                    appRuntime.agent.startIfEnabled()
                }
            }
            saving = false
            result.onSuccess {
                tokenDraft = null
                mcpLanDraft = null
                agentLanDraft = null
                noAuthDraft = null
                weakDraft = null
                loaded.reload()
                onMessage("已保存：设备操作接口会重连，小爱任务接口等待小爱重新加载")
            }.onFailure {
                onMessage("保存失败：${it.message ?: "未知错误"}")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = xiaoiTopBarInset(), bottom = 112.dp)
                .padding(horizontal = XiaoiTheme.dimens.pagePad)
                .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
        ) {
            if (!writable) {
                XiaoiCard {
                    Text(
                        if (facts.readOnly) "配置只读" else "尚未初始化配置",
                        color = colors.text,
                        style = XiaoiTheme.typography.rowTitle,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        facts.loadError.ifEmpty { "令牌与局域网只能查看，不能保存。" },
                        color = colors.textSecondary,
                        style = XiaoiTheme.typography.body,
                    )
                }
                Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
            }

            XiaoiGroupHeader("令牌")
            XiaoiCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("接入令牌", color = colors.text, style = XiaoiTheme.typography.rowTitle)
                    Spacer(Modifier.weight(1f))
                    XiaoiBadge(
                        text = tokenStrengthText(strength),
                        tone = when (strength) {
                            IssueResolver.TokenStrength.STRONG -> XiaoiTone.SUCCESS
                            IssueResolver.TokenStrength.WEAK -> XiaoiTone.WARNING
                            else -> XiaoiTone.NEUTRAL
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                XiaoiTextField(
                    value = token,
                    onValueChange = { raw ->
                        val v = raw.trim()
                        tokenDraft = if (v == baseToken) null else v
                    },
                    label = "令牌 / 密码",
                    placeholder = "自定义密码或 32 位十六进制；留空 = 拒绝所有请求",
                    isSecret = true,
                    enabled = writable,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = XiaoiTheme.dimens.touch),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (revealed) token.ifEmpty { "未设置" } else maskedToken(token),
                        color = colors.text,
                        style = XiaoiTheme.typography.mono,
                        modifier = Modifier.weight(1f),
                    )
                    XiaoiTextButton(if (revealed) "隐藏" else "显示") { revealed = !revealed }
                    XiaoiTextButton("复制") { copyToken() }
                }
                Text(
                    "令牌同时用于 8771 与 8770；复制内容含令牌，勿外传。",
                    color = colors.textHint,
                    style = XiaoiTheme.typography.caption,
                )
                Spacer(Modifier.height(8.dp))
                XiaoiButton(
                    text = "生成新令牌（32 位十六进制）",
                    style = XiaoiButtonStyle.SECONDARY,
                    enabled = writable,
                    onClick = { rotateDialog = true },
                )
                if (tokenDraft != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "新令牌尚未保存；保存后旧的接入客户端会立即 401。",
                        color = colors.warning,
                        style = XiaoiTheme.typography.caption,
                    )
                }
            }

            XiaoiGroupHeader("本机免鉴权（可选）")
            XiaoiPreferenceCard {
                XiaoiSwitchRow(
                    title = "允许免令牌调用",
                    subtitle = "本机与局域网都不校验 Bearer；打开后同一 Wi-Fi 的任何设备都能调用",
                    checked = noAuth,
                    enabled = writable,
                    onCheckedChange = { on ->
                        if (on) noAuthDialog = true else noAuthDraft = false
                    },
                    icon = MiuixIcons.Unlock,
                    iconTone = XiaoiIconTone.DANGER,
                )
                if (noAuth) {
                    Text(
                        "已开启：本机与局域网都能无令牌调用 8771/8770。" +
                            "仅用于临时联调，测完请关闭。",
                        color = colors.warning,
                        style = XiaoiTheme.typography.caption,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            XiaoiGroupHeader("密码强度")
            XiaoiPreferenceCard {
                XiaoiSwitchRow(
                    title = "允许弱密码用于局域网",
                    subtitle = "任意非空密码都可以当令牌；默认只接受 32 位十六进制",
                    checked = weak,
                    enabled = writable,
                    onCheckedChange = { weakDraft = it },
                    icon = MiuixIcons.Lock,
                    iconTone = XiaoiIconTone.WARNING,
                )
                if (weak) {
                    Text(
                        "已允许弱密码：局域网只要密码非空就能绑定 0.0.0.0，" +
                            "建议只在临时联调时打开。",
                        color = colors.warning,
                        style = XiaoiTheme.typography.caption,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            XiaoiGroupHeader("局域网")
            XiaoiCardGroup {
                XiaoiPreferenceCard {
                    XiaoiSwitchRow(
                        title = "小爱任务 · 局域网",
                        subtitle = "同一 Wi-Fi 下的设备可访问小爱任务接口",
                        checked = mcpLan,
                        enabled = writable,
                        onCheckedChange = { on ->
                            if (on) lanDialog = LanTarget.MCP else mcpLanDraft = false
                        },
                        icon = MiuixIcons.WorldClock,
                        iconTone = XiaoiIconTone.ACCENT,
                    )
                    XiaoiValueRow(
                        "保存后将监听",
                        effectiveBindText(mcpLan, mcpEnabled, token, isAgent = false, noAuth = noAuth, weak = weak),
                        mono = true,
                    )
                }
                XiaoiPreferenceCard {
                    XiaoiSwitchRow(
                        title = "设备操作 · 局域网",
                        subtitle = "只改监听地址；是否启动仍跟随「外部接入」开关",
                        checked = agentLan,
                        enabled = writable,
                        onCheckedChange = { on ->
                            if (on) lanDialog = LanTarget.AGENT else agentLanDraft = false
                        },
                        icon = MiuixIcons.WorldClock,
                        iconTone = XiaoiIconTone.ACCENT,
                    )
                    XiaoiValueRow(
                        "保存后将监听",
                        effectiveBindText(agentLan, mcpEnabled, token, isAgent = true, noAuth = noAuth, weak = weak),
                        mono = true,
                    )
                }
                if (strength == IssueResolver.TokenStrength.WEAK &&
                    (mcpLan || agentLan) && !weak && !noAuth
                ) {
                    XiaoiCard {
                        Text(
                            "令牌不足 32 位：局域网请求已回退仅本机（保存后仍按回退执行）。" +
                                "联调时可打开「允许弱密码」或「免鉴权」。",
                            color = colors.warning,
                            style = XiaoiTheme.typography.caption,
                        )
                    }
                }
                if (strength == IssueResolver.TokenStrength.NONE) {
                    XiaoiCard {
                        Text(
                            if (noAuth) {
                                "没有令牌：仅本机免鉴权可用，局域网不会生效。"
                            } else {
                                "没有令牌：小爱任务接口不会启动，局域网也不会生效。"
                            },
                            color = colors.warning,
                            style = XiaoiTheme.typography.caption,
                        )
                    }
                }
            }

            XiaoiGroupHeader("当前运行绑定")
            XiaoiPreferenceCard {
                XiaoiValueRow("8771", actualBridgeBind(bridge))
                XiaoiValueRow("8770", actualAgentBind(agent))
                Text(
                    "这里是运行快照的实际情况，可能落后于草稿；保存后以服务重绑结果为准。",
                    color = colors.textHint,
                    style = XiaoiTheme.typography.caption,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            XiaoiGroupHeader("说明")
            XiaoiCard {
                Text(
                    "同一 Wi-Fi 下的设备都能访问 MCP 接口。公共 Wi-Fi / 公司网络建议保持关闭。",
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.body,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (noAuth) {
                        "已开免鉴权：局域网设备无需令牌即可调用。"
                    } else if (weak) {
                        "已允许弱密码：局域网只要密码非空即可访问，建议只在联调时使用。"
                    } else {
                        "局域网必须使用 32 位十六进制令牌；不足 32 位会自动回退仅本机。"
                    },
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.body,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "生成新令牌会让旧 MCP 客户端立即 401。",
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.body,
                )
            }
        }

        XiaoiDirtyBar(
            dirty = dirty,
            saving = saving,
            onSave = { save() },
            onDiscard = {
                tokenDraft = null
                mcpLanDraft = null
                agentLanDraft = null
                noAuthDraft = null
                weakDraft = null
                revealed = false
                onMessage("已放弃修改")
            },
        )
    }

    XiaoiConfirmDialog(
        show = rotateDialog,
        title = "生成新令牌？",
        message = "生成新令牌会让旧 MCP 客户端立即 401。保存后生效。",
        confirmText = "生成",
        onConfirm = {
            rotateDialog = false
            tokenDraft = newToken()
            revealed = true
            onMessage("已生成新令牌，保存后生效")
        },
        onDismiss = { rotateDialog = false },
        secondaryNote = "新令牌为 32 位十六进制（128 bit），可安全用于局域网。",
    )

    XiaoiConfirmDialog(
        show = noAuthDialog,
        title = "允许本机免令牌调用？",
        message = "开启后，本机与**同一 Wi-Fi 下的所有设备**都能不带令牌调用 8771/8770：" +
            "读配置、读日志、切换模型、让小爱执行任务、执行设备动作。\n" +
            "只建议在临时联调时打开，测完立即关闭。",
        confirmText = "开启",
        danger = true,
        secondaryNote = "打开后仍可用「允许局域网访问」控制是否监听 0.0.0.0；可随时关闭。",
        onConfirm = {
            noAuthDialog = false
            noAuthDraft = true
            onMessage("已开启本机免鉴权，保存后生效")
        },
        onDismiss = { noAuthDialog = false },
    )

    val lanTarget = lanDialog
    XiaoiConfirmDialog(
        show = lanTarget != null,
        title = if (lanTarget == LanTarget.AGENT) "允许 8770 局域网访问？" else "允许 8771 局域网访问？",
        message = buildString {
            if (lanTarget == LanTarget.AGENT) {
                append("同一 Wi-Fi 下的设备凭令牌可执行打开应用、发通知、调音量等设备动作。\n")
            } else {
                append("同一 Wi-Fi 下的设备都能访问 MCP 接口。\n")
            }
            append("公共 Wi-Fi / 公司网络建议保持关闭。\n")
            if (!noAuth && !weak && !BridgePolicy.tokenStrongEnoughForLan(token)) {
                append("当前令牌不足 32 位：开启后实际会回退仅本机。\n")
                append("联调可先打开「允许弱密码」或「免鉴权」。")
            }
        },
        confirmText = "仍然开启",
        onConfirm = {
            when (lanTarget) {
                LanTarget.MCP -> mcpLanDraft = true
                LanTarget.AGENT -> agentLanDraft = true
                null -> Unit
            }
            lanDialog = null
        },
        onDismiss = { lanDialog = null },
        danger = true,
        secondaryNote = "保存后才会重新绑定；现在取消不改变配置。",
    )
}

private fun effectiveBindText(
    requested: Boolean,
    mcpEnabled: Boolean,
    token: String,
    isAgent: Boolean,
    noAuth: Boolean,
    weak: Boolean,
): String {
    if (!requested) return "127.0.0.1（仅本机）"
    if (!mcpEnabled) return "127.0.0.1（MCP 未开启）"
    if (noAuth) return "0.0.0.0（免鉴权，局域网无需令牌）"
    val strong = BridgePolicy.tokenStrongEnoughForLan(token)
    if (token.isBlank()) return "127.0.0.1（未设密码，回退仅本机）"
    if (!strong && !weak) return "127.0.0.1（密码不足 32 位，回退仅本机）"
    return if (isAgent) {
        AgentLanPolicy.bindHost(true, token, weak, noAuth)
    } else {
        BridgePolicy.bindHost(true, mcpEnabled, token, noAuth, weak)
    }
}

private fun actualBridgeBind(snapshot: plus.xiaoi.module.core.BridgeSnapshot): String {
    if (snapshot.port <= 0) return "未确认"
    return bindDetail(snapshot.bindHost, snapshot.lanEnabled)
}

private fun actualAgentBind(snapshot: plus.xiaoi.module.core.AgentRuntimeSnapshot): String {
    if (!snapshot.known()) return "未确认"
    return bindDetail(snapshot.bindHost, snapshot.bindHost == "0.0.0.0")
}

private fun newToken(): String {
    val buf = ByteArray(16)
    SecureRandom().nextBytes(buf)
    val sb = StringBuilder(32)
    for (b in buf) {
        val v = b.toInt() and 0xFF
        if (v < 16) sb.append('0')
        sb.append(Integer.toHexString(v))
    }
    return sb.toString()
}
