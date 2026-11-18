package plus.xiaoi.module.core.hook;

/**
 * 小爱 getter 覆盖策略（纯 JVM）。
 *
 * <p>把 {@code Hook} 里“暂停不离线、空值不覆盖”的判断抽出来，保证
 * enabled=false 时请求回到小爱官方模型，enabled=true 且配置非空才改写。</p>
 */
public final class XiaoiOverridePolicy {

    private XiaoiOverridePolicy() {}

    /** @return 需 setResult 的值；null = 不覆盖。 */
    public static String override(boolean enabled, String configured) {
        if (!enabled) return null;
        if (configured == null || configured.length() == 0) return null;
        return configured;
    }

    /** 仅启用且启用 marker 时才声明第三方 provider id。 */
    public static String providerId(boolean enabled, boolean marker, String id) {
        if (!enabled || !marker) return null;
        if (id == null || id.length() == 0) return null;
        return id;
    }

    /** 第三方模型是否支持看图；暂停态不覆盖小爱原生结论。 */
    public static boolean supportsVision(boolean enabled) {
        return enabled;
    }
}