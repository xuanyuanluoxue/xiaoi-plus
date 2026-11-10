package plus.xiaoi.module.ui.screen.me

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import plus.xiaoi.module.AppConfig
import plus.xiaoi.module.ConfigCodec
import plus.xiaoi.module.ConfigRepository
import plus.xiaoi.module.ConfigSync
import plus.xiaoi.module.ShareProvider
import plus.xiaoi.module.agent.AgentService
import plus.xiaoi.module.core.ErrorCatalog
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiCardGroup
import plus.xiaoi.module.ui.components.XiaoiConfirmDialog
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiIconTone
import plus.xiaoi.module.ui.components.XiaoiInfoRow
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.components.xiaoiScrollState
import plus.xiaoi.module.ui.components.xiaoiTopBarHeight
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Paste
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import java.io.File

/**
 * 配置管理（SCREENS.md §11）。
 *
 * 所有写操作都先校验再 [ConfigRepository] 原子写入：
 * - 还原失败（解析/校验/写入）不覆盖现有 config.json
 * - 脱敏副本（apiKey/token = ***）拒绝还原
 * - 配置损坏时本页只显示恢复入口，不提供覆盖式保存
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun ConfigManageScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = XiaoiTheme.colors
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf<ConfigSnapshot?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var showFullBackupConfirm by remember { mutableStateOf(false) }
    // 跨 Activity 重建（如打开 SAF 后旋转屏幕）保留导出模式，避免脱敏导出变成完整备份。
    var exportMasked by rememberSaveable { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var showRuntimeImportConfirm by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf<RecoveryKind?>(null) }

    fun reload() {
        refresh++
    }

    LaunchedEffect(refresh) {
        snapshot = withContext(Dispatchers.IO) {
            val repo = ConfigRepository.get(context)
            val cfg = runCatching { repo.load() }.getOrNull()
            ConfigSnapshot(
                cfg = cfg,
                readOnly = repo.isReadOnly(),
                loadError = repo.loadError(),
                legacyBackups = runCatching { repo.legacyBackups() }.getOrDefault(emptyList()),
            )
        }
    }

    // ---------------------------------------------------------------- 文件导出

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val masked = exportMasked
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val repo = ConfigRepository.get(context)
                    if (repo.isReadOnly()) throw IllegalStateException("配置只读，已取消导出")
                    val cfg = repo.load()
                    val text = if (masked) {
                        MeSupport.maskedForExport(cfg)
                    } else {
                        ConfigRepository.toJson(cfg).toString(2)
                    }
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("无法打开目标文件")
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }.exceptionOrNull()
            }
            onMessage(
                when {
                    error != null -> "导出失败：${error.message ?: error.javaClass.simpleName}"
                    masked -> "已导出脱敏副本（不能用于还原）"
                    else -> "已导出完整备份（含明文密钥，用完请删除）"
                },
            )
        }
    }

    // ---------------------------------------------------------------- 文件还原

    fun prepareImport(text: String?) {
        if (text.isNullOrBlank()) {
            onMessage("文件为空或读取失败")
            return
        }
        if (text.length > MAX_IMPORT_BYTES) {
            onMessage("内容超过 2 MB，请确认是配置文件")
            return
        }
        scope.launch {
            val parsed = withContext(Dispatchers.IO) { parseImport(text) }
            if (parsed.error != null) {
                onMessage(parsed.error)
            } else if (parsed.config != null) {
                pendingImport = PendingImport(parsed.config, parsed.source)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        if (bytes.size > MAX_IMPORT_BYTES) {
                            throw IllegalStateException("文件超过 2 MB，请确认是配置文件")
                        }
                        bytes.toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            prepareImport(text)
        }
    }

    // ---------------------------------------------------------------- 执行动作

    fun applyImport(pending: PendingImport) {
        busy = true
        pendingImport = null
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val repo = ConfigRepository.get(context)
                    if (repo.isReadOnly()) {
                        "配置处于只读态，已拒绝写入；请先用下方「损坏恢复」"
                    } else {
                        repo.save(pending.config)
                        AgentService.startIfEnabled(context)
                        "已还原并应用（rev 已递增，等待小爱重新加载）"
                    }
                } catch (t: Throwable) {
                    "还原失败：${t.message ?: t.javaClass.simpleName}（现有配置未改动）"
                }
            }
            busy = false
            onMessage(message)
            reload()
        }
    }

    fun runRecovery(kind: RecoveryKind) {
        recovery = null
        busy = true
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val repo = ConfigRepository.get(context)
                    when (kind) {
                        RecoveryKind.LEGACY -> {
                            val backup = repo.legacyBackups().firstOrNull()
                                ?: throw IllegalStateException("没有可恢复的旧配置")
                            repo.restoreLegacy(backup)
                            AgentService.startIfEnabled(context)
                            "已从 ${backup.name} 恢复"
                        }

                        RecoveryKind.DEFAULTS -> {
                            repo.resetToDefaultsAfterCorruption()
                            AgentService.startIfEnabled(context)
                            "已重建默认配置；损坏原文保留为 .corrupt 备份"
                        }
                    }
                } catch (t: Throwable) {
                    "恢复失败：${t.message ?: t.javaClass.simpleName}（原文件未改动）"
                }
            }
            busy = false
            onMessage(message)
            reload()
        }
    }

    fun shareMasked() {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val repo = ConfigRepository.get(context)
                    if (repo.isReadOnly()) throw IllegalStateException("配置只读，已取消分享")
                    val cfg = repo.load()
                    val base = context.getExternalFilesDir(null)
                        ?: throw IllegalStateException("外部文件目录不可用")
                    val dir = File(base, "exports")
                    if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("无法创建导出目录")
                    val file = File(dir, "xiaoi-plus-config-masked.json")
                    file.writeText(MeSupport.maskedForExport(cfg), Charsets.UTF_8)
                    Uri.parse("content://${ShareProvider.AUTHORITY}/${file.name}")
                }
            }
            val uri = outcome.getOrNull()
            if (uri == null) {
                onMessage("分享失败：${outcome.exceptionOrNull()?.message ?: "无法生成脱敏文件"}")
                return@launch
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (!MeSupport.open(context, android.content.Intent.createChooser(intent, "分享配置（已脱敏）"))) {
                onMessage("没有可用的分享目标")
            }
        }
    }

    val snap = snapshot
    val readOnly = snap?.readOnly ?: false

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(xiaoiScrollState())
            .padding(top = xiaoiTopBarHeight() + 8.dp, bottom = 112.dp)
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        if (snap == null) {
            XiaoiCard {
                Text("正在读取配置…", color = colors.textSecondary, style = XiaoiTheme.typography.body)
            }
            return@Column
        }

        if (readOnly) {
            XiaoiCard {
                XiaoiBadge("配置只读", XiaoiTone.ERROR)
                Spacer(Modifier.height(8.dp))
                Text(
                    snap.loadError.takeIf { it.isNotBlank() } ?: "config.json 无法解析",
                    color = colors.text,
                    style = XiaoiTheme.typography.rowTitle,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "原文件已保留在应用私有目录（可能含明文密钥），本页不会覆盖或删除它。" +
                        "请选择下方恢复方式；普通备份/还原/导入已暂时禁用。",
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.body,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        if (readOnly) {
            XiaoiGroupHeader("损坏恢复")
            XiaoiCardGroup {
                val restore = snap.legacyBackups.firstOrNull()
                if (restore != null) {
                    XiaoiPreferenceCard {
                        XiaoiInfoRow(
                            title = "恢复最近旧配置",
                            subtitle = "${restore.name} · 校验通过后才原子写入",
                            onClick = { recovery = RecoveryKind.LEGACY },
                            icon = MiuixIcons.Backup,
                            iconTone = XiaoiIconTone.PURPLE,
                        )
                    }
                } else {
                    XiaoiPreferenceCard {
                        XiaoiInfoRow(
                            title = "没有可恢复的旧配置",
                            subtitle = "继续使用「重建默认配置」，原文件会保留为 .corrupt",
                        )
                    }
                }
                XiaoiPreferenceCard {
                    XiaoiInfoRow(
                        title = "重建默认配置",
                        subtitle = "默认骨架 + 当前 prefs；损坏原文移入 .corrupt 备份",
                        onClick = { recovery = RecoveryKind.DEFAULTS },
                        icon = MiuixIcons.Refresh,
                        iconTone = XiaoiIconTone.RED,
                        badge = "写入",
                        badgeTone = XiaoiTone.WARNING,
                    )
                }
            }
            Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
            XiaoiCard {
                Text(
                    "恢复操作需要二次确认，且只会在校验通过后写入；" +
                        "失败的恢复不会改动现有 config.json。",
                    color = colors.textHint,
                    style = XiaoiTheme.typography.caption,
                )
            }
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        XiaoiGroupHeader("当前配置")
        XiaoiPreferenceCard {
            XiaoiInfoRow(
                title = "config.json",
                subtitle = buildString {
                    append("v").append(snap.cfg?.version ?: "?")
                    append(" · rev ").append(snap.cfg?.rev ?: 0)
                    append(" · ").append(snap.cfg?.providers?.size ?: 0).append(" 个服务商")
                    append(" · 令牌").append(
                        if (snap.cfg?.mcpToken?.isNotBlank() == true) "已设置" else "未设置",
                    )
                },
                icon = MiuixIcons.File,
                iconTone = XiaoiIconTone.PURPLE,
            )
        }

        XiaoiGroupHeader("备份与还原")
        XiaoiCardGroup {
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "备份（含密钥）",
                    subtitle = "完整 JSON · 含明文 API Key 与 MCP Token",
                    onClick = { showFullBackupConfirm = true },
                    icon = MiuixIcons.Backup,
                    iconTone = XiaoiIconTone.PURPLE,
                    badge = "含密钥",
                    badgeTone = XiaoiTone.WARNING,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "还原配置",
                    subtitle = "选择 JSON；校验失败或脱敏文件不会写入",
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    icon = MiuixIcons.Import,
                    iconTone = XiaoiIconTone.RED,
                    badge = "覆盖",
                    badgeTone = XiaoiTone.WARNING,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "从剪贴板还原",
                    subtitle = "粘贴 JSON 后先校验，确认后才覆盖",
                    onClick = {
                        val text = MeSupport.readClipboard(context)
                        if (text == null) onMessage("剪贴板里没有可用的 JSON") else prepareImport(text)
                    },
                    icon = MiuixIcons.Paste,
                    iconTone = XiaoiIconTone.RED,
                    badge = "覆盖",
                    badgeTone = XiaoiTone.WARNING,
                )
            }
        }

        XiaoiGroupHeader("导出与分享")
        XiaoiCardGroup {
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "导出（脱敏）",
                    subtitle = "密钥与令牌写成 ***；只能排障，不能还原",
                    onClick = {
                        exportMasked = true
                        exportLauncher.launch("xiaoi-plus-config-masked.json")
                    },
                    icon = MiuixIcons.UploadCloud,
                    iconTone = XiaoiIconTone.PURPLE,
                )
            }
            XiaoiPreferenceCard {
                XiaoiInfoRow(
                    title = "分享（脱敏）",
                    subtitle = "通过系统分享；文件可能被转发或留存",
                    onClick = { shareMasked() },
                    icon = MiuixIcons.Share,
                    iconTone = XiaoiIconTone.PURPLE,
                )
            }
        }

        XiaoiGroupHeader("从运行中的小爱导入")
        XiaoiPreferenceCard {
            XiaoiInfoRow(
                title = "从运行中的小爱导入",
                subtitle = "读取当前生效的模型/端点；密钥沿用当前文件",
                onClick = { showRuntimeImportConfirm = true },
                icon = MiuixIcons.Refresh,
                iconTone = XiaoiIconTone.ORANGE,
                badge = "写入",
                badgeTone = XiaoiTone.INFO,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "还原与导入都会写入 config.json 并请求小爱重新加载；令牌、日志与旧备份不会被删除。",
            color = colors.textHint,
            style = XiaoiTheme.typography.caption,
        )
        Spacer(Modifier.height(24.dp))
    }

    XiaoiConfirmDialog(
        show = showFullBackupConfirm,
        title = "备份包含明文密钥",
        message = "文件将包含明文 API Key 与 MCP Token，可用于换机或回滚恢复。" +
            "任何拿到文件的人都能使用这些凭据，请只在可信位置保存。",
        confirmText = "仍然导出",
        secondaryNote = "只做排障请改用「导出（脱敏）」；脱敏副本不能还原。",
        danger = true,
        onConfirm = {
            showFullBackupConfirm = false
            exportMasked = false
            exportLauncher.launch("xiaoi-plus-config.json")
        },
        onDismiss = { showFullBackupConfirm = false },
    )

    XiaoiConfirmDialog(
        show = showRuntimeImportConfirm,
        title = "从运行中的小爱导入？",
        message = "将把当前生效的模型与端点写入 config.json，密钥沿用当前文件；" +
            "随后请求小爱重新加载。若小爱未运行或 MCP 未开启，导入会失败且不写文件。",
        confirmText = "开始导入",
        secondaryNote = "导入是文件写入，不会清空令牌或旧备份。",
        danger = false,
        onConfirm = {
            showRuntimeImportConfirm = false
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { ConfigSync.importFromRuntime(context) }
                busy = false
                onMessage(
                    if (ok) "已从运行中的小爱导入（密钥沿用当前文件）"
                    else ErrorCatalog.message(ErrorCatalog.Code.NO_XIAOI),
                )
                reload()
            }
        },
        onDismiss = { showRuntimeImportConfirm = false },
    )

    ImportConfirmDialog(
        pending = pendingImport,
        busy = busy,
        onDismiss = { pendingImport = null },
        onConfirm = { applyImport(it) },
    )
    RecoveryDialogs(
        recovery = recovery,
        onDismiss = { recovery = null },
        onConfirm = { runRecovery(it) },
    )
}

// ---------------------------------------------------------------- 数据与对话框

private data class ConfigSnapshot(
    val cfg: AppConfig?,
    val readOnly: Boolean,
    val loadError: String,
    val legacyBackups: List<File>,
)

private data class PendingImport(val config: AppConfig, val source: String)

private data class ParsedImport(
    val config: AppConfig? = null,
    val error: String? = null,
    val source: String = "JSON",
)

private enum class RecoveryKind { LEGACY, DEFAULTS }

/** 配置文件读取上限：超过 2 MB 基本可以确定不是 config.json，避免无界读入。 */
private const val MAX_IMPORT_BYTES = 2 * 1024 * 1024

