package plus.xiaoi.module.ui.screen.xiaoai

import java.util.Locale
import plus.xiaoi.module.AppConfig

/** 表单逐项错误；空 = 该项通过。 */
internal data class ModelErrors(
    val providerName: String? = null,
    val baseUrl: String? = null,
    val modelId: String? = null,
    val apiKey: String? = null,
    val general: String? = null,
) {
    fun any(): Boolean =
        general != null || providerName != null || baseUrl != null ||
            modelId != null || apiKey != null

    fun firstMessage(): String? = general ?: providerName ?: baseUrl ?: modelId ?: apiKey
}

/** 端点校验（ModelValidator）的展示状态；不参与 D4。 */
internal data class CheckUi(
    val running: Boolean = false,
    val ok: Boolean = false,
    val message: String = "",
)

internal sealed class SaveOutcome {
    object Saved : SaveOutcome()
    object ReadOnly : SaveOutcome()
    object Conflict : SaveOutcome()
    data class Invalid(val message: String) : SaveOutcome()
    data class Failed(val message: String) : SaveOutcome()
}

/**
 * 深拷贝：手工复制而不走 JSON round-trip。
 *
 * ConfigCodec.fromJson 会 normalize（丢弃空 id / 去重），在输入过程中会把
 * 用户刚清空的模型 ID 直接吞掉，导致无法继续输入。
 */
internal fun copyConfig(cfg: AppConfig): AppConfig {
    val copy = AppConfig()
    copy.version = cfg.version
    copy.rev = cfg.rev
    copy.skeleton = cfg.skeleton
    copy.enabled = cfg.enabled
    copy.marker = cfg.marker
    copy.markerTemplate = cfg.markerTemplate
    copy.mcpEnabled = cfg.mcpEnabled
    copy.mcpLan = cfg.mcpLan
    copy.agentLan = cfg.agentLan
    copy.mcpToken = cfg.mcpToken
    copy.activeProviderId = cfg.activeProviderId
    copy.activeModelId = cfg.activeModelId
    copy.extra.putAll(cfg.extra)
    for (provider in cfg.providers) {
        val target = AppConfig.Provider()
        target.id = provider.id
        target.name = provider.name
        target.baseUrl = provider.baseUrl
        target.apiKey = provider.apiKey
        for (model in provider.models) {
            val item = AppConfig.ModelItem()
            item.id = model.id
            item.name = model.name
            target.models.add(item)
        }
        copy.providers.add(target)
    }
    return copy
}

/** provider id = slug(name)：小写、非字母数字转 -、截断 24、冲突追加 -2/-3。 */
internal fun slugProviderId(cfg: AppConfig, name: String): String {
    val builder = StringBuilder()
    for (ch in name.lowercase(Locale.ROOT)) {
        builder.append(if (ch in 'a'..'z' || ch in '0'..'9') ch else '-')
    }
    var base = builder.toString().replace(Regex("-+"), "-").trim('-')
    if (base.isEmpty()) base = "provider"
    if (base.length > 24) base = base.take(24)
    var candidate = base
    var suffixIndex = 2
    while (cfg.providers.any { it.id == candidate }) {
        val suffix = "-$suffixIndex"
        suffixIndex++
        val head = if (base.length > 24 - suffix.length) base.take(24 - suffix.length) else base
        candidate = head + suffix
    }
    return candidate
}

/**
 * 保存前校验（不联网，只判断字段与结构）：
 * - 服务商名称/同名；端点必须 http(s)（启用时不能为空）
 * - 模型 ID 非空、同一服务商内不重名
 * - 启用时必须填写 API Key；暂停允许保存不完整草稿
 *
 * 这里只给出“具体哪一项错了”，完整语义仍以 ConfigRepository.validate 为准。
 */
internal fun validateDraft(cfg: AppConfig): ModelErrors {
    val provider = cfg.activeProvider()
        ?: return ModelErrors(general = "还没有服务商，点「添加」新建一个")
    var result = ModelErrors()
    val name = provider.name.trim()
    if (name.isEmpty()) {
        result = result.copy(providerName = "名称不能为空")
    } else if (cfg.providers.any {
            it.id != provider.id && it.name.trim().equals(name, ignoreCase = true)
        }
    ) {
        result = result.copy(providerName = "已存在同名服务商")
    }
    val base = provider.baseUrl.trim()
    if (base.isEmpty()) {
        if (cfg.enabled) result = result.copy(baseUrl = "启用前需要填写端点")
    } else if (!(base.startsWith("http://") || base.startsWith("https://"))) {
        result = result.copy(baseUrl = "端点必须以 http:// 或 https:// 开头")
    }
    val models = provider.models
    val activeIndex = models.indexOfFirst { it.id == cfg.activeModelId }
    if (models.isNotEmpty()) {
        if (activeIndex < 0) {
            result = result.copy(modelId = "请选择一个模型")
        } else if (models.any { it.id.trim().isEmpty() }) {
            result = result.copy(modelId = "模型 ID 不能为空")
        } else if (models.map { it.id.trim() }.toSet().size != models.size) {
            result = result.copy(modelId = "同一服务商下已有同名模型")
        }
    } else if (cfg.enabled) {
        result = result.copy(modelId = "启用前需要添加模型")
    }
    if (cfg.enabled && provider.apiKey.trim().isEmpty()) {
        result = result.copy(apiKey = "启用前需要填写 API Key")
    }
    return result
}
