package plus.xiaoi.module.ui.screen.xiaoai

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import plus.xiaoi.module.Json
import plus.xiaoi.module.ui.components.LocalAppRuntime
import plus.xiaoi.module.ui.components.LocalConfigGateway
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiButton
import plus.xiaoi.module.ui.components.XiaoiButtonStyle
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiLoadingSkeleton
import plus.xiaoi.module.ui.components.XiaoiSwitchRow
import plus.xiaoi.module.ui.components.XiaoiTextButton
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.components.XiaoiTextField
import plus.xiaoi.module.ui.components.xiaoiTopBarInset
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

/** 记忆页里一个小节的投影。 */
private data class MemorySectionUi(
    val level: Int,
    val path: String,
    val title: String,
    val items: List<String>,
    val count: Int,
    val truncated: Boolean,
)

/**
 * 记忆（P2-G）：分节只读 + 追加。
 *
 * <p>记忆文件在小爱进程私有目录，本页通过 8771 的 {@code xiaoai_memory} 间接读写。
 * 写操作默认关闭；打开后每次写入都会在小爱目录里留备份（保留 5 份）。
 * 不提供删除或清空入口——界面里根本没有这样的按钮。</p>
 */
@Composable
fun MemoryScreen(onMessage: (String) -> Unit) {
    val configGateway = LocalConfigGateway.current
    val appRuntime = LocalAppRuntime.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var writeEnabled by remember { mutableStateOf(false) }
    var savingSwitch by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var raw by remember { mutableStateOf("") }
    var exists by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var appending by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = true
            val cfg = runCatching {
                appRuntime.xiaoaiIo { configGateway.loadState().config }
            }.getOrNull()
            writeEnabled = cfg?.memoryWriteEnabled == true
            val out = runCatching {
                appRuntime.xiaoaiIo { appRuntime.memory.call("get", "", "", 50) }
            }.getOrNull()
            raw = out.orEmpty()
            exists = raw.contains("\"exists\":true")
            error = if (out == null) "读不到记忆：小爱未运行，或能力桥（8771）未开启" else ""
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    fun setWrite(enabled: Boolean) {
        if (savingSwitch) return
        scope.launch {
            savingSwitch = true
            val problem = runCatching {
                appRuntime.xiaoaiIo {
                    val loaded = configGateway.loadState()
                    if (loaded.readOnly) return@xiaoaiIo "配置已损坏，保存被阻止"
                    val cfg = loaded.config
                    if (cfg.memoryWriteEnabled == enabled) return@xiaoaiIo ""
                    cfg.memoryWriteEnabled = enabled
                    val saved = configGateway.saveIfRev(cfg, cfg.rev)
                    when {
                        saved.conflict() -> "配置已被其他页面修改，请重试"
                        !saved.ok() -> "保存失败：${saved.error}"
                        else -> ""
                    }
                }
            }.getOrElse { "保存失败" }
            savingSwitch = false
            if (problem.isEmpty()) {
                writeEnabled = enabled
                onMessage(
                    if (enabled) "已允许写记忆；每次写入前会自动备份原文件" else "已关闭写记忆",
                )
            } else {
                onMessage(problem)
            }
        }
    }

    fun append() {
        val target = section.trim()
        val body = text.trim()
        if (target.isEmpty()) {
            onMessage("先填小节，例如「长期记忆/重要事实」")
            return
        }
        if (body.isEmpty()) {
            onMessage("先写要记住的内容")
            return
        }
        scope.launch {
            appending = true
            val out = runCatching {
                appRuntime.xiaoaiIo { appRuntime.memory.call("append", target, body, 0) }
            }.getOrNull()
            appending = false
            if (out != null && out.contains("\"ok\":true")) {
                text = ""
                onMessage("已追加到「$target」，原文件已备份")
                load()
            } else {
                onMessage(explain(out))
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
        if (loading) {
            XiaoiLoadingSkeleton(height = 100.dp)
            Spacer(Modifier.height(12.dp))
            XiaoiLoadingSkeleton(height = 260.dp)
            return@Column
        }

        XiaoiCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "MEMORY.md",
                    color = XiaoiTheme.colors.text,
                    style = XiaoiTheme.typography.rowTitle,
                    modifier = Modifier.weight(1f),
                )
                XiaoiBadge(
                    text = if (exists) "已读取" else "未生成",
                    tone = if (exists) XiaoiTone.SUCCESS else XiaoiTone.NEUTRAL,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                error.ifEmpty {
                    "只读展示；写入前自动备份（保留 5 份）。云端可能覆盖本地改动。"
                },
                color = if (error.isEmpty()) XiaoiTheme.colors.textHint else XiaoiTheme.colors.error,
                style = XiaoiTheme.typography.caption,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                XiaoiTextButton(text = "刷新", onClick = { load() })
            }
        }

        Spacer(Modifier.height(12.dp))
        XiaoiCard {
            XiaoiSwitchRow(
                title = "允许写记忆",
                subtitle = "默认关闭；打开后 MCP 与 App 才能追加或替换小节",
                checked = writeEnabled,
                enabled = !savingSwitch,
                onCheckedChange = { setWrite(it) },
            )
        }

        if (error.isEmpty()) {
            val sections = parseSections(raw)
            if (sections.isEmpty()) {
                XiaoiGroupHeader("小节")
                XiaoiCard {
                    Text(
                        if (exists) "这个文件还没有分节内容。" else "小爱还没有生成记忆文件；先正常对话几轮再看看。",
                        color = XiaoiTheme.colors.textHint,
                        style = XiaoiTheme.typography.caption,
                    )
                }
            } else {
                sections.forEach { s ->
                    XiaoiGroupHeader(s.path)
                    XiaoiCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                s.title,
                                color = XiaoiTheme.colors.text,
                                style = XiaoiTheme.typography.rowTitle,
                                modifier = Modifier.weight(1f),
                            )
                            if (s.truncated) XiaoiBadge("前 ${s.items.size} 条", XiaoiTone.WARNING)
                            XiaoiTextButton(
                                text = "追加",
                                enabled = writeEnabled,
                                onClick = {
                                    section = s.path
                                },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        if (s.items.isEmpty()) {
                            Text(
                                "（空）",
                                color = XiaoiTheme.colors.textHint,
                                style = XiaoiTheme.typography.caption,
                            )
                        } else {
                            s.items.forEach { item ->
                                Text(
                                    item,
                                    color = XiaoiTheme.colors.textSecondary,
                                    style = XiaoiTheme.typography.body,
                                    modifier = Modifier.padding(start = if (s.level > 1) 8.dp else 0.dp),
                                )
                            }
                        }
                        if (s.truncated) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "共 ${s.count} 条，这里只显示前 ${s.items.size} 条。",
                                color = XiaoiTheme.colors.textHint,
                                style = XiaoiTheme.typography.caption,
                            )
                        }
                    }
                }
            }
        }

        XiaoiGroupHeader("追加一条")
        XiaoiCard {
            if (!writeEnabled) {
                Text(
                    "写记忆默认关闭。打开上面的「允许写记忆」后，才能追加或替换小节。",
                    color = XiaoiTheme.colors.textHint,
                    style = XiaoiTheme.typography.caption,
                )
                Spacer(Modifier.height(8.dp))
            }
            XiaoiTextField(
                value = section,
                onValueChange = { section = it },
                label = "小节路径，例如 长期记忆/重要事实",
                enabled = writeEnabled,
            )
            Spacer(Modifier.height(8.dp))
            XiaoiTextField(
                value = text,
                onValueChange = { text = it },
                label = "要记住的内容（一行一条）",
                enabled = writeEnabled,
            )
            Spacer(Modifier.height(10.dp))
            XiaoiButton(
                text = if (appending) "写入中…" else "追加",
                style = XiaoiButtonStyle.PRIMARY,
                enabled = writeEnabled && !appending,
                loading = appending,
                onClick = { append() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "只追加，不删除；小节不存在时会新建。每次写入前自动备份原文件（保留 5 份）。",
                color = XiaoiTheme.colors.textHint,
                style = XiaoiTheme.typography.caption,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** 从 xiaoai_memory get 的 JSON 里取分节。 */
private fun parseSections(json: String): List<MemorySectionUi> {
    if (json.isEmpty()) return emptyList()
    val root = runCatching { Json.read(json) }.getOrNull() as? Map<*, *> ?: return emptyList()
    val list = root["sections"] as? List<*> ?: return emptyList()
    var parent = ""
    val out = ArrayList<MemorySectionUi>()
    for (rawSection in list) {
        val m = rawSection as? Map<*, *> ?: continue
        val level = (m["level"] as? Number)?.toInt() ?: 1
        val title = (m["title"] as? String).orEmpty()
        if (level <= 1) parent = title
        val path = if (level <= 1) title else listOf(parent, title).filter { it.isNotEmpty() }.joinToString("/")
        val items = (m["items"] as? List<*>).orEmpty().map { it?.toString().orEmpty() }
        out.add(
            MemorySectionUi(
                level = level,
                path = path,
                title = title,
                items = items,
                count = (m["count"] as? Number)?.toInt() ?: items.size,
                truncated = m["truncated"] == true,
            ),
        )
    }
    return out
}

/** 把工具返回的 JSON 变成一句能看懂的话（不打印正文）。 */
private fun explain(json: String?): String {
    if (json == null) return "写失败：能力桥不可达（小爱未运行或 8771 未开启）"
    val root = runCatching { Json.read(json) }.getOrNull() as? Map<*, *>
        ?: return "写失败：返回内容无法解析"
    val code = (root["code"] as? String).orEmpty()
    val message = (root["message"] as? String).orEmpty()
    return when {
        message.isNotEmpty() -> message
        code.isNotEmpty() -> "写失败：$code"
        else -> "写失败"
    }
}
