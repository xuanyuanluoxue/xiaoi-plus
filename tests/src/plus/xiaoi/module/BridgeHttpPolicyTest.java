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

        T.eq("log limit 0 → 80", 80, BridgeHttpPolicy.clampLogLimit(0));
        T.eq("log limit -1 → 80", 80, BridgeHttpPolicy.clampLogLimit(-1));
        T.eq("log limit 301 → 80", 80, BridgeHttpPolicy.clampLogLimit(301));
        T.eq("log limit 1 保留", 1, BridgeHttpPolicy.clampLogLimit(1));
        T.eq("log limit 300 保留", 300, BridgeHttpPolicy.clampLogLimit(300));

        T.eq("stats days 0 → 7", 7, BridgeHttpPolicy.clampStatsDays(0));
        T.eq("stats days -1 → 7", 7, BridgeHttpPolicy.clampStatsDays(-1));
        T.eq("stats days 91 → 7", 7, BridgeHttpPolicy.clampStatsDays(91));
        T.eq("stats days 1 保留", 1, BridgeHttpPolicy.clampStatsDays(1));
        T.eq("stats days 90 保留", 90, BridgeHttpPolicy.clampStatsDays(90));

        return T.report("BridgeHttpPolicyTest");
    }
}