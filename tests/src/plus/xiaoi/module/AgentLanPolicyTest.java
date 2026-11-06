package plus.xiaoi.module;

import plus.xiaoi.module.agent.AgentLanPolicy;

/**
 * 8770 Agent 动作面监听策略测试（纯 JVM）。
 *
 * 规则：只有「显式请求 LAN + 32 位十六进制 token」才绑 0.0.0.0；
 * 关闭、空 token、短 token、非 hex 一律回退 127.0.0.1。
 */
public final class AgentLanPolicyTest {

    private static final String STRONG = "0123456789abcdef0123456789abcdef";
    private static final String STRONG_UPPER = "0123456789ABCDEF0123456789ABCDEF";

    private AgentLanPolicyTest() {}

    public static int run() {
        // ------------------------------------------------------------ 关闭：永远 loopback
        T.eq("开关关闭 -> 127.0.0.1", "127.0.0.1", AgentLanPolicy.bindHost(false, STRONG));
        T.eq("开关关闭 + 空 token -> 127.0.0.1", "127.0.0.1",
                AgentLanPolicy.bindHost(false, ""));
        T.ok("开关关闭 lanEffective=false", !AgentLanPolicy.lanEffective(false, STRONG));

        // ------------------------------------------------------------ token 不合格：loopback
        T.eq("token 空 -> 127.0.0.1", "127.0.0.1", AgentLanPolicy.bindHost(true, ""));
        T.eq("token null -> 127.0.0.1", "127.0.0.1", AgentLanPolicy.bindHost(true, null));
        T.eq("短 token -> 127.0.0.1", "127.0.0.1", AgentLanPolicy.bindHost(true, "short"));
        T.eq("31 hex -> 127.0.0.1", "127.0.0.1",
                AgentLanPolicy.bindHost(true, "0123456789abcdef0123456789abcde"));
        T.eq("33 hex -> 127.0.0.1", "127.0.0.1",
                AgentLanPolicy.bindHost(true, "0123456789abcdef0123456789abcdef0"));
        T.eq("非 hex 字符 -> 127.0.0.1", "127.0.0.1",
                AgentLanPolicy.bindHost(true, "0123456789abcdef0123456789abcdeg"));
        T.ok("弱 token lanEffective=false", !AgentLanPolicy.lanEffective(true, "short"));

        // ------------------------------------------------------------ 32 hex：开放局域网
        T.eq("32 hex 小写 -> 0.0.0.0", "0.0.0.0", AgentLanPolicy.bindHost(true, STRONG));
        T.eq("32 hex 大写 -> 0.0.0.0", "0.0.0.0", AgentLanPolicy.bindHost(true, STRONG_UPPER));
        T.eq("首尾空白裁剪后有效 -> 0.0.0.0", "0.0.0.0",
                AgentLanPolicy.bindHost(true, "  " + STRONG + "\n"));
        T.ok("强 token lanEffective=true", AgentLanPolicy.lanEffective(true, STRONG));

        // ------------------------------------------------------------ 回退说明（不含 token）
        T.eq("未请求 LAN 无回退说明", "", AgentLanPolicy.fallbackReason(false, "short"));
        T.eq("强 token 无回退说明", "", AgentLanPolicy.fallbackReason(true, STRONG));
        String emptyReason = AgentLanPolicy.fallbackReason(true, "");
        T.ok("空 token 回退说明", emptyReason.length() > 0 && emptyReason.contains("已回退"));
        String weakReason = AgentLanPolicy.fallbackReason(true, "short");
        T.ok("弱 token 回退说明含 32 位", weakReason.contains("32 位"));
        T.ok("回退说明不泄露 token", !weakReason.contains("short")
                && !emptyReason.contains(STRONG));

        // ------------------------------------------------------------ 常量与回退组合
        T.eq("loopback 常量", "127.0.0.1", AgentLanPolicy.LOOPBACK);
        T.eq("any 常量", "0.0.0.0", AgentLanPolicy.ANY);
        T.eq("请求 LAN + null 仍 loopback", AgentLanPolicy.LOOPBACK,
                AgentLanPolicy.bindHost(true, null));

        return T.report("AgentLanPolicyTest");
    }
}
