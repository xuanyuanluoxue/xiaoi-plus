package plus.xiaoi.module

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import plus.xiaoi.module.ui.screen.XiaoiPlaceholder
import plus.xiaoi.module.ui.screen.access.AccessScreen
import plus.xiaoi.module.ui.screen.access.BridgeScreen
import plus.xiaoi.module.ui.screen.access.CapabilitiesScreen
import plus.xiaoi.module.ui.screen.access.CapabilityDetailScreen
import plus.xiaoi.module.ui.screen.access.ConnectScreen
import plus.xiaoi.module.ui.screen.access.PermissionsScreen
import plus.xiaoi.module.ui.screen.access.TokenScreen
import plus.xiaoi.module.ui.screen.access.ToolsScreen
import plus.xiaoi.module.ui.screen.home.HomeScreen
import plus.xiaoi.module.ui.screen.me.AboutScreen
import plus.xiaoi.module.ui.screen.me.AppearanceScreen
import plus.xiaoi.module.ui.screen.me.ConfigManageScreen
import plus.xiaoi.module.ui.screen.me.DiagnosticsScreen
import plus.xiaoi.module.ui.screen.me.LegacyScreen
import plus.xiaoi.module.ui.screen.me.LicensesScreen
import plus.xiaoi.module.ui.screen.me.LogsScreen
import plus.xiaoi.module.ui.screen.me.MeScreen
import plus.xiaoi.module.ui.screen.xiaoai.ModelScreen
import plus.xiaoi.module.ui.screen.xiaoai.XiaoaiScreen
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState

/**
 * 单 Activity 壳：只做路由、标题、底栏显隐与提示。
 * 页面状态由各 screen 自己持有；本文件不写业务逻辑。
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

/** SCREENS.md §0 的 19 个路由；tab 决定底栏归属，一级/二级由 [NavStack.isRootRoute] 判断。 */
private data class XiaoiRoute(
    val title: String,
    val tab: NavStack.MainTab,
)

private object Routes {
    const val HOME = "Home"
    const val XIAOAI = "Xiaoai"
    const val MODEL = "Model"
    const val ACCESS = "Access"
    const val BRIDGE = "Bridge"
    const val TOKEN = "Token"
    const val CONNECT = "Connect"
    const val CAPABILITIES = "Capabilities"
    const val CAPABILITY_DETAIL = "CapabilityDetail"
    const val TOOLS = "Tools"
    const val PERMISSIONS = "Permissions"
    const val ME = "Me"
    const val APPEARANCE = "Appearance"
    const val CONFIG_MANAGE = "ConfigManage"
    const val DIAGNOSTICS = "Diagnostics"
    const val LOGS = "Logs"
    const val ABOUT = "About"
    const val LICENSES = "Licenses"
    const val LEGACY = "Legacy"

    private val table: Map<String, XiaoiRoute> = mapOf(
        HOME to XiaoiRoute("首页", NavStack.MainTab.HOME),
        XIAOAI to XiaoiRoute("小爱", NavStack.MainTab.XIAOAI),
        MODEL to XiaoiRoute("模型与服务商", NavStack.MainTab.XIAOAI),
        ACCESS to XiaoiRoute("接入", NavStack.MainTab.ACCESS),
        BRIDGE to XiaoiRoute("端口与服务", NavStack.MainTab.ACCESS),
        TOKEN to XiaoiRoute("令牌与局域网", NavStack.MainTab.ACCESS),
        CONNECT to XiaoiRoute("连接信息", NavStack.MainTab.ACCESS),
        CAPABILITIES to XiaoiRoute("能力清单", NavStack.MainTab.ACCESS),
        CAPABILITY_DETAIL to XiaoiRoute("能力详情", NavStack.MainTab.ACCESS),
        TOOLS to XiaoiRoute("工具清单", NavStack.MainTab.ACCESS),
        PERMISSIONS to XiaoiRoute("权限", NavStack.MainTab.ACCESS),
        ME to XiaoiRoute("我的", NavStack.MainTab.ME),
        APPEARANCE to XiaoiRoute("外观", NavStack.MainTab.ME),
        CONFIG_MANAGE to XiaoiRoute("配置管理", NavStack.MainTab.ME),
        DIAGNOSTICS to XiaoiRoute("诊断", NavStack.MainTab.ME),
        LOGS to XiaoiRoute("日志", NavStack.MainTab.ME),
        ABOUT to XiaoiRoute("关于", NavStack.MainTab.ME),
        LICENSES to XiaoiRoute("开源许可", NavStack.MainTab.ME),
        LEGACY to XiaoiRoute("兼容模式", NavStack.MainTab.ME),
    )

