package plus.xiaoi.module.ui.screen.me

import android.content.Intent
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import plus.xiaoi.module.AppConfig
import plus.xiaoi.module.ConfigActivity
import plus.xiaoi.module.ConfigRepository
import plus.xiaoi.module.HomeActivity
import plus.xiaoi.module.core.ConfigStore
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiCodeBlock
import plus.xiaoi.module.ui.components.XiaoiCopyRow
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiInfoRow
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiTextButton
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.components.xiaoiScrollState
import plus.xiaoi.module.ui.components.xiaoiTopBarHeight
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 兼容模式（SCREENS.md §15，只读）。
 *
 * 只展示旧 config.json 的关键字段与脱敏 JSON；没有保存/导入/修改入口。
 * .corrupt/.legacy 文件名会列出，但内容不展示（可能含明文密钥）。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun LegacyScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = XiaoiTheme.colors
    var snapshot by remember { mutableStateOf<LegacySnapshot?>(null) }
    var showJson by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        snapshot = withContext(Dispatchers.IO) {
            val repo = ConfigRepository.get(context)
            val cfg = runCatching { repo.load() }.getOrNull()
            val file = repo.configFile()
            val store = ConfigStore(context.filesDir)
            val corrupt = runCatching { store.list("config.json.corrupt.") }.getOrDefault(emptyList())
            val legacy = runCatching { repo.legacyBackups() }.getOrDefault(emptyList())
            val exists = file.isFile
            LegacySnapshot(
                cfg = cfg,
                readOnly = repo.isReadOnly(),
                loadError = repo.loadError(),
                path = file.absolutePath,
                exists = exists,
                modifiedAt = if (exists) file.lastModified() else 0L,
                size = if (exists) file.length() else 0L,
                legacy = legacy,
                corrupt = corrupt,
                maskedJson = when {
                    repo.isReadOnly() -> "（配置损坏，内容已隐藏；请回「配置管理 > 损坏恢复」）"
                    !exists -> "（config.json 尚未创建，当前只是默认骨架）"
                    cfg == null -> "（无法读取配置）"
                    else -> MeSupport.maskedForDisplay(cfg)
                },
            )
        }
    }

    val snap = snapshot
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(xiaoiScrollState())
            .padding(top = xiaoiTopBarHeight() + 8.dp, bottom = 112.dp)
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        XiaoiCard {
            XiaoiBadge("只读", XiaoiTone.INFO)
            Spacer(Modifier.height(8.dp))
            Text(
                "旧版配置只读，保存请回新页面",
                color = colors.text,
                style = XiaoiTheme.typography.rowTitle,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "本页不会写入 config.json，也不提供保存、导入或删除。" +
                    "旧 ConfigActivity 仅作为过渡入口保留。",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
        }

        if (snap == null) {
            Spacer(Modifier.height(12.dp))
            XiaoiCard {
                Text("正在读取 config.json…", color = colors.textSecondary, style = XiaoiTheme.typography.body)
            }
            return@Column
        }

        XiaoiGroupHeader("文件")
        XiaoiPreferenceCard {
            XiaoiCopyRow(
                title = "config.json 路径",
                value = snap.path,
                onCopy = {
                    onMessage(
                        if (MeSupport.copy(context, "配置路径", snap.path)) "已复制配置路径"
                        else "复制失败",
                    )
                },
            )
            XiaoiInfoRow(
                title = "状态",
                subtitle = when {
                    snap.readOnly -> "只读 · ${snap.loadError.ifBlank { "无法解析" }}"
                    !snap.exists -> "尚未创建 · 当前只是默认骨架，未写入文件"
                    else -> "正常 · 可被新版配置管理读写"
                },
            )
            XiaoiInfoRow(
                title = "最后修改 / 大小",
                subtitle = "${formatTime(snap.modifiedAt)} · ${snap.size} 字节",
            )
        }

        XiaoiGroupHeader("关键字段（不含密钥）")
        XiaoiPreferenceCard {
            val cfg = snap.cfg
            XiaoiInfoRow(
                title = "模型",
                subtitle = cfg?.activeModel()?.id?.takeIf { it.isNotBlank() } ?: "未选择",
            )
            XiaoiInfoRow(
                title = "端点",
                subtitle = MeSupport.hostOf(cfg?.baseUrl()),
            )
            XiaoiInfoRow(
                title = "模型重定向",
                subtitle = if (cfg?.enabled == true) "开" else "暂停",
            )
            XiaoiInfoRow(
                title = "8771 控制面 / 8770 动作面",
                subtitle = if (cfg?.mcpEnabled == true) "开（8771 + 8770 按令牌启动）" else "关",
            )
            XiaoiInfoRow(
                title = "8771 局域网",
                subtitle = if (cfg?.mcpLan == true) "开" else "关",
            )
            XiaoiInfoRow(
                title = "8770 局域网",
                subtitle = if (cfg?.agentLan == true) "开" else "关",
            )
            XiaoiInfoRow(
                title = "令牌强度",
                subtitle = MeSupport.tokenStrength(cfg?.mcpToken),
            )
            XiaoiInfoRow(
                title = "API Key",
                subtitle = if (cfg?.apiKeyOfActive()?.isNotBlank() == true) "已设置（不显示）" else "未设置",
            )
        }

        XiaoiGroupHeader("脱敏 JSON")
        XiaoiPreferenceCard {
            XiaoiInfoRow(
                title = if (showJson) "收起 JSON" else "展开 JSON",
                subtitle = "apiKey / token 显示为 ***；不能用于还原",
                onClick = { showJson = !showJson },
            )
        }
        if (showJson) {
            Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
            XiaoiCard {
                XiaoiCodeBlock(
                    text = snap.maskedJson,
                    maxLines = 14,
                    onCopy = {
                        onMessage(
                            if (MeSupport.copy(context, "脱敏配置", snap.maskedJson)) "已复制脱敏 JSON"
                            else "复制失败",
                        )
                    },
                )
            }
        }

        XiaoiGroupHeader("备份文件")
        XiaoiPreferenceCard {
            if (snap.legacy.isEmpty()) {
                XiaoiInfoRow(
                    title = "旧配置归档",
                    subtitle = "没有 config.json.legacy.* 文件",
                )
            } else {
                snap.legacy.take(5).forEach { file ->
                    XiaoiInfoRow(
                        title = file.name,
                        subtitle = "${formatTime(file.lastModified())} · ${file.length()} 字节",
                    )
                }
            }
            if (snap.corrupt.isEmpty()) {
                XiaoiInfoRow(
                    title = "损坏原文",
                    subtitle = "没有 config.json.corrupt.* 文件",
                )
            } else {
                XiaoiInfoRow(
                    title = "损坏原文",
                    subtitle = "${snap.corrupt.size} 份 · 含明文密钥，请勿外传",
                )
                snap.corrupt.take(3).forEach { file ->
                    XiaoiInfoRow(
                        title = file.name,
                        subtitle = "${formatTime(file.lastModified())} · ${file.length()} 字节",
                    )
                }
            }
        }

        XiaoiGroupHeader("过渡入口")
        XiaoiCard {
            Text(
                "仅在 0.14 过渡期保留；新版页面不依赖旧 ConfigActivity。",
                color = colors.textHint,
                style = XiaoiTheme.typography.caption,
            )
            Spacer(Modifier.height(8.dp))
            XiaoiTextButton("打开旧版配置") {
                if (!MeSupport.open(context, Intent(context, ConfigActivity::class.java))) {
                    onMessage("无法打开旧版配置")
                }
            }
            XiaoiTextButton("打开旧版首页") {
                if (!MeSupport.open(context, Intent(context, HomeActivity::class.java))) {
                    onMessage("无法打开旧版首页")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private data class LegacySnapshot(
    val cfg: AppConfig?,
    val readOnly: Boolean,
    val loadError: String,
    val path: String,
    val exists: Boolean,
    val modifiedAt: Long,
    val size: Long,
    val legacy: List<File>,
    val corrupt: List<File>,
    val maskedJson: String,
)

private fun formatTime(at: Long): String {
    if (at <= 0L) return "未知"
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(at))
    }.getOrDefault("未知")
}
