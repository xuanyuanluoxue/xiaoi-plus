package plus.xiaoi.module.ui.screen.access

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.core.CapabilityRegistry
import plus.xiaoi.module.ui.components.XiaoiBadge
import plus.xiaoi.module.ui.components.XiaoiButton
import plus.xiaoi.module.ui.components.XiaoiButtonStyle
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiEmptyState
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiTextButton
import plus.xiaoi.module.ui.components.XiaoiTone
import plus.xiaoi.module.ui.components.XiaoiValueRow
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

/** 能力详情：只读；未知字段显示“未记录”，不补 schema/示例等假数据。 */
@Composable
fun CapabilityDetailScreen(
    capabilityId: String?,
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val colors = XiaoiTheme.colors
    val descriptor = capabilityId?.let { CapabilityRegistry.find(it) }

    if (descriptor == null) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = XiaoiTheme.dimens.pagePad)
                .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
        ) {
            XiaoiEmptyState(
                title = "未找到能力",
                description = if (capabilityId.isNullOrBlank()) {
                    "路由没有携带能力编号。"
                } else {
                    "编号“$capabilityId”不在能力表中。"
                },
            )
            Text(
                "能力清单共 ${CapabilityRegistry.all().size} 条；返回上一页重新选择。",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
            Spacer(Modifier.height(8.dp))
            XiaoiTextButton("打开能力清单") { onNavigate("Capabilities") }
        }
        return
    }

    val risk = descriptor.risk
    val riskText = when (risk) {
        CapabilityRegistry.Risk.LOW -> "低风险"
        CapabilityRegistry.Risk.MEDIUM -> "中风险"
        CapabilityRegistry.Risk.HIGH -> "高风险"
        CapabilityRegistry.Risk.CRITICAL -> "极高风险"
    }
    val riskTone = when (risk) {
        CapabilityRegistry.Risk.LOW -> XiaoiTone.NEUTRAL
        CapabilityRegistry.Risk.MEDIUM -> XiaoiTone.INFO
        CapabilityRegistry.Risk.HIGH, CapabilityRegistry.Risk.CRITICAL -> XiaoiTone.ERROR
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        XiaoiCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(descriptor.id, color = colors.textSecondary, style = XiaoiTheme.typography.mono)
                Spacer(Modifier.weight(1f))
                XiaoiBadge(riskText, riskTone)
                Spacer(Modifier.width(6.dp))
                XiaoiBadge(
                    text = statusText(descriptor),
                    tone = statusTone(descriptor),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(descriptor.title, color = colors.text, style = XiaoiTheme.typography.title)
            Spacer(Modifier.height(6.dp))
            Text(
                descriptor.summary.ifEmpty { "未提供说明" },
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
        }

        XiaoiGroupHeader("属性")
        XiaoiCard {
            XiaoiValueRow("域", "${descriptor.domain.prefix} · ${descriptor.domain.title}")
            XiaoiValueRow("实现方式", descriptor.channel.ifEmpty { "未记录" })
            XiaoiValueRow("来源", sourceText(descriptor.source))
            XiaoiValueRow("风险", riskText)
            XiaoiValueRow(
                "暴露给",
                if (descriptor.exposedTo.isEmpty()) "未暴露" else descriptor.exposedTo.joinToString("、"),
            )
            XiaoiValueRow("依赖/前置", "未记录")
            XiaoiValueRow("配置入口", descriptor.deepLink ?: "无")
            XiaoiValueRow("最近变化", "未记录")
        }

        XiaoiGroupHeader("说明")
        XiaoiCard {
            Text(
                "能力详情为只读：0.14 不提供试运行、不修改配置、不执行动作。",
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "schema、示例与时间线在 0.15 接入；当前不伪造参数结构。",
                color = colors.textHint,
                style = XiaoiTheme.typography.caption,
            )
        }

        val deepLink = descriptor.deepLink
        if (deepLink != null) {
            Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
            XiaoiButton(
                text = "打开配置入口（$deepLink）",
                style = XiaoiButtonStyle.SECONDARY,
                onClick = { onNavigate(deepLink) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun statusText(descriptor: CapabilityRegistry.Descriptor): String =
    when (descriptor.source) {
        CapabilityRegistry.Source.IMPLEMENTED -> "已实现"
        CapabilityRegistry.Source.PARTIAL -> "部分可用"
        CapabilityRegistry.Source.PLANNED -> "规划中"
        CapabilityRegistry.Source.UNSUPPORTED -> "不提供"
    }

private fun statusTone(descriptor: CapabilityRegistry.Descriptor): XiaoiTone =
    when (descriptor.source) {
        CapabilityRegistry.Source.IMPLEMENTED -> XiaoiTone.SUCCESS
        CapabilityRegistry.Source.PARTIAL -> XiaoiTone.WARNING
        CapabilityRegistry.Source.PLANNED -> XiaoiTone.INFO
        CapabilityRegistry.Source.UNSUPPORTED -> XiaoiTone.NEUTRAL
    }

private fun sourceText(source: CapabilityRegistry.Source): String = when (source) {
    CapabilityRegistry.Source.IMPLEMENTED -> "IMPLEMENTED"
    CapabilityRegistry.Source.PARTIAL -> "PARTIAL"
    CapabilityRegistry.Source.PLANNED -> "PLANNED"
    CapabilityRegistry.Source.UNSUPPORTED -> "UNSUPPORTED"
}
