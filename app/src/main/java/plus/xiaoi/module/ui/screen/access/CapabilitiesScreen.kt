package plus.xiaoi.module.ui.screen.access

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.core.CapabilityRegistry
import plus.xiaoi.module.ui.components.CapabilityRisk
import plus.xiaoi.module.ui.components.CapabilityRow
import plus.xiaoi.module.ui.components.CapabilityStatus
import plus.xiaoi.module.ui.components.FilterChips
import plus.xiaoi.module.ui.components.SearchField
import plus.xiaoi.module.ui.components.XiaoiEmptyState
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

private const val FILTER_ALL = "全部"

private val DOMAIN_FILTERS = listOf(FILTER_ALL, "小爱", "系统", "桥")
private val STATUS_FILTERS = listOf(FILTER_ALL, "可用", "部分可用", "需授权", "规划中", "不提供", "不支持")

/** 能力清单：67 条只读能力，按域/状态筛选 + 搜索；无执行按钮。 */
@Composable
fun CapabilitiesScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val colors = XiaoiTheme.colors
    val initialFocus = remember { FocusRequester() }
    // Compose 窗口首次获得焦点时会把焦点给第一个可聚焦控件；用 1dp 焦点占位块
    // 接住初始焦点，避免搜索框一进页面就弹键盘。
    LaunchedEffect(Unit) { initialFocus.requestFocus() }
    var query by rememberSaveable { mutableStateOf("") }
    var domain by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var status by rememberSaveable { mutableStateOf(FILTER_ALL) }

    val all = remember { CapabilityRegistry.all() }
    val filtered = remember(query, domain, status) {
        all.filter { descriptor ->
            matchesQuery(descriptor, query) &&
                matchesDomain(descriptor, domain) &&
                matchesStatus(descriptor, status)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop),
    ) {
        Box(
            Modifier
                .size(1.dp)
                .focusRequester(initialFocus)
                .focusable(),
        )
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 112.dp),
        ) {
            item {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索能力名称、编号或说明",
                    onClear = { query = "" },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                FilterChips(
                    options = DOMAIN_FILTERS,
                    selected = domain,
                    onSelect = { domain = it },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                FilterChips(
                    options = STATUS_FILTERS,
                    selected = status,
                    onSelect = { status = it },
                )
            }
            item {
                Text(
                    "共 ${all.size} 条 · 当前 ${filtered.size} 条 · 只读",
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.caption,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }

            if (filtered.isEmpty()) {
                item {
                    XiaoiEmptyState(
                        title = "没有匹配的能力",
                        description = "试试清除搜索，或切换到“全部”筛选。",
                    )
                }
            } else {
                CapabilityRegistry.Domain.values().forEach { d ->
                    val group = filtered.filter { it.domain == d }
                    if (group.isEmpty()) return@forEach
                    item(key = "header-${d.prefix}") {
                        XiaoiGroupHeader("${d.prefix} · ${d.title}")
                    }
                    item(key = "group-${d.prefix}") {
                        XiaoiPreferenceCard {
                            group.forEach { descriptor ->
                                CapabilityRow(
                                    id = descriptor.id,
                                    title = descriptor.title,
                                    channel = descriptor.channel.ifEmpty { "—" },
                                    status = descriptor.defaultStatus().toUiStatus(),
                                    risk = descriptor.risk.toUiRisk(),
                                    onClick = { onNavigate("CapabilityDetail:${descriptor.id}") },
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                )
            }
            item {
                Text(
                    "状态是静态来源映射，运行时授权/可用性由后续探针补充；0.14 不提供试运行。",
                    color = colors.textHint,
                    style = XiaoiTheme.typography.caption,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

private fun matchesQuery(descriptor: CapabilityRegistry.Descriptor, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return descriptor.id.contains(q, ignoreCase = true) ||
        descriptor.title.contains(q, ignoreCase = true) ||
        descriptor.summary.contains(q, ignoreCase = true) ||
        descriptor.channel.contains(q, ignoreCase = true)
}

private fun matchesDomain(descriptor: CapabilityRegistry.Descriptor, selected: String): Boolean {
    if (selected == FILTER_ALL) return true
    return descriptor.domain.title == selected
}

private fun matchesStatus(descriptor: CapabilityRegistry.Descriptor, selected: String): Boolean {
    if (selected == FILTER_ALL) return true
    return statusLabel(descriptor.defaultStatus()) == selected
}

private fun statusLabel(status: CapabilityRegistry.CapabilityStatus): String = when (status) {
    CapabilityRegistry.CapabilityStatus.AVAILABLE -> "可用"
    CapabilityRegistry.CapabilityStatus.LIMITED -> "部分可用"
    CapabilityRegistry.CapabilityStatus.PERMISSION_MISSING -> "需授权"
    CapabilityRegistry.CapabilityStatus.PLANNED -> "规划中"
    CapabilityRegistry.CapabilityStatus.DISABLED,
    CapabilityRegistry.CapabilityStatus.UNAVAILABLE,
    -> "不提供"
    CapabilityRegistry.CapabilityStatus.UNSUPPORTED -> "不支持"
}

private fun CapabilityRegistry.CapabilityStatus.toUiStatus(): CapabilityStatus = when (this) {
    CapabilityRegistry.CapabilityStatus.AVAILABLE -> CapabilityStatus.AVAILABLE
    CapabilityRegistry.CapabilityStatus.LIMITED -> CapabilityStatus.LIMITED
    CapabilityRegistry.CapabilityStatus.PERMISSION_MISSING -> CapabilityStatus.PERMISSION_MISSING
    CapabilityRegistry.CapabilityStatus.PLANNED -> CapabilityStatus.PLANNED
    CapabilityRegistry.CapabilityStatus.DISABLED -> CapabilityStatus.DISABLED
    CapabilityRegistry.CapabilityStatus.UNAVAILABLE -> CapabilityStatus.UNAVAILABLE
    CapabilityRegistry.CapabilityStatus.UNSUPPORTED -> CapabilityStatus.UNSUPPORTED
}

private fun CapabilityRegistry.Risk.toUiRisk(): CapabilityRisk = when (this) {
    CapabilityRegistry.Risk.LOW -> CapabilityRisk.LOW
    CapabilityRegistry.Risk.MEDIUM -> CapabilityRisk.MEDIUM
    CapabilityRegistry.Risk.HIGH -> CapabilityRisk.HIGH
    CapabilityRegistry.Risk.CRITICAL -> CapabilityRisk.CRITICAL
}
