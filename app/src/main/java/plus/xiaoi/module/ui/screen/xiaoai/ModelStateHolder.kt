package plus.xiaoi.module.ui.screen.xiaoai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import plus.xiaoi.module.AppConfig
import plus.xiaoi.module.ConfigCodec
import plus.xiaoi.module.ConfigRepository
import plus.xiaoi.module.ModelValidator
import plus.xiaoi.module.StatusCache
import plus.xiaoi.module.StatusResolver
import plus.xiaoi.module.agent.AgentService

/**
 * 进程内草稿缓存：顶栏返回由壳直接 pop，页面无法拦截，所以把草稿留在内存里，
 * 再次进入本页时恢复（rev 变了就作废）。只存内存、不落盘、不写日志。
 */
private object ModelDraftCache {
    var json: String? = null
    var baseRev: Long = 0L

    fun clear() {
        json = null
        baseRev = 0L
    }
}

/**
 * 模型与服务商页状态。
 *
 * 事实源只有 [ConfigRepository]：draft 是内存副本，显式保存才写盘；
 * 保存前比对文件 rev，冲突时回到 UI 让用户选择“载入最新 / 覆盖保存”，
 * 不做静默覆盖。校验只更新 [StatusCache]（D2），不改运行态。
 */
internal class ModelStateHolder(private val context: Context) {

    var loading by mutableStateOf(true)
        private set
    var readOnly by mutableStateOf(false)
        private set
    var loadError by mutableStateOf("")
        private set
    var saving by mutableStateOf(false)
        private set
    var errors by mutableStateOf(ModelErrors())
        private set
    var check by mutableStateOf(CheckUi())
        private set
    /** 冲突时对方写入的文件 rev；null = 无冲突。 */
    var conflictRev by mutableStateOf<Long?>(null)
        private set
    var draft by mutableStateOf<AppConfig?>(null)
        private set
    var draftJson by mutableStateOf("")
        private set
    var baseRev by mutableStateOf(0L)
        private set

    private var baselineJson = ""

    val dirty: Boolean get() = draft != null && draftJson != baselineJson

    fun provider(): AppConfig.Provider? = draft?.activeProvider()

    fun model(): AppConfig.ModelItem? = draft?.activeModel()

    suspend fun load() {
        loading = true
        val loaded = runCatching {
            xiaoaiIo {
                val repo = ConfigRepository.get(context)
                val cfg = repo.loadForUi()
                Triple(cfg, repo.isReadOnly(), repo.loadError())
            }
        }.getOrNull()
        if (loaded != null) applyLoaded(loaded.first, loaded.second, loaded.third)
        loading = false
    }

    /** 冲突时载入文件版本（放弃草稿）。 */
    suspend fun loadLatest() {
        ModelDraftCache.clear()
        load()
    }

    private fun applyLoaded(cfg: AppConfig, isReadOnly: Boolean, error: String) {
        val copy = copyConfig(cfg)
        draft = copy
        draftJson = json(copy)
        baselineJson = draftJson
        baseRev = cfg.rev
        readOnly = isReadOnly
        loadError = error
        errors = ModelErrors()
        check = CheckUi()
        conflictRev = null
        if (isReadOnly) {
            ModelDraftCache.clear()
            return
        }
        // 再次进入本页时恢复未保存草稿；文件 rev 变了说明别处已写入，旧草稿作废。
        val cached = ModelDraftCache.json
        if (cached != null && cached.isNotEmpty() &&
            ModelDraftCache.baseRev == baseRev && cached != baselineJson
        ) {
            restoreDraft(cached)
        }
    }

    fun dismissConflict() {
        conflictRev = null
    }

    fun discard() {
        val baseline = runCatching { ConfigCodec.fromJson(baselineJson) }.getOrNull() ?: return
        draft = baseline
        draftJson = baselineJson
        errors = ModelErrors()
        check = CheckUi()
        conflictRev = null
        ModelDraftCache.clear()
    }

