package plus.xiaoi.module.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import plus.xiaoi.module.ui.components.IssueRow
import plus.xiaoi.module.ui.components.StatusHero
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiCardGroup
import plus.xiaoi.module.ui.components.XiaoiIconTone
import plus.xiaoi.module.ui.components.XiaoiLoadingSkeleton
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiRowIcon
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.theme.XiaoiTheme
import plus.xiaoi.module.ui.components.xiaoiTopBarInset
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Tasks

/**
 * 首页：一屏回答“哪里不对、下一步点哪”，只读、不做配置。
 *
 * <p>形态对齐参考截图：状态 Hero 独立卡 + 语义彩色圆图标的入口卡清单。
 * 数据由 [HomeStateHolder] 在 IO 线程读取；未知一律显示“未确认/—”。</p>
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
            .padding(top = xiaoiTopBarInset(), bottom = 112.dp)
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        if (state.loading) {
            XiaoiLoadingSkeleton(height = 188.dp)
            Spacer(Modifier.height(12.dp))
            XiaoiLoadingSkeleton(height = 84.dp)
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

        if (state.issues.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            state.issues.forEach { issue ->
                XiaoiCardGroup {
                    XiaoiPreferenceCard {
                        IssueRow(issue.ui, onClick = { onNavigate(issue.route) })
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        XiaoiCardGroup {
            XiaoiEntryCard(
                icon = MiuixIcons.Mic,
                tone = XiaoiIconTone.ACCENT,
                title = "小爱同学",
                summary = "${state.xiaoai.status} · 端口 ${state.xiaoai.port} · ${state.xiaoai.bind}",
                badge = state.xiaoai.status,
                badgeTone = state.xiaoai.tone,
                onClick = { onNavigate(state.xiaoai.route) },
            )
            XiaoiEntryCard(
                icon = MiuixIcons.Link,
                tone = XiaoiIconTone.ACCENT,
                title = "接入通道",
                summary = "8771 ${state.xiaoai.status} · 8770 ${state.agent.status}",
                badge = state.agent.status,
                badgeTone = state.agent.tone,
                onClick = { onNavigate("Access") },
            )
            XiaoiEntryCard(
                icon = MiuixIcons.Tasks,
                tone = XiaoiIconTone.ACCENT,
                title = "今日调用",
                summary = when {
                    state.usageThird != null && state.usageOfficial != null ->
                        "三方 ${state.usageThird} · 官方 ${state.usageOfficial}"
                    else -> "未确认（需要 MCP 与令牌）"
                },
                onClick = { onNavigate("Bridge") },
            )
            XiaoiEntryCard(
                icon = MiuixIcons.Notes,
                tone = XiaoiIconTone.ACCENT,
                title = "最近活动",
                summary = state.activities.firstOrNull()?.let { "${it.time} · ${it.domain} · ${it.text}" }
                    ?: "暂无活动",
                onClick = { onNavigate("Logs") },
            )
            XiaoiEntryCard(
                icon = MiuixIcons.ListView,
                tone = XiaoiIconTone.ACCENT,
                title = "能力清单",
                summary = "只读能力、工具与权限矩阵",
                onClick = { onNavigate("Capabilities") },
            )
            XiaoiEntryCard(
                icon = MiuixIcons.Backup,
                tone = XiaoiIconTone.ACCENT,
                title = "配置管理",
                summary = "备份、还原、脱敏导出与运行时导入",
                onClick = { onNavigate("ConfigManage") },
            )
            XiaoiEntryCard(
                icon = MiuixIcons.Info,
                tone = XiaoiIconTone.ACCENT,
                title = "关于",
                summary = "版本、GitHub、许可证与致谢",
                onClick = { onNavigate("About") },
            )
        }
    }
}

/**
 * 首页入口卡：独立卡 + 48dp 语义色圆图标 + 标题/副标题 + 可选徽章 + chevron。
 * 只有首页使用彩色圆；设置页走 [plus.xiaoi.module.ui.components.XiaoiInfoRow]。
 */
@Composable
private fun XiaoiEntryCard(
    icon: ImageVector,
    tone: XiaoiIconTone,
    title: String,
    summary: String,
    onClick: () -> Unit,
    badge: String? = null,
    badgeTone: XiaoiTone = XiaoiTone.NEUTRAL,
) {
    val colors = XiaoiTheme.colors
    XiaoiCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            XiaoiRowIcon(icon, tone)
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.text, style = XiaoiTheme.typography.rowTitle)
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (badge != null) {
                Spacer(Modifier.size(8.dp))
                XiaoiBadge(badge, badgeTone)
            }
            Spacer(Modifier.size(6.dp))
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = colors.textHint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
