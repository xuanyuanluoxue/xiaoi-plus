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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.platform.LocalDensity
import plus.xiaoi.module.ui.components.LocalXiaoiScrollY
import plus.xiaoi.module.ui.components.XiaoiTopBarHeight
import top.yukonga.miuix.kmp.blur.drawBackdrop
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
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
        // 沉浸式：两条系统栏都透明，内容延伸到小白条与状态栏下方（顶栏自带 statusBarsPadding）
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
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
    // 滚动量单源：各页写、顶栏读。换页先归零，避免上一页的滚动量带到新页。
    val scrollYState = remember { mutableIntStateOf(0) }
    LaunchedEffect(route) { scrollYState.intValue = 0 }
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
    val tabs = NavStack.MainTab.values()
    val pagerState = rememberPagerState(
        initialPage = tabs.indexOf(nav.tab()).coerceAtLeast(0),
    ) { tabs.size }
    LaunchedEffect(pagerState.currentPage, root) {
        if (root) {
            val t = tabs.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
            if (nav.tab() != t) {
                nav.switchTab(t)
                route = nav.current()
            }
        }
    }
    LaunchedEffect(nav.tab(), root) {
        if (root) {
            val target = tabs.indexOf(nav.tab())
            if (target >= 0 && pagerState.currentPage != target) pagerState.animateScrollToPage(target)
        }
    }
    // BLUR 分支需要底栏在内容坐标系中的位置；FULL/SOLID 不使用。
    var contentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Scaffold(
        topBar = {},  // 顶栏改为内容区浮层（见下方 TopBlurScrim + XiaoiTopBar）
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
                // 所有页面都不再留白：内容直接穿到顶栏下方，
                // 顶部呼吸感由各页自己的 xiaoiTopBarHeight() + 8dp 负责。
                .padding(top = 0.dp, bottom = 0.dp),
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
                CompositionLocalProvider(
                    LocalXiaoiPageActions provides pageActions,
                    LocalXiaoiScrollY provides scrollYState,
                ) {
                    val renderKey: @Composable (String) -> Unit = { _k ->
                        when (_k) {
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
                    if (root) {
                        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                            val pageTab = tabs[page]
                            screenStates.SaveableStateProvider(pageTab.root!!) { renderKey(pageTab.root!!) }
                        }
                    } else {
                        screenStates.SaveableStateProvider(key ?: Routes.HOME) { renderKey(key ?: Routes.HOME) }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                TopBlurScrim(
                    source = glassBackdrop,
                    scrollY = scrollYState,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            XiaoiTopBarOverlay(
                scrollY = scrollYState,
                title = Routes.titleOf(key),
                onBack = if (nav.isRoot()) null else ({ back() }),
                actions = { pageActions.actions?.invoke(this) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            if (root) {
                XiaoiBottomBar(
                    current = nav.tab(),
                    onSelect = { tab ->
                        nav.switchTab(tab)
                        route = nav.current()
                    },
                    backdrop = glassBackdrop,
                    contentCoordinates = contentCoordinates,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                )
            }
        }
    }
}


/** 顶栏下方渐变模糊带：真采样页面内容做高斯模糊，再叠「底色→透明」渐变遮罩。 */
@androidx.annotation.RequiresApi(33)
@androidx.compose.runtime.Composable
private fun TopBlurScrim(
    source: plus.xiaoi.module.ui.components.glass.GlassBackdropSource,
    scrollY: MutableIntState,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    val backdrop = source.miuixBackdrop as? top.yukonga.miuix.kmp.blur.Backdrop ?: return
    val colors = plus.xiaoi.module.ui.theme.XiaoiTheme.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topInset = androidx.compose.foundation.layout.WindowInsets.statusBars
        .asPaddingValues().calculateTopPadding()
    // 顶部时完全不可见（alpha = 0），滚起来才淡入，避免静止时压暗标题。
    val scrolled = scrollY.intValue > with(density) { XiaoiScrollThreshold.roundToPx() }
    val blurAlpha by animateFloatAsState(if (scrolled) 1f else 0f, label = "xiaoiTopBlurAlpha")
    val radiusPx = with(density) { 22.dp.toPx() }
    val effect = androidx.compose.runtime.remember(radiusPx) {
        plus.xiaoi.module.ui.components.glass.GlassNativeBlur.effect(radiusPx)
    }
    androidx.compose.foundation.layout.Box(
        modifier
            .fillMaxWidth()
            .height(topInset + XiaoiTopBarHeight)  // 正好等于顶栏高度，避免盖到内容
            .alpha(blurAlpha)
            .topBlurBackdrop(backdrop, effect, colors.bg),
    )
}

private fun androidx.compose.ui.Modifier.topBlurBackdrop(
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop,
    effect: android.graphics.RenderEffect,
    bg: androidx.compose.ui.graphics.Color,
): androidx.compose.ui.Modifier = this.then(
    androidx.compose.ui.Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { androidx.compose.ui.graphics.RectangleShape },
        effects = {
            renderEffect = effect.asComposeRenderEffect()
            downscaleFactor = 1
        },
        onDrawSurface = {
            // 1) 用竖直 alpha 渐变把模糊层本身掩掉：顶部满强度 → 底部透明（渐变式模糊的关键）
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    0.0f to androidx.compose.ui.graphics.Color.Black,
                    0.55f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.85f),
                    0.82f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f),
                    1.0f to androidx.compose.ui.graphics.Color.Transparent,
                ),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
            // 2) 极淡底色，保证标题可读
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    0.0f to bg.copy(alpha = 0.42f),
                    0.6f to bg.copy(alpha = 0.16f),
                    1.0f to bg.copy(alpha = 0f),
                ),
            )
        },
    ),
)

/** 判定「内容已经滚起来」的阈值：超过它就切到半透明 + 背景模糊。 */
private val XiaoiScrollThreshold = 8.dp

/**
 * 顶栏浮层：默认实色（alpha 1.0）；页面内容向上滚过 [XiaoiScrollThreshold] 后
 * 渐变到半透明（alpha 0.35），配合 [TopBlurScrim] 的背景模糊与下边缘渐变。
 */
@Composable
private fun XiaoiTopBarOverlay(
    scrollY: MutableIntState,
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrolled = scrollY.intValue > with(LocalDensity.current) { XiaoiScrollThreshold.roundToPx() }
    val barAlpha by animateFloatAsState(
        targetValue = if (scrolled) 0.35f else 1f,
        label = "xiaoiTopBarAlpha",
    )
    Box(
        modifier
            .fillMaxWidth()
            .background(XiaoiTheme.colors.bg.copy(alpha = barAlpha)),
    ) {
        XiaoiTopBar(title = title, onBack = onBack, actions = actions)
    }
}