    /** 路由名允许 `Name:arg` 形式（仅能力详情使用），冒号前必须是白名单。 */
    fun keyOf(raw: String?): String? {
        val name = raw?.trim()?.substringBefore(':') ?: return null
        return if (table.containsKey(name)) name else null
    }

    fun argOf(raw: String): String? = raw.substringAfter(':', "").ifEmpty { null }

    fun titleOf(key: String?): String = table[key]?.title ?: "首页"

    fun tabOf(key: String): NavStack.MainTab = table.getValue(key).tab
}

@Composable
private fun XiaoiApp(initialRoute: String?, onThemeChange: (Int) -> Unit) {
    val context = LocalContext.current
    val colors = XiaoiTheme.colors
    val nav = remember {
        val key = Routes.keyOf(initialRoute)
        if (key == null) {
            NavStack()
        } else {
            NavStack(Routes.tabOf(key)).apply {
                if (!NavStack.isRootRoute(key)) push(initialRoute!!.trim())
            }
        }
    }
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

    BackHandler { back() }

    val key = Routes.keyOf(route)
    Scaffold(
        topBar = {
            XiaoiTopBar(
                title = Routes.titleOf(key),
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
            when (key) {
                Routes.HOME -> HomeScreen(
                    onNavigate = { navigate(it) },
                    onMessage = { message(it) },
                )

                Routes.XIAOAI -> XiaoaiScreen(
                    onNavigate = { navigate(it) },
                    onMessage = { message(it) },
                )

                Routes.MODEL -> ModelScreen(
                    onNavigate = { navigate(it) },
                    onMessage = { message(it) },
                )

                Routes.ACCESS -> AccessScreen(
                    onNavigate = { navigate(it) },
                    onMessage = { message(it) },
                )

                Routes.BRIDGE -> BridgeScreen(
                    onNavigate = { navigate(it) },
                    onMessage = { message(it) },
                )

                Routes.TOKEN -> TokenScreen(
                    onNavigate = { navigate(it) },
                    onMessage = { message(it) },
                )

                Routes.CONNECT -> ConnectScreen(
                    onNavigate = { navigate(it) },
                    onMessage = { message(it) },
                )

                Routes.CAPABILITIES -> CapabilitiesScreen(
                    onNavigate = { navigate(it) },
                    onMessage = { message(it) },
                )

                Routes.CAPABILITY_DETAIL -> CapabilityDetailScreen(
                    capabilityId = Routes.argOf(route),
                    onNavigate = { navigate(it) },
                    onMessage = { message(it) },
                )

                Routes.TOOLS -> ToolsScreen(
                    onNavigate = { navigate(it) },
                    onMessage = { message(it) },
                )

                Routes.PERMISSIONS -> PermissionsScreen(
                    onNavigate = { navigate(it) },
                    onMessage = { message(it) },
                )

                Routes.ME -> MeScreen(
                    themeMode = ThemeManager.get(context),
                    onThemeChange = onThemeChange,
                    onOpenLogs = { navigate(Routes.LOGS) },
                    onOpenAbout = { navigate(Routes.ABOUT) },
                    onOpenLegacyHome = { openLegacy(HomeActivity::class.java) },
                    onOpenLegacyConfig = { openLegacy(ConfigActivity::class.java) },
                    onMessage = { message(it) },
                )

                Routes.APPEARANCE -> AppearanceScreen(
                    themeMode = ThemeManager.get(context),
                    onThemeChange = onThemeChange,
                    onMessage = { message(it) },
                )

                Routes.CONFIG_MANAGE -> ConfigManageScreen(
                    onNavigate = { navigate(it) },
                    onMessage = { message(it) },
                )

                Routes.DIAGNOSTICS -> DiagnosticsScreen(
                    onNavigate = { navigate(it) },
                    onMessage = { message(it) },
                )

                Routes.LOGS -> LogsScreen(onMessage = { message(it) })
                Routes.ABOUT -> AboutScreen(onMessage = { message(it) })

                Routes.LICENSES -> LicensesScreen(onMessage = { message(it) })

                Routes.LEGACY -> LegacyScreen(
                    onNavigate = { navigate(it) },
                    onMessage = { message(it) },
                )

                else -> XiaoiPlaceholder(
                    title = "页面不存在",
                    note = "未知路由，返回首页。",
                )
            }
        }
    }
}
