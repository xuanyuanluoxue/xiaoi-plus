package plus.xiaoi.module.core;


/**
 * 8770 Agent 动作面的监听策略（纯 JVM，无 android 依赖）。
 *
 * <p>与 8771 的 {@link BridgePolicy} 共用同一套令牌规则：默认只有显式请求局域网且
 * {@code mcpToken} 恰好是 32 位十六进制时才绑 {@code 0.0.0.0}；用户可以显式
 * 打开「允许弱密码」（任意非空）或「免鉴权」（不需要令牌）来放宽，默认都关闭。</p>
 */
public final class AgentLanPolicy {

    public static final String LOOPBACK = "127.0.0.1";
    public static final String ANY = "0.0.0.0";

    private AgentLanPolicy() {}

    /** 实际是否开放局域网：请求打开，且令牌够强 / 允许弱密码 / 免鉴权。 */
    public static boolean lanEffective(boolean requested, String token) {
        return lanEffective(requested, token, false, false);
    }

    public static boolean lanEffective(boolean requested, String token,
                                       boolean weakToken, boolean noAuth) {
        if (!requested) return false;
        if (noAuth) return true;
        boolean hasToken = BridgePolicy.normalize(token).length() > 0;
        return hasToken && (BridgePolicy.tokenStrongEnoughForLan(token) || weakToken);
    }

    /** 实际监听地址：{@code 0.0.0.0} 或 {@code 127.0.0.1}。 */
    public static String bindHost(boolean requested, String token) {
        return bindHost(requested, token, false, false);
    }

    public static String bindHost(boolean requested, String token,
                                  boolean weakToken, boolean noAuth) {
        return lanEffective(requested, token, weakToken, noAuth) ? ANY : LOOPBACK;
    }

    /**
     * 请求了局域网但被回退时的说明；空串 = 没有回退。
     * 只描述原因，绝不包含令牌本身。
     */
    public static String fallbackReason(boolean requested, String token) {
        return fallbackReason(requested, token, false, false);
    }

    public static String fallbackReason(boolean requested, String token,
                                        boolean weakToken, boolean noAuth) {
        if (!requested || lanEffective(requested, token, weakToken, noAuth)) return "";
        String t = BridgePolicy.normalize(token);
        return t.length() == 0
                ? "Agent 局域网需要设置令牌，已回退仅本机"
                : "Agent 局域网需要 32 位十六进制令牌（或打开弱密码），已回退仅本机";
    }
}
