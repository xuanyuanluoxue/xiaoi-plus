package plus.xiaoi.module.ui.screen.me

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import plus.xiaoi.module.ConfigRepository
import plus.xiaoi.module.ThemeMode
import plus.xiaoi.module.agent.AgentService
import plus.xiaoi.module.control.BridgeControlService
import plus.xiaoi.module.core.BridgeStatus
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiButton
import plus.xiaoi.module.ui.components.XiaoiButtonStyle
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiCardGroup
import plus.xiaoi.module.ui.components.XiaoiConfirmDialog
import plus.xiaoi.module.ui.components.XiaoiIconTone
import plus.xiaoi.module.ui.components.XiaoiInfoRow
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiTextButton
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Promotions
import top.yukonga.miuix.kmp.icon.extended.Theme

/**
 * 我的（SCREENS.md §9）。
 *
 * 独立卡片清单：项目介绍、赞助、作者、致谢与各设置入口各自一张卡。
 * 危险动作默认折叠，展开后仍需弹窗二次确认；执行结果用重新读取的 config.json 验证。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun MeScreen(
    themeMode: Int,
    onThemeChange: (Int) -> Unit,
    onNavigate: (String) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLegacyHome: () -> Unit,
    onOpenLegacyConfig: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = XiaoiTheme.colors
    val scope = rememberCoroutineScope()
    val version = MeSupport.versionName(context)
    val versionCode = MeSupport.versionCode(context)

    var rootInstalled by remember { mutableStateOf<Boolean?>(null) }
    var errorCount by remember { mutableStateOf<Int?>(null) }
    var dangerOpen by remember { mutableStateOf(false) }
    var showStopConfirm by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var stopResult by remember { mutableStateOf<StopResult?>(null) }

    LaunchedEffect(Unit) {
        rootInstalled = withContext(Dispatchers.IO) { MeSupport.rootInstalled() }
    }
    LaunchedEffect(Unit) {
        errorCount = withContext(Dispatchers.IO) {
            val dump = BridgeControlService.logDump().orEmpty()
            dump.split('\n').count { line ->
                line.length >= 14 && line[12] == ' ' && line[13] == 'E'
            }
        }
    }

    val lsposedConnected = BridgeStatus.get().connected
    val themeLabel = when (themeMode) {
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "深色"
        else -> "跟随系统"
    }

    fun copyAuthor() {
        val ok = MeSupport.copy(context, "作者名", "轩辕落雪")
        onMessage(if (ok) "已复制作者名：轩辕落雪" else "复制失败")
    }

    fun executeStopAll() {
        showStopConfirm = false
        stopping = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val repo = ConfigRepository.get(context)
                    if (repo.isReadOnly()) {
                        return@withContext StopResult(false, "配置处于只读态，未执行任何写入")
                    }
                    val cfg = repo.load()
                    if (repo.isSkeleton()) {
                        return@withContext StopResult(
                            true,
                            "配置尚未初始化，当前没有可停用的对外能力（未写入文件）",
                        )
                    }
                    if (!cfg.enabled && !cfg.mcpEnabled && !cfg.mcpLan && !cfg.agentLan) {
                        return@withContext StopResult(
                            true,
                            "当前已经是「全部停用」状态，未写入 config.json",
                        )
                    }
                    cfg.enabled = false
                    cfg.mcpEnabled = false
                    cfg.mcpLan = false
                    cfg.agentLan = false
                    repo.save(cfg)
                    AgentService.startIfEnabled(context)
                    // 以重新读取的文件为准验证，而不是相信内存草稿。
                    val check = repo.load()
                    val ok = !check.enabled && !check.mcpEnabled &&
                        !check.mcpLan && !check.agentLan
                    if (ok) {
                        StopResult(
                            true,
                            "已停用：模型重定向 · 8771 控制面 · 8770 动作面 · 两侧局域网。令牌保留，重新开启时无需重新配对。",
                        )
                    } else {
                        StopResult(false, "已写入但状态未完全确认，请到诊断页核对四维状态")
                    }
                } catch (t: Throwable) {
                    StopResult(false, "停用失败：${t.message ?: t.javaClass.simpleName}（原配置未被覆盖）")
                }
            }
            stopping = false
            stopResult = result
            onMessage(result.text)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 112.dp)
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        XiaoiCard {
            Text("小爱同学plus", color = colors.text, style = XiaoiTheme.typography.title)
            Text(
                "模型重定向 · 8771 控制面 · 8770 动作面；仅在本机生效。",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.caption,
            )
            Spacer(Modifier.height(12.dp))
            MeValueRow("版本", "v$version ($versionCode)")
            MeValueRow("设备", Build.MODEL.ifBlank { "未识别" })
            MeValueRow(
                "系统",
                "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    "Root",
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.caption,
                    modifier = Modifier.width(64.dp),
                )
                XiaoiBadge(
                    text = when (rootInstalled) {
                        true -> "已安装"
                        false -> "未检测到"
                        null -> "未确认"
                    },
                    tone = when (rootInstalled) {
                        true -> XiaoiTone.INFO
                        false -> XiaoiTone.NEUTRAL
                        null -> XiaoiTone.NEUTRAL
                    },
                )
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    "LSPosed",
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.caption,
                    modifier = Modifier.width(64.dp),
                )
                XiaoiBadge(
                    text = if (lsposedConnected) "已连接" else "未确认",
                    tone = if (lsposedConnected) XiaoiTone.SUCCESS else XiaoiTone.NEUTRAL,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Root 只检测 su 是否存在，不显示“已授权”；LSPosed 以控制面连接为准。",
                color = colors.textHint,
                style = XiaoiTheme.typography.caption,
            )
        }

        Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))

        XiaoiCardGroup {
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "赞助支持",
                    subtitle = "方式尚未配置 · 可在 GitHub Issues 联系作者",
                    onClick = {
                        onMessage("赞助方式尚未配置，可在 GitHub Issues 联系作者")
                    },
                    icon = MiuixIcons.Promotions,
                    iconTone = XiaoiIconTone.ORANGE,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "作者",
                    subtitle = "轩辕落雪 · 点击复制作者名",
                    onClick = { copyAuthor() },
                    icon = MiuixIcons.Community,
                    iconTone = XiaoiIconTone.INDIGO,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "致谢项目",
                    subtitle = "OpenClaw · KernelSU Next · Miuix",
                    onClick = { onNavigate("Licenses") },
                    icon = MiuixIcons.Favorites,
                    iconTone = XiaoiIconTone.PINK,
                )
            }
        }

        Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))

        XiaoiCardGroup {
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "外观",
                    subtitle = "当前：$themeLabel · 三档主题即时生效",
                    onClick = { onNavigate("Appearance") },
                    icon = MiuixIcons.Theme,
                    iconTone = XiaoiIconTone.PURPLE,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "配置管理",
                    subtitle = "备份（含密钥）、还原、脱敏导出、运行时导入、损坏恢复",
                    onClick = { onNavigate("ConfigManage") },
                    icon = MiuixIcons.Backup,
                    iconTone = XiaoiIconTone.PURPLE,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "诊断",
                    subtitle = "四维状态、8771/8770、权限矩阵、配置完整性、兼容模式",
                    onClick = { onNavigate("Diagnostics") },
                    icon = MiuixIcons.Info,
                    iconTone = XiaoiIconTone.RED,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "日志",
                    subtitle = when (val n = errorCount) {
                        null -> "读取本机日志缓冲…"
                        0 -> "没有错误日志"
                        else -> "$n 条错误 · 查看控制面与小爱上报"
                    },
                    onClick = onOpenLogs,
                    icon = MiuixIcons.Notes,
                    iconTone = XiaoiIconTone.PINK,
                    badge = errorCount?.takeIf { it > 0 }?.toString(),
                    badgeTone = XiaoiTone.ERROR,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "关于",
                    subtitle = "版本、GitHub、许可证与致谢",
                    onClick = onOpenAbout,
                    icon = MiuixIcons.Help,
                    iconTone = XiaoiIconTone.INDIGO,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "开源许可",
                    subtitle = "GPL-3.0、Apache-2.0（Miuix）、Kotlin/Compose/AndroidX",
                    onClick = { onNavigate("Licenses") },
                    icon = MiuixIcons.File,
                    iconTone = XiaoiIconTone.INDIGO,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        XiaoiPreferenceCard {
            XiaoiInfoRow(
                title = "停用全部对外能力",
                subtitle = if (dangerOpen) "点按收起" else "默认折叠 · 执行前需要二次确认",
                onClick = {
                    dangerOpen = !dangerOpen
                    if (!dangerOpen) stopResult = null
                },
                icon = MiuixIcons.Delete,
                iconTone = XiaoiIconTone.RED,
                badge = "需确认",
                badgeTone = XiaoiTone.WARNING,
            )
        }
        if (dangerOpen) {
            Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
            XiaoiCard {
                Text("将立即写入 config.json", color = colors.text, style = XiaoiTheme.typography.rowTitle)
                Spacer(Modifier.height(8.dp))
                DangerLine("关闭模型重定向（小爱恢复官方端点）")
                DangerLine("关闭 8771 控制面（MCP 工具不可用）")
                DangerLine("关闭 8770 动作面（设备动作不可用）")
                DangerLine("关闭 8771 / 8770 局域网监听")
                Spacer(Modifier.height(8.dp))
                Text(
                    "令牌会保留；重新开启任一通道时无需重新配对。旧配置和日志不会被删除。",
                    color = colors.textHint,
                    style = XiaoiTheme.typography.caption,
                )
                if (stopResult != null) {
                    Spacer(Modifier.height(10.dp))
                    val r = stopResult!!
                    XiaoiBadge(
                        text = if (r.ok) "已执行并核对" else "未完成",
                        tone = if (r.ok) XiaoiTone.SUCCESS else XiaoiTone.ERROR,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(r.text, color = colors.textSecondary, style = XiaoiTheme.typography.body)
                    XiaoiTextButton("到诊断页核对 8771/8770") { onNavigate("Diagnostics") }
                }
                Spacer(Modifier.height(12.dp))
                XiaoiButton(
                    text = if (stopping) "正在停用…" else "停用全部",
                    onClick = { showStopConfirm = true },
                    style = XiaoiButtonStyle.SECONDARY,
                    enabled = !stopping,
                    loading = stopping,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // 旧版入口只允许从「诊断 > 兼容模式」进入，不在主界面占位；签名保留给路由壳。

    XiaoiConfirmDialog(
        show = showStopConfirm,
        title = "停用全部对外能力？",
        message = "将关闭模型重定向、8771 控制面、8770 动作面与两侧局域网，" +
            "并立即写入 config.json。小爱会回到官方端点，外部 Agent 断开。",
        confirmText = "停用全部",
        secondaryNote = "令牌与历史备份会保留，可重新开启；此操作可逆。",
        danger = true,
        onConfirm = { executeStopAll() },
        onDismiss = { showStopConfirm = false },
    )
}

private data class StopResult(val ok: Boolean, val text: String)

@Composable
private fun MeValueRow(label: String, value: String, mono: Boolean = false) {
    val colors = XiaoiTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            color = colors.textSecondary,
            style = XiaoiTheme.typography.caption,
            modifier = Modifier.width(64.dp),
        )
        Text(
            value,
            color = colors.text,
            style = if (mono) XiaoiTheme.typography.mono else XiaoiTheme.typography.body,
        )
    }
}

@Composable
private fun DangerLine(text: String) {
    Text(
        "· $text",
        color = XiaoiTheme.colors.textSecondary,
        style = XiaoiTheme.typography.body,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