/**
 * 解析 + 校验；任何失败只返回错误文案，不触碰现有文件。
 * 脱敏文件在这里被拒绝，避免把 `***` 写成有效密钥。
 */
private fun parseImport(text: String): ParsedImport {
    return try {
        val cfg = ConfigCodec.fromJson(text)
        if (ConfigCodec.containsMaskedSecrets(cfg)) {
            return ParsedImport(error = ErrorCatalog.message(ErrorCatalog.Code.IMPORT_MASKED))
        }
        val error = ConfigCodec.validate(cfg)
        if (error != null) {
            ParsedImport(error = "配置无效：$error")
        } else {
            ParsedImport(config = cfg)
        }
    } catch (t: Throwable) {
        ParsedImport(error = "无法解析 JSON：${t.message ?: t.javaClass.simpleName}")
    }
}

@Composable
private fun ImportConfirmDialog(
    pending: PendingImport?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (PendingImport) -> Unit,
) {
    XiaoiConfirmDialog(
        show = pending != null,
        title = "覆盖当前配置？",
        message = pending?.let { MeSupport.importSummary(it.config) }.orEmpty(),
        confirmText = if (busy) "正在写入…" else "覆盖导入",
        secondaryNote = "只有校验通过的配置才会原子写入；失败时现有配置不变。",
        danger = true,
        onConfirm = {
            if (!busy && pending != null) onConfirm(pending)
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun RecoveryDialogs(
    recovery: RecoveryKind?,
    onDismiss: () -> Unit,
    onConfirm: (RecoveryKind) -> Unit,
) {
    val title = when (recovery) {
        RecoveryKind.LEGACY -> "恢复最近旧配置？"
        RecoveryKind.DEFAULTS -> "重建默认配置？"
        null -> ""
    }
    val message = when (recovery) {
        RecoveryKind.LEGACY ->
            "将解析并校验最近一份旧配置，通过后原子写入 config.json。" +
                "失败不会改动现有文件；旧备份不会被删除。"

        RecoveryKind.DEFAULTS ->
            "将把当前损坏的 config.json 移入 .corrupt 备份，" +
                "再写入基于当前 prefs 的默认配置。损坏原文不会删除。"

        null -> ""
    }
    XiaoiConfirmDialog(
        show = recovery != null,
        title = title,
        message = message,
        confirmText = when (recovery) {
            RecoveryKind.LEGACY -> "恢复"
            RecoveryKind.DEFAULTS -> "重建"
            null -> "确定"
        },
        secondaryNote = "损坏文件可能含明文密钥：不要外传 .corrupt 备份。",
        danger = true,
        onConfirm = { recovery?.let(onConfirm) },
        onDismiss = onDismiss,
    )
}

// 页面只读 config.json，不直接操作文件；所有写入都经 ConfigRepository。
