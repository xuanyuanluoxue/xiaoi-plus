package plus.xiaoi.module.core;

/**
 * 数据面监听策略（纯 JVM，无 android 依赖，可桌面穷举测试）。
 *
 * WP3 起「是否监听」不再由进程名/启动顺序决定，而是只看用户配置：
 * MCP 开关关闭或 token 为空时，任何进程都不得绑定 8771~8775。
 *
 * 局域网判定在本包保持 beta.4 行为（lan && mcpEnabled && token 非空）。
 * {@link #tokenStrongEnoughForLan(String)} 预留给 WP4：LAN 只接受 32 hex（128 bit）
 * token，本包调用方**不要**用它收紧 bindHost，否则会改变现有 LAN 行为。
 */
public final class BridgePolicy {

    /** WP4 起 LAN 要求的 token 长度（32 个十六进制字符 = 128 bit）。 */
    public static final int LAN_TOKEN_HEX_LENGTH = 32;

    private BridgePolicy() {}

    /** 是否允许启动/保留 HTTP 数据面：MCP 开启且 token 非空。 */
    public static boolean shouldListen(boolean mcpEnabled, String token) {
        return mcpEnabled && normalize(token).length() > 0;
    }

    /** 监听地址：只有 lan、MCP 开启、token 非空三者同时满足才绑 0.0.0.0。 */
    public static String bindHost(boolean lan, boolean mcpEnabled, String token) {
        return lan && shouldListen(mcpEnabled, token) ? "0.0.0.0" : "127.0.0.1";
    }

    /**
     * WP4 预留：token 是否强到可以开放局域网（恰好 32 个十六进制字符）。
     * 本包只提供判定，不改变 {@link #bindHost} 的现有行为。
     */
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
