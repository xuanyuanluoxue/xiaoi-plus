package plus.xiaoi.module.core.bridge;

import plus.xiaoi.module.core.Contract;

/**
 * 8771 HTTP 路由/参数策略（WP3，纯 JVM）。
 *
 * <p>只做大小写敏感的精确路径判定与数值 clamp；限流桶、鉴权与状态码仍由
 * {@code Bridge.handle} 持有，且判定顺序不变（限流在路由之前）。</p>
 *
 * <p>0.16.0 起 clamp 是「显式」的：超过上限不再悄悄回默认值，而是夹到上限并让
 * 调用方在响应里回报 {@code clamped:true}，模型能看出自己传错了。</p>
 */
public final class BridgeHttpPolicy {

    private BridgeHttpPolicy() {}

    /** 参数缺省（或无法解析）时调用方传给 clamp 的哨兵值。 */
    public static final int ABSENT = -1;

    /** 低信任端点：/health 与 /island，按源 IP 限流。 */
    public static boolean isLowTrust(String path) {
        return "/health".equals(path) || "/island".equals(path);
    }

    /** 只允许 loopback：/island（无 token 副作用端点）。 */
    public static boolean isLoopbackOnly(String path) {
        return "/island".equals(path);
    }

    public static boolean isMcp(String path) {
        return "/mcp".equals(path);
    }

    public static boolean isConfig(String path) {
        return "/config".equals(path);
    }

    /**
     * GET /logs?limit= 的取值：缺省/{@code <0} → 80；{@code 0} → 0（返回空数组）；
     * 1..300 原样；{@code >300} → 300。
     */
    public static int logLimit(int requested) {
        if (requested < 0) return Contract.LOGS_LIMIT_DEFAULT;
        return Math.min(requested, Contract.LOGS_LIMIT_MAX);
    }

    /** limit 是否被夹到上限（用于回 {@code clamped:true}）。 */
    public static boolean logLimitClamped(int requested) {
        return requested > Contract.LOGS_LIMIT_MAX;
    }

    /**
     * GET /stats?days= 的取值：缺省/{@code <=0} → 7；1..90 原样；{@code >90} → 90。
     */
    public static int statsDays(int requested) {
        if (requested <= 0) return Contract.STATS_DAYS_DEFAULT;
        return Math.min(requested, Contract.STATS_DAYS_MAX);
    }

    /** days 是否被夹到上限（用于回 {@code clamped:true}）。 */
    public static boolean statsDaysClamped(int requested) {
        return requested > Contract.STATS_DAYS_MAX;
    }
}
