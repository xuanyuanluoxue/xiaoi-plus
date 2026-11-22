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
                        applyPromptInjection(param);
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

    /** 请求体顶层键只打印一次（debug 档），用于核对字段名，不打印任何值。 */
    private static volatile boolean sRequestKeysLogged;

    /**
     * 请求构造后的提示词注入。
     *
     * <p>链路：复制返回的 {@code JsonObject} → 改 {@code instructions} / system 消息 →
     * 用同类型构造函数重建。**不经过 JSON 文本，也不依赖 kotlinx Json 单例**——
     * 那个单例的类名/字段名会随版本混淆变化（实测 8.2.70 已改）。</p>
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
            if (result == null || !(result instanceof Map)) return;

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

            // 直接复制活对象再重建：不依赖 kotlinx Json 单例（其类名/字段名随版本混淆变化）。
            java.util.Map<String, Object> root = copyMap((java.util.Map<?, ?>) result);
            String note = rewriteRequest(root, block, replace);
            if (note == null) {
                PromptRuntime.recordSkipped("no-target");
                return;
            }
            Object rebuilt = constructLike(result, root);
            if (rebuilt == null) {
                PromptRuntime.recordFailed("construct-" + note);
                return;
            }
            param.setResult(rebuilt);
            PromptRuntime.recordInjected(note);
        } catch (Throwable t) {
            PromptRuntime.recordFailed(t.getClass().getSimpleName());
        }
    }

    /**
     * 在请求体的键值上做替换，返回命中位置；没有可改字段返回 null。
     *
     * <p>Responses 改 {@code instructions}；chat 改 {@code messages} 里第一条
     * {@code role=system} 的 {@code content}。消息/数组/对象都靠"复制后重建"完成，
     * 不经过 JSON 文本。</p>
     */
    private static String rewriteRequest(java.util.Map<String, Object> root,
                                        String block, boolean replace) {
        Object instructions = root.get("instructions");
        if (isScalar(instructions)) {
            root.put("instructions",
                    newStringLike(instructions, PromptInjector.merge(contentOf(instructions), block, replace)));
            return "instructions";
        }
        Object messages = root.get("messages");
        if (!(messages instanceof java.util.List)) return null;

        logMessageShape((java.util.List<?>) messages);

        java.util.List<?> list = (java.util.List<?>) messages;
        java.util.List<Object> out = new java.util.ArrayList<Object>();
        Object template = null;
        boolean replaced = false;
        for (Object item : list) {
            if (!(item instanceof java.util.Map)) {
                out.add(item);
                continue;
            }
            if (template == null) template = item;
            java.util.Map<String, Object> msg = copyMap((java.util.Map<?, ?>) item);
            if (!replaced && "system".equals(contentOf(msg.get("role")))) {
                Object merged = mergeContent(msg.get("content"), block, replace);
                if (merged != null) msg.put("content", merged);
                Object rebuilt = constructLike(item, msg);
                if (!sMsgRebuildLogged) {
                    sMsgRebuildLogged = true;
                    StringBuilder sb = new StringBuilder(item.getClass().getName());
                    for (java.lang.reflect.Constructor<?> c2 : item.getClass().getDeclaredConstructors()) {
                        sb.append(" (").append(c2.getParameterCount()).append(':');
                        for (Class<?> t2 : c2.getParameterTypes()) sb.append(t2.getSimpleName()).append(',');
                        sb.append(')');
                    }
                    Logs.i("消息重建: ok=" + (rebuilt != null) + " ctors=" + sb);
                }
                out.add(rebuilt == null ? item : rebuilt);
                replaced = true;
                if (!sMergeLogged) {
                    sMergeLogged = true;
                    Logs.i("注入后 system 长度 "
                            + (merged == null ? -1 : contentOf(merged).length())
                            + "（block=" + block.length() + " 替换=" + replace + "）");
                }
            } else {
                out.add(item);
            }
        }
        if (template == null) return null;

        if (!replaced) {
            java.util.Map<String, Object> sys = new java.util.LinkedHashMap<String, Object>();
            sys.put("role", newStringLike(((java.util.Map<?, ?>) template).get("role"), "system"));
            sys.put("content", newStringLike(((java.util.Map<?, ?>) template).get("content"), block));
            Object sysMsg = constructLike(template, sys);
            if (sysMsg == null) return null;
            out.add(0, sysMsg);
        }
        Object rebuiltMessages = constructLike(messages, out);
        if (rebuiltMessages == null) return null;
        root.put("messages", rebuiltMessages);
        return replaced ? "system-message" : "system-inserted";
    }

    /** content 可能是字符串、text part 数组，或带 {@code text} 的对象。 */
    private static Object mergeContent(Object content, String block, boolean replace) {
        if (!sContentSeen) {
            sContentSeen = true;
            Logs.i("content 诊断: null=" + (content == null)
                    + " class=" + (content == null ? "-" : content.getClass().getName())
                    + " scalar=" + isScalar(content)
                    + " map=" + (content instanceof java.util.Map)
                    + " list=" + (content instanceof java.util.List));
        }
        if (isScalar(content)) {
            return newStringLike(content, PromptInjector.merge(contentOf(content), block, replace));
        }
        if (content instanceof java.util.Map) {
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) content;
            Object text = map.get("text");
            if (isScalar(text)) {
                java.util.Map<String, Object> copy = copyMap(map);
                copy.put("text", newStringLike(text,
                        PromptInjector.merge(contentOf(text), block, replace)));
                Object rebuilt = constructLike(content, copy);
                if (rebuilt != null) return rebuilt;
            }
            logContentShapeOnce(content);
            return null;
        }
        if (!(content instanceof java.util.List)) {
            logContentShapeOnce(content);
            return null;
        }
        java.util.List<?> parts = (java.util.List<?>) content;
        java.util.List<Object> out = new java.util.ArrayList<Object>();
        boolean done = false;
        for (Object raw : parts) {
            if (!done && raw instanceof java.util.Map
                    && isScalar(((java.util.Map<?, ?>) raw).get("text"))) {
                java.util.Map<String, Object> part = copyMap((java.util.Map<?, ?>) raw);
                Object text = part.get("text");
                part.put("text", newStringLike(text,
                        PromptInjector.merge(contentOf(text), block, replace)));
                Object rebuilt = constructLike(raw, part);
                out.add(rebuilt == null ? raw : rebuilt);
                done = true;
            } else {
                out.add(raw);
            }
        }
        if (!done) return null;
        return constructLike(content, out);
    }

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

    private static volatile boolean sContentSeen;
    private static volatile boolean sMsgRebuildLogged;

    private static void logContentShapeOnce(Object content) {
        if (sContentLogged || content == null) return;
        sContentLogged = true;
        try {
            StringBuilder sb = new StringBuilder(content.getClass().getName());
            for (java.lang.reflect.Field f : content.getClass().getDeclaredFields()) {
                sb.append(' ').append(f.getName()).append(':').append(f.getType().getSimpleName());
            }
            Logs.i("content 结构: " + sb);
        } catch (Throwable ignored) {
        }
    }

    private static volatile boolean sContentLogged;

    /** 每进程打一次请求形状：角色序列与每段长度（不含正文）。 */
    private static void logMessageShape(java.util.List<?> messages) {
        if (sShapeLogged) return;
        sShapeLogged = true;
        try {
            StringBuilder roles = new StringBuilder();
            StringBuilder lens = new StringBuilder();
            int limit = Math.min(messages.size(), 12);
            for (int i = 0; i < limit; i++) {
                Object m = messages.get(i);
                if (roles.length() > 0) roles.append(',');
                lens.append(lens.length() == 0 ? "" : ",");
                if (m instanceof java.util.Map) {
                    java.util.Map<?, ?> map = (java.util.Map<?, ?>) m;
                    roles.append(contentOf(map.get("role")));
                    lens.append(contentOf(map.get("content")).length());
                } else {
                    roles.append('?');
                    lens.append(0);
                }
            }
            if (messages.size() > limit) roles.append(",...");
            Logs.i("请求形状: 条数=" + messages.size() + " roles=" + roles + " 长度=" + lens);
            Object first = messages.isEmpty() ? null : messages.get(0);
            if (first != null) {
                StringBuilder fs = new StringBuilder(first.getClass().getName()).append(" 字段=");
                for (java.lang.reflect.Field f : first.getClass().getDeclaredFields()) {
                    fs.append(f.getName()).append(':').append(f.getType().getSimpleName()).append(' ');
                }
                Logs.i("消息对象结构: " + fs);
            }
        } catch (Throwable ignored) {
        }
    }

    private static volatile boolean sShapeLogged;
    private static volatile boolean sMergeLogged;

    /** 既不是 Map 也不是 List 的 JsonElement（字符串/数字/布尔）。 */
    private static boolean isScalar(Object o) {
        return o != null && !(o instanceof java.util.Map) && !(o instanceof java.util.List);
    }

    /** 取 JsonPrimitive 的正文；getContent 被混淆时退回用我们自己的 JSON 解析。 */
    private static String contentOf(Object element) {
        if (element == null) return "";
        if (element instanceof String) return (String) element;
        try {
            Object v = element.getClass().getMethod("getContent").invoke(element);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {
        }
        try {
            Object v = Json.read(String.valueOf(element));
            if (v instanceof String) return (String) v;
            if (v != null) return String.valueOf(v);
        } catch (Throwable ignored) {
        }
        return String.valueOf(element);
    }

    /** 用同类元素的构造函数造一个新的字符串元素。 */
    private static Object newStringLike(Object sample, String text) {
        String value = text == null ? "" : text;
        if (sample == null) return null;
        try {
            for (java.lang.reflect.Constructor<?> ctor : sample.getClass().getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 1 && p[0] == String.class) {
                    ctor.setAccessible(true);
                    return ctor.newInstance(value);
                }
            }
            for (java.lang.reflect.Constructor<?> ctor : sample.getClass().getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 2 && p[0] == String.class && p[1] == boolean.class) {
                    ctor.setAccessible(true);
                    return ctor.newInstance(value, Boolean.TRUE);
                }
            }
            // (Object, boolean, ...)：kotlinx JsonLiteral 的混淆形态
            for (java.lang.reflect.Constructor<?> ctor : sample.getClass().getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length < 2 || p[0] != Object.class || p[1] != boolean.class) continue;
                Object[] args = new Object[p.length];
                args[0] = value;
                args[1] = Boolean.TRUE;
                boolean ok = true;
                for (int i = 2; i < p.length; i++) {
                    Object d = defaultFor(p[i], sample);
                    if (d == null && p[i].isPrimitive()) { ok = false; break; }
                    args[i] = d;
                }
                if (!ok) continue;
                try {
                    ctor.setAccessible(true);
                    return ctor.newInstance(args);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        if (!sCtorLogged) {
            sCtorLogged = true;
            try {
                StringBuilder sb = new StringBuilder(sample.getClass().getName());
                for (java.lang.reflect.Constructor<?> c : sample.getClass().getDeclaredConstructors()) {
                    sb.append(" (").append(c.getParameterCount()).append(':');
                    for (Class<?> t : c.getParameterTypes()) sb.append(t.getSimpleName()).append(',');
                    sb.append(')');
                }
                Logs.i("字符串元素构造器: " + sb);
            } catch (Throwable ignored2) {
            }
        }
        return null;
    }

    private static volatile boolean sCtorLogged;

    /** 给缺失的构造参数取一个安全默认值：优先复用样本里同类型的字段。 */
    private static Object defaultFor(Class<?> type, Object sample) {
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return Integer.valueOf(0);
        if (type == long.class) return Long.valueOf(0L);
        if (sample != null) {
            for (java.lang.reflect.Field f : sample.getClass().getDeclaredFields()) {
                if (!type.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(sample);
                    if (v != null) return v;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 用同类型的构造函数把"复制后的 Map/List"变回 JsonObject/JsonArray。 */
    private static Object constructLike(Object sample, Object arg) {
        if (sample == null || arg == null) return null;
        try {
            // 1) 单参数构造（常规 Kotlin 类）
            for (java.lang.reflect.Constructor<?> ctor : sample.getClass().getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 1 && p[0].isInstance(arg)) {
                    ctor.setAccessible(true);
                    return ctor.newInstance(arg);
                }
            }
            // 2) (Companion, Map/List)：kotlinx 的 JsonObject/JsonArray 是这种形状
            Object companion = companionOf(sample);
            if (companion != null) {
                for (java.lang.reflect.Constructor<?> ctor : sample.getClass().getDeclaredConstructors()) {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length == 2 && p[0].isInstance(companion) && p[1].isInstance(arg)) {
                        ctor.setAccessible(true);
                        return ctor.newInstance(companion, arg);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 取类的 Kotlin 伴生对象实例（静态字段 {@code Companion}）；没有返回 null。 */
    private static Object companionOf(Object sample) {
        if (sample == null) return null;
        try {
            java.lang.reflect.Field f = sample.getClass().getDeclaredField("Companion");
            f.setAccessible(true);
            try {
                return f.get(null);
            } catch (Throwable ignored) {
                return f.get(sample);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static java.util.Map<String, Object> copyMap(java.util.Map<?, ?> src) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        if (src == null) return out;
        for (java.util.Map.Entry<?, ?> e : src.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
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
