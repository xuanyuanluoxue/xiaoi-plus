package plus.xiaoi.module.application.hook;

/**
 * 进程内 hook 就绪度（纯 JVM）。
 *
 * <p>为什么放应用层：Hook 在 :app 里运行，状态要能被 :platform 的 Bridge 读到并
 * 通过 {@code xiaoai_status} 上报；三者共同的下层只有 :application。</p>
 *
 * <p>每次进程启动由 {@link #reset(String, String)} 重置，安装 hook 的过程中由各
 * installer 打点，最后汇总成 {@code hooks=ok|partial|none}。这样远程只看一条
 * status 就能判断「没生效」是版本没进白名单、类没找到，还是部分 hook 失败。</p>
 */
public final class HookStatus {

    /** 版本在 SUPPORTED_VERSIONS 里，按已验证路径启用。 */
    public static final String COMPAT_WHITELIST = "whitelist";
    /** 版本未知但按既有逻辑降级放行（早期加载拿不到版本）。 */
    public static final String COMPAT_PROBE = "probe";
    /** 版本不在白名单，已 no-op。 */
    public static final String COMPAT_NONE = "none";

    public static final String HOOKS_OK = "ok";
    public static final String HOOKS_PARTIAL = "partial";
    public static final String HOOKS_NONE = "none";

    /**
     * 版本来源，由 :app 的 Hook 注册。
     *
     * <p>为什么不在这里直接读包信息：应用层禁用 I/O 与 Android API。更关键的是
     * {@code handleLoadPackage} 执行时 Application 还没就绪，那里读不到 versionName，
     * 所以版本必须在真正要上报时（{@code xiaoai_status}）惰性补读，否则白名单
     * 判定会永远停在 {@code probe}。</p>
     */
    public interface Probe {
        /** 小爱当前 versionName；读不到返回 null。 */
        String versionName();

        /** 该版本是否在已验证白名单内。 */
        boolean whitelisted(String version);
    }

    private static volatile String sVersion = "";
    private static volatile String sCompat = COMPAT_NONE;
    private static volatile Probe sProbe;
    private static volatile boolean sConfig;
    private static volatile boolean sProvider;
    private static volatile boolean sVision;
    private static volatile boolean sFacade;
    private static volatile boolean sUsage;
    private static volatile boolean sRewrite;
    private static volatile boolean sCallSeen;

    private HookStatus() {}

    /** 注册版本来源；由 Hook 在 handleLoadPackage 开头调用。 */
    public static void setProbe(Probe probe) {
        sProbe = probe;
    }

    /** 进程内重置：每次 handleLoadPackage 判定版本来一次。 */
    public static void reset(String xiaoiVersion, String compat) {
        sVersion = xiaoiVersion == null ? "" : xiaoiVersion;
        sCompat = compat == null ? COMPAT_NONE : compat;
        sConfig = false;
        sProvider = false;
        sVision = false;
        sFacade = false;
        sUsage = false;
        sRewrite = false;
        sCallSeen = false;
    }

    public static void markConfig(boolean ok) { sConfig = ok; }

    public static void markProvider(boolean ok) { sProvider = ok; }

    public static void markVision(boolean ok) { sVision = ok; }

    public static void markFacade(boolean ok) { sFacade = ok; }

    public static void markUsage(boolean ok) { sUsage = ok; }

    /** 配置 getter 真的被小爱读到了（改写已生效），而不是只把 hook 挂上。 */
    public static void markRewrite() { sRewrite = true; }

    /** 观察到一次模型调用（无论三方还是官方）。 */
    public static void markCall() { sCallSeen = true; }

    public static boolean rewriteHit() { return sRewrite; }

    /**
     * 改写是否真的生效。
     *
     * <p>{@code hit} 有 getter 被覆盖过；{@code none} 已经发生过模型调用但 getter 一次没被读
     * （该版本不吃这套 hook）；{@code ?} 还没发生调用，无法判断。</p>
     */
    public static String rewrite() {
        if (sRewrite) return "hit";
        if (sCallSeen) return "none";
        return "?";
    }

    /**
     * 核心 hook 全中就 ok，任意一个就 partial，一个都没有就 none。
     *
     * <p>核心是配置改写（重定向）、provider 标识、视觉声明、facade（能力桥）；
     * 调用统计只影响用量数字，不计入 ok 门槛。</p>
     */
    public static String hooks() {
        if (sConfig && sProvider && sVision && sFacade) return HOOKS_OK;
        if (sConfig || sProvider || sVision || sFacade || sUsage) return HOOKS_PARTIAL;
        return HOOKS_NONE;
    }

    public static String version() { return sVersion; }

    public static String compat() { return sCompat; }

    /**
     * 早期加载拿不到版本时，在真正上报前补读一次并重算兼容模式。
     *
     * <p>此时才调用 {@link Probe}：Application 已就绪，PackageManager 可用。
     * 补读成功会把 {@code compat} 从 {@code probe} 修正为 {@code whitelist}/{@code none}，
     * 这就是「同一版本为何一边 no-op 一边放行」的唯一可见出口。</p>
     */
    private static void resolve() {
        if (sVersion.length() > 0) return;
        Probe p = sProbe;
        if (p == null) return;
        String v;
        try {
            v = p.versionName();
        } catch (Throwable t) {
            return;
        }
        if (v == null || v.length() == 0) return;
        sVersion = v;
        try {
            sCompat = p.whitelisted(v) ? COMPAT_WHITELIST : COMPAT_NONE;
        } catch (Throwable ignored) {}
    }

    /**
     * {@code xiaoai_status} 追加的摘要。
     *
     * <p>从未装载过 hook（例如 App 进程里的实现）返回空串，避免把"不适用"报成 none。</p>
     */
    public static String summary() {
        resolve();
        if (sVersion.length() == 0
                && COMPAT_NONE.equals(sCompat)
                && HOOKS_NONE.equals(hooks())) return "";
        return "hooks=" + hooks()
                + " compat=" + sCompat
                + " xiaoai=" + (sVersion.length() == 0 ? "?" : sVersion)
                + " rewrite=" + rewrite();
    }
}
