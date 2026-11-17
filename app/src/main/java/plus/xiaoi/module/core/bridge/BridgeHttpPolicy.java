package plus.xiaoi.module.core.bridge;

/**
 * 8771 HTTP 路由/参数策略（WP3，纯 JVM）。
 *
 * <p>只做大小写敏感的精确路径判定与数值 clamp；限流桶、鉴权与状态码仍由
 * {@code Bridge.handle} 持有，且判定顺序不变（限流在路由之前）。</p>
 */
public final class BridgeHttpPolicy {

    private BridgeHttpPolicy() {}

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

    /** GET /logs?limit=：1..300，非法回 80。仅用于 Bridge 的 HTTP 层。 */
    public static int clampLogLimit(int limit) {
        return limit <= 0 || limit > 300 ? 80 : limit;
    }

    /** GET /stats?days=：1..90，非法回 7。 */
    public static int clampStatsDays(int days) {
        return days <= 0 || days > 90 ? 7 : days;
    }
}