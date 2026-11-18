package plus.xiaoi.module;

import plus.xiaoi.module.core.bridge.BridgeHttpPolicy;

/** WP3：8771 HTTP 路径/参数策略。 */
public final class BridgeHttpPolicyTest {

    private BridgeHttpPolicyTest() {}

    public static int run() {
        T.ok("health 是低信任", BridgeHttpPolicy.isLowTrust("/health"));
        T.ok("island 是低信任", BridgeHttpPolicy.isLowTrust("/island"));
        T.ok("mcp 不是低信任", !BridgeHttpPolicy.isLowTrust("/mcp"));
        T.ok("大小写敏感 /HEALTH", !BridgeHttpPolicy.isLowTrust("/HEALTH"));
        T.ok("尾随空格不匹配", !BridgeHttpPolicy.isLowTrust("/health "));

        T.ok("island loopback-only", BridgeHttpPolicy.isLoopbackOnly("/island"));
        T.ok("health 不 loopback-only", !BridgeHttpPolicy.isLoopbackOnly("/health"));

        T.ok("mcp 路径", BridgeHttpPolicy.isMcp("/mcp"));
        T.ok("config 路径", BridgeHttpPolicy.isConfig("/config"));
        T.ok("MCP 大写不匹配", !BridgeHttpPolicy.isMcp("/MCP"));

        T.eq("log limit 缺省 → 80", 80, BridgeHttpPolicy.logLimit(BridgeHttpPolicy.ABSENT));
        T.eq("log limit 0 → 0（空数组）", 0, BridgeHttpPolicy.logLimit(0));
        T.eq("log limit -1 → 80", 80, BridgeHttpPolicy.logLimit(-1));
        T.eq("log limit 301 → 300", 300, BridgeHttpPolicy.logLimit(301));
        T.ok("log limit 301 标记 clamped", BridgeHttpPolicy.logLimitClamped(301));
        T.eq("log limit 1 保留", 1, BridgeHttpPolicy.logLimit(1));
        T.eq("log limit 300 保留", 300, BridgeHttpPolicy.logLimit(300));
        T.ok("log limit 300 不标 clamped", !BridgeHttpPolicy.logLimitClamped(300));

        T.eq("stats days 0 → 7", 7, BridgeHttpPolicy.statsDays(0));
        T.eq("stats days -1 → 7", 7, BridgeHttpPolicy.statsDays(-1));
        T.eq("stats days 91 → 90", 90, BridgeHttpPolicy.statsDays(91));
        T.ok("stats days 91 标记 clamped", BridgeHttpPolicy.statsDaysClamped(91));
        T.eq("stats days 1 保留", 1, BridgeHttpPolicy.statsDays(1));
        T.eq("stats days 90 保留", 90, BridgeHttpPolicy.statsDays(90));
        T.ok("stats days 90 不标 clamped", !BridgeHttpPolicy.statsDaysClamped(90));

        return T.report("BridgeHttpPolicyTest");
    }
}