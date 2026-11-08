package plus.xiaoi.module.ui.screen.xiaoai

import androidx.compose.runtime.Composable
import plus.xiaoi.module.AppConfig
import plus.xiaoi.module.ui.components.XiaoiConfirmDialog

/** 删除目标；弹窗只负责展示，真正删除由状态持有者执行。 */
internal sealed interface DeleteTarget {
    data class Provider(val provider: AppConfig.Provider) : DeleteTarget
    data class Model(val provider: AppConfig.Provider, val model: AppConfig.ModelItem) : DeleteTarget
}

/** 模型页的三个确认弹窗：放弃草稿、覆盖保存、删除服务商/模型。 */
@Composable
internal fun ModelDialogs(
    showDiscard: Boolean,
    discardThenExit: Boolean,
    onConfirmDiscard: () -> Unit,
    onDismissDiscard: () -> Unit,
    showOverwrite: Boolean,
    baseRev: Long,
    conflictRev: Long?,
    onConfirmOverwrite: () -> Unit,
    onDismissOverwrite: () -> Unit,
    deleteTarget: DeleteTarget?,
    activeModelId: String,
    onConfirmDelete: (DeleteTarget) -> Unit,
    onDismissDelete: () -> Unit,
) {
    XiaoiConfirmDialog(
        show = showDiscard,
        title = "放弃未保存的修改？",
        message = "本次修改尚未写入 config.json。",
        confirmText = if (discardThenExit) "放弃并返回" else "放弃",
        danger = true,
        onConfirm = onConfirmDiscard,
        onDismiss = onDismissDiscard,
    )

    XiaoiConfirmDialog(
        show = showOverwrite,
        title = "配置已被其他页面修改",
        message = "文件 rev 已从 $baseRev 变为 ${conflictRev ?: 0L}，" +
            "覆盖保存会丢弃对方写入的修改。",
        confirmText = "覆盖保存",
        danger = true,
        onConfirm = onConfirmOverwrite,
        onDismiss = onDismissOverwrite,
    )

    XiaoiConfirmDialog(
        show = deleteTarget != null,
        title = "删除确认",
        message = when (deleteTarget) {
            is DeleteTarget.Provider ->
                "将被删除：${deleteTarget.provider.name}\n" +
                    "该服务商的模型与密钥会一并移除；保存后生效。"
            is DeleteTarget.Model -> buildString {
                append("将被删除：")
                append(deleteTarget.model.id)
                if (deleteTarget.model.id == activeModelId) {
                    append("\n这是当前使用的模型，删除后自动切到相邻模型；保存后生效。")
                } else {
                    append("\n保存后生效。")
                }
            }
            null -> ""
        },
        confirmText = "删除",
        danger = true,
        onConfirm = { deleteTarget?.let(onConfirmDelete) },
        onDismiss = onDismissDelete,
    )
}
