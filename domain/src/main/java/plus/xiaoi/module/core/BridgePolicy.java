package plus.xiaoi.module.core;

/**
 * 数据面监听策略（纯 JVM，无 android 依赖，可桌面穷举测试）。
 *
 * WP3 起「是否监听」不再由进程名/启动顺序决定，而是只看用户配置：
 * MCP 开关关闭或 token 为空时，任何进程都不得绑定 8771~8775。
 *
 * 局域网判定在本包保持 beta.4 行为（lan && mcpEnabled && token 非空）。
 * {@link #tokenStrongEnoughForLan(String)} 自 WP4 起**已收紧 bindHost**：
 * LAN 只接受 32 hex（128 bit）token，短 token / 非 hex token 一律回退 loopback。
 */
public final class BridgePolicy {

    /** WP4 起 LAN 要求的 token 长度（32 个十六进制字符 = 128 bit）。 */
    public static final int LAN_TOKEN_HEX_LENGTH = 32;

    private BridgePolicy() {}

    /** 是否允许启动/保留 HTTP 数据面：MCP 开启，且 token 非空**或**开了本机免鉴权。 */
    public static boolean shouldListen(boolean mcpEnabled, String token) {
        return shouldListen(mcpEnabled, token, false);
    }

    /** @param noAuth 本机免鉴权开关（只影响 loopback，见 {@link #allowsAnonymous}） */
    public static boolean shouldListen(boolean mcpEnabled, String token, boolean noAuth) {
        return mcpEnabled && (normalize(token).length() > 0 || noAuth);
    }

    /**
     * 监听地址：只有 lan、MCP 开启、token 非空且 token 强到可以开放局域网时
     * 才绑 0.0.0.0；否则一律 loopback（弱 token 不阻断本机 MCP）。
     *
     * <p>免鉴权**不会**让局域网变成无鉴权：LAN 仍要求 32 hex token。</p>
     */
    public static String bindHost(boolean lan, boolean mcpEnabled, String token) {
        return bindHost(lan, mcpEnabled, token, false);
    }

    public static String bindHost(boolean lan, boolean mcpEnabled, String token, boolean noAuth) {
        return lan && shouldListen(mcpEnabled, token, noAuth) && tokenStrongEnoughForLan(token)
                ? "0.0.0.0" : "127.0.0.1";
    }

    /**
     * 本次请求是否可跳过 Bearer 校验。
     *
     * <p>只有「用户显式开了免鉴权」且「来源是本机回环」时才成立；
     * 局域网来源永远要求 token。</p>
     */
    public static boolean allowsAnonymous(boolean noAuth, boolean loopback) {
        return noAuth && loopback;
    }

    /** token 是否强到可以开放局域网（恰好 32 个十六进制字符）。 */
    public static boolean tokenStrongEnoughForLan(String token) {
        String t = normalize(token);
        if (t.length() != LAN_TOKEN_HEX_LENGTH) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    /** token 归一化：null / 纯空白视为空。 */
    public static String normalize(String token) {
        return token == null ? "" : token.trim();
    }
}
