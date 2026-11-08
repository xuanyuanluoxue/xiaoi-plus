package plus.xiaoi.module.ui.screen.me

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import plus.xiaoi.module.control.BridgeControlService
import plus.xiaoi.module.ui.components.FilterChips
import plus.xiaoi.module.ui.components.XiaoiConfirmDialog
import plus.xiaoi.module.ui.components.XiaoiEmptyState
import plus.xiaoi.module.ui.components.XiaoiPageActions
import plus.xiaoi.module.ui.components.XiaoiPreferenceGroup
import plus.xiaoi.module.ui.components.XiaoiSwitchRow
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Share

private data class LogLine(val time: String, val level: String, val message: String)

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
    var filter by rememberSaveable { mutableStateOf(0) } // 0 全部 / 1 信息 / 2 错误
    var follow by rememberSaveable { mutableStateOf(false) }
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

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(follow) {
        while (follow) {
            delay(2000)
            reload()
        }
    }

    val shown = lines.filter {
        filter == 0 || (filter == 1 && it.level == "I") || (filter == 2 && it.level == "E")
    }

    LaunchedEffect(shown.size, follow) {
        if (follow && shown.isNotEmpty()) {
            listState.scrollToItem(shown.lastIndex)
        }
    }

    fun share() {
        val text = lines.joinToString("\n") { "${it.time} ${it.level} ${it.message}" }
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

    Column(Modifier.fillMaxSize().padding(horizontal = XiaoiTheme.dimens.pagePad)) {
        Spacer(Modifier.height(4.dp))
        FilterChips(
            options = listOf("全部", "信息", "错误"),
            selected = when (filter) {
                1 -> "信息"
                2 -> "错误"
                else -> "全部"
            },
            onSelect = {
                filter = when (it) {
                    "信息" -> 1
                    "错误" -> 2
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
        XiaoiPreferenceGroup {
            XiaoiSwitchRow(
                title = "跟随最新",
                subtitle = "每 2 秒读取 App 内存镜像，不依赖 MCP",
                checked = follow,
                onCheckedChange = { follow = it },
            )
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (shown.isEmpty()) {
                item {
                    val (title, desc) = when {
                        !received -> "等待小爱上报日志" to "小爱进程连接后这里会自动出现"
                        lines.isEmpty() -> "还没有日志" to "小爱还没有上报运行日志"
                        else -> "筛选无结果" to "切换筛选条件查看其他日志"
                    }
                    XiaoiEmptyState(title, desc)
                }
            } else {
                itemsIndexed(shown) { _, line ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (line.level == "E") colors.errorBg else Color.Transparent)
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
                            color = if (line.level == "E") colors.error else colors.success,
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
