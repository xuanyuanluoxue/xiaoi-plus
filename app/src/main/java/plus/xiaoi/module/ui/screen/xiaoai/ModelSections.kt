package plus.xiaoi.module.ui.screen.xiaoai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.AppConfig
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiButton
import plus.xiaoi.module.ui.components.XiaoiButtonStyle
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiTextField
import plus.xiaoi.module.ui.components.XiaoiTextButton
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text

/** 分组标题 + 右侧动作（添加/管理）；页面级布局，不新增行样式。 */
@Composable
internal fun SectionHeader(
    title: String,
    actionText: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            color = XiaoiTheme.colors.textSecondary,
            style = XiaoiTheme.typography.caption,
        )
        if (actionText != null && onAction != null) {
            XiaoiTextButton(text = actionText, enabled = actionEnabled, onClick = onAction)
        }
    }
}

/** 服务商列表：整行可点切换；只剩一个时不给删除，避免出现“没有服务商”的空壳。 */
@Composable
internal fun ProviderListSection(
    providers: List<AppConfig.Provider>,
    activeProviderId: String,
    editable: Boolean,
    onSelect: (String) -> Unit,
    onDelete: (AppConfig.Provider) -> Unit,
) {
    XiaoiPreferenceCard {
        if (providers.isEmpty()) {
            Text(
                "还没有服务商 · 点「添加」新建第一个",
                color = XiaoiTheme.colors.textHint,
                style = XiaoiTheme.typography.body,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            providers.forEach { provider ->
                val active = provider.id == activeProviderId
                BasicComponent(
                    onClick = { onSelect(provider.id) },
                    enabled = editable,
                    endActions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (active) XiaoiBadge("当前", XiaoiTone.INFO)
                            if (editable && providers.size > 1) {
                                XiaoiTextButton(
                                    text = "删除",
                                    danger = true,
                                    onClick = { onDelete(provider) },
                                )
                            }
                        }
                    },
                ) {
                    Text(
                        provider.name.ifEmpty { provider.id },
                        color = XiaoiTheme.colors.text,
                        style = XiaoiTheme.typography.rowTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(hostOf(provider.baseUrl).ifEmpty { "接口地址未填写" })
                            append(" · ${provider.models.size} 个模型")
                            if (provider.apiKey.isNotEmpty()) append(" · 已设置密钥")
                        },
                        color = XiaoiTheme.colors.textSecondary,
                        style = XiaoiTheme.typography.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 模型列表：只展示当前服务商的模型；删除当前项会切到相邻模型。 */
@Composable
internal fun ModelListSection(
    provider: AppConfig.Provider?,
    activeModelId: String,
    editable: Boolean,
    onSelect: (String) -> Unit,
    onDelete: (AppConfig.ModelItem) -> Unit,
) {
    XiaoiPreferenceCard {
        when {
            provider == null -> Text(
                "先添加服务商",
                color = XiaoiTheme.colors.textHint,
                style = XiaoiTheme.typography.body,
                modifier = Modifier.padding(16.dp),
            )

            provider.models.isEmpty() -> Text(
                "这个服务商还没有模型 · 点「添加」",
                color = XiaoiTheme.colors.textHint,
                style = XiaoiTheme.typography.body,
                modifier = Modifier.padding(16.dp),
            )

            else -> provider.models.forEach { model ->
                val active = model.id == activeModelId
                BasicComponent(
                    onClick = { onSelect(model.id) },
                    enabled = editable,
                    endActions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (active) XiaoiBadge("当前", XiaoiTone.INFO)
                            if (editable) {
                                XiaoiTextButton(
                                    text = "删除",
                                    danger = true,
                                    onClick = { onDelete(model) },
                                )
                            }
                        }
                    },
                ) {
                    Text(
                        model.id,
                        color = XiaoiTheme.colors.text,
                        style = XiaoiTheme.typography.rowTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            if (model.name.isNotEmpty() && model.name != model.id) {
                                append(model.name)
                                append(" · ")
                            }
                            if (active) append("当前使用") else append("点按切换")
                        },
                        color = XiaoiTheme.colors.textSecondary,
                        style = XiaoiTheme.typography.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 表单：名称/端点/模型 ID/密钥 + 端点校验；密钥掩码，不提供复制。 */
@Composable
internal fun ModelFormCard(
    provider: AppConfig.Provider?,
    model: AppConfig.ModelItem?,
    errors: ModelErrors,
    check: CheckUi,
    editable: Boolean,
    onNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelIdChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onCheck: () -> Unit,
) {
    val colors = XiaoiTheme.colors
    XiaoiCard {
        if (provider == null) {
            Text(
                "先添加服务商，再填写接口地址与密钥",
                color = colors.textHint,
                style = XiaoiTheme.typography.body,
            )
            return@XiaoiCard
        }
        XiaoiTextField(
            value = provider.name,
            onValueChange = onNameChange,
            label = "名称",
            placeholder = "名称，如 GLM",
            error = errors.providerName,
            enabled = editable,
        )
        Spacer(Modifier.height(10.dp))
        XiaoiTextField(
            value = provider.baseUrl,
            onValueChange = onBaseUrlChange,
            label = "接口地址",
            placeholder = "如 https://api.example.com/v1",
            error = errors.baseUrl,
            enabled = editable,
            keyboardType = KeyboardType.Uri,
        )
        Spacer(Modifier.height(10.dp))
        XiaoiTextField(
            value = model?.id.orEmpty(),
            onValueChange = onModelIdChange,
            label = "模型 ID",
            placeholder = if (model == null) "先在上方添加模型" else "模型 ID，如 deepseek-chat",
            error = errors.modelId,
            enabled = editable && model != null,
        )
        Spacer(Modifier.height(10.dp))
        XiaoiTextField(
            value = provider.apiKey,
            onValueChange = onApiKeyChange,
            label = "API Key",
            placeholder = "API Key（sk-…）",
            isSecret = true,
            error = errors.apiKey,
            enabled = editable,
            keyboardType = KeyboardType.Password,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "超时：连接 5 秒 / 读取 8 秒（ModelValidator 固定值，不写入配置）",
            color = colors.textHint,
            style = XiaoiTheme.typography.caption,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            XiaoiButton(
                text = "校验接口",
                style = XiaoiButtonStyle.SECONDARY,
                enabled = editable && !check.running,
                loading = check.running,
                onClick = onCheck,
            )
        }
        if (check.message.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = check.message,
                color = when {
                    check.running -> colors.textSecondary
                    check.ok -> colors.textSecondary
                    else -> colors.error
                },
                style = XiaoiTheme.typography.caption,
            )
        }
        if (errors.general != null) {
            Spacer(Modifier.height(8.dp))
            Text(errors.general, color = colors.error, style = XiaoiTheme.typography.caption)
        }
    }
}

/** 添加服务商：显式「添加」才写入草稿；校验失败保留输入。 */
@Composable
internal fun AddProviderCard(
    onAdd: (String, String) -> String?,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    XiaoiCard {
        Text("添加服务商", color = XiaoiTheme.colors.text, style = XiaoiTheme.typography.rowTitle)
        Spacer(Modifier.height(10.dp))
        XiaoiTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = "名称",
            placeholder = "名称，如 GLM",
        )
        Spacer(Modifier.height(10.dp))
        XiaoiTextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = "接口地址",
            placeholder = "如 https://api.example.com/v1",
            keyboardType = KeyboardType.Uri,
        )
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error!!, color = XiaoiTheme.colors.error, style = XiaoiTheme.typography.caption)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            XiaoiButton(
                text = "添加",
                enabled = name.isNotBlank() && url.isNotBlank(),
                onClick = {
                    val problem = onAdd(name, url)
                    if (problem == null) onCancel() else error = problem
                },
            )
            Spacer(Modifier.width(8.dp))
            XiaoiTextButton(text = "取消", onClick = onCancel)
        }
    }
}

/** 添加模型：同一服务商下 ID 去重；新模型在无当前模型时自动选中。 */
@Composable
internal fun AddModelCard(
    providerName: String,
    onAdd: (String, String) -> String?,
    onCancel: () -> Unit,
) {
    var modelId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    XiaoiCard {
        Text(
            "添加模型到 $providerName",
            color = XiaoiTheme.colors.text,
            style = XiaoiTheme.typography.rowTitle,
        )
        Spacer(Modifier.height(10.dp))
        XiaoiTextField(
            value = modelId,
            onValueChange = { modelId = it; error = null },
            label = "模型 ID",
            placeholder = "模型 ID，如 deepseek-chat",
        )
        Spacer(Modifier.height(10.dp))
        XiaoiTextField(
            value = displayName,
            onValueChange = { displayName = it; error = null },
            label = "显示名（可选）",
            placeholder = "显示名，如 DeepSeek Chat",
        )
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error!!, color = XiaoiTheme.colors.error, style = XiaoiTheme.typography.caption)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            XiaoiButton(
                text = "添加",
                enabled = modelId.isNotBlank(),
                onClick = {
                    val problem = onAdd(modelId, displayName)
                    if (problem == null) onCancel() else error = problem
                },
            )
            Spacer(Modifier.width(8.dp))
            XiaoiTextButton(text = "取消", onClick = onCancel)
        }
    }
}
