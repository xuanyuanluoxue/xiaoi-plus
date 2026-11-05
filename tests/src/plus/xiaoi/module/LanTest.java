package plus.xiaoi.module;

/** Lan 测试：URL 拼接与 loopback 回退（不依赖真实网卡）。 */
public final class LanTest {

    private LanTest() {}

    public static int run() {
        T.eq("mcpUrl 本机", "http://127.0.0.1:8771/mcp", Lan.mcpUrl(8771, false));
        T.eq("mcpUrl 指定端口", "http://127.0.0.1:9000/mcp", Lan.mcpUrl(9000, false));

        // 局域网模式下探测不到私有 IPv4 时必须回退 loopback，不能生成 http://:port
        String lan = Lan.mcpUrl(8771, true);
        T.ok("mcpUrl 局域网总是带主机", lan.startsWith("http://") && !lan.startsWith("http://:"));
        T.ok("mcpUrl 局域网以 /mcp 结尾", lan.endsWith(":8771/mcp"));

        // 探测本身不应抛异常（CI/容器里可能没有非回环网卡）
        String ip = Lan.ipv4();
        T.ok("ipv4 不返回 null", ip != null);
        T.ok("ipv4 不含端口", ip.indexOf(':') < 0);
        return T.report("LanTest");
    }
}
