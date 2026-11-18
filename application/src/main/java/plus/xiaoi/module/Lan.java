package plus.xiaoi.module;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * 局域网地址探测（纯 JVM，无 android 依赖）。
 *
 * 只返回私有网段的 IPv4：能力桥开放局域网时，设置页用它显示
 * {@code http://<ip>:<port>/mcp} 和拼接复制文案。
 * 优先 wlan/wifi/eth，避免 VPN、热点、蜂窝接口干扰。
 */
public final class Lan {

    private Lan() {}

    /** @return 形如 192.168.0.112；没连 Wi-Fi/找不到私有地址时返回空串。 */
    public static String ipv4() {
        String best = "";
        int bestScore = -1;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                int base = score(ni.getName());
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia == null) continue;
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    if (!a.isSiteLocalAddress()) continue;
                    int s = base;
                    // 169.254/16 是链路本地地址，通常没有可用路由，排到最后。
                    if (a.isLinkLocalAddress()) s -= 50;
                    if (s > bestScore) {
                        bestScore = s;
                        best = a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return best == null ? "" : best;
    }

    /** 生成 MCP URL；lan=false 或没有局域网地址时退回 loopback。 */
    public static String mcpUrl(int port, boolean lan) {
        String ip = lan ? ipv4() : "";
        return "http://" + (ip.length() > 0 ? ip : "127.0.0.1") + ":" + port + "/mcp";
    }

    private static int score(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (n.startsWith("wlan") || n.contains("wifi")) return 40;
        if (n.startsWith("eth") || n.startsWith("en")) return 30;
        if (n.startsWith("ap") || n.contains("hotspot")) return 10;
        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.contains("cellular")) return -20;
        if (n.startsWith("tun") || n.startsWith("ppp") || n.contains("vpn")) return -10;
        return 0;
    }
}
