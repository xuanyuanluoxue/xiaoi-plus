package plus.xiaoi.module;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

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
    /** 每个客户端类采样一次 tools 参数形状（只打键名与类型，不打正文）。 */
    private static final java.util.Set<String> sToolSampled =
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
            syncCustomPrompt(c);
            syncInbound(c);
            Logs.i("配置已热更新 model=" + c.model()
                    + " marker=" + c.marker() + " bridge=" + c.bridge());
        } catch (Throwable t) {
            Logs.i("配置热更新失败（已忽略）: " + t);
        }
    }

    /**
     * 把提示词写进官方 {@code custom_prompt.md} 并让缓存失效。
     *
     * <p>这是 8.2.70 上唯一被证明「改得到」的路径：官方工具
     * {@code UpdateCustomPromptTool} 做的就是「写文件 + invalidateMemoryCache」。
     * 成功时置位 {@code sFilePromptActive}，请求体注入随即让位，避免双重追加。</p>
     */
    private static void syncCustomPrompt(Config c) {
        try {
            String block = "";
            if (c.promptEnabled() || c.memoryInject()) {
                String outline = c.memoryInject() ? MemoryFiles.outline(c.memoryInjectLines()) : "";
                PromptInjector.Options options = new PromptInjector.Options(
                        c.promptEnabled(), c.promptMode(), c.promptText(),
                        c.memoryInject(), c.memoryInjectLines(), outline);
                block = PromptInjector.block(options, promptVars(c));
            }
            boolean replace = c.promptEnabled() && PromptInjector.isReplace(c.promptMode())
                    && c.promptText() != null && c.promptText().trim().length() > 0;
            sFilePromptActive = PromptFiles.sync(block, replace);
            if (sFilePromptActive && block.length() > 0) {
                PromptRuntime.recordInjected("custom-prompt-file");
            }
        } catch (Throwable t) {
            sFilePromptActive = false;
            Logs.i("自定义提示词接线失败: " + t);
        }
    }

    /** 官方 custom_prompt.md 已接管注入时，请求体侧的两个兜底都让位。 */
    private static volatile boolean sFilePromptActive;

    /**
     * 双向枢纽（默认关）：把 8771 端点写进小爱的 {@code mcp/mcp_servers.json}。
     *
     * <p>小爱要重启（或调 {@code reload_mcp_config}）才会加载新配置；这里只负责
     * 把文件写对。开关关掉时精确移除我们的条目，不动别人的服务。</p>
     */
    private static void syncInbound(Config c) {
        try {
            boolean on = Config.inboundEnabledPref();
            int port = host().port();
            Logs.i("双向枢纽: inbound=" + on + " port=" + port);
            boolean ok = McpServerConfig.sync(on, port, c.token());
            if (on) {
                Logs.i("双向枢纽: mcp_servers.json " + (ok ? "已写入" : "写入失败")
                        + "（重启小爱后生效）");
            }
        } catch (Throwable t) {
            Logs.i("双向枢纽接线失败: " + t);
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
            hookSystemPromptProviders(cl);
            sConfigHooked = true;
            Logs.i("配置 hook 完成");
        } catch (Throwable t) {
            Logs.i("配置 hook 失败: " + t);
        }
    }

    /**
     * 系统提示词注入：把「取 system 提示词」的几个出口统一挂上。
     *
     * <p>请求体里那条 30 KB system 不是从消息列表来的（{@code MessageRole} 只有
     * USER/ASSISTANT/TOOL），而是从提示词提供方现取。已知四类出口：</p>
     * <ul>
     *   <li>{@code sm.a#getSystemPrompt()} —— 模型配置对象，实测返回 29870 字</li>
     *   <li>{@code com.aios.osbot.facade.b$a#getResolvedSystemPrompt()} —— facade 解析后的版本</li>
     *   <li>{@code rj.d0#getResolvedSystemPrompt()} —— 同名的另一处实现（混淆短名）</li>
     *   <li>{@code com.aios.osbot.agent.unified.AgentDefinition#getSystemPrompt()}</li>
     * </ul>
     *
     * <p>注入块带标记，{@code PromptInjector.merge} 幂等：同一条串被多个出口依次处理
     * 也只会出现一份。失败即原样，绝不 setResult。</p>
     */
    private static void hookSystemPromptProviders(ClassLoader cl) {
        hookSystemPromptMethod(cl, "sm.a", "getSystemPrompt");
        hookSystemPromptMethod(cl, "com.aios.osbot.facade.b$a", "getResolvedSystemPrompt");
        hookSystemPromptMethod(cl, "rj.d0", "getResolvedSystemPrompt");
        hookSystemPromptMethod(cl, "com.aios.osbot.agent.unified.AgentDefinition", "getSystemPrompt");
        hookAgentDefinition(cl);
    }

    /**
     * Agent 定义里的 {@code systemPrompt} 是一个**字段**，请求组装时很可能直接 GETFIELD
     * 读，不经过 getter（实测挂 getter 一次都没触发）。所以在构造完成后改字段。
     */
    private static void hookAgentDefinition(ClassLoader cl) {
        try {
            Class<?> c = Class.forName("com.aios.osbot.agent.unified.AgentDefinition", false, cl);
            int n = 0;
            for (java.lang.reflect.Constructor<?> ctor : c.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyAgentDefinition(param.thisObject);
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            Logs.i("AgentDefinition 构造 hook 完成（" + n + " 个）");
        } catch (Throwable t) {
            Logs.i("AgentDefinition hook 失败: " + t);
        }
    }

    /** 读/改 AgentDefinition#systemPrompt 字段；顺带每进程打一次原始长度。 */
    private static void applyAgentDefinition(Object def) {
        try {
            java.lang.reflect.Field field = null;
            for (java.lang.reflect.Field f : def.getClass().getDeclaredFields()) {
                if ("systemPrompt".equals(f.getName())) {
                    field = f;
                    break;
                }
            }
            if (field == null) return;
            field.setAccessible(true);
            Object value = field.get(def);
            if (!(value instanceof String)) return;
            String original = (String) value;
            if (sDefLogged.compareAndSet(false, true)) {
                Logs.i("AgentDefinition systemPrompt=" + original.length());
            }
            Config c = Config.current();
            if (c == null) return;
            if (!c.promptEnabled() && !c.memoryInject()) return;
            String outline = c.memoryInject() ? MemoryFiles.outline(c.memoryInjectLines()) : "";
            PromptInjector.Options options = new PromptInjector.Options(
                    c.promptEnabled(), c.promptMode(), c.promptText(),
                    c.memoryInject(), c.memoryInjectLines(), outline);
            String block = PromptInjector.block(options, promptVars(c));
            if (block.length() == 0) return;
            boolean replace = c.promptEnabled() && PromptInjector.isReplace(c.promptMode())
                    && c.promptText() != null && c.promptText().trim().length() > 0;
            String merged = PromptInjector.merge(original, block, replace);
            if (merged.equals(original)) return;
            field.set(def, merged);
            PromptRuntime.recordInjected("agent-definition");
        } catch (Throwable t) {
            PromptRuntime.recordFailed("def-" + t.getClass().getSimpleName());
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean sDefLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static void hookSystemPromptMethod(ClassLoader cl, final String className, String methodName) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!methodName.equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        applySystemPrompt(param, className);
                    }
                });
                Logs.i("系统提示词 hook: " + className + "#" + methodName);
                return;
            }
        } catch (Throwable t) {
            Logs.i("系统提示词 hook 跳过: " + className + "#" + methodName + "（" + t + "）");
        }
    }

    /** 追加或替换 system 提示词；没有开关、没有内容时原样返回。 */
    private static void applySystemPrompt(XC_MethodHook.MethodHookParam param, String source) {
        try {
            Config c = Config.current();
            if (c == null) return;
            if (sFilePromptActive) return;
            if (!c.promptEnabled() && !c.memoryInject()) return;
            Object result = param.getResult();
            String original = result instanceof String ? (String) result : "";

            String outline = c.memoryInject() ? MemoryFiles.outline(c.memoryInjectLines()) : "";
            PromptInjector.Options options = new PromptInjector.Options(
                    c.promptEnabled(), c.promptMode(), c.promptText(),
                    c.memoryInject(), c.memoryInjectLines(), outline);
            String block = PromptInjector.block(options, promptVars(c));
            if (block.length() == 0) {
                PromptRuntime.recordSkipped("off");
                return;
            }
            boolean replace = c.promptEnabled() && PromptInjector.isReplace(c.promptMode())
                    && c.promptText() != null && c.promptText().trim().length() > 0;
            String merged = PromptInjector.merge(original, block, replace);
            if (merged.equals(original)) {
                PromptRuntime.recordSkipped("no-change");
                return;
            }
            param.setResult(merged);
            if (sSystemLogged.add(source)) {
                Logs.i("system 注入源 " + source + ": 原始=" + original.length()
                        + " 注入后=" + merged.length()
                        + "（block=" + block.length() + " 替换=" + replace + "）");
            }
            PromptRuntime.recordInjected("system-prompt");
        } catch (Throwable t) {
            PromptRuntime.recordFailed(t.getClass().getSimpleName());
        }
    }

    /** qm.j.buildResponsesRequest：Responses 链路，marker 注入 + 图文条件分流 */
    private static void hookRequestBuilder(ClassLoader cl) {
        // 即使模块暂停（enabled=false）也要挂：此时请求走小爱官方模型，
        // 统计要能区分「三方 / 官方」两边的调用量。
        if (sResponsesHooked) return;
        try {
            Class<?> c = Class.forName("qm.j", false, cl);
            int hooked = hookAllBuilders(c, "buildResponsesRequest", "Responses");
            if (hooked == 0) {
                Logs.i("qm.j 未找到 buildResponsesRequest 方法（进程 " + android.os.Process.myPid() + "）");
                return;
            }
            Logs.i("Responses 请求 hook 完成（共 " + hooked + " 个重载）");
            sResponsesHooked = true;
        } catch (Throwable t) {
            Logs.i("Responses 请求 hook 失败: " + t);
        }
    }

    /**
     * 把类里**所有**名字含 {@code token} 的方法都挂上。
     *
     * <p>只挂第一个会踩坑：Kotlin 会生成 {@code xxx$llm_client} 之类的重载，
     * 真正被调用往往不是声明顺序里的第一个——实测只挂第一个时注入计数不涨。</p>
     */
    private static int hookAllBuilders(Class<?> c, String token, String label) {
        int count = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().contains(token)) continue;
            final String methodName = m.getName();
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        logBuilderOnce(label, methodName);
                        mutateRequestInPlace(param, label + "#" + methodName);
                    }
                });
                count++;
            } catch (Throwable t) {
                Logs.i(label + " 挂载失败 " + methodName + ": " + t);
            }
        }
        return count;
    }

    /** 每个重载只报一次，用来确认到底哪个方法在跑。 */
    private static void logBuilderOnce(String label, String method) {
        String key = label + "#" + method;
        if (!sBuilderLogged.add(key)) return;
        Logs.i(label + " 构造被调用: " + method);
    }

    private static final java.util.Set<String> sBuilderLogged =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

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
            int hooked = hookAllBuilders(c, "buildOpenAIRequest", "chat");
            if (hooked == 0) {
                Logs.i("qm.i 未找到 buildOpenAIRequest 方法（进程 " + android.os.Process.myPid() + "）");
                return;
            }
            Logs.i("chat 请求 hook 完成（共 " + hooked + " 个重载）");
            sChatHooked = true;
        } catch (Throwable t) {
            Logs.i("chat 请求 hook 失败: " + t);
        }
    }

    // ------------------------------------------------------------- P2-C 提示词注入

    /**
     * 原位改写请求体：改 {@code JsonObject} 内部的 backing Map，而不是 setResult。
     *
     * <p>实测：{@code build*Request} 的返回值被重建后，模型拿到的仍是原文——说明调用方
     * 要么忽略返回值、要么用的是另一个引用。这里改成<b>原地修改</b>：反射拿到
     * {@code JsonObject} 的 backing {@code Map} 与 {@code messages} 数组的 backing
     * {@code List}，直接把 system 元素换掉，谁持有这个对象都看得到改动。</p>
     *
     * <p>失败即原样：任何异常都不改，只记一行原因。</p>
     */
    private static void mutateRequestInPlace(XC_MethodHook.MethodHookParam param, String method) {
        try {
            Config c = Config.current();
            if (c == null) return;
            if (sFilePromptActive) return;
            if (!c.promptEnabled() && !c.memoryInject()) return;
            Object result = param.getResult();
            if (result == null) return;
            int before = bodyLength(result);

            java.util.Map<String, Object> root = JsonNodes.backingMap(result);
            if (root == null) {
                PromptRuntime.recordFailed("no-backing-map");
                return;
            }
            String outline = c.memoryInject() ? MemoryFiles.outline(c.memoryInjectLines()) : "";
            PromptInjector.Options options = new PromptInjector.Options(
                    c.promptEnabled(), c.promptMode(), c.promptText(),
                    c.memoryInject(), c.memoryInjectLines(), outline);
            String block = PromptInjector.block(options, promptVars(c));
            if (block.length() == 0) {
                PromptRuntime.recordSkipped("off");
                return;
            }
            boolean replace = c.promptEnabled() && PromptInjector.isReplace(c.promptMode())
                    && c.promptText() != null && c.promptText().trim().length() > 0;

            String note = rewriteInPlace(root, block, replace);
            if (note == null) {
                PromptRuntime.recordSkipped("no-target");
                return;
            }
            PromptRuntime.recordInjected(note);
            logWireOnce(before, result, method);
        } catch (Throwable t) {
            PromptRuntime.recordFailed(t.getClass().getSimpleName());
        }
    }

    /** 原位改「第一条 system 消息」与 {@code instructions}（两者可能同时存在）。 */
    private static String rewriteInPlace(java.util.Map<String, Object> root, String block, boolean replace) {
        String note = null;
        Object messages = root.get("messages");
        java.util.List<Object> list = JsonNodes.backingList(messages);
        if (sStructCalls.incrementAndGet() <= 8) {
            StringBuilder sb = new StringBuilder("请求结构: instructions=")
                    .append(JsonNodes.isScalar(root.get("instructions")));
            sb.append(" messages=").append(messages == null ? "null" : messages.getClass().getName());
            sb.append(" listFound=").append(list != null);
            if (list != null) {
                sb.append(" size=").append(list.size());
                if (!list.isEmpty()) {
                    Object first = list.get(0);
                    java.util.Map<String, Object> m0 = JsonNodes.backingMap(first);
                    sb.append(" first=").append(first == null ? "null" : first.getClass().getSimpleName());
                    sb.append(" mapFound=").append(m0 != null);
                    if (m0 != null) {
                        sb.append(" role=").append(JsonNodes.contentOf(m0.get("role")));
                        sb.append(" contentLen=").append(JsonNodes.contentOf(m0.get("content")).length());
                    }
                }
            }
            Logs.i(sb.toString());
        }
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                java.util.Map<String, Object> msg = JsonNodes.backingMap(list.get(i));
                if (msg == null) continue;
                if (!"system".equals(JsonNodes.contentOf(msg.get("role")))) continue;
                Object merged = JsonNodes.mergeContent(msg.get("content"), block, replace);
                if (merged != null) {
                    msg.put("content", merged);
                    note = "system-message";
                }
                break;
            }
            if (note == null) {
                // 没有 system：用第一条消息做模板造一条，插到最前
                Object template = list.isEmpty() ? null : list.get(0);
                java.util.Map<String, Object> tpl = JsonNodes.backingMap(template);
                if (tpl != null) {
                    java.util.Map<String, Object> sys = new java.util.LinkedHashMap<String, Object>();
                    sys.put("role", JsonNodes.newStringLike(tpl.get("role"), "system"));
                    sys.put("content", JsonNodes.newStringLike(tpl.get("content"), block));
                    Object sysMsg = JsonNodes.constructLike(template, sys);
                    if (sysMsg != null) {
                        list.add(0, sysMsg);
                        note = "system-inserted";
                    }
                }
            }
        }
        Object instructions = root.get("instructions");
        if (JsonNodes.isScalar(instructions)) {
            root.put("instructions",
                    JsonNodes.newStringLike(instructions,
                            PromptInjector.merge(JsonNodes.contentOf(instructions), block, replace)));
            if (note == null) note = "instructions";
        }
        return note;
    }

    /** 每次请求体构建都打一行（每进程最多 8 行）：注入前后长度。 */
    private static void logWireOnce(int before, Object request, String method) {
        if (sWireCalls.incrementAndGet() > 8) return;
        try {
            Logs.i("请求体: " + method + " 注入前=" + before + " 注入后=" + bodyLength(request));
        } catch (Throwable ignored) {
        }
    }

    private static int bodyLength(Object o) {
        try {
            return String.valueOf(o).length();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean sWireLogged =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicInteger sStructCalls =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger sWireCalls =
            new java.util.concurrent.atomic.AtomicInteger();

    // ------------------------------------------------------------- 结构反射

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




    /** 每个「提示词出口」只打一次注入结果。 */
    private static final java.util.Set<String> sSystemLogged =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

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
            int messages = JsonNodes.sizeOf(args, 0);
            int tools = JsonNodes.sizeOf(args, 1);
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
            // 工具链采样（一次性）：只打类型、键名与名字，不打工具正文。
            if (sToolSampled.add(className)) {
                String desc = "工具采样: " + className + " messages=" + messages + " " + JsonNodes.describeTools(args);
                // 日志单条有长度上限，按 280 字符分片输出，保证工具名不丢。
                for (int off = 0; off < desc.length(); off += 280) {
                    Logs.i(desc.substring(off, Math.min(desc.length(), off + 280)));
                }
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

