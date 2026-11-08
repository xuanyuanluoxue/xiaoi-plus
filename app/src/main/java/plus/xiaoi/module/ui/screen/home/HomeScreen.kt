package plus.xiaoi.module.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import plus.xiaoi.module.core.ActivityFeed
import plus.xiaoi.module.ui.components.IssueRow
import plus.xiaoi.module.ui.components.StatusHero
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiDivider
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiLoadingSkeleton
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiTextButton
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

/**
 * 首页：一屏回答“哪里不对、下一步点哪”，只读、不做配置。
 *
 * <p>数据由 [HomeStateHolder] 在 IO 线程读取；四维结论来自 StateModel，
 * 待处理来自 IssueResolver，8771/8770 分开渲染。未知一律显示“未确认/—”。</p>
 */
@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val holder = remember { HomeStateHolder(context) }
    val state = holder.state
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = XiaoiTheme.colors

    LaunchedEffect(lifecycleOwner) {
        // 只在 RESUMED 时重读：控制面超过 30 秒未上报时自动退回“未确认”。
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            holder.refresh()
            while (true) {
                delay(5_000)
                holder.refresh()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        if (state.loading) {
            XiaoiLoadingSkeleton(height = 168.dp)
            Spacer(Modifier.height(12.dp))
            XiaoiLoadingSkeleton(height = 120.dp)
            return@Column
        }

        val cta = state.cta
        StatusHero(
            title = state.heroTitle,
            subtitle = state.heroSub,
            dimensions = state.dimensions,
            primaryText = cta?.text,
            onPrimary = cta?.let { target -> { onNavigate(target.route) } },
        )

        XiaoiGroupHeader("待处理")
        if (state.issues.isEmpty()) {
            Text(
                "没有待处理项",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            XiaoiPreferenceCard {
                state.issues.forEachIndexed { index, issue ->
                    if (index > 0) XiaoiDivider()
                    IssueRow(issue.ui, onClick = { onNavigate(issue.route) })
                }
            }
            if (state.issueTotal > state.issues.size) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    XiaoiTextButton("查看全部 ${state.issueTotal} 条") { onNavigate("Access") }
                }
            }
        }

        XiaoiGroupHeader("双域入口")
        DomainCard(state.xiaoai) { onNavigate(state.xiaoai.route) }
        Spacer(Modifier.height(12.dp))
        DomainCard(state.agent) { onNavigate(state.agent.route) }

        XiaoiGroupHeader("运行概况")
        XiaoiCard {
            Text("8771 · 小爱", color = colors.text, style = XiaoiTheme.typography.rowTitle)
            Spacer(Modifier.height(6.dp))
            state.bridgeRows.forEach { ValueLine(it) }
            Spacer(Modifier.height(12.dp))
            XiaoiDivider()
            Spacer(Modifier.height(12.dp))
            Text("8770 · 接入", color = colors.text, style = XiaoiTheme.typography.rowTitle)
            Spacer(Modifier.height(6.dp))
            state.agentRows.forEach { ValueLine(it) }
        }

        XiaoiGroupHeader("最近活动")
        XiaoiCard {
            if (state.activities.isEmpty()) {
                Text(
                    "暂无活动",
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.body,
                )
            } else {
                state.activities.forEachIndexed { index, item ->
                    if (index > 0) XiaoiDivider()
                    ActivityLine(item)
                }
            }
        }
        if (state.activities.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                XiaoiTextButton("查看日志") { onNavigate("Logs") }
            }
        }
    }
}

/** 双域入口卡：标题 + 状态徽章 + 端口/绑定 + 一行事实说明。 */
@Composable
private fun DomainCard(card: DomainCardUi, onClick: () -> Unit) {
    val colors = XiaoiTheme.colors
    XiaoiCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                card.title,
                color = colors.text,
                style = XiaoiTheme.typography.rowTitle,
                modifier = Modifier.weight(1f),
            )
            XiaoiBadge(card.status, card.tone)
        }
        Spacer(Modifier.height(8.dp))
        DomainValue("端口", card.port, mono = true)
        DomainValue("绑定", card.bind)
        if (card.note.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(card.note, color = colors.textSecondary, style = XiaoiTheme.typography.caption)
        }
    }
}

@Composable
private fun DomainValue(label: String, value: String, mono: Boolean = false) {
    val colors = XiaoiTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            color = colors.textSecondary,
            style = XiaoiTheme.typography.caption,
            modifier = Modifier.width(56.dp),
        )
        Text(
            value,
            color = colors.text,
            style = if (mono) XiaoiTheme.typography.mono else XiaoiTheme.typography.body,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 运行概况键值行；长文本换行，数字用 Mono。 */
@Composable
private fun ValueLine(value: ValueUi) {
    val colors = XiaoiTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            value.label,
            color = colors.textSecondary,
            style = XiaoiTheme.typography.caption,
            modifier = Modifier.width(84.dp),
        )
        Text(
            value.value,
            color = colors.text,
            style = if (value.mono) XiaoiTheme.typography.mono else XiaoiTheme.typography.body,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 最近活动一行：时间（Mono）+ 域/级别文字 + 事实文本；级别不只靠颜色。 */
@Composable
private fun ActivityLine(item: ActivityUi) {
    val colors = XiaoiTheme.colors
    val (label, tint) = when (item.level) {
        ActivityFeed.Level.ERROR -> "错误" to colors.error
        ActivityFeed.Level.WARN -> "注意" to colors.warning
        ActivityFeed.Level.INFO -> "信息" to colors.textSecondary
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            item.time,
            color = colors.textHint,
            style = XiaoiTheme.typography.mono,
            modifier = Modifier.width(64.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                "${item.domain} · $label",
                color = tint,
                style = XiaoiTheme.typography.caption,
            )
            Spacer(Modifier.height(2.dp))
            Text(item.text, color = colors.text, style = XiaoiTheme.typography.body)
        }
    }
}
