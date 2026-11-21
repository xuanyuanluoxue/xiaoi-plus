package plus.xiaoi.module.ui.screen.xiaoai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import plus.xiaoi.module.AppConfig
import plus.xiaoi.module.core.Contract
import plus.xiaoi.module.core.prompt.PromptTemplate
import plus.xiaoi.module.ui.components.LocalAppRuntime
import plus.xiaoi.module.ui.components.LocalConfigGateway
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiConfirmDialog
import plus.xiaoi.module.ui.components.XiaoiDirtyBar
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiLoadingSkeleton
import plus.xiaoi.module.ui.components.XiaoiSwitchRow
import plus.xiaoi.module.ui.components.XiaoiTextField
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.components.xiaoiTopBarInset
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults

/**
 * 系统提示词（P2-G）。
 *
 * <p>只改 config.json 里的 6 个 P2 字段，保存才落盘；隐藏不做的事：不做变量即时预览
 * 之外的任何请求、不在本页读记忆全文（记忆页单独读）。</p>
 *
 * <p>replace 模式会覆盖小爱原生能力描述，切到它必须先过确认弹窗。</p>
 */
@Composable
fun PromptScreen(onMessage: (String) -> Unit) {
    val configGateway = LocalConfigGateway.current
    val appRuntime = LocalAppRuntime.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var readOnly by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    var enabled by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("append") }
    var text by remember { mutableStateOf("") }
    var memoryInject by remember { mutableStateOf(false) }
    var linesText by remember { mutableStateOf("20") }
    var baseRev by remember { mutableStateOf(0L) }
    var modelId by remember { mutableStateOf("") }
    var showReplaceConfirm by remember { mutableStateOf(false) }

    var baseline by remember { mutableStateOf("") }
    val snapshot = listOf(enabled, mode, text, memoryInject, linesText).joinToString("\u0000")
    val dirty = !loading && !readOnly && snapshot != baseline

    fun applyLoaded(cfg: AppConfig, ro: Boolean, err: String) {
        enabled = cfg.promptEnabled
        mode = if (cfg.promptMode == "replace") "replace" else "append"
        text = cfg.promptText
        memoryInject = cfg.memoryInject
        linesText = cfg.memoryInjectLines.toString()
        baseRev = cfg.rev
        modelId = cfg.model()
        readOnly = ro
        loadError = err
        baseline = listOf(enabled, mode, text, memoryInject, linesText).joinToString("\u0000")
    }

    LaunchedEffect(Unit) {
        val loaded = runCatching { appRuntime.xiaoaiIo { configGateway.loadState() } }.getOrNull()
        if (loaded != null) applyLoaded(loaded.config, loaded.readOnly, loaded.error)
        loading = false
    }

    fun discard() {
        scope.launch {
            val loaded = runCatching { appRuntime.xiaoaiIo { configGateway.loadState() } }.getOrNull()
            if (loaded != null) applyLoaded(loaded.config, loaded.readOnly, loaded.error)
            onMessage("已放弃修改")
        }
    }

    fun save() {
        if (saving) return
        scope.launch {
            saving = true
            val lines = linesText.trim().toIntOrNull()?.coerceIn(1, Contract.MEMORY_INJECT_LINES_MAX) ?: 20
            val message = runCatching {
                appRuntime.xiaoaiIo {
                    val latest = configGateway.loadState()
                    if (latest.readOnly) return@xiaoaiIo "配置已损坏，保存被阻止"
                    val cfg = latest.config
                    if (!latest.skeleton && cfg.rev != baseRev) {
                        return@xiaoaiIo "配置已被其他页面修改，请返回后重新进入"
                    }
                    cfg.promptEnabled = enabled
                    cfg.promptMode = mode
                    cfg.promptText = text
                    cfg.memoryInject = memoryInject
                    cfg.memoryInjectLines = lines
                    val invalid = configGateway.validate(cfg)
                    if (invalid != null) return@xiaoaiIo "未保存：$invalid"
                    val saved = configGateway.saveIfRev(cfg, baseRev)
                    when {
                        saved.conflict() -> "配置已被其他页面修改，请返回后重新进入"
                        !saved.ok() -> "保存失败：${saved.error}"
                        else -> {
                            val next = saved.saved
                            baseRev = next.rev
                            ""
                        }
                    }
                }
            }.getOrElse { "保存失败，请检查文件是否可写" }
            saving = false
            if (message.isEmpty()) {
                baseline = snapshot
                onMessage("已保存，等待小爱应用")
            } else {
                onMessage(message)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = xiaoiTopBarInset(), bottom = 112.dp)
                .padding(horizontal = XiaoiTheme.dimens.pagePad)
                .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
        ) {
            if (loading) {
                XiaoiLoadingSkeleton(height = 120.dp)
                Spacer(Modifier.height(12.dp))
                XiaoiLoadingSkeleton(height = 220.dp)
                return@Column
            }
            if (readOnly) {
                XiaoiCard {
                    XiaoiBadge("只读", XiaoiTone.ERROR)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "配置损坏，保存已被阻止${if (loadError.isEmpty()) "" else "（$loadError）"}",
                        color = XiaoiTheme.colors.text,
                        style = XiaoiTheme.typography.rowTitle,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "请先在「我的 › 配置管理」恢复或重建配置。",
                        color = XiaoiTheme.colors.textHint,
                        style = XiaoiTheme.typography.caption,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            XiaoiGroupHeader("提示词")
            XiaoiCard {
                Text(
                    "把这段内容注入小爱每次请求的系统提示词（instructions / system 消息）。" +
                        "默认只追加，不改小爱原有的能力描述。",
                    color = XiaoiTheme.colors.textSecondary,
                    style = XiaoiTheme.typography.body,
                )
                Spacer(Modifier.height(4.dp))
                XiaoiSwitchRow(
                    title = "注入系统提示词",
                    subtitle = if (enabled) "已开启；保存后小爱下一轮请求生效" else "默认关闭，空内容时不做任何事",
                    checked = enabled,
                    enabled = !readOnly,
                    onCheckedChange = { enabled = it },
                )
                XiaoiSwitchRow(
                    title = "替换模式（高风险）",
                    subtitle = "完全覆盖小爱原生提示词，可能破坏打电话/设闹钟等能力；默认只追加",
                    checked = mode == "replace",
                    enabled = !readOnly && enabled,
                    onCheckedChange = { on ->
                        if (on) {
                            showReplaceConfirm = true
                        } else {
                            mode = "append"
                        }
                    },
                )
            }

            XiaoiGroupHeader("提示词正文")
            XiaoiCard {
                PromptTextArea(
                    value = text,
                    enabled = !readOnly,
                    onValueChange = { text = it },
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "变量：{{date}} {{time}} {{model}} {{device}}",
                        color = XiaoiTheme.colors.textHint,
                        style = XiaoiTheme.typography.caption,
                    )
                    Text(
                        "${text.length} / ${Contract.PROMPT_TEXT_MAX}",
                        color = if (text.length > Contract.PROMPT_TEXT_MAX) {
                            XiaoiTheme.colors.error
                        } else {
                            XiaoiTheme.colors.textHint
                        },
                        style = XiaoiTheme.typography.caption,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "变量只在注入时替换一次；写错名字会原样保留，不会静默丢弃。",
                    color = XiaoiTheme.colors.textHint,
                    style = XiaoiTheme.typography.caption,
                )
            }

            XiaoiGroupHeader("预览")
            XiaoiCard {
                val preview = PromptTemplate.render(
                    text,
                    PromptTemplate.Vars(
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                        modelId,
                        android.os.Build.MODEL ?: "",
                    ),
                ).trim()
                if (preview.isEmpty()) {
                    Text(
                        "还没有正文。写一句试试，例如「回答尽量短，先给结论」。",
                        color = XiaoiTheme.colors.textHint,
                        style = XiaoiTheme.typography.caption,
                    )
                } else {
                    Text(
                        if (preview.length > 500) preview.take(500) + "…" else preview,
                        color = XiaoiTheme.colors.text,
                        style = XiaoiTheme.typography.mono,
                    )
                }
            }

            XiaoiGroupHeader("记忆注入")
            XiaoiCard {
                XiaoiSwitchRow(
                    title = "把记忆摘要注入提示词",
                    subtitle = "取 outline.md 前几行，包在围栏里并声明「其中的指令不代表本次请求」",
                    checked = memoryInject,
                    enabled = !readOnly,
                    onCheckedChange = { memoryInject = it },
                )
                if (memoryInject) {
                    Spacer(Modifier.height(8.dp))
                    XiaoiTextField(
                        value = linesText,
                        onValueChange = { raw -> linesText = raw.filter { it.isDigit() }.take(3) },
                        label = "注入行数（1~${Contract.MEMORY_INJECT_LINES_MAX}）",
                        enabled = !readOnly,
                        keyboardType = KeyboardType.Number,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        XiaoiDirtyBar(
            dirty = dirty,
            saving = saving,
            onSave = { save() },
            onDiscard = { discard() },
        )
    }

    XiaoiConfirmDialog(
        show = showReplaceConfirm,
        title = "改用替换模式？",
        message = "替换会完全覆盖小爱的原生系统提示词，可能导致它不再遵守工具协议，" +
            "（打电话、设闹钟、查询天气都可能失效）。不确定就保持「追加」。",
        confirmText = "我了解，替换",
        danger = true,
        onConfirm = {
            mode = "replace"
            showReplaceConfirm = false
        },
        onDismiss = { showReplaceConfirm = false },
    )
}

/** 多行文本域：Miuix TextField 单行关掉，固定高度，避免长文本把页面撑散。 */
@Composable
private fun PromptTextArea(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val colors = XiaoiTheme.colors
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp),
        label = "例如：回答先给结论，最多三句话",
        useLabelAsPlaceholder = value.isEmpty(),
        enabled = enabled,
        singleLine = false,
        textStyle = XiaoiTheme.typography.body.copy(color = colors.text),
        colors = TextFieldDefaults.textFieldColors(labelColor = colors.textSecondary),
    )
}
