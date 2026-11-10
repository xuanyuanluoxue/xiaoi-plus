package plus.xiaoi.module.ui.screen.me

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import plus.xiaoi.module.AppConfig
import plus.xiaoi.module.ConfigRepository
import plus.xiaoi.module.StatusCache
import plus.xiaoi.module.StatusResolver
import plus.xiaoi.module.agent.AgentRuntimeStatus
import plus.xiaoi.module.control.BridgeControlService
import plus.xiaoi.module.core.BridgeStatus
import plus.xiaoi.module.core.IssueInputFactory
import plus.xiaoi.module.core.IssueResolver
import plus.xiaoi.module.core.PermissionProbe
import plus.xiaoi.module.core.PermissionSnapshot
import plus.xiaoi.module.core.StateModel
import plus.xiaoi.module.ui.components.PermissionRow
import plus.xiaoi.module.ui.components.PermissionStatus
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiCardGroup
import plus.xiaoi.module.ui.components.XiaoiConfirmDialog
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiIconTone
import plus.xiaoi.module.ui.components.XiaoiInfoRow
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.status.agentPortUi
import plus.xiaoi.module.ui.theme.XiaoiTheme
import plus.xiaoi.module.ui.components.xiaoiTopBarInset
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Share

/**
 * 诊断（SCREENS.md §12）。
 *
 * 只展示事实：四维状态来自 StateModel，8771/8770 分别读取各自快照，
 * 权限来自 PermissionProbe，Root/LSPosed 拿不到证据就写「未确认」。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun DiagnosticsScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = XiaoiTheme.colors
    val scope = rememberCoroutineScope()
    val version = MeSupport.versionName(context)
    val versionCode = MeSupport.versionCode(context)

    var refresh by remember { mutableStateOf(0) }
    var snapshot by remember { mutableStateOf<DiagSnapshot?>(null) }
    var rootInstalled by remember { mutableStateOf<Boolean?>(null) }
    var showClearLogs by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        // 冷启动时 8770 可能比页面晚 1–3 秒起来；定时重采，避免与接入页结论不一致。
        while (true) {
            snapshot = withContext(Dispatchers.IO) { capture(context) }
            delay(3000)
        }
    }
    LaunchedEffect(Unit) {
        rootInstalled = withContext(Dispatchers.IO) { MeSupport.rootInstalled() }
    }

    val snap = snapshot
    val lsposedConnected = snap?.bridge?.connected ?: BridgeStatus.get().connected

    fun copyDiagnostics() {
        val s = snap ?: return
        val text = diagnosticsText(context, s, rootInstalled, lsposedConnected, version, versionCode)
        onMessage(
            if (MeSupport.copy(context, "诊断信息", text)) "已复制诊断信息（脱敏，无密钥/令牌）"
            else "复制失败",
        )
    }

    fun exportLogs() {
        scope.launch {
            val dump = withContext(Dispatchers.IO) { BridgeControlService.logDump().orEmpty() }
            if (dump.isBlank()) {
                onMessage("暂无可导出的日志")
                return@launch
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "小爱同学plus 日志")
                putExtra(Intent.EXTRA_TEXT, dump)
            }
            if (!MeSupport.open(context, Intent.createChooser(intent, "导出日志"))) {
                onMessage("没有可用的分享目标")
            }
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
        if (snap == null) {
            XiaoiCard {
                Text("正在采集状态…", color = colors.textSecondary, style = XiaoiTheme.typography.body)
            }
            return@Column
        }

        XiaoiGroupHeader("四维状态")
        XiaoiPreferenceCard {
            StatusLine("D1 配置", d1Text(snap.d1), d1Tone(snap.d1), "只要求服务商/模型/密钥/端点齐全")
            StatusLine("D2 端点", d2Text(snap.d2), d2Tone(snap.d2), "由最近一次校验结论决定，不回写 D4")
            StatusLine("D3 已应用", d3Text(snap.d3), d3Tone(snap.d3), "控制面是否已加载当前文件 rev")
            StatusLine("D4 运行时", d4Text(snap.d4), d4Tone(snap.d4), "只有「已确认」表示小爱真的在用")
        }

        XiaoiGroupHeader("通道")
        XiaoiPreferenceCard {
            StatusLine(
                "8771 控制面",
                portText(snap.bridge8771),
                portTone(snap.bridge8771),
                bridgeDetail(snap.bridge, snap.cfg?.mcpEnabled == true),
            )
            val agentState = agentPortUi(snap.cfg?.mcpEnabled == true, snap.agent)
            StatusLine(
                "8770 动作面",
                agentState.text,
                agentState.tone,
                agentDetail(snap.agent, snap.cfg?.mcpEnabled == true),
            )
        }

        XiaoiGroupHeader("配置完整性")
        XiaoiPreferenceCard {
            val cfg = snap.cfg
            StatusLine(
                "config.json",
                if (snap.readOnly) "只读" else "正常",
                if (snap.readOnly) XiaoiTone.ERROR else XiaoiTone.SUCCESS,
                if (snap.readOnly) snap.loadError.ifBlank { "无法解析，已保留原文件" } else null,
            )
            XiaoiInfoRow(
                title = "schema / rev",
                subtitle = "v${cfg?.version ?: "?"} · rev ${cfg?.rev ?: 0}",
            )
            XiaoiInfoRow(
                title = "服务商 / 模型",
                subtitle = "${cfg?.providers?.size ?: 0} 个服务商 · " +
                    (cfg?.activeModel()?.id?.takeIf { it.isNotBlank() } ?: "未选择模型"),
            )
            XiaoiInfoRow(
                title = "密钥 / 令牌",
                subtitle = "API Key " +
                    (if (cfg?.apiKeyOfActive()?.isNotBlank() == true) "已设置" else "未设置") +
                    " · 令牌 " + MeSupport.tokenStrength(cfg?.mcpToken),
            )
        }

        XiaoiGroupHeader("本机与框架")
        XiaoiPreferenceCard {
            XiaoiInfoRow(
                title = "Root",
                subtitle = when (rootInstalled) {
                    true -> "已安装（只检测 su 路径，不验证授权）"
                    false -> "未检测到"
                    null -> "未确认"
                },
            )
            XiaoiInfoRow(
                title = "LSPosed",
                subtitle = if (lsposedConnected) {
                    "已连接（控制面收到小爱上报）"
                } else {
                    "未确认（没有控制面证据，不判定为未启用）"
                },
            )
            XiaoiInfoRow(
                title = "设备 / 系统",
                subtitle = "${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            )
        }

        XiaoiGroupHeader("权限矩阵")
        XiaoiPreferenceCard {
            val permissions = snap.permissions
            val denied = listOf(
                permissions.notifications,
                permissions.exactAlarm,
                permissions.fullScreenIntent,
                permissions.batteryOptimization,
                permissions.usageAccess,
                permissions.notificationListener,
            ).count { it == PermissionSnapshot.State.DENIED }
            XiaoiInfoRow(
                title = "摘要",
                subtitle = if (denied == 0) {
                    "没有明确拒绝项；未确认项不代表有问题"
                } else {
                    "$denied 项明确未授权 · 「未确认」是 ROM 无标准 API，不当成拒绝"
                },
            )
            PermissionItem(
                "通知", permissions.notifications, "告警与前台服务提示",
                settingsIntent(context, "notifications"), onMessage,
            )
            PermissionItem(
                "精确闹钟", permissions.exactAlarm, "定时提醒需要；低版本默认可用",
                settingsIntent(context, "exactAlarm"), onMessage,
            )
            PermissionItem(
                "全屏通知", permissions.fullScreenIntent, "可选 · API 34+ 免打扰提醒",
                settingsIntent(context, "fullScreenIntent"), onMessage,
            )
            PermissionItem(
                "电池优化", permissions.batteryOptimization, "已忽略 = 已授权；未忽略 = 未授权",
                settingsIntent(context, "battery"), onMessage,
            )
            PermissionItem(
                "使用情况访问", permissions.usageAccess, "统计前台应用（部分能力）",
                settingsIntent(context, "usage"), onMessage,
            )
            PermissionItem(
                "通知监听", permissions.notificationListener, "读取通知内容（部分能力）",
                settingsIntent(context, "listener"), onMessage,
            )
        }

        XiaoiGroupHeader("工具")
        XiaoiCardGroup {
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "复制诊断信息",
                    subtitle = "版本、状态、端口、权限；脱敏且不含密钥/令牌",
                    onClick = { copyDiagnostics() },
                    icon = MiuixIcons.Copy,
                    iconTone = XiaoiIconTone.BLUE,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "导出日志",
                    subtitle = "通过系统分享导出本机日志镜像",
                    onClick = { exportLogs() },
                    icon = MiuixIcons.Share,
                    iconTone = XiaoiIconTone.PINK,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "清空本机日志",
                    subtitle = "只清 App 内存镜像，不删除小爱落盘日志",
                    onClick = { showClearLogs = true },
                    icon = MiuixIcons.Delete,
                    iconTone = XiaoiIconTone.RED,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "兼容模式（只读）",
                    subtitle = "旧版 config.json 路径、脱敏 JSON、.corrupt/.legacy 列表",
                    onClick = { onNavigate("Legacy") },
                    icon = MiuixIcons.File,
                    iconTone = XiaoiIconTone.PURPLE,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "状态每 30 秒失效；离开本页再回来会重新采集。未知一律显示「未确认/—」，不伪造健康。",
            color = colors.textHint,
            style = XiaoiTheme.typography.caption,
        )
        Spacer(Modifier.height(24.dp))
    }

    XiaoiConfirmDialog(
        show = showClearLogs,
        title = "清空本机日志？",
        message = "只清空 App 进程内的日志镜像，不影响小爱进程的落盘日志，也无法恢复。",
        confirmText = "清空",
        danger = true,
        onConfirm = {
            showClearLogs = false
            BridgeControlService.clearLogs()
            onMessage("已清空本机日志缓冲（不删除小爱落盘日志）")
            refresh++
        },
        onDismiss = { showClearLogs = false },
    )
}

// ---------------------------------------------------------------- 采集

private data class DiagSnapshot(
    val cfg: AppConfig?,
    val readOnly: Boolean,
    val loadError: String,
    val d1: StateModel.ConfigState,
    val d2: StateModel.EndpointState,
    val d3: StateModel.AppliedState,
    val d4: StateModel.RuntimeState,
    val bridge8771: IssueResolver.PortState,
    val bridge: BridgeStatus.Snapshot,
    val agent: AgentRuntimeStatus.Snapshot,
    val permissions: PermissionSnapshot,
    val errorLines: Int,
)

private fun capture(context: android.content.Context): DiagSnapshot {
    val repo = ConfigRepository.get(context)
    val cfg = runCatching { repo.load() }.getOrNull()
    val readOnly = repo.isReadOnly()
    val bridge = BridgeStatus.get()
    val agent = AgentRuntimeStatus.get()
    val permissions = PermissionProbe.capture(context)

    val facts = IssueInputFactory.ConfigFacts.from(cfg, !readOnly, readOnly)
    facts.probe = mapProbe(
        StatusCache.probe(
            context,
            StatusCache.fingerprint(cfg?.baseUrl() ?: "", cfg?.apiKey() ?: "", cfg?.model() ?: ""),
        ),
    )
    val input = IssueInputFactory.from(facts, bridge, agent, permissions, System.currentTimeMillis())
    val dump = BridgeControlService.logDump().orEmpty()
    val errors = dump.split('\n').count { line ->
        line.length >= 14 && line[12] == ' ' && line[13] == 'E'
    }
    return DiagSnapshot(
        cfg = cfg,
        readOnly = readOnly,
        loadError = repo.loadError(),
        d1 = input.d1,
        d2 = input.d2,
        d3 = input.d3,
        d4 = input.d4,
        bridge8771 = input.bridge8771,
        bridge = bridge,
        agent = agent,
        permissions = permissions,
        errorLines = errors,
    )
}

private fun mapProbe(probe: StatusResolver.Probe): StateModel.Probe = when (probe) {
    StatusResolver.Probe.OK -> StateModel.Probe.OK
    StatusResolver.Probe.AUTH -> StateModel.Probe.AUTH
    StatusResolver.Probe.MODEL -> StateModel.Probe.MODEL
    StatusResolver.Probe.ENDPOINT -> StateModel.Probe.ENDPOINT
    else -> StateModel.Probe.UNKNOWN
}

// ---------------------------------------------------------------- 文案映射

private fun d1Text(s: StateModel.ConfigState) = when (s) {
    StateModel.ConfigState.NOT_CONFIGURED -> "未配置"
    StateModel.ConfigState.PAUSED -> "已暂停"
    StateModel.ConfigState.READY -> "就绪"
    StateModel.ConfigState.INVALID -> "损坏/只读"
}

private fun d1Tone(s: StateModel.ConfigState) = when (s) {
    StateModel.ConfigState.READY -> XiaoiTone.SUCCESS
    StateModel.ConfigState.PAUSED -> XiaoiTone.WARNING
    StateModel.ConfigState.INVALID -> XiaoiTone.ERROR
    else -> XiaoiTone.NEUTRAL
}

private fun d2Text(s: StateModel.EndpointState) = when (s) {
    StateModel.EndpointState.UNKNOWN -> "未确认"
    StateModel.EndpointState.CHECKING -> "校验中"
    StateModel.EndpointState.OK -> "端点可用"
    StateModel.EndpointState.AUTH -> "密钥无效"
    StateModel.EndpointState.MODEL -> "模型不存在"
    StateModel.EndpointState.ENDPOINT -> "端点不可达"
}

private fun d2Tone(s: StateModel.EndpointState) = when (s) {
    StateModel.EndpointState.OK -> XiaoiTone.SUCCESS
    StateModel.EndpointState.CHECKING -> XiaoiTone.INFO
    StateModel.EndpointState.AUTH,
    StateModel.EndpointState.MODEL,
    StateModel.EndpointState.ENDPOINT -> XiaoiTone.ERROR

    else -> XiaoiTone.NEUTRAL
}

private fun d3Text(s: StateModel.AppliedState) = when (s) {
    StateModel.AppliedState.UNKNOWN -> "未确认"
    StateModel.AppliedState.WAITING -> "等待上报"
    StateModel.AppliedState.APPLIED -> "已应用"
    StateModel.AppliedState.STALE -> "上报超时"
}

private fun d3Tone(s: StateModel.AppliedState) = when (s) {
    StateModel.AppliedState.APPLIED -> XiaoiTone.SUCCESS
    StateModel.AppliedState.STALE -> XiaoiTone.WARNING
    else -> XiaoiTone.NEUTRAL
}

private fun d4Text(s: StateModel.RuntimeState) = when (s) {
    StateModel.RuntimeState.UNKNOWN -> "未确认"
    StateModel.RuntimeState.IN_USE -> "已确认"
    StateModel.RuntimeState.MODEL_MISMATCH -> "等待应用"
    StateModel.RuntimeState.DISABLED -> "小爱未启用"
    StateModel.RuntimeState.NO_KEY -> "缺少密钥"
}

private fun d4Tone(s: StateModel.RuntimeState) = when (s) {
    StateModel.RuntimeState.IN_USE -> XiaoiTone.SUCCESS
    StateModel.RuntimeState.MODEL_MISMATCH -> XiaoiTone.WARNING
    StateModel.RuntimeState.DISABLED,
    StateModel.RuntimeState.NO_KEY -> XiaoiTone.ERROR

    else -> XiaoiTone.NEUTRAL
}

private fun portText(s: IssueResolver.PortState) = when (s) {
    IssueResolver.PortState.RUNNING -> "运行中"
    IssueResolver.PortState.OFF -> "未开启"
    IssueResolver.PortState.WAITING -> "等待上报"
    IssueResolver.PortState.FAILED -> "启动失败"
    IssueResolver.PortState.UNKNOWN -> "未确认"
}

private fun portTone(s: IssueResolver.PortState) = when (s) {
    IssueResolver.PortState.RUNNING -> XiaoiTone.SUCCESS
    IssueResolver.PortState.WAITING -> XiaoiTone.WARNING
    IssueResolver.PortState.FAILED -> XiaoiTone.ERROR
    else -> XiaoiTone.NEUTRAL
}

private fun bridgeDetail(b: BridgeStatus.Snapshot, mcpEnabled: Boolean): String {
    if (!b.connected || b.port <= 0) {
        return if (mcpEnabled) "等待小爱上报控制面；未知端口不猜 loopback"
        else "MCP 关闭或未设置令牌，8771 不启动"
    }
    val bind = when {
        b.bindHost.isNotBlank() -> b.bindHost
        b.lanEnabled == true -> "绑定局域网（地址未上报）"
        b.lanEnabled == false -> "仅本机（地址未上报）"
        else -> "绑定未确认"
    }
    return "端口 ${b.port} · $bind · rev ${b.appliedRev}"
}

private fun agentDetail(a: AgentRuntimeStatus.Snapshot, mcpEnabled: Boolean): String {
    if (!a.known()) {
        return if (mcpEnabled) "未收到 8770 上报；不下结论" else "MCP 关闭，动作面不启动"
    }
    if (a.running) {
        val bind = a.bindHost.ifBlank { "地址未上报" }
        return "端口 ${a.port} · $bind"
    }
    if (a.lastError.isNotBlank()) return a.lastError
    return if (mcpEnabled) "已知停止 · 等待重新启动" else "已停止"
}

// ---------------------------------------------------------------- 诊断文本

private fun diagnosticsText(
    context: android.content.Context,
    s: DiagSnapshot,
    rootInstalled: Boolean?,
    lsposed: Boolean,
    version: String,
    versionCode: Long,
): String {
    val cfg = s.cfg
    val sb = StringBuilder()
    sb.append("小爱同学plus 诊断\n")
    sb.append("版本: v").append(version).append(" (").append(versionCode).append(")\n")
    sb.append("设备: ").append(Build.MODEL).append(" / ").append(Build.MANUFACTURER).append('\n')
    sb.append("系统: Android ").append(Build.VERSION.RELEASE)
        .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
    sb.append("Root: ").append(
        when (rootInstalled) {
            true -> "已安装（未验证授权）"
            false -> "未检测到"
            null -> "未确认"
        },
    ).append('\n')
    sb.append("LSPosed: ").append(if (lsposed) "已连接" else "未确认").append('\n')
    sb.append("配置: ").append(if (s.readOnly) "只读 · ${s.loadError.ifBlank { "无法解析" }}" else "正常")
        .append('\n')
    sb.append("schema/rev: v").append(cfg?.version ?: "?").append(" / ").append(cfg?.rev ?: 0).append('\n')
    sb.append("服务商: ").append(cfg?.providers?.size ?: 0).append(" · 模型: ")
        .append(cfg?.activeModel()?.id?.takeIf { it.isNotBlank() } ?: "未选择").append('\n')
    sb.append("端点: ").append(MeSupport.hostOf(cfg?.baseUrl())).append('\n')
    sb.append("密钥: ").append(if (cfg?.apiKeyOfActive()?.isNotBlank() == true) "已设置" else "未设置").append('\n')
    sb.append("令牌: ").append(MeSupport.tokenStrength(cfg?.mcpToken)).append('\n')
    sb.append("D1: ").append(d1Text(s.d1)).append('\n')
    sb.append("D2: ").append(d2Text(s.d2)).append('\n')
    sb.append("D3: ").append(d3Text(s.d3)).append('\n')
    sb.append("D4: ").append(d4Text(s.d4)).append('\n')
    sb.append("8771: ").append(portText(s.bridge8771)).append(" · ")
        .append(bridgeDetail(s.bridge, cfg?.mcpEnabled == true)).append('\n')
    sb.append("8770: ")
        .append(agentPortUi(cfg?.mcpEnabled == true, s.agent).text).append(" · ")
        .append(agentDetail(s.agent, cfg?.mcpEnabled == true)).append('\n')
    sb.append("权限: ")
        .append("通知=").append(permissionText(s.permissions.notifications)).append(' ')
        .append("精确闹钟=").append(permissionText(s.permissions.exactAlarm)).append(' ')
        .append("全屏=").append(permissionText(s.permissions.fullScreenIntent)).append(' ')
        .append("电池=").append(permissionText(s.permissions.batteryOptimization)).append(' ')
        .append("用量=").append(permissionText(s.permissions.usageAccess)).append(' ')
        .append("通知监听=").append(permissionText(s.permissions.notificationListener)).append('\n')
    sb.append("日志错误行: ").append(s.errorLines).append('\n')
    sb.append("包名: ").append(context.packageName)
    return sb.toString()
}

private fun permissionText(state: PermissionSnapshot.State) = when (state) {
    PermissionSnapshot.State.GRANTED -> "已授权"
    PermissionSnapshot.State.DENIED -> "未授权"
    PermissionSnapshot.State.UNKNOWN -> "未确认"
    PermissionSnapshot.State.NOT_APPLICABLE -> "不需要"
}

// ---------------------------------------------------------------- 权限设置入口

private fun permStatus(state: PermissionSnapshot.State): PermissionStatus = when (state) {
    PermissionSnapshot.State.GRANTED -> PermissionStatus.GRANTED
    PermissionSnapshot.State.DENIED -> PermissionStatus.DENIED
    PermissionSnapshot.State.UNKNOWN -> PermissionStatus.UNKNOWN
    PermissionSnapshot.State.NOT_APPLICABLE -> PermissionStatus.NOT_APPLICABLE
}

private fun settingsIntent(context: android.content.Context, key: String): Intent? {
    val pkgUri = Uri.parse("package:${context.packageName}")
    return runCatching {
        when (key) {
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

            "exactAlarm" -> if (Build.VERSION.SDK_INT >= 31) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)
            } else null

            "fullScreenIntent" -> if (Build.VERSION.SDK_INT >= 34) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri)
            } else null

            // 直接请求本 App 的电池白名单对话框；列表页里常常找不到本 App。
            "battery" -> if (Build.VERSION.SDK_INT >= 23) {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
            } else {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            }
            "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            else -> null
        }
    }.getOrNull()
}

@Composable
private fun PermissionItem(
    name: String,
    state: PermissionSnapshot.State,
    detail: String,
    intent: Intent?,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    PermissionRow(
        name = name,
        status = permStatus(state),
        detail = detail,
        onOpenSettings = if (state == PermissionSnapshot.State.GRANTED ||
            state == PermissionSnapshot.State.NOT_APPLICABLE || intent == null
        ) {
            null
        } else {
            {
                if (!MeSupport.open(context, intent)) {
                    onMessage("无法打开系统设置")
                }
            }
        },
    )
}

@Composable
private fun StatusLine(
    title: String,
    value: String,
    tone: XiaoiTone,
    detail: String?,
) {
    val colors = XiaoiTheme.colors
    BasicComponent(
        title = title,
        titleColor = BasicComponentDefaults.titleColor(color = colors.text),
        summary = detail,
        summaryColor = BasicComponentDefaults.summaryColor(color = colors.textSecondary),
        endActions = { XiaoiBadge(value, tone) },
    )
}