    /** 只接受与当前文件同 rev 的草稿；调用方已校验 baseline。 */
    private fun restoreDraft(raw: String) {
        val restored = runCatching { ConfigCodec.fromJson(raw) }.getOrNull() ?: return
        draft = restored
        draftJson = json(restored)
    }

    // ------------------------------------------------------------- 选择

    fun selectProvider(id: String) {
        edit { cfg ->
            val provider = cfg.providers.firstOrNull { it.id == id } ?: return@edit
            cfg.activeProviderId = provider.id
            cfg.activeModelId = provider.models.firstOrNull()?.id.orEmpty()
        }
    }

    fun selectModel(id: String) {
        edit { cfg -> cfg.activeModelId = id }
    }

    // ------------------------------------------------------------- 表单

    fun setProviderName(value: String) {
        edit { cfg -> cfg.activeProvider()?.name = value }
    }

    fun setBaseUrl(value: String) {
        edit { cfg -> cfg.activeProvider()?.baseUrl = value }
    }

    fun setApiKey(value: String) {
        edit { cfg -> cfg.activeProvider()?.apiKey = value }
    }

    fun setModelId(value: String) {
        edit { cfg ->
            val provider = cfg.activeProvider() ?: return@edit
            val index = provider.models.indexOfFirst { it.id == cfg.activeModelId }
            if (index < 0) return@edit
            val model = provider.models[index]
            if (model.name == model.id) model.name = value
            model.id = value
            cfg.activeModelId = value
        }
    }

    // ------------------------------------------------------------- 增删

