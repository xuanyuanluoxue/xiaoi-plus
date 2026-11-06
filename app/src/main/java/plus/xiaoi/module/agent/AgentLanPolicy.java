package plus.xiaoi.module.agent;

import plus.xiaoi.module.core.BridgePolicy;

/**
 * 8770 Agent 动作面的监听策略（纯 JVM，无 android 依赖）。
 *
 * <p>与 8771 的 {@link BridgePolicy} 共用同一套令牌强度规则：
 * 只有显式请求局域网且 {@code mcpToken} 恰好是 32 位十六进制时才绑
 * {@code 0.0.0.0}，否则一律回退 {@code 127.0.0.1}。弱令牌不阻断本机 Agent，
 * 只是不会把可执行设备动作的接口暴露到局域网。</p>
 */
public final class AgentLanPolicy {

    public static final String LOOPBACK = "127.0.0.1";
    public static final String ANY = "0.0.0.0";

    private AgentLanPolicy() {}

    /** 实际是否开放局域网：请求打开且令牌强到可以开放。 */
    public static boolean lanEffective(boolean requested, String token) {
        return requested && BridgePolicy.tokenStrongEnoughForLan(token);
    }

    /** 实际监听地址：{@code 0.0.0.0} 或 {@code 127.0.0.1}。 */
    public static String bindHost(boolean requested, String token) {
        return lanEffective(requested, token) ? ANY : LOOPBACK;
    }

    /**
     * 请求了局域网但被回退时的说明；空串 = 没有回退。
     * 只描述原因，绝不包含令牌本身。
     */
    public static String fallbackReason(boolean requested, String token) {
        if (!requested || BridgePolicy.tokenStrongEnoughForLan(token)) return "";
        String t = BridgePolicy.normalize(token);
        return t.length() == 0
                ? "Agent 局域网需要设置令牌，已回退仅本机"
                : "Agent 局域网需要 32 位十六进制令牌，已回退仅本机";
    }
}
