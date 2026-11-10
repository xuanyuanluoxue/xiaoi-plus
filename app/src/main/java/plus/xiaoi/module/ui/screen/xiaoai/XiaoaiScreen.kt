package plus.xiaoi.module.ui.screen.xiaoai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import plus.xiaoi.module.ui.components.StatusHero
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiButton
import plus.xiaoi.module.ui.components.XiaoiButtonStyle
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiLoadingSkeleton
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiSwitchRow
import plus.xiaoi.module.ui.components.XiaoiTextButton
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.components.XiaoiValueRow
import plus.xiaoi.module.ui.components.xiaoiScrollState
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

/** 增强能力：0.14 只列规划项，不做可点击假入口。 */
private val PLANNED_ABILITIES = listOf(
    "系统提示词",
    "工具注入",
    "技能包",
    "记忆",
    "语音与路由",
)

/**
 * 小爱一级页：状态卡（四维 + 主开关）、当前模型、运行态、增强能力占位。
 * 主开关即时保存；失败回滚并提示。不伪造“在线”。
 */
@Composable
fun XiaoaiScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val holder = remember { XiaoaiStateHolder(context) }
    val scope = rememberCoroutineScope()
    val state = holder.state
    val lifecycleOwner = context as? LifecycleOwner
    var resumed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        resumed = lifecycleOwner?.lifecycle?.currentState
            ?.isAtLeast(Lifecycle.State.RESUMED) == true
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        holder.refresh()
        // 8771 快照是进程内事实：仅在前台每 5 秒刷新，超过 30 秒自然回落“未确认”。
        while (true) {
            delay(5_000)
            holder.refresh()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(xiaoiScrollState())
            .padding(top = plus.xiaoi.module.ui.components.xiaoiTopBarHeight() + 8.dp, bottom = 112.dp)
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        if (state.loading) {
            XiaoiLoadingSkeleton(height = 168.dp)
            Spacer(Modifier.height(12.dp))
            XiaoiLoadingSkeleton(height = 120.dp)
            Spacer(Modifier.height(12.dp))
            XiaoiLoadingSkeleton(height = 120.dp)
            return@Column
        }

        if (state.readOnly) {
            CorruptCard(state.loadError)
            Spacer(Modifier.height(12.dp))
        }

        StatusHero(
            title = state.heroTitle,
            subtitle = state.heroSub,
            dimensions = state.dimensions,
            primaryText = null,
            onPrimary = null,
        )

        Spacer(Modifier.height(12.dp))
        XiaoiPreferenceCard {
            XiaoiSwitchRow(
                title = "启用第三方模型",
                subtitle = if (state.enabled) {
                    "小爱会按当前服务商与模型转发"
                } else {
                    "配置全部保留，随时可恢复"
                },
                checked = state.enabled,
                enabled = !state.readOnly && !state.saving,
                onCheckedChange = { target ->
                    scope.launch { holder.setEnabled(target, onMessage) }
                },
            )
            if (state.readOnly) {
                Text(
                    "配置损坏时开关禁用；请先在「我的 › 配置管理」恢复或重建。",
                    color = XiaoiTheme.colors.textHint,
                    style = XiaoiTheme.typography.caption,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        XiaoiGroupHeader("当前模型")
        XiaoiCard {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    state.providerName.ifEmpty { "未配置服务商" },
                    color = XiaoiTheme.colors.text,
                    style = XiaoiTheme.typography.rowTitle,
                )
                XiaoiBadge(
                    text = if (state.enabled) "已启用" else "已暂停",
                    tone = if (state.enabled) XiaoiTone.SUCCESS else XiaoiTone.WARNING,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                state.modelId.ifEmpty { "未配置模型" },
                color = XiaoiTheme.colors.textSecondary,
                style = XiaoiTheme.typography.mono,
            )
            Spacer(Modifier.height(6.dp))
            XiaoiValueRow("端点", hostOf(state.endpoint).ifEmpty { "未设置" })
            XiaoiValueRow("密钥", if (state.keySet) "已设置" else "未设置")
            Spacer(Modifier.height(8.dp))
            XiaoiButton(
                text = "管理模型",
                style = XiaoiButtonStyle.SECONDARY,
                onClick = { onNavigate("Model") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        XiaoiGroupHeader("运行态")
        XiaoiCard {
            Text(
                state.bridgeLine,
                color = XiaoiTheme.colors.text,
                style = XiaoiTheme.typography.body,
            )
            Spacer(Modifier.height(6.dp))
            XiaoiValueRow("已应用", state.appliedLine)
            XiaoiValueRow("最近上报", state.lastReport, mono = true)
            XiaoiValueRow("运行模型", state.runtimeModelLine)
            XiaoiValueRow("最后错误", state.lastError.ifEmpty { "无" })
            if (state.lastError.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatClock(state.lastErrorAt)} · 完整记录见日志",
                    color = XiaoiTheme.colors.textHint,
                    style = XiaoiTheme.typography.caption,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                XiaoiTextButton(text = "查看日志", onClick = { onNavigate("Logs") })
            }
        }

        XiaoiGroupHeader("增强能力")
        XiaoiCard {
            Text(
                "P2 占位，暂不可用；以下能力不在 0.14 范围内。",
                color = XiaoiTheme.colors.textHint,
                style = XiaoiTheme.typography.caption,
            )
            Spacer(Modifier.height(10.dp))
            PLANNED_ABILITIES.forEachIndexed { index, name ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        name,
                        color = XiaoiTheme.colors.textSecondary,
                        style = XiaoiTheme.typography.body,
                    )
                    XiaoiBadge("规划中", XiaoiTone.NEUTRAL)
                }
                if (index != PLANNED_ABILITIES.lastIndex) Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CorruptCard(loadError: String) {
    XiaoiCard {
        XiaoiBadge("只读", XiaoiTone.ERROR)
        Spacer(Modifier.height(8.dp))
        Text(
            "配置损坏，保存已被阻止",
            color = XiaoiTheme.colors.text,
            style = XiaoiTheme.typography.rowTitle,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${loadError.ifEmpty { "无法解析 config.json" }}。原文件已保留（含明文密钥，请勿外传）。",
            color = XiaoiTheme.colors.textSecondary,
            style = XiaoiTheme.typography.body,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "请在「我的 › 配置管理」恢复旧配置或重建默认配置后再编辑。",
            color = XiaoiTheme.colors.textHint,
            style = XiaoiTheme.typography.caption,
        )
    }
}
