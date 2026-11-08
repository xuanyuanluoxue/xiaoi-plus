package plus.xiaoi.module.ui.screen.access

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.Json
import plus.xiaoi.module.Mcp
import plus.xiaoi.module.agent.ActionRisk
import plus.xiaoi.module.agent.Actions
import plus.xiaoi.module.agent.AgentMcp
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiDivider
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

private data class ToolItemUi(
    val name: String,
    val description: String,
    val params: String,
    val risk: String,
    val riskTone: XiaoiTone,
)

private data class ToolGroupUi(
    val title: String,
    val note: String,
    val items: List<ToolItemUi>,
)

/**
 * 工具清单：5 个动作 + 8771 8 个工具 + 8770 6 个工具。
 * 名称/描述/参数从现有注册表生成，只读；没有风险元数据的工具显示“未声明”。
 */
@Composable
fun ToolsScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val colors = XiaoiTheme.colors
    val groups = remember { buildToolGroups() }
    val total = groups.sumOf { it.items.size }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop),
    ) {
        item {
            XiaoiCard {
                Text(
                    "共 $total 个条目 · 只读",
                    color = colors.text,
                    style = XiaoiTheme.typography.rowTitle,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "动作来自 Actions.builtins()，MCP 工具来自 8771/8770 的 tools/list。",
                    color = colors.textSecondary,
                    style = XiaoiTheme.typography.body,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "参数带 * 为必填；风险“未声明”表示工具描述没有风险字段，不代填。",
                    color = colors.textHint,
                    style = XiaoiTheme.typography.caption,
                )
            }
        }
        groups.forEach { group ->
            item(key = "header-${group.title}") {
                XiaoiGroupHeader(group.title)
            }
            item(key = "group-${group.title}") {
                XiaoiPreferenceCard {
                    group.items.forEachIndexed { index, item ->
                        if (index > 0) XiaoiDivider()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = item.name,
                                    color = colors.text,
                                    style = XiaoiTheme.typography.rowTitle,
                                    modifier = Modifier.weight(1f),
                                )
                                XiaoiBadge(item.risk, item.riskTone)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = item.description.ifEmpty { "未提供说明" },
                                color = colors.textSecondary,
                                style = XiaoiTheme.typography.body,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "参数：${item.params}",
                                color = colors.textHint,
                                style = XiaoiTheme.typography.caption,
                            )
                        }
                    }
                }
                Text(
                    group.note,
                    color = colors.textHint,
                    style = XiaoiTheme.typography.caption,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
        }
        item {
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun buildToolGroups(): List<ToolGroupUi> = listOf(
    ToolGroupUi(
        title = "5 个内置动作",
        note = "动作由 8770 执行；风险来自 ActionRisk，需确认/危险动作要一次性 token。",
        items = Actions.builtins().map { descriptor ->
            ToolItemUi(
                name = descriptor.name,
                description = descriptor.description,
                params = schemaParams(descriptor.inputSchema),
                risk = actionRiskText(descriptor.risk),
                riskTone = actionRiskTone(descriptor.risk),
            )
        },
    ),
    ToolGroupUi(
        title = "8771 控制面工具",
        note = "只操作小爱/配置/统计，不执行设备动作；工具表未声明风险等级。",
        items = toolItems(Mcp.toolsList()),
    ),
    ToolGroupUi(
        title = "8770 Agent 工具",
        note = "capabilities/action/confirm/audit/notify/ask；action 的实际风险由每个动作声明。",
        items = toolItems(AgentMcp("0").toolsList()),
    ),
)

private fun toolItems(table: Map<*, *>): List<ToolItemUi> {
    val list = table["tools"] as? List<*> ?: return emptyList()
    val out = ArrayList<ToolItemUi>(list.size)
    for (entry in list) {
        val map = entry as? Map<*, *> ?: continue
        val name = Json.optString(map, "name", "")
        if (name.isEmpty()) continue
        out.add(
            ToolItemUi(
                name = name,
                description = Json.optString(map, "description", ""),
                params = schemaParams(map["inputSchema"] as? Map<*, *>),
                risk = "未声明",
                riskTone = XiaoiTone.NEUTRAL,
            ),
        )
    }
    return out
}

private fun schemaParams(schema: Map<*, *>?): String {
    if (schema == null) return "无参数"
    val props = schema["properties"] as? Map<*, *> ?: return "无参数"
    if (props.isEmpty()) return "无参数"
    val required = (schema["required"] as? List<*>)
        ?.mapNotNull { it?.toString() }
        ?.toSet()
        .orEmpty()
    return props.keys.mapNotNull { key ->
        val name = key?.toString() ?: return@mapNotNull null
        if (required.contains(name)) "$name*" else name
    }.joinToString("、").ifEmpty { "无参数" }
}

private fun actionRiskText(risk: ActionRisk): String = when (risk) {
    ActionRisk.SAFE -> "安全"
    ActionRisk.CONFIRM -> "需确认"
    ActionRisk.DANGER -> "危险"
}

private fun actionRiskTone(risk: ActionRisk): XiaoiTone = when (risk) {
    ActionRisk.SAFE -> XiaoiTone.SUCCESS
    ActionRisk.CONFIRM -> XiaoiTone.WARNING
    ActionRisk.DANGER -> XiaoiTone.ERROR
}
