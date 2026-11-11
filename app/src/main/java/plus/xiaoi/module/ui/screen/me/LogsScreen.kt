package plus.xiaoi.module.ui.screen.me

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import plus.xiaoi.module.ConfigRepository
import plus.xiaoi.module.control.BridgeControlService
import plus.xiaoi.module.core.LogLevel
import plus.xiaoi.module.core.LogSettings
import plus.xiaoi.module.ui.components.FilterChips
import plus.xiaoi.module.ui.components.XiaoiConfirmDialog
import plus.xiaoi.module.ui.components.XiaoiEmptyState
import plus.xiaoi.module.ui.components.XiaoiIconTone
import plus.xiaoi.module.ui.components.XiaoiInfoRow
import plus.xiaoi.module.ui.components.XiaoiPageActions
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiSwitchRow
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
        val dump = BridgeControlService.logDump()
        val parsed = if (dump.isNullOrBlank()) emptyList() else dump.split("\n")
            .filter { it.isNotEmpty() }
            .map { parseLine(it) }
        lines = parsed
        if (parsed.isNotEmpty()) received = true
        if (parsed.isEmpty()) confirmClear = false
    }

    LaunchedEffect(Unit) {
        val cfg = withContext(Dispatchers.IO) {
            runCatching { ConfigRepository.get(context).loadForUi() }.getOrNull()
        }
        if (cfg != null) {
            val loaded = LogLevel.parse(cfg.logLevel)
            level = loaded
            LogSettings.set(loaded)
        }
        reload()
    }

    fun applyLevel(next: LogLevel) {
        if (levelSaving) return
        levelSaving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val repo = ConfigRepository.get(context)
                    val cfg = repo.loadForUi()
                    cfg.logLevel = next.wire
                    repo.save(cfg)
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

    fun share() {
        val text = "# logLevel=${level.wire}\n" +
            lines.joinToString("\n") { "${it.time} ${it.level} ${it.message}" }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "分享日志"))
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
            onClick = { share() },
            enabled = lines.isNotEmpty(),
        ) {
            Icon(
                MiuixIcons.Share,
                contentDescription = "分享日志",
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
            BridgeControlService.clearLogs()
            lines = emptyList()
            confirmClear = false
            onMessage("已清空本机日志缓冲（不删除小爱落盘日志）")
        },
        onDismiss = { confirmClear = false },
    )
}
