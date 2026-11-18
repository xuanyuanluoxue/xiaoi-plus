package plus.xiaoi.module.application.runtime;

/** 用量查询端口（WP11）：读 8771 /stats，UI 不认识 BridgeClient。 */
public interface UsageStats {

    /**
     * 拉取本机 8771 的 {@code /stats} 原始 JSON。
     *
     * @return 响应体；未开启 / 端口无效 / 请求失败返回 null
     */
    String fetch(int port, String token, int days);
}