    fun addProvider(name: String, url: String): String? {
        val title = name.trim()
        val endpoint = url.trim()
        if (title.isEmpty()) return "服务商名称不能为空"
        if (!(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
            return "端点必须以 http:// 或 https:// 开头"
        }
        val cfg = draft ?: return "尚未加载配置"
        if (cfg.providers.any { it.name.equals(title, ignoreCase = true) }) {
            return "已存在同名服务商"
        }
        edit { next ->
            val hadActive = next.activeProvider() != null && next.activeModel() != null
            val provider = AppConfig.Provider(slugProviderId(next, title), title, endpoint, "")
            next.providers.add(provider)
            if (!hadActive) {
                next.activeProviderId = provider.id
                next.activeModelId = ""
            }
        }
        return null
    }

    fun deleteProvider(id: String) {
        edit { cfg ->
            if (cfg.providers.size <= 1) return@edit
            val index = cfg.providers.indexOfFirst { it.id == id }
            if (index < 0) return@edit
            val wasActive = cfg.activeProviderId == id
            cfg.providers.removeAt(index)
            if (wasActive) {
                val first = cfg.providers.first()
                cfg.activeProviderId = first.id
                cfg.activeModelId = first.models.firstOrNull()?.id.orEmpty()
            }
        }
    }

    fun addModel(providerId: String, modelId: String, name: String): String? {
        val id = modelId.trim()
        if (id.isEmpty()) return "模型 ID 不能为空"
        val cfg = draft ?: return "尚未加载配置"
        val provider = cfg.providers.firstOrNull { it.id == providerId } ?: return "先添加服务商"
        if (provider.models.any { it.id == id }) return "该服务商下已有同名模型"
        edit { next ->
            val target = next.providers.firstOrNull { it.id == providerId } ?: return@edit
            target.models.add(AppConfig.ModelItem(id, name.trim()))
            if (next.activeModelId.isEmpty() || next.activeProviderId != providerId) {
                next.activeProviderId = providerId
                next.activeModelId = id
            }
        }
        return null
    }

    fun deleteModel(providerId: String, modelId: String) {
        edit { cfg ->
            val provider = cfg.providers.firstOrNull { it.id == providerId } ?: return@edit
            val index = provider.models.indexOfFirst { it.id == modelId }
            if (index < 0) return@edit
            provider.models.removeAt(index)
            if (cfg.activeProviderId == providerId && cfg.activeModelId == modelId) {
                cfg.activeModelId = provider.models.getOrNull(
                    if (provider.models.isEmpty()) -1 else minOf(index, provider.models.size - 1),
                )?.id.orEmpty()
            }
        }
    }

    // ------------------------------------------------------------- 校验 / 保存

    /** 端点连通性校验：只写 StatusCache（D2），不保存文件、不改变运行态。 */
    suspend fun checkEndpoint(onMessage: (String) -> Unit) {
        val cfg = draft ?: return
        val provider = cfg.activeProvider()
        val model = cfg.activeModel()
        val base = provider?.baseUrl?.trim().orEmpty()
        val key = provider?.apiKey?.trim().orEmpty()
        val modelId = model?.id?.trim().orEmpty()
        val missing = when {
            base.isEmpty() -> "端点不能为空"
            !(base.startsWith("http://") || base.startsWith("https://")) -> "端点必须以 http(s):// 开头"
            modelId.isEmpty() -> "模型 ID 不能为空"
            key.isEmpty() -> "先填写 API Key"
            else -> null
        }
        if (missing != null) {
            check = CheckUi(message = missing)
            onMessage("未校验：$missing")
            return
        }
        check = CheckUi(running = true, message = "正在请求 $base")
        val result = runCatching {
            xiaoaiIo { ModelValidator.check(base, key, modelId) }
        }.getOrNull()
        if (result == null) {
            check = CheckUi(message = "校验失败：无法完成请求")
            onMessage("校验失败，请检查网络")
            return
        }
        check = CheckUi(ok = result.ok, message = result.message)
        // 只记录 D2 结论；配置一变指纹失配，缓存自动作废。
        xiaoaiIo {
            StatusCache.saveProbe(
                context,
                StatusCache.fingerprint(base, key, modelId),
                when (result.code) {
                    "ok" -> StatusResolver.Probe.OK
                    "auth" -> StatusResolver.Probe.AUTH
                    "model" -> StatusResolver.Probe.MODEL
                    else -> StatusResolver.Probe.ENDPOINT
                },
            )
        }
    }

    suspend fun save(force: Boolean = false): SaveOutcome {
        val cfg = draft ?: return SaveOutcome.Failed("尚未加载配置")
        val localErrors = validateDraft(cfg)
        errors = localErrors
        if (localErrors.any()) {
            return SaveOutcome.Invalid(localErrors.firstMessage() ?: "请先修正表单")
        }
        saving = true
        return try {
            val repo = ConfigRepository.get(context)
            val latest = xiaoaiIo { repo.load() }
            if (repo.isReadOnly) {
                readOnly = true
                loadError = repo.loadError()
                SaveOutcome.ReadOnly
            } else if (!force && !latest.skeleton && latest.rev != baseRev) {
                conflictRev = latest.rev
                SaveOutcome.Conflict
            } else {
                val invalid = repo.validate(cfg)
                if (invalid != null) {
                    errors = errors.copy(general = invalid)
                    SaveOutcome.Invalid(invalid)
                } else {
                    val saved = xiaoaiIo {
                        repo.save(cfg)
                        repo.load()
                    }
                    val copy = copyConfig(saved)
                    draft = copy
                    draftJson = json(copy)
                    baselineJson = draftJson
                    baseRev = saved.rev
                    conflictRev = null
                    check = CheckUi()
                    ModelDraftCache.clear()
                    // 与旧配置页一致：保存后按配置对齐 8770（本页不改 MCP 开关）。
                    AgentService.startIfEnabled(context)
                    SaveOutcome.Saved
                }
            }
        } catch (t: Throwable) {
            SaveOutcome.Failed("保存失败，请检查文件是否可写")
        } finally {
            saving = false
        }
    }

    // ------------------------------------------------------------- 基础设施

    /** 每次编辑都基于副本，避免 Compose 读到同一对象时漏掉重组。 */
    private fun edit(block: (AppConfig) -> Unit) {
        val current = draft ?: return
        val next = copyConfig(current)
        block(next)
        draft = next
        draftJson = json(next)
        ModelDraftCache.json = draftJson
        ModelDraftCache.baseRev = baseRev
        if (errors.any()) errors = ModelErrors()
    }

    private fun json(cfg: AppConfig): String = runCatching { ConfigCodec.toJson(cfg) }.getOrDefault("")

}
