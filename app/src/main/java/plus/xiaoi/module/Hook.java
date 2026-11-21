package plus.xiaoi.module;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import plus.xiaoi.module.core.hook.UsageSignal;
import plus.xiaoi.module.core.hook.XiaoiOverridePolicy;
import plus.xiaoi.module.core.prompt.PromptTemplate;
import plus.xiaoi.module.application.hook.HookStatus;

/**
 * 模块入口。全部 hook 包 try/catch，失败只记日志，不影响小爱主流程。
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "[xiaoi+] ";
    private static final String TARGET = "com.miui.voiceassist";
    /** 已验证的版本白名单，其他版本一律 no-op */
    private static final String[] SUPPORTED_VERSIONS = {"8.2.65.4714", "8.2.70.8421"};

    private static volatile Config sConfig;
    /** 供配置热更新（控制面 reload / MCP switch_model）重新安装 hook 使用。 */
    private static volatile ClassLoader sCl;
    private static volatile boolean sConfigHooked;
    private static volatile boolean sResponsesHooked;
    private static volatile boolean sChatHooked;
    private static volatile boolean sChatMarkerLogged;
    private static volatile boolean sVisionHooked;
    /** 版本行每进程只打一次（配置热更新时 Application 已就绪，读得到）。 */
    private static volatile boolean sVersionLogged;
    private static volatile boolean sFacadeHooked;
    private static volatile boolean sLlmHooked;
    private static volatile boolean sProviderHooked;
    private static volatile boolean sProviderLogged;
    /** 每个 Kind 只记一次"改写命中"，避免每次 getter 调用都刷日志。 */
    private static final boolean[] sKindLogged = new boolean[3];
    /** 每个 LLM 客户端类只记一次"开始调用"，用于确认到底走的是三方还是官方。 */
    private static final java.util.Set<String> sLlmClassLogged =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
    /** 官方客户端只告警一次，不要每次调用都刷。 */
    private static final java.util.Set<String> sOfficialWarned =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
    /** 请求头 X-Model-Provider-Id：用接口字段声明"当前走第三方 provider"，不碰提示词 */
    private static final String PROVIDER_ID = "xiaoi-plus";

    /**
     * 进程宿主：首次调用时注入；Hook 不再直接依赖 Bridge/Island。
     * 配置热更新的回调仍然回到 {@link #applyConfig(Config)}。
     */
    private static XiaoiProcessHost host() {
        XiaoiProcessHost current = XiaoiProcess.host();
        if (current != null) return current;
        return XiaoiProcess.installIfAbsent(new XiaoiProcessHostImpl(
                new XiaoiProcessHostImpl.ConfigReloader() {
                    @Override
                    public void reload(Config config) {
                        Hook.applyConfig(config);
                    }
                }));
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || !TARGET.equals(lp.packageName)) return;
        try {
            // 先注册版本来源：这里 Application 还没就绪，读不到 versionName，
            // 所以白名单判定可能退化成「放行但未知」；真正的版本在 xiaoai_status
            // 上报时才惰性补读（见 HookStatus.resolve）。
            HookStatus.setProbe(new HookStatus.Probe() {
                @Override
                public String versionName() {
                    return readVersionName();
                }

                @Override
                public boolean whitelisted(String version) {
                    return isWhitelisted(version);
                }
            });
            if (!isSupported(lp)) return;

            sCl = lp.classLoader;
            sConfig = Config.current();
            if (!sConfig.enabled()) {
                Logs.i("配置为空，仅启动能力桥等待控制面配置");
            } else {
                Logs.i("已启用 model=" + sConfig.model());
            }
            // WP3：每个小爱进程都参与控制面 + leader 选举（不依赖「主进程」命名）。
            // 是否监听端口由 BridgePolicy 决定；非 leader 仍然只 hook / 拉配置。
            host().onProcessStart(sConfig, lp.classLoader);

            hookConfigObject(lp.classLoader);
            hookProviderId(lp.classLoader);
            installVisionHook(lp.classLoader);
            hookRequestBuilder(lp.classLoader);
            hookChatRequestBuilder(lp.classLoader);
            hookLlmClients(lp.classLoader);
            // hookResponsePrefix 暂禁用：多进程下同一条回复会被注入多次（详见 CHANGELOG 已知问题）
            hookFacade(lp.classLoader);
            refreshHookStatus();
        } catch (Throwable t) {
            Logs.i("初始化失败（已忽略）: " + t);
        }
    }

    /**
     * 由控制面 reload / MCP xiaoai_switch_model 调用：替换内存配置并幂等重装 hook。
     * 任何异常只记日志，不影响小爱主流程。
     */
    public static void applyConfig(Config c) {
        if (c == null) return;
        try {
            sConfig = c;
            Config.setCurrent(c);
            logVersions();
            ClassLoader cl = sCl;
            if (cl != null) {
                hookConfigObject(cl);
                hookProviderId(cl);
                installVisionHook(cl);
                hookRequestBuilder(cl);
                hookChatRequestBuilder(cl);
                hookLlmClients(cl);
                // hookResponsePrefix 暂禁用，同上
                hookFacade(cl);
                refreshHookStatus();
            }
            host().updateToken(c.token());
            Logs.i("配置已热更新 model=" + c.model()
                    + " marker=" + c.marker() + " bridge=" + c.bridge());
        } catch (Throwable t) {
            Logs.i("配置热更新失败（已忽略）: " + t);
        }
    }

    /** 读小爱 versionName；早期加载（Application 未就绪）返回 null。 */
    private static String readVersionName() {
        return versionNameOf(TARGET);
    }

    /** 读任意包的 versionName；Application 未就绪或包不存在时返回 null。 */
    private static String versionNameOf(String pkg) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            android.content.pm.PackageManager pm =
                    (android.content.pm.PackageManager) app.getClass()
                            .getMethod("getPackageManager").invoke(app);
            return pm.getPackageInfo(pkg, 0).versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 每进程打一次"模块 + 小爱"版本行。
     *
     * <p>为什么在这里打而不是启动行：进程刚起来时 Application 还没就绪，读不到
     * PackageManager，启动行只能写 {@code version=?}。配置热更新发生在控制面连上之后，
     * 那时读得到——这条是"一份日志能不能对上兼容性矩阵"的唯一依据。</p>
     */
    private static void logVersions() {
        if (sVersionLogged) return;
        sVersionLogged = true;
        String module = versionNameOf("plus.xiaoi.module");
        String xiaoi = readVersionName();
        Logs.i("版本: 模块 " + (module == null ? "?" : module)
                + " · 小爱 " + (xiaoi == null ? "?" : xiaoi));
    }

    private static boolean isWhitelisted(String version) {
        for (String s : SUPPORTED_VERSIONS) {
            if (s.equals(version)) return true;
        }
        return false;
    }

    private boolean isSupported(XC_LoadPackage.LoadPackageParam lp) {
        try {
            String v = readVersionName();
            if (v == null) {
                Logs.i("版本未知（早期加载），降级放行");
                HookStatus.reset("", HookStatus.COMPAT_PROBE);
                return true;
            }
            if (isWhitelisted(v)) {
                HookStatus.reset(v, HookStatus.COMPAT_WHITELIST);
                return true;
            }
            Logs.i("版本不在白名单: " + v + "，不启用");
            HookStatus.reset(v, HookStatus.COMPAT_NONE);
            return false;
        } catch (Throwable ignored) {}
        return true;
    }

    /** sm.a：模型/端点/密钥的 getter，用于重定向请求 */

    /** sm.f.getSupportsVision()：告诉小爱“当前模型支持看图”，否则图片会被替换成占位文本 */
    private static void installVisionHook(ClassLoader cl) {
        try {
            Class<?> f = Class.forName("sm.f", false, cl);
            XposedBridge.hookMethod(f.getMethod("getSupportsVision"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Config c = Config.current();
                    if (c != null && XiaoiOverridePolicy.supportsVision(c.enabled())) {
                        param.setResult(Boolean.TRUE);
                    }
                }
            });
            Logs.i("supportsVision=true 已启用");
            sVisionHooked = true;
        } catch (Throwable t) {
            Logs.i("supportsVision hook 失败: " + t);
        }
    }

    /**
     * 汇总本进程各 hook 的安装结果，供 {@code xiaoai_status} 上报。
     * 只反映"挂上了没有"，不代表 hook 逻辑在运行时一定被调用。
     */
    private static void refreshHookStatus() {
        HookStatus.markConfig(sConfigHooked);
        HookStatus.markProvider(sProviderHooked);
        HookStatus.markVision(sVisionHooked);
        HookStatus.markFacade(sFacadeHooked);
        HookStatus.markUsage(sLlmHooked);
    }

    private static void hookConfigObject(ClassLoader cl) {
        if (sConfigHooked) return;
        try {
            Class<?> a = Class.forName("sm.a", false, cl);
            hookStr(a, "getBaseUrl", Kind.BASE);
            hookStr(a, "getApiKey", Kind.KEY);
            hookStr(a, "getModel", Kind.MODEL);
            sConfigHooked = true;
            Logs.i("配置 hook 完成");
        } catch (Throwable t) {
            Logs.i("配置 hook 失败: " + t);
        }
    }

    /** qm.j.buildResponsesRequest：Responses 链路，marker 注入 + 图文条件分流 */
    private static void hookRequestBuilder(ClassLoader cl) {
        // 即使模块暂停（enabled=false）也要挂：此时请求走小爱官方模型，
        // 统计要能区分「三方 / 官方」两边的调用量。
        if (sResponsesHooked) return;
        try {
            Class<?> c = Class.forName("qm.j", false, cl);
            Method target = null;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().contains("buildResponsesRequest")) { target = m; break; }
            }
            if (target == null) {
                Logs.i("qm.j 未找到 buildResponsesRequest 方法（进程 " + android.os.Process.myPid() + "）");
                return;
            }

            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // P2-C：Responses 链路（instructions）——任何异常都保持原结果。
                    applyPromptInjection(param);
                }
            });
            Logs.i("Responses 请求 hook 完成");
            sResponsesHooked = true;
        } catch (Throwable t) {
            Logs.i("Responses 请求 hook 失败: " + t);
        }
    }

    /**
     * qm.i.buildOpenAIRequest：provider=openai（chat/completions）链路。
     * 与 qm.j（Responses）是互斥分支；能力桥/agent.submit 走这里时也必须注入，
     * 否则只有 App 内 Responses 请求带标记。规则前置到 system 消息，避免被长提示稀释。
     */
    private static void hookChatRequestBuilder(ClassLoader cl) {
        // 同 hookRequestBuilder：暂停态也要挂，用于统计官方模型调用。
        if (sChatHooked) return;
        try {
            Class<?> c = Class.forName("qm.i", false, cl);
            Method target = null;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().contains("buildOpenAIRequest")) { target = m; break; }
            }
            if (target == null) {
                Logs.i("qm.i 未找到 buildOpenAIRequest 方法（进程 " + android.os.Process.myPid() + "）");
                return;
            }

            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // P2-C：chat/completions 链路（messages 里的 system）——失败即原样。
                    applyPromptInjection(param);
                }
            });
            Logs.i("chat 请求 hook 完成");
            sChatHooked = true;
        } catch (Throwable t) {
            Logs.i("chat 请求 hook 失败: " + t);
        }
    }

    // ------------------------------------------------------------- P2-C 提示词注入

    /** 记住小爱的 kotlinx {@code Json} 单例与 {@code parseToJsonElement}，只解析一次。 */
    private static volatile Object sKotlinJson;
    private static volatile java.lang.reflect.Method sParseToJsonElement;
    /** 请求体顶层键只打印一次（debug 档），用于核对字段名，不打印任何值。 */
    private static volatile boolean sRequestKeysLogged;

    /**
     * 请求构造后的提示词注入。
     *
     * <p>链路：{@code JsonObject.toString()} → {@link PromptInjector}（纯函数）→
     * 小爱的 kotlinx {@code Json.parseToJsonElement} → {@code setResult}。</p>
     *
     * <p><b>失败即原样</b>：任何异常都只记一行日志并保持小爱好原来的请求，
     * 绝不因为提示词配置影响对话。</p>
     */
    private static void applyPromptInjection(XC_MethodHook.MethodHookParam param) {
        try {
            Config c = Config.current();
            if (c == null) return;
            if (!c.promptEnabled() && !c.memoryInject()) return;
            Object result = param.getResult();
            if (result == null) return;
            if (!result.getClass().getName().contains("JsonObject")) return;

            String json = String.valueOf(result);
            logRequestKeysOnce(json);
            String outline = c.memoryInject() ? MemoryFiles.outline(c.memoryInjectLines()) : "";
            PromptInjector.Options options = new PromptInjector.Options(
                    c.promptEnabled(), c.promptMode(), c.promptText(),
                    c.memoryInject(), c.memoryInjectLines(), outline);
            PromptInjector.Result injected = PromptInjector.apply(json, options, promptVars(c));
            if (!injected.changed) {
                PromptRuntime.recordSkipped(injected.note);
                return;
            }
            Object parsed = parseJsonElement(injected.json);
            if (parsed == null) {
                PromptRuntime.recordFailed("parse-" + injected.note);
                return;
            }
            param.setResult(parsed);
            PromptRuntime.recordInjected(injected.note);
        } catch (Throwable t) {
            PromptRuntime.recordFailed(t.getClass().getSimpleName());
        }
    }

    /** 模板变量：只在注入时取一次。 */
    private static PromptTemplate.Vars promptVars(Config c) {
        String date = "";
        String time = "";
        try {
            long now = System.currentTimeMillis();
            date = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(new java.util.Date(now));
            time = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(now));
        } catch (Throwable ignored) {
        }
        String device = "";
        try {
            device = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL;
        } catch (Throwable ignored) {
        }
        return new PromptTemplate.Vars(date, time, c.model(), device);
    }

    /**
     * 用小爱自己的 kotlinx {@code Json} 单例把字符串解析回 {@code JsonElement}。
     *
     * <p>类名/字段名是版本相关的（当前 {@code s92.b} 的静态字段 {@code d}），
     * 解析不了就返回 null，调用方保持原请求。加载失败会记一次日志，便于适配新版本。</p>
     */
    private static Object parseJsonElement(String text) {
        ClassLoader cl = sCl;
        if (cl == null || text == null || text.length() == 0) return null;
        try {
            Object parser = sKotlinJson;
            if (parser == null) {
                Class<?> holder = Class.forName("s92.b", false, cl);
                parser = holder.getField("d").get(null);
                if (parser == null) return null;
                sParseToJsonElement = parser.getClass()
                        .getMethod("parseToJsonElement", String.class);
                sKotlinJson = parser;
            }
            Object element = sParseToJsonElement.invoke(parser, text);
            if (element == null) return null;
            if (!element.getClass().getName().contains("JsonObject")) return null;
            return element;
        } catch (Throwable t) {
            // 每个进程只记一次：类名/字段名变了是适配问题，不是请求问题。
            if (!sParseFailedLogged) {
                sParseFailedLogged = true;
                Logs.i("提示词注入：Json 单例不可用（" + t.getClass().getSimpleName()
                        + "），已跳过注入");
            }
            return null;
        }
    }

    private static volatile boolean sParseFailedLogged;

    /** debug 档打印一次请求体顶层键名（无值），用来核对字段名是否与假设一致。 */
    private static void logRequestKeysOnce(String json) {
        if (sRequestKeysLogged) return;
        sRequestKeysLogged = true;
        try {
            Object v = plus.xiaoi.module.Json.read(json);
            if (!(v instanceof java.util.Map)) return;
            StringBuilder sb = new StringBuilder();
            for (Object k : ((java.util.Map<?, ?>) v).keySet()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(String.valueOf(k));
            }
            Logs.event(plus.xiaoi.module.core.LogLevel.DEBUG, "D", "prompt",
                    "请求顶层键=" + sb);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 调用量统计：挂在真正的模型客户端上，一次模型调用记一行。     *
     * 实际链路是 InstrumentedLlmClient(ft.j) → DynLlmClient(qm.c) → 具体客户端，
     * 具体客户端才是发网络请求的地方：
     * <ul>
     *   <li>{@code qm.i} OpenAIClient / {@code qm.j} ResponsesClient / {@code qm.b} AnthropicClient → 三方</li>
     *   <li>{@code um.h} TaiyiLlmClient（mimo-omni / 本地 AI）→ 官方</li>
     * </ul>
     * 不挂 qm.c/ft.j 这两个分发/埋点包装类，否则同一次调用会被记两次。
     * 主对话 / 子调用按参数判定（tools 非空，或消息数 ≥6），与
     * {@link UsageCounter#mainCall} 的口径一致。
     */
    private static void hookLlmClients(ClassLoader cl) {
        if (sLlmHooked) return;
        boolean any = false;
        any |= hookStreamCompletion(cl, "qm.i", true);
        any |= hookStreamCompletion(cl, "qm.j", true);
        any |= hookStreamCompletion(cl, "qm.b", true);
        any |= hookStreamCompletion(cl, "um.h", false);
        sLlmHooked = any;
    }

    private static boolean hookStreamCompletion(ClassLoader cl, final String className, final boolean third) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            Method target = null;
            for (Method m : c.getDeclaredMethods()) {
                if ("streamCompletion".equals(m.getName())) { target = m; break; }
            }
            if (target == null) {
                Logs.i(className + " 未找到 streamCompletion（进程 " + android.os.Process.myPid() + "）");
                return false;
            }
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    recordLlmCall(third, className, param.args);
                }
            });
            Logs.i(className + " 调用统计 hook 完成（" + (third ? "三方" : "官方") + "）");
            return true;
        } catch (Throwable t) {
            Logs.i(className + " 调用统计 hook 失败: " + t);
            return false;
        }
    }

    /** 参数固定为 (messages, tools, sm.a config, continuation)。 */
    private static void recordLlmCall(boolean third, String className, Object[] args) {
        try {
            int messages = sizeOf(args, 0);
            int tools = sizeOf(args, 1);
            String reported = modelOf(args, 2);
            String fallback = "";
            Config c = Config.current();
            if (c != null) fallback = c.model();
            String model = UsageSignal.modelOrFallback(reported, fallback);
            boolean main = UsageSignal.mainCall(messages, tools);
            HookStatus.markCall();
            // 每个客户端类只记一次：确认这次请求到底从哪个客户端出去。
            if (sLlmClassLogged.add(className)) {
                Logs.i("模型调用: " + (third ? "三方" : "官方") + " " + className + " model=" + model);
            }
            // 已启用三方模型，却仍然是官方客户端在跑，且 getter 从未被读取 →
            // 这个版本装上了也不生效，是"hook 成功但功能不生效"的唯一硬证据。
            if (!third && c != null && c.enabled() && !HookStatus.rewriteHit()
                    && sOfficialWarned.add(className)) {
                Logs.w("第三方模型已启用，但配置 getter 从未被读取（" + className
                        + "）：当前小爱版本可能不兼容，请求仍走官方模型");
            }
            Usage.record(third, main, model);
        } catch (Throwable ignored) {
        }
    }

    private static int sizeOf(Object[] args, int idx) {
        if (args == null || idx >= args.length) return 0;
        Object v = args[idx];
        if (v instanceof java.util.List) return ((java.util.List<?>) v).size();
        if (v instanceof java.util.Collection) return ((java.util.Collection<?>) v).size();
        return 0;
    }

    private static String modelOf(Object[] args, int idx) {
        String m = call(args.length > idx ? args[idx] : null, "getModel");
        return m;
    }

    /** 反射调用无参 getter，失败返回空串。 */
    private static String call(Object target, String method) {
        if (target == null) return "";
        try {
            Object v = target.getClass().getMethod(method).invoke(target);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 响应侧前缀状态：距上条 delta 超过该间隔视为新一轮对话，重新注入。 */
    private static volatile long sLastDeltaAt;

    /**
     * 响应侧前缀（v0.8.1）：hook sm.b.c / sm.b.d 的构造器——
     * c = chat/completions 的 delta 文本事件（qm.i 内联 emit 的唯一构造点），
     * d = Responses 的首段文本事件。改写构造参数把前缀拼在正文最前，
     * 不依赖模型服从提示词，前缀必然出现在回复开头。
     * 新一轮判定：距上一条 delta > 15 秒（一轮回复的分段间隔远小于此）。
     */
    private static void hookResponsePrefix(ClassLoader cl) {
        int hooked = 0;
        for (final String inner : new String[]{"c", "d"}) {
            try {
                Class<?> evt = Class.forName("sm.b$" + inner, false, cl);
                for (final java.lang.reflect.Constructor<?> ctor : evt.getDeclaredConstructors()) {
                    try {
                        ctor.setAccessible(true);
                        XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    applyResponsePrefix(param);
                                } catch (Throwable ignored) {}
                            }
                        });
                        hooked++;
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        Logs.i("响应前缀 hook 完成（sm.b.c/d 构造器，共 " + hooked + " 个）");
    }

    /** 当前模板渲染出的前缀（幂等守卫用）；无模板时返回不可能匹配的哨兵。 */
    private static String prefixGuard() {
        Config c = Config.current();
        if (c == null || !c.enabled()) return "\u0000__no_guard__";
        String tpl = c.markerTemplate();
        if (tpl == null || tpl.trim().length() == 0) return "\u0000__no_guard__";
        String p = tpl.replace("{model}", c.model()).trim();
        return p.length() == 0 ? "\u0000__no_guard__" : p;
    }

    private static void applyResponsePrefix(XC_MethodHook.MethodHookParam param) {
        if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
        String original = (String) param.args[0];
        if (original.length() == 0) return;

        // 全程持锁：多线程并发 emit 时只有第一条能进注入分支
        synchronized (Hook.class) {
            // 幂等守卫：kotlin 合成构造器会委托链式调用，同一条 delta 会被多层 hook 改写；
            // 已带前缀的文本直接放行，保证任意层数都只注入一次。
            if (original.startsWith(prefixGuard())) return;
            long now = android.os.SystemClock.elapsedRealtime();
            boolean newRound = now - sLastDeltaAt > 15_000L;
            sLastDeltaAt = now;
            if (!newRound) return;

            Config c = Config.current();
            if (c == null || !c.enabled()) return;
            String tpl = c.markerTemplate();
            if (tpl == null || tpl.trim().length() == 0) return;
            String prefix = tpl.replace("{model}", c.model()).trim();
            if (prefix.length() == 0) return;

            param.args[0] = prefix + " " + original;
            // 拉远冷却基线：同一条回复的后续 delta（含多线程并发）都落回冷却内；
            // 下一轮对话距本条 >60s，重新注入。
            sLastDeltaAt = now + 45_000L;
            Logs.i("输出前缀已注入响应: " + prefix);
        }
    }

    /**
     * 抓 OsbotFacadeImpl 实例。无论是否已配置都挂 ctor：首次推送配置需要桥先起来；
     * 桥自身只启动一次，token 与安装流程由其内部判断，失败只记日志。
     */
    private static void hookFacade(ClassLoader cl) {
        if (sFacadeHooked) return;
        try {
            Class<?> impl = Class.forName("com.aios.osbot.facade.OsbotFacadeImpl", false, cl);
            for (final Constructor<?> ctor : impl.getDeclaredConstructors()) {
                try {
                    ctor.setAccessible(true);
                    XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                host().onFacadeReady(cl, param.thisObject);
                            } catch (Throwable t) {
                                Logs.i("能力桥启动失败（已忽略）: " + t);
                            }
                        }
                    });
                } catch (Throwable t) {
                    Logs.i("能力桥 ctor hook 失败: " + t);
                }
            }
            Logs.i("能力桥 hook 完成");
            sFacadeHooked = true;
        } catch (Throwable t) {
            Logs.i("能力桥 hook 失败（已忽略）: " + t);
        }
    }

    /**
     * sm.a.getModelProviderId() → 请求头 X-Model-Provider-Id。
     * 小爱本来就会把这个 getter 的值写进请求头（qm.i/qm.j 的 header 构造），
     * 所以用它声明第三方 provider 是最"原生"的非文本渠道：模型看不到，日志/上游能看到。
     */
    private static void hookProviderId(ClassLoader cl) {
        if (sProviderHooked) return;
        try {
            Class<?> a = Class.forName("sm.a", false, cl);
            XposedBridge.hookMethod(a.getMethod("getModelProviderId"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Config c = Config.current();
                        if (c == null) return;
                        String id = XiaoiOverridePolicy.providerId(c.enabled(), c.marker(), PROVIDER_ID);
                        if (id == null) return;
                        param.setResult(id);
                        if (!sProviderLogged) {
                            sProviderLogged = true;
                            Logs.i("模型标识已改走请求头 X-Model-Provider-Id=" + PROVIDER_ID);
                            host().showIsland(c.model(), false);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            sProviderHooked = true;
            Logs.i("provider 标识 hook 完成");
        } catch (Throwable t) {
            Logs.i("provider 标识 hook 失败（已忽略）: " + t);
        }
    }

    private enum Kind { BASE, KEY, MODEL }

    private static void hookStr(Class<?> cls, String method, final Kind kind) {
        try {
            XposedBridge.hookMethod(cls.getMethod(method), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Config c = Config.current();
                        if (c == null) return;
                        String v = kind == Kind.BASE ? c.baseUrl()
                                 : kind == Kind.KEY ? c.apiKey()
                                 : c.model();
                        String applied = XiaoiOverridePolicy.override(c.enabled(), v);
                        if (applied != null) {
                            param.setResult(applied);
                            onOverride(kind, applied);
                        }
                    } catch (Throwable t) {
                        Logs.i("hook " + method + " 处理失败: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            Logs.i("hook " + method + " 失败: " + t);
        }
    }

    /**
     * 记录一次"改写真的被读到了"。
     *
     * <p>这是区分「hook 装上了」和「hook 生效了」的唯一证据：小爱自己调用
     * {@code sm.a} 的 getter 时才会走到这里。若日志里始终没有这一行，却出现了
     * 官方模型的调用，就说明该版本根本不读这些 getter——装上了也是白装。</p>
     *
     * <p>每种 Kind 每进程只记一次；端点只记 host，密钥只记"已设置"，避免把
     * 敏感内容写进日志。</p>
     */
    private static void onOverride(Kind kind, String value) {
        HookStatus.markRewrite();
        int idx = kind == Kind.BASE ? 0 : kind == Kind.KEY ? 1 : 2;
        synchronized (sKindLogged) {
            if (sKindLogged[idx]) return;
            sKindLogged[idx] = true;
        }
        Logs.i(logLine(kind, value));
    }

    /**
     * 一行"改写命中"。
     *
     * <p>密钥故意不写成 {@code apiKey=...}：日志在 App 侧会再过一遍 {@code Redact}，
     * 键值形状会把「已设置（51 字符）」也当成秘密值吃掉，剩下一截残句。
     * 这里只报长度，不报内容，也不制造键值形状。</p>
     */
    private static String logLine(Kind kind, String value) {
        if (kind == Kind.KEY) {
            return "改写命中: apiKey 已设置（" + value.length() + " 字符）";
        }
        return "改写命中: " + label(kind) + "=" + display(kind, value);
    }

    private static String label(Kind kind) {
        return kind == Kind.BASE ? "baseUrl" : kind == Kind.KEY ? "apiKey" : "model";
    }

    /** 端点只留 host：路径里可能带租户 key，不能整条打出来。 */
    private static String display(Kind kind, String value) {
        if (kind == Kind.MODEL) return value;
        return plus.xiaoi.module.core.Redact.host(value);
    }
}
