package plus.xiaoi.module.ui.screen.me

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import plus.xiaoi.module.core.LogLevel
import plus.xiaoi.module.application.log.LogExport
import plus.xiaoi.module.application.log.LogSettings
import plus.xiaoi.module.ui.components.FilterChips
import plus.xiaoi.module.ui.components.LocalAppRuntime
import plus.xiaoi.module.ui.components.LocalConfigGateway
import plus.xiaoi.module.ui.components.XiaoiConfirmDialog
import plus.xiaoi.module.ui.components.XiaoiEmptyState
import plus.xiaoi.module.ui.components.XiaoiIconTone
import plus.xiaoi.module.ui.components.XiaoiInfoRow
import plus.xiaoi.module.ui.components.XiaoiPageActions
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiSwitchRow
import plus.xiaoi.module.ui.components.XiaoiTextButton
import plus.xiaoi.module.ui.components.XiaoiTextField
import plus.xiaoi.module.ui.theme.XiaoiTheme
import plus.xiaoi.module.ui.components.xiaoiTopBarInset
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.window.WindowDialog

private data class LogLine(val time: String, val level: String, val message: String)

private data class LevelOption(val level: LogLevel, val label: String, val desc: String)

private val LEVEL_OPTIONS = listOf(
    LevelOption(LogLevel.OFF, "关闭", "不写日志；只保留错误到 logcat"),
    LevelOption(LogLevel.BRIEF, "精简", "生命周期、警告/错误、安全事件"),
    LevelOption(LogLevel.STANDARD, "标准", "再加每次工具调用的成败/耗时/来源，不含正文"),
    LevelOption(LogLevel.DETAILED, "详细", "再加参数摘要、UA、确认事件（已脱敏）"),
    LevelOption(LogLevel.DEBUG, "调试", "再加 HTTP 细节，只在排障时临时打开"),
)

private fun levelLabel(level: LogLevel): String =
    LEVEL_OPTIONS.firstOrNull { it.level == level }?.label ?: level.wire

private fun levelDesc(level: LogLevel): String =
    LEVEL_OPTIONS.firstOrNull { it.level == level }?.desc ?: ""

/** 来源只看 msg 前缀：mcp=小爱任务，agent=设备操作，http=接口。 */
private fun sourceOf(msg: String): Int = when {
    msg.startsWith("[mcp]") -> 1
    msg.startsWith("[agent]") -> 2
    msg.startsWith("[http]") -> 3
    else -> 0
}

/** 进程名只显示冒号后的短名：com.miui.voiceassist:core → core。 */
private fun shortProc(proc: String): String {
    val i = proc.lastIndexOf(':')
    return if (i >= 0 && i + 1 < proc.length) proc.substring(i + 1) else proc
}

private fun parseLine(line: String): LogLine {
    if (line.length >= 16 && line[12] == ' ' && line[14] == ' ') {
        val t = line.substring(0, 12)
        if (t.matches(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}"))) {
            return LogLine(t, line.substring(13, 14), line.substring(15))
        }
    }
    return LogLine("", "I", line)
}

/**
 * 日志（SCREENS.md §13）。
 *
 * <p>页面级动作（清空/分享）放在顶栏；内容区只保留筛选、跟随开关与列表，
 * 不再出现“大计数 + 右对齐按钮”的第二条工具栏。</p>
 */
