package plus.xiaoi.module

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import plus.xiaoi.module.core.NavStack
import plus.xiaoi.module.ui.components.XiaoiBottomBar
import plus.xiaoi.module.ui.components.XiaoiTopBar
import plus.xiaoi.module.ui.screen.PlaceholderScreen
import plus.xiaoi.module.ui.screen.me.AboutScreen
import plus.xiaoi.module.ui.screen.me.LogsScreen
import plus.xiaoi.module.ui.screen.me.MeScreen
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState

/**
 * P1 单 Activity 壳：4 个底部 tab + 主题 + 日志/关于。
 * 首页/小爱/接入在 P2 接入；当前保留旧版入口，避免功能不可达。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(ThemeManager.get(this))
        setContent {
            XiaoiTheme(mode = ThemeManager.get(this)) {
                XiaoiApp(
                    initialRoute = intent?.getStringExtra("route"),
                    onThemeChange = { mode ->
                        ThemeManager.set(this, mode)
                        recreate()
                    },
                )
            }
        }
    }
    /**
     * targetSdk 26 下用平台 API 同步状态栏/导航栏；深色主题必须切浅色图标，
     * 否则会出现“黑界面 + 白状态栏”。
     */
    private fun applySystemBars(mode: Int) {
        val dark = when (mode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        val bg = if (dark) 0xFF0B0B0D.toInt() else 0xFFF7F8FA.toInt()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        var flags = window.decorView.systemUiVisibility
        flags = if (dark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= 26) {
            flags = if (dark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        window.decorView.systemUiVisibility = flags
    }
}

@Composable
private fun XiaoiApp(initialRoute: String?, onThemeChange: (Int) -> Unit) {
    val context = LocalContext.current
    val colors = XiaoiTheme.colors
    val nav = remember { NavStack() }
    var route by remember { mutableStateOf(nav.current()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun message(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    fun openLegacy(clazz: Class<*>) {
        try {
            context.startActivity(Intent(context, clazz))
        } catch (t: Throwable) {
            message("无法打开旧版页面")
        }
    }

    fun navigate(target: String) {
        nav.push(target)
        route = nav.current()
    }

    fun back() {
        if (nav.back()) {
            route = nav.current()
        } else {
            (context as? ComponentActivity)?.finish()
        }
    }

    LaunchedEffect(Unit) {
        val allowed = setOf("Home", "Xiaoai", "Access", "Me", "Logs", "About")
        if (!initialRoute.isNullOrEmpty() && initialRoute in allowed) {
            nav.push(initialRoute)
            route = nav.current()
        }
    }

    BackHandler { back() }

    val title = when (route) {
        "Home" -> "首页"
        "Xiaoai" -> "小爱"
        "Access" -> "接入"
        "Me" -> "我的"
        "Logs" -> "日志"
        "About" -> "关于"
        else -> route
    }

    Scaffold(
        topBar = {
            XiaoiTopBar(
                title = title,
                onBack = if (nav.isRoot()) null else ({ back() }),
            )
        },
        bottomBar = {
            if (nav.isRoot()) {
                XiaoiBottomBar(current = nav.tab(), onSelect = { tab ->
                    nav.switchTab(tab)
                    route = nav.current()
                })
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = colors.bg,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (route) {
                "Home" -> PlaceholderScreen(
                    title = "首页",
                    description = "状态总览、待处理、双域入口、运行概况与最近活动将在 P2 接入。",
                    legacyLabel = "打开旧版首页",
                ) { openLegacy(HomeActivity::class.java) }

                "Xiaoai" -> PlaceholderScreen(
                    title = "小爱",
                    description = "模型与服务商、提示词、工具注入、技能包、记忆将在 P2/P3 接入。",
                    legacyLabel = "打开旧版配置",
                ) { openLegacy(ConfigActivity::class.java) }

                "Access" -> PlaceholderScreen(
                    title = "接入",
                    description = "8771/8770、令牌与局域网、连接信息、能力清单将在 P2 接入。",
                    legacyLabel = "打开旧版配置",
                ) { openLegacy(ConfigActivity::class.java) }

                "Me" -> MeScreen(
                    themeMode = ThemeManager.get(context),
                    onThemeChange = onThemeChange,
                    onOpenLogs = { navigate("Logs") },
                    onOpenAbout = { navigate("About") },
                    onOpenLegacyHome = { openLegacy(HomeActivity::class.java) },
                    onOpenLegacyConfig = { openLegacy(ConfigActivity::class.java) },
                    onMessage = { message(it) },
                )

                "Logs" -> LogsScreen(onMessage = { message(it) })
                "About" -> AboutScreen(onMessage = { message(it) })
                else -> PlaceholderScreen(
                    title = route,
                    description = "该页面尚未接入。",
                    legacyLabel = "打开旧版首页",
                ) { openLegacy(HomeActivity::class.java) }
            }
        }
    }
}
