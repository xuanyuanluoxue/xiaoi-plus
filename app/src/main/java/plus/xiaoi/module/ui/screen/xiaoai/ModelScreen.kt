package plus.xiaoi.module.ui.screen.xiaoai

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import plus.xiaoi.module.ui.components.ConfirmCard
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiDirtyBar
import plus.xiaoi.module.ui.components.XiaoiLoadingSkeleton
import plus.xiaoi.module.ui.components.XiaoiTextButton
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

/**
 * 模型与服务商：0.14 唯一可编辑的模型表单。
 *
 * - 只改内存草稿；「保存」才写 [plus.xiaoi.module.ConfigRepository]
 * - 保存前比对文件 rev，冲突必须显式选择“载入最新 / 覆盖保存”
 * - 只读（配置损坏）时整页禁用，不提供保存
 * - 密钥只展示掩码，不复制、不写日志、不进 Snackbar
 */
@Composable
fun ModelScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val holder = remember { ModelStateHolder(context) }
    val scope = rememberCoroutineScope()
    val editable = !holder.readOnly

    var showDiscard by remember { mutableStateOf(false) }
    var discardThenExit by remember { mutableStateOf(false) }
    var showAddProvider by remember { mutableStateOf(false) }
    var showAddModel by remember { mutableStateOf(false) }
    var showOverwrite by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DeleteTarget?>(null) }
    var exiting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        holder.load()
    }
    BackHandler(enabled = holder.dirty && !exiting) {
        discardThenExit = true
        showDiscard = true
    }
    LaunchedEffect(exiting) {
        if (exiting) {
            // 等 BackHandler 的 enabled=false 生效后再交给壳，避免二次拦截。
            withFrameNanos { }
            goBack(activity)
        }
    }

    fun save(force: Boolean = false) {
        scope.launch {
            when (val outcome = holder.save(force)) {
                SaveOutcome.Saved -> {
                    onMessage("已保存，等待小爱应用")
                    exiting = true
                }

                SaveOutcome.Conflict -> showOverwrite = true
                SaveOutcome.ReadOnly -> onMessage("配置已损坏，保存被阻止")
                is SaveOutcome.Invalid -> onMessage("未保存：${outcome.message}")
                is SaveOutcome.Failed -> onMessage(outcome.message)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (holder.loading) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = XiaoiTheme.dimens.pagePad)
                        .padding(top = XiaoiTheme.dimens.pagePadTop),
                ) {
                    XiaoiLoadingSkeleton(height = 96.dp)
                    Spacer(Modifier.height(12.dp))
                    XiaoiLoadingSkeleton(height = 96.dp)
                    Spacer(Modifier.height(12.dp))
                    XiaoiLoadingSkeleton(height = 240.dp)
                }
            } else {
                if (holder.draft == null) {
                    LoadFailedCard(onRetry = { scope.launch { holder.load() } })
                } else {
                    ModelContent(
                        holder = holder,
                        editable = editable,
                        showAddProvider = showAddProvider,
                        showAddModel = showAddModel,
                        onNavigate = onNavigate,
                        onMessage = onMessage,
                        onCheck = { scope.launch { holder.checkEndpoint(onMessage) } },
                        onDelete = { pendingDelete = it },
                        onToggleAddProvider = { showAddProvider = !showAddProvider },
                        onToggleAddModel = { showAddModel = !showAddModel },
                        onLoadLatest = {
                            scope.launch {
                                holder.loadLatest()
                                onMessage("已载入文件版本")
                            }
                        },
                        onDismissConflict = { holder.dismissConflict() },
                    )
                }
            }
        }
        XiaoiDirtyBar(
            dirty = holder.dirty,
            saving = holder.saving,
            onSave = { save() },
            onDiscard = {
                discardThenExit = false
                showDiscard = true
            },
        )
    }

    ModelDialogs(
        showDiscard = showDiscard,
        discardThenExit = discardThenExit,
        onConfirmDiscard = {
            showDiscard = false
            holder.discard()
            if (discardThenExit) exiting = true
            discardThenExit = false
        },
        onDismissDiscard = {
            showDiscard = false
            discardThenExit = false
        },
        showOverwrite = showOverwrite,
        baseRev = holder.baseRev,
        conflictRev = holder.conflictRev,
        onConfirmOverwrite = {
            showOverwrite = false
            save(force = true)
        },
        onDismissOverwrite = { showOverwrite = false },
        deleteTarget = pendingDelete,
        activeModelId = holder.draft?.activeModelId.orEmpty(),
        onConfirmDelete = { target ->
            when (target) {
                is DeleteTarget.Provider -> holder.deleteProvider(target.provider.id)
                is DeleteTarget.Model -> holder.deleteModel(target.provider.id, target.model.id)
            }
            pendingDelete = null
            onMessage("已删除，保存后生效")
        },
        onDismissDelete = { pendingDelete = null },
    )

}