@Composable
fun LogsScreen(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val configGateway = LocalConfigGateway.current
    val appRuntime = LocalAppRuntime.current
    val colors = XiaoiTheme.colors
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var filter by rememberSaveable { mutableStateOf(0) } // 0 全部 / 1 信息 / 2 警告 / 3 错误 / 4 调试
    var sourceFilter by rememberSaveable { mutableStateOf(0) } // 0 全部 / 1 小爱 / 2 设备 / 3 接口
    var follow by rememberSaveable { mutableStateOf(false) }
    var level by remember { mutableStateOf(LogSettings.get()) }
    var levelDialog by remember { mutableStateOf(false) }
    var levelSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var received by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val reload: () -> Unit = {
        val dump = appRuntime.controlLog.dump()
        val parsed = if (dump.isBlank()) emptyList() else dump.split("\n")
            .filter { it.isNotEmpty() }
            .map { parseLine(it) }
        lines = parsed
        if (parsed.isNotEmpty()) received = true
        if (parsed.isEmpty()) confirmClear = false
    }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { configGateway.loadState() }.getOrNull()
        }
        if (loaded != null) {
            val level0 = LogLevel.parse(loaded.config.logLevel)
            level = level0
            LogSettings.set(level0)
        }
        reload()
    }

    fun applyLevel(next: LogLevel) {
        if (levelSaving) return
        levelSaving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val loaded = configGateway.loadState()
                    if (loaded.readOnly) throw IllegalStateException("配置只读，已拒绝写入")
                    val cfg = loaded.config
                    cfg.logLevel = next.wire
                    val saved = configGateway.saveIfRev(cfg, cfg.rev)
                    when {
                        saved.conflict() -> throw IllegalStateException("配置已被其他页面修改，请重试")
                        !saved.ok() -> throw IllegalStateException(saved.error)
                    }
                }
            }
            levelSaving = false
            result.onSuccess {
                level = next
                LogSettings.set(next)
                levelDialog = false
                onMessage("日志档位已切换为「${levelLabel(next)}」")
                reload()
            }.onFailure {
                onMessage("保存失败：${it.message ?: "未知错误"}")
            }
        }
    }
    LaunchedEffect(follow) {
        while (follow) {
            delay(2000)
            reload()
        }
    }

    val shown = lines.filter { line ->
        val levelOk = when (filter) {
            1 -> line.level == "I"
            2 -> line.level == "W"
            3 -> line.level == "E"
            4 -> line.level == "D"
            else -> true
        }
        val sourceOk = when (sourceFilter) {
            1 -> sourceOf(line.message) == 1
            2 -> sourceOf(line.message) == 2
            3 -> sourceOf(line.message) == 3
            else -> true
        }
        levelOk && sourceOk
    }

    LaunchedEffect(shown.size, follow) {
        if (follow && shown.isNotEmpty()) {
            listState.scrollToItem(shown.lastIndex)
        }
    }

    // ---------------------------------------------------------------- 导出
    var exportDialog by remember { mutableStateOf(false) }
    /** 0=ZIP（meta+txt+json） 1=纯文本 2=JSON */
    var exportFormat by rememberSaveable { mutableStateOf(0) }
    /** 0=全部 1=最近 30 分钟 2=最近 2 小时 */
    var exportRange by rememberSaveable { mutableStateOf(0) }
    /** 级别阈值，与 LogExport.rank 同尺度：0=全部 2=警告 3=错误 */
    var exportLevel by rememberSaveable { mutableStateOf(0) }
    var exportKeyword by rememberSaveable { mutableStateOf("") }
    var exportProc by rememberSaveable { mutableStateOf("") }
    var knownProcs by remember { mutableStateOf<List<String>>(emptyList()) }

    val exportStamp = remember { SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) }

    /** 把当前筛选结果写成文件；format 与 dialog 的选项一一对应。 */
    fun runExport(uri: Uri, format: Int) {
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val now = System.currentTimeMillis()
                    val filter = LogExport.Filter().apply {
                        minSeverity = exportLevel
                        proc = exportProc
                        keyword = exportKeyword
                        sinceTs = when (exportRange) {
                            1 -> now - 30L * 60 * 1000
                            2 -> now - 2L * 60 * 60 * 1000
                            else -> 0L
                        }
                        rangeLabel = when (exportRange) {
                            1 -> "最近 30 分钟"
                            2 -> "最近 2 小时"
                            else -> "全部时间"
                        }
                        limit = LogExport.MAX_LINES
                    }
                    val version = runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: "?"
                    val header = listOf(
                        "模块: $version",
                        "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        "机型: ${Build.MANUFACTURER} ${Build.MODEL}",
                        "日志档位: ${level.wire}",
                    )
                    // 读合并视图而不是屏幕文本：只有它带 pid/proc/ts，能按进程和时间筛。
                    val dump = appRuntime.controlLog.dumpJson(LogExport.MAX_LINES, 0)
                    val result = LogExport.build(dump, filter, header)
                    if (result.count == 0) {
                        throw IllegalStateException("当前筛选没有日志，换个条件再试")
                    }
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("无法打开目标文件")
                    out.use { os ->
                        when (format) {
                            0 -> ZipOutputStream(os).use { zos ->
                                fun put(name: String, body: String) {
                                    zos.putNextEntry(ZipEntry(name))
                                    zos.write(body.toByteArray(Charsets.UTF_8))
                                    zos.closeEntry()
                                }
                                put("meta.txt", result.meta)
                                put("logs.txt", result.text)
                                put("logs.json", result.json)
                            }
                            1 -> os.write(result.text.toByteArray(Charsets.UTF_8))
                            else -> os.write(result.json.toByteArray(Charsets.UTF_8))
                        }
                    }
                    "已导出 ${result.count} 条 · ${LogExport.describe(filter)}"
                }.getOrElse {
                    "导出失败：${it.message ?: it.javaClass.simpleName}"
                }
            }
            onMessage(message)
        }
    }

    /** 打开导出面板前先取一次进程列表，避免用固定文案猜进程名。 */
    fun openExport() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    LogExport.procs(appRuntime.controlLog.dumpJson(LogExport.MAX_LINES, 0))
                }.getOrDefault(emptyList())
            }
            knownProcs = list
            if (exportProc.isNotEmpty() && !list.contains(exportProc)) exportProc = ""
            exportDialog = true
        }
    }

    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) runExport(uri, 0) }
    val txtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> if (uri != null) runExport(uri, 1) }
    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) runExport(uri, 2) }

    fun launchExport() {
        exportDialog = false
        when (exportFormat) {
            0 -> zipLauncher.launch("xiaoi-logs-$exportStamp.zip")
            1 -> txtLauncher.launch("xiaoi-logs-$exportStamp.txt")
            else -> jsonLauncher.launch("xiaoi-logs-$exportStamp.json")
        }
    }

    XiaoiPageActions {
        IconButton(
            onClick = { confirmClear = true },
            enabled = lines.isNotEmpty(),
        ) {
            Icon(
                MiuixIcons.Delete,
                contentDescription = "清空日志",
                tint = if (lines.isNotEmpty()) colors.text else colors.textHint,
            )
        }
        IconButton(
            onClick = { openExport() },
            enabled = lines.isNotEmpty(),
        ) {
            Icon(
                MiuixIcons.Share,
                contentDescription = "导出日志",
                tint = if (lines.isNotEmpty()) colors.text else colors.textHint,
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = XiaoiTheme.dimens.pagePad),
    ) {
        Spacer(Modifier.height(xiaoiTopBarInset() + 4.dp))
        XiaoiPreferenceCard {
            XiaoiInfoRow(
                title = "日志档位",
                subtitle = "${levelLabel(level)} · ${levelDesc(level)}",
                onClick = { levelDialog = true },
                icon = MiuixIcons.Notes,
                iconTone = XiaoiIconTone.ACCENT,
            )
        }
        Spacer(Modifier.height(8.dp))
        FilterChips(
            options = listOf("全部", "信息", "警告", "错误", "调试"),
            selected = when (filter) {
                1 -> "信息"
                2 -> "警告"
                3 -> "错误"
                4 -> "调试"
                else -> "全部"
            },
            onSelect = {
                filter = when (it) {
                    "信息" -> 1
                    "警告" -> 2
                    "错误" -> 3
                    "调试" -> 4
                    else -> 0
                }
            },
        )
        Spacer(Modifier.height(4.dp))
        FilterChips(
            options = listOf("全部", "小爱任务", "设备操作", "接口"),
            selected = when (sourceFilter) {
                1 -> "小爱任务"
                2 -> "设备操作"
                3 -> "接口"
                else -> "全部"
            },
            onSelect = {
                sourceFilter = when (it) {
                    "小爱任务" -> 1
                    "设备操作" -> 2
                    "接口" -> 3
                    else -> 0
                }
            },
        )
        Text(
            "${shown.size} / ${lines.size} 条 · 只读本机内存镜像",
            color = colors.textSecondary,
            style = XiaoiTheme.typography.caption,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        XiaoiPreferenceCard {
            XiaoiSwitchRow(
                title = "跟随最新",
                subtitle = "每 2 秒读取 App 内存镜像，不依赖 MCP",
                checked = follow,
                onCheckedChange = { follow = it },
                icon = MiuixIcons.Notes,
                iconTone = XiaoiIconTone.ACCENT,
            )
        }
        Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 112.dp),
        ) {
            if (shown.isEmpty()) {
                item {
                    val (title, desc) = when {
                        level == LogLevel.OFF -> "日志档位为关闭" to "打开日志档位后，新事件才会被记录"
                        !received -> "等待小爱上报日志" to "小爱进程连接后这里会自动出现"
                        lines.isEmpty() -> "还没有日志" to "小爱还没有上报运行日志"
                        else -> "筛选无结果" to "切换筛选条件查看其他日志"
                    }
                    XiaoiEmptyState(title, desc)
                }
            } else {
                itemsIndexed(shown) { _, line ->
                    val levelColor = when (line.level) {
                        "E" -> colors.error
                        "W" -> colors.warning
                        "D" -> colors.textHint
                        else -> colors.textSecondary
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                when (line.level) {
                                    "E" -> colors.errorBg
                                    "W" -> colors.warningBg
                                    else -> Color.Transparent
                                },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (line.level == "E") {
                            Box(
                                Modifier
                                    .width(2.dp)
                                    .height(18.dp)
                                    .background(colors.error),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            line.time,
                            color = colors.textHint,
                            style = XiaoiTheme.typography.mono,
                            modifier = Modifier.width(84.dp),
                        )
                        Text(
                            line.level,
                            color = levelColor,
                            style = XiaoiTheme.typography.mono,
                            modifier = Modifier.width(16.dp),
                        )
                        Text(
                            line.message,
                            color = colors.text,
                            style = XiaoiTheme.typography.mono,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    WindowDialog(
        show = levelDialog,
        title = "日志档位",
        summary = "只影响记录粒度；不影响模型、令牌和接口。",
        summaryColor = colors.textSecondary,
        backgroundColor = colors.surface2,
        onDismissRequest = { if (!levelSaving) levelDialog = false },
    ) {
        Column(Modifier.fillMaxWidth()) {
            LEVEL_OPTIONS.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !levelSaving) { applyLevel(option.level) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.label,
                            color = colors.text,
                            style = XiaoiTheme.typography.rowTitle,
                        )
                        Text(
                            option.desc,
                            color = colors.textSecondary,
                            style = XiaoiTheme.typography.caption,
                        )
                    }
                    if (option.level == level) {
                        Icon(
                            MiuixIcons.Ok,
                            contentDescription = "当前档位",
                            tint = colors.primary,
                        )
                    }
                }
            }
        }
    }

    XiaoiConfirmDialog(
        show = confirmClear,
        title = "清空本机日志缓冲？",
        message = "只会清空 App 内存镜像，不删除小爱进程落盘的 xiaoi_plus.log。",
        confirmText = "清空",
        secondaryNote = "新的日志会在小爱再次上报后继续出现。",
        danger = true,
        onConfirm = {
            appRuntime.controlLog.clear()
            lines = emptyList()
            confirmClear = false
            onMessage("已清空本机日志缓冲（不删除小爱落盘日志）")
        },
        onDismiss = { confirmClear = false },
    )

    WindowDialog(
        show = exportDialog,
        title = "导出日志",
        summary = "按当前条件导出合并视图（含各进程日志）；ZIP 内含 meta.txt / logs.txt / logs.json。",
        summaryColor = colors.textSecondary,
        backgroundColor = colors.surface2,
        onDismissRequest = { exportDialog = false },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text("格式", color = colors.textSecondary, style = XiaoiTheme.typography.caption)
            FilterChips(
                options = listOf("ZIP", "文本", "JSON"),
                selected = when (exportFormat) {
                    1 -> "文本"
                    2 -> "JSON"
                    else -> "ZIP"
                },
                onSelect = {
                    exportFormat = when (it) {
                        "文本" -> 1
                        "JSON" -> 2
                        else -> 0
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Text("时间范围", color = colors.textSecondary, style = XiaoiTheme.typography.caption)
            FilterChips(
                options = listOf("全部", "30 分钟", "2 小时"),
                selected = when (exportRange) {
                    1 -> "30 分钟"
                    2 -> "2 小时"
                    else -> "全部"
                },
                onSelect = {
                    exportRange = when (it) {
                        "30 分钟" -> 1
                        "2 小时" -> 2
                        else -> 0
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Text("级别", color = colors.textSecondary, style = XiaoiTheme.typography.caption)
            FilterChips(
                options = listOf("全部", "警告以上", "仅错误"),
                selected = when (exportLevel) {
                    2 -> "警告以上"
                    3 -> "仅错误"
                    else -> "全部"
                },
                onSelect = {
                    exportLevel = when (it) {
                        "警告以上" -> 2
                        "仅错误" -> 3
                        else -> 0
                    }
                },
            )
            if (knownProcs.size > 1) {
                Spacer(Modifier.height(8.dp))
                Text("进程", color = colors.textSecondary, style = XiaoiTheme.typography.caption)
                val labels = listOf("全部") + knownProcs.map { shortProc(it) }
                FilterChips(
                    options = labels,
                    selected = if (exportProc.isEmpty()) "全部"
                    else shortProc(exportProc),
                    onSelect = { picked ->
                        exportProc = if (picked == "全部") ""
                        else knownProcs.firstOrNull { shortProc(it) == picked } ?: ""
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            XiaoiTextField(
                value = exportKeyword,
                onValueChange = { exportKeyword = it },
                label = "关键字（可选）",
                placeholder = "例如 改写命中、qm.i、某个工具名",
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "关键字匹配正文与 tag，大小写不敏感；上限 ${LogExport.MAX_LINES} 条。",
                color = colors.textHint,
                style = XiaoiTheme.typography.caption,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                XiaoiTextButton("取消", onClick = { exportDialog = false })
                Spacer(Modifier.width(8.dp))
                XiaoiTextButton("导出", onClick = { launchExport() })
            }
        }
    }
}
