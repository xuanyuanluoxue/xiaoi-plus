package plus.xiaoi.module;

import plus.xiaoi.module.core.BridgePolicy;

/** BridgePolicy 组合穷举：shouldListen / bindHost / LAN token 强度。 */
public final class BridgePolicyTest {

    private BridgePolicyTest() {}

    public static int run() {
        System.out.println("-- BridgePolicy");

        // shouldListen：只看 MCP 开关 + token 是否非空
        T.ok("开启 + token → 监听", BridgePolicy.shouldListen(true, "abc"));
        T.ok("开启 + 空 token → 不监听", !BridgePolicy.shouldListen(true, ""));
        T.ok("开启 + null token → 不监听", !BridgePolicy.shouldListen(true, null));
        T.ok("开启 + 空白 token → 不监听", !BridgePolicy.shouldListen(true, "   "));
        T.ok("关闭 + token → 不监听", !BridgePolicy.shouldListen(false, "abc"));
        T.ok("关闭 + 空 token → 不监听", !BridgePolicy.shouldListen(false, ""));

        // bindHost：lan && mcp && token 三者同时满足才 0.0.0.0
        T.eq("lan+开启+token → 0.0.0.0", "0.0.0.0",
                BridgePolicy.bindHost(true, true, "abc"));
        T.eq("lan+开启+token(前后空白) → 0.0.0.0", "0.0.0.0",
                BridgePolicy.bindHost(true, true, "  abc  "));
        T.eq("非 lan → loopback", "127.0.0.1",
                BridgePolicy.bindHost(false, true, "abc"));
        T.eq("lan 但 MCP 关闭 → loopback", "127.0.0.1",
                BridgePolicy.bindHost(true, false, "abc"));
        T.eq("lan 但 token 为空 → loopback", "127.0.0.1",
                BridgePolicy.bindHost(true, true, ""));
        T.eq("lan 但 token 为 null → loopback", "127.0.0.1",
                BridgePolicy.bindHost(true, true, null));
        T.eq("关闭 + 非 lan → loopback", "127.0.0.1",
                BridgePolicy.bindHost(false, false, ""));

        // tokenStrongEnoughForLan：WP4 才启用；本包只验证判定正确
        T.ok("32 hex → strong", BridgePolicy.tokenStrongEnoughForLan(
                "0123456789abcdef0123456789abcdef"));
        T.ok("32 hex 大写 → strong", BridgePolicy.tokenStrongEnoughForLan(
                "0123456789ABCDEF0123456789ABCDEF"));
        T.ok("32 位移除空白 → strong", BridgePolicy.tokenStrongEnoughForLan(
                "  0123456789abcdef0123456789abcdef  "));
        T.ok("31 hex → weak", !BridgePolicy.tokenStrongEnoughForLan(
                "0123456789abcdef0123456789abcde"));
        T.ok("33 hex → weak", !BridgePolicy.tokenStrongEnoughForLan(
                "0123456789abcdef0123456789abcdef0"));
        T.ok("32 位非 hex → weak", !BridgePolicy.tokenStrongEnoughForLan(
                "0123456789abcdef0123456789abcdeg"));
        T.ok("空 token → weak", !BridgePolicy.tokenStrongEnoughForLan(""));
        T.ok("null token → weak", !BridgePolicy.tokenStrongEnoughForLan(null));
        T.ok("短可读 token → weak", !BridgePolicy.tokenStrongEnoughForLan("123456"));

        // 归一化
        T.eq("normalize null", "", BridgePolicy.normalize(null));
        T.eq("normalize trim", "abc", BridgePolicy.normalize("  abc "));

        return T.report("BridgePolicyTest");
    }
}
