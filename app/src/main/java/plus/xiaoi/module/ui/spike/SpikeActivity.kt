package plus.xiaoi.module.ui.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import plus.xiaoi.module.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * P0 spike：只验证 Compose + Miuix 能在本工程编译、安装、渲染。
 *
 * 不是启动入口，也不承担页面重写；颜色使用现有 res 令牌的近似映射，
 * P1 会替换为正式的 XiaoiTheme。
 */
class SpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpikeScreen() }
    }
}

@Composable
private fun SpikeScreen() {
    val colors = if (isSystemInDarkTheme()) darkSpikeColors() else lightSpikeColors()
    MiuixTheme(colors = colors) {
        var enabled by remember { mutableStateOf(true) }
        var input by remember { mutableStateOf(TextFieldValue("xiaoi-plus")) }
        var clicks by remember { mutableIntStateOf(0) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Miuix Hello",
                style = MiuixTheme.textStyles.title1,
            )
            Text(
                text = "P0 spike · 构建与渲染验证，不是最终页面",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("示例开关", style = MiuixTheme.textStyles.subtitle)
                        Text(
                            "整行可点，验证 Miuix Switch",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Text("输入框", style = MiuixTheme.textStyles.subtitle)
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "示例输入",
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "当前值：${input.text}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Text("按钮", style = MiuixTheme.textStyles.subtitle)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { clicks++ },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("点我：$clicks")
                }
            }

            Text(
                text = "开关=${if (enabled) "开" else "关"} · 主题=${if (isSystemInDarkTheme()) "深色" else "浅色"}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun lightSpikeColors(): Colors = lightColorScheme(
    primary = colorResource(R.color.primary),
    onPrimary = colorResource(R.color.on_primary),
    background = colorResource(R.color.bg),
    onBackground = colorResource(R.color.text),
    surface = colorResource(R.color.surface_2),
    onSurface = colorResource(R.color.text),
    surfaceVariant = colorResource(R.color.surface),
    onSurfaceVariantSummary = colorResource(R.color.text_secondary),
    outline = colorResource(R.color.line),
    dividerLine = colorResource(R.color.line),
)

@Composable
private fun darkSpikeColors(): Colors = darkColorScheme(
    primary = colorResource(R.color.primary),
    onPrimary = colorResource(R.color.on_primary),
    background = colorResource(R.color.bg),
    onBackground = colorResource(R.color.text),
    surface = colorResource(R.color.surface_2),
    onSurface = colorResource(R.color.text),
    surfaceVariant = colorResource(R.color.surface),
    onSurfaceVariantSummary = colorResource(R.color.text_secondary),
    outline = colorResource(R.color.line),
    dividerLine = colorResource(R.color.line),
)
