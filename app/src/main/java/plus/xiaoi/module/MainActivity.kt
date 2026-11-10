package plus.xiaoi.module

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import plus.xiaoi.module.core.NavStack
import plus.xiaoi.module.ui.components.LocalXiaoiPageActions
import plus.xiaoi.module.ui.components.XiaoiBottomBar
import plus.xiaoi.module.ui.components.XiaoiPageActionsState
import plus.xiaoi.module.ui.components.XiaoiTopBar
import plus.xiaoi.module.ui.components.glass.GlassSupport
import plus.xiaoi.module.ui.components.glass.glassBackdropSource
import plus.xiaoi.module.ui.components.glass.rememberGlassBackdrop
import plus.xiaoi.module.ui.components.glass.rememberGlassBarReservedHeight
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
        // 沉浸式：状态栏/导航栏透明，内容延伸到系统栏下方，避免底部出现一条遮挡的背景色
        if (Build.VERSION.SDK_INT >= 21) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
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

/**
 * Activity 重建（外观页切换玻璃底栏、主题切换等）时保存/恢复整条导航栈。
 * 保存的是旧到新的路由列表，首项即当前 tab 的根路由；非法状态回退默认首页。
 */
private val NavStackSaver = listSaver<NavStack, String>(
    save = { ArrayList(it.snapshot()) },
    restore = { NavStack.restore(it) },
)

@Composable
private fun XiaoiApp(initialRoute: String?, onThemeChange: (Int) -> Unit) {
    val context = LocalContext.current
    val colors = XiaoiTheme.colors
    val nav = rememberSaveable(saver = NavStackSaver) {
        val key = Routes.keyOf(initialRoute)
        if (key == null) {
            NavStack()
        } else {
            NavStack(Routes.tabOf(key)).apply {
                if (!NavStack.isRootRoute(key)) push(initialRoute!!.trim())
            }
        }
    }
    // 当前页由恢复后的栈顶派生，避免与 nav 各自保存而错位。
    var route by remember { mutableStateOf(nav.current()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 每个路由一份页面动作容器：screen 通过 LocalXiaoiPageActions 把动作放进顶栏。
    val pageActions = remember(route) { XiaoiPageActionsState() }
    // 二级页返回后恢复搜索/筛选/滚动位置，而不是重建页面。
    val screenStates = rememberSaveableStateHolder()

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

    // 一级页返回由普通 BackHandler 处理（非首页回首页，首页退出）；
    // 二级页交给 PredictiveBackHandler，API 34+ 播放系统预测性返回动画。
    BackHandler(enabled = nav.isRoot()) { back() }

    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = !nav.isRoot()) { progress ->
        try {
            progress.collect { event -> backProgress = event.progress }
            backProgress = 0f
            back()
        } catch (c: CancellationException) {
            backProgress = 0f
            throw c
        }
    }

    val key = Routes.keyOf(route)
    val dimensions = XiaoiTheme.dimens
    val glassMode = GlassSupport.rememberMode()
    // 采样层要带页面底色，否则底栏在空白带采到的是全透明层（只有半透明填充）。
    val glassBackdrop = rememberGlassBackdrop(glassMode, colors.bg)
    val barReserved = rememberGlassBarReservedHeight()
    val root = nav.isRoot()
    // BLUR 分支需要底栏在内容坐标系中的位置；FULL/SOLID 不使用。
    var contentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Scaffold(
        topBar = {
            XiaoiTopBar(
                title = Routes.titleOf(key),
                onBack = if (nav.isRoot()) null else ({ back() }),
                actions = { pageActions.actions?.invoke(this) },
            )
        },
        // 底栏悬浮在内容之上：不占用 Scaffold bottomBar，避免把页面挤窄。
        snackbarHost = {
            Box(
                Modifier.padding(
                    bottom = if (root) barReserved + dimensions.glassBarGap else 0.dp,
                ),
            ) {
                SnackbarHost(snackbarHostState)
            }
        },
        containerColor = colors.bg,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { if (it.isAttached) contentCoordinates = it }
                    .graphicsLayer {
                        val p = backProgress.coerceIn(0f, 1f)
                        scaleX = 1f - 0.06f * p
                        scaleY = 1f - 0.06f * p
                        alpha = 1f - 0.18f * p
                    }
                    .glassBackdropSource(glassBackdrop)
                    // 一级页内容底部预留玻璃条高度；二级页隐藏底栏后不留白。
                    .padding(bottom = 0.dp), // TEST: 让内容从玻璃底栏下穿过，验证折射
            ) {
                CompositionLocalProvider(LocalXiaoiPageActions provides pageActions) {
                    screenStates.SaveableStateProvider(route) {
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
                                onNavigate = { navigate(it) },
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
                            Routes.ABOUT -> AboutScreen(
                                onNavigate = { navigate(it) },
                                onMessage = { message(it) },
                            )

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
            if (root) {
                XiaoiBottomBar(
                    current = nav.tab(),
                    onSelect = { tab ->
                        nav.switchTab(tab)
                        route = nav.current()
                    },
                    backdrop = glassBackdrop,
                    contentCoordinates = contentCoordinates,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