@Composable
private fun ModelContent(
    holder: ModelStateHolder,
    editable: Boolean,
    showAddProvider: Boolean,
    showAddModel: Boolean,
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
    onCheck: () -> Unit,
    onDelete: (DeleteTarget) -> Unit,
    onToggleAddProvider: () -> Unit,
    onToggleAddModel: () -> Unit,
    onLoadLatest: () -> Unit,
    onDismissConflict: () -> Unit,
) {
    val colors = XiaoiTheme.colors
    val draft = holder.draft ?: return
    val provider = draft.activeProvider()
    val model = draft.activeModel()
    val conflictRev = holder.conflictRev

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        if (holder.readOnly) {
            ReadOnlyCard(loadError = holder.loadError, onNavigate = onNavigate)
            Spacer(Modifier.height(12.dp))
        }
        if (conflictRev != null) {
            ConfirmCard(
                title = "配置已被其他页面修改",
                message = "文件 rev ${holder.baseRev} → $conflictRev。" +
                    "保存前必须选择：载入文件版本（放弃草稿），或显式覆盖保存。",
                confirmText = "载入最新（放弃草稿）",
                onConfirm = onLoadLatest,
                onDismiss = onDismissConflict,
            )
            Spacer(Modifier.height(12.dp))
        }

        SectionHeader(
            title = "服务商",
            actionText = if (editable) "添加" else null,
            actionEnabled = editable,
            onAction = onToggleAddProvider,
        )
        ProviderListSection(
            providers = draft.providers,
            activeProviderId = draft.activeProviderId,
            editable = editable,
            onSelect = holder::selectProvider,
            onDelete = { onDelete(DeleteTarget.Provider(it)) },
        )
        if (showAddProvider && editable) {
            Spacer(Modifier.height(8.dp))
            AddProviderCard(
                onAdd = { name, url ->
                    val problem = holder.addProvider(name, url)
                    if (problem == null) onMessage("已添加服务商，记得保存")
                    problem
                },
                onCancel = onToggleAddProvider,
            )
        }

        SectionHeader(
            title = "模型",
            actionText = if (editable && provider != null) "添加" else null,
            actionEnabled = editable && provider != null,
            onAction = onToggleAddModel,
        )
        ModelListSection(
            provider = provider,
            activeModelId = draft.activeModelId,
            editable = editable,
            onSelect = holder::selectModel,
            onDelete = { selectedModel ->
                provider?.let { onDelete(DeleteTarget.Model(it, selectedModel)) }
            },
        )
        if (showAddModel && editable && provider != null) {
            Spacer(Modifier.height(8.dp))
            AddModelCard(
                providerName = provider.name,
                onAdd = { id, name ->
                    val problem = holder.addModel(provider.id, id, name)
                    if (problem == null) onMessage("已添加模型，记得保存")
                    problem
                },
                onCancel = onToggleAddModel,
            )
        }

        SectionHeader("服务商设置")
        ModelFormCard(
            provider = provider,
            model = model,
            errors = holder.errors,
            check = holder.check,
            editable = editable,
            onNameChange = holder::setProviderName,
            onBaseUrlChange = holder::setBaseUrl,
            onModelIdChange = holder::setModelId,
            onApiKeyChange = holder::setApiKey,
            onCheck = onCheck,
        )
        if (!draft.enabled) {
            Spacer(Modifier.height(8.dp))
            Text(
                "当前为暂停状态：可以保存不完整的草稿；启用前需要端点、模型与密钥齐全。",
                color = colors.textHint,
                style = XiaoiTheme.typography.caption,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LoadFailedCard(onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop),
    ) {
        XiaoiCard {
            XiaoiBadge("未载入", XiaoiTone.WARNING)
            Spacer(Modifier.height(8.dp))
            Text(
                "配置读取失败",
                color = XiaoiTheme.colors.text,
                style = XiaoiTheme.typography.rowTitle,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "没有改动任何文件；可以重试读取，或到「我的 › 配置管理」检查。",
                color = XiaoiTheme.colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
            Spacer(Modifier.height(10.dp))
            XiaoiTextButton(text = "重新载入", onClick = onRetry)
        }
    }
}

@Composable
private fun ReadOnlyCard(loadError: String, onNavigate: (String) -> Unit) {
    val colors = XiaoiTheme.colors
    XiaoiCard {
        XiaoiBadge("只读", XiaoiTone.ERROR)
        Spacer(Modifier.height(8.dp))
        Text(
            "配置损坏，保存被阻止",
            color = colors.text,
            style = XiaoiTheme.typography.rowTitle,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${loadError.ifEmpty { "无法解析 config.json" }}。原文件已保留（含明文密钥，请勿外传）；" +
                "本页不写入任何内容。",
            color = colors.textSecondary,
            style = XiaoiTheme.typography.body,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            XiaoiTextButton(
                text = "打开配置管理",
                onClick = { onNavigate("ConfigManage") },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "恢复或重建后再编辑",
                color = colors.textHint,
                style = XiaoiTheme.typography.caption,
            )
        }
    }
}
