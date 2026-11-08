package plus.xiaoi.module.ui.screen.me

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import plus.xiaoi.module.AppConfig
import plus.xiaoi.module.ConfigRepository
import plus.xiaoi.module.core.IssueInputFactory
import plus.xiaoi.module.core.IssueResolver
import java.io.File

/**
 * 我的域的纯工具：版本、脱敏、摘要、复制、Root 检测。
 *
 * 约束：这里不读取/写入 config.json（读写只由 [ConfigRepository] 负责），
 * 不输出 apiKey / mcpToken，不把完整 URL 的 query、userinfo 带进界面或剪贴板。
 */
internal object MeSupport {

    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    fun versionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    /** 只保留 host；解析失败时手工剥离 query/userinfo/path，绝不把完整 URL 展示出去。 */
    fun hostOf(url: String?): String {
        val v = url?.trim().orEmpty()
        if (v.isEmpty()) return "未填写"
        runCatching { java.net.URL(v).host }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        var s = v.substringBefore('?')
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        val at = s.lastIndexOf('@')
        if (at >= 0) s = s.substring(at + 1)
        s = s.substringBefore('/')
        return s.ifEmpty { "未填写" }
    }

    /** 令牌强度文案；只给结论，不回显令牌。 */
    fun tokenStrength(token: String?): String = when (IssueInputFactory.tokenStrength(token)) {
        IssueResolver.TokenStrength.NONE -> "未设置"
        IssueResolver.TokenStrength.WEAK -> "偏弱（局域网会回退仅本机）"
        IssueResolver.TokenStrength.STRONG -> "32 位十六进制"
    }

    /**
     * 脱敏 JSON：apiKey/token 替换为 `***`，不能用于还原（ConfigCodec.containsMaskedSecrets）。
     * token 即使为空也写 `***`，保证任何脱敏导出都不可被当成有效备份。
     */
    fun maskedForExport(c: AppConfig): String = mask(c, forceTokenMask = true)

    /** 兼容模式展示用：只遮蔽真实存在的密钥，避免把“未设置”显示成“已掩码”。 */
    fun maskedForDisplay(c: AppConfig): String = mask(c, forceTokenMask = false)

    private fun mask(c: AppConfig, forceTokenMask: Boolean): String {
        return try {
            val o = ConfigRepository.toJson(c)
            val providers = o.optJSONArray("providers")
            if (providers != null) {
                for (i in 0 until providers.length()) {
                    val p = providers.optJSONObject(i) ?: continue
                    if (p.optString("apiKey").isNotEmpty()) p.put("apiKey", "***")
                }
            }
            val mcp: JSONObject? = o.optJSONObject("mcp")
            if (mcp != null && (forceTokenMask || mcp.optString("token").isNotEmpty())) {
                mcp.put("token", "***")
            }
            o.toString(2)
        } catch (t: Throwable) {
            "{\n  \"error\": \"配置无法序列化\"\n}"
        }
    }

    /** 覆盖导入前的摘要：只列非敏感事实，不复述密钥/令牌/完整 URL。 */
    fun importSummary(c: AppConfig): String {
        val p = c.activeProvider()
        val m = c.activeModel()
        val sb = StringBuilder()
        sb.append("服务商：").append(c.providers.size).append(" 个")
        if (p != null && p.name.isNotBlank()) sb.append("（当前 ").append(p.name.trim()).append("）")
        sb.append('\n')
        sb.append("模型：").append(m?.id?.takeIf { it.isNotBlank() } ?: "未选择").append('\n')
        sb.append("端点：").append(hostOf(p?.baseUrl)).append('\n')
        sb.append("API Key：").append(if (p != null && p.apiKey.trim().isNotEmpty()) "已设置" else "未设置")
        sb.append('\n')
        sb.append("MCP Token：").append(if (c.mcpToken.trim().isNotEmpty()) "已设置" else "未设置")
        sb.append('\n')
        sb.append("启用：").append(if (c.enabled) "开" else "暂停").append('\n')
        sb.append("版本：v").append(c.version).append(" · rev ").append(c.rev)
        return sb.toString()
    }

    fun copy(context: Context, label: String, text: String): Boolean {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun readClipboard(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount <= 0) return null
            clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    fun open(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * “Root 已安装”检测：只查常见 su 路径，不执行 su、不申请授权。
     * 找不到就返回 false → 页面显示「未检测到」，绝不写成「已授权」。
     */
    fun rootInstalled(): Boolean {
        val paths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su",
        )
        return try {
            paths.any { File(it).exists() }
        } catch (t: Throwable) {
            false
        }
    }
}
