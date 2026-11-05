package plus.xiaoi.module;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

/**
 * BridgePusher 测试：本地 HTTP 桩验证「认端口 + 全端口聚合」。
 *
 * 覆盖：
 * - 正常桥（ok facade=... port=）→ 认领并推送成功
 * - 冒名服务（ok facade=true 但没有 port=）→ 跳过（8770 场景回归）
 * - health 401 → 不计成功
 * - 非本模块响应（html / ok）→ 跳过
 * - 不可达端口 → 聚合结果为 0 且带原因
 * - 载荷分组（R3）：无密钥不推 baseUrl/model/apiKey/enabled
 */
public final class BridgePusherTest {

    private BridgePusherTest() {}

    private static final String TOKEN = "tok-abc";

    public static int run() throws Exception {
        // ------------------------------------------------------------ 身份判据（纯函数）
        T.ok("health 正常响应认领", BridgePusher.isOurBridge("ok facade=true port=8771"));
        T.ok("health facade=false 也认领", BridgePusher.isOurBridge("ok facade=false port=8775"));
        T.ok("health 带换行仍认领", BridgePusher.isOurBridge("ok facade=true port=8771\n"));
        T.ok("冒名服务（无 port=）不认领", !BridgePusher.isOurBridge("ok facade=true"));
        T.ok("冒名服务（多空格）不认领", !BridgePusher.isOurBridge("ok  facade=true port=8771"));
        T.ok("html 不认领", !BridgePusher.isOurBridge("<html>ok facade=true port=1</html>"));
        T.ok("空 body 不认领", !BridgePusher.isOurBridge(""));
        T.ok("null body 不认领", !BridgePusher.isOurBridge(null));
        T.ok("前缀不同不认领", !BridgePusher.isOurBridge("OK facade=true port=8771"));

        // ------------------------------------------------------------ 桩：正常桥 ×2
        String[] bodyOk = new String[1];
        HttpServer ok1 = serve("ok facade=true port=%d", 200, 200, bodyOk);
        HttpServer ok2 = serve("ok facade=false port=%d", 200, 200, null);

        BridgePusher.Result r = BridgePusher.pushAll(TOKEN, "{\"marker\":true}",
                new int[]{ok1.getAddress().getPort(), ok2.getAddress().getPort()});
        T.eq("两个正常桥 total", 2, r.total);
        T.eq("两个正常桥 pushed", 2, r.pushed);
        T.eq("两个正常桥 errors 为空", 0, r.errors.size());
        T.ok("两个正常桥 allOk", r.allOk());
        T.ok("两个正常桥端口被记录", r.ports.contains(ok1.getAddress().getPort())
                && r.ports.contains(ok2.getAddress().getPort()));
        T.eq("body 原样送达", "{\"marker\":true}", bodyOk[0]);
        T.eq("两个正常桥文案", "已推送到 2 个进程", r.message());

        // ------------------------------------------------------------ 桩：冒名 + 401 + html
        HttpServer fake = serve("ok facade=true", 200, 200, null);          // 8770 冒名
        HttpServer denied = serve("denied", 401, 200, null);               // health 401
        HttpServer html = serve("<html>nginx</html>", 200, 200, null);     // 别的服务
        int closed = freePort();

        r = BridgePusher.pushAll(TOKEN, "{}", new int[]{
                fake.getAddress().getPort(),
                denied.getAddress().getPort(),
                html.getAddress().getPort(),
                closed});
        T.eq("失败组 probed", 4, r.probed);
        T.eq("失败组 total", 0, r.total);
        T.eq("失败组 pushed", 0, r.pushed);
        T.ok("失败组带原因", r.errors.size() >= 4);
        T.ok("冒名端口有原因", hasError(r, fake.getAddress().getPort(), "非本模块桥"));
        T.ok("401 端口有原因", hasError(r, denied.getAddress().getPort(), "health 401"));
        T.ok("html 端口有原因", hasError(r, html.getAddress().getPort(), "非本模块桥"));
        T.ok("不可达端口有原因", hasError(r, closed, "连接失败"));
        T.ok("无桥时补一条总原因", lastErrorContains(r, "未发现本模块的桥"));
        T.ok("无桥时 allOk=false", !r.allOk());
        T.eq("无桥时文案", "已保存到文件；未找到运行中的小爱", r.message());

        // ------------------------------------------------------------ 桩：认领成功但 POST 失败
        HttpServer pushFail = serve("ok facade=true port=%d", 200, 500, null);
        r = BridgePusher.pushAll(TOKEN, "{}", new int[]{pushFail.getAddress().getPort()});
        T.eq("认领成功但推送失败 total", 1, r.total);
        T.eq("认领成功但推送失败 pushed", 0, r.pushed);
        T.ok("推送失败有原因", hasError(r, pushFail.getAddress().getPort(), "config 500"));
        T.eq("全部失败文案", "已保存到文件；推送失败（共 1 个进程）", r.message());

        // ------------------------------------------------------------ 部分成功（1 好 1 推失败）
        r = BridgePusher.pushAll(TOKEN, "{}", new int[]{
                ok1.getAddress().getPort(), pushFail.getAddress().getPort()});
        T.eq("部分成功 total", 2, r.total);
        T.eq("部分成功 pushed", 1, r.pushed);
        T.ok("部分成功 allOk=false", !r.allOk());
        T.eq("部分成功文案", "已推送 1/2，其余进程将在 800ms 内从 prefs 生效", r.message());

        // ------------------------------------------------------------ 空/异常输入
        T.eq("null 端口列表 total", 0, BridgePusher.pushAll(TOKEN, "{}", null).total);
        T.eq("空端口列表 probed", 0, BridgePusher.pushAll(TOKEN, "{}", new int[0]).probed);

        // ------------------------------------------------------------ 载荷分组（R3）
        AppConfig noKey = new AppConfig();
        noKey.enabled = true;
        noKey.marker = true;
        noKey.markerTemplate = "[T] ";
        noKey.mcpEnabled = true;
        noKey.mcpToken = "tok";
        noKey.providers.add(new AppConfig.Provider("p", "P", "https://api.example.com/v1", ""));
        noKey.activeProviderId = "p";
        noKey.providers.get(0).models.add(new AppConfig.ModelItem("m1", "M1"));
        noKey.activeModelId = "m1";

        java.util.Map<?, ?> noKeyMap = asMap(BridgePusher.payloadFor(noKey));
        T.ok("无密钥不含 apiKey", !noKeyMap.containsKey("apiKey"));
        T.ok("无密钥不含 baseUrl", !noKeyMap.containsKey("baseUrl"));
        T.ok("无密钥不含 model", !noKeyMap.containsKey("model"));
        T.ok("无密钥不含 enabled", !noKeyMap.containsKey("enabled"));
        T.eq("无密钥仍推 markerTemplate", "[T] ", String.valueOf(noKeyMap.get("markerTemplate")));
        T.eq("无密钥仍推 bridge", Boolean.TRUE, noKeyMap.get("bridge"));
        T.eq("无密钥仍推 token", "tok", String.valueOf(noKeyMap.get("token")));
        T.eq("无密钥仍推 marker", Boolean.TRUE, noKeyMap.get("marker"));

        AppConfig withKey = new AppConfig();
        withKey.enabled = false;
        withKey.providers.add(new AppConfig.Provider(
                "p", "P", "https://api.example.com/v1", "sk-secret"));
        withKey.providers.get(0).models.add(new AppConfig.ModelItem("m2", "M2"));
        withKey.activeProviderId = "p";
        withKey.activeModelId = "m2";
        withKey.mcpToken = "tok2";
        java.util.Map<?, ?> fullMap = asMap(BridgePusher.payloadFor(withKey));
        T.eq("有密钥推 apiKey", "sk-secret", String.valueOf(fullMap.get("apiKey")));
        T.eq("有密钥推 baseUrl", "https://api.example.com/v1", String.valueOf(fullMap.get("baseUrl")));
        T.eq("有密钥推 model", "m2", String.valueOf(fullMap.get("model")));
        T.eq("有密钥推 enabled", Boolean.FALSE, fullMap.get("enabled"));
        T.eq("有密钥推 token", "tok2", String.valueOf(fullMap.get("token")));
        T.ok("null 配置返回空对象", "{}".equals(BridgePusher.payloadFor(null)));

        ok1.stop(0);
        ok2.stop(0);
        fake.stop(0);
        denied.stop(0);
        html.stop(0);
        pushFail.stop(0);
        return T.report("BridgePusherTest");
    }

    // ------------------------------------------------------------- 桩与工具

    @SuppressWarnings("unchecked")
    private static java.util.Map<?, ?> asMap(String json) {
        Object v = Json.read(json);
        return (java.util.Map<?, ?>) v;
    }

    /**
     * 起一个桩：/health 返回 healthFmt（%d 替换为实际端口），/config 返回 configCode，
     * 并把收到的 body 写进 bodyOut[0]。
     */
    private static HttpServer serve(final String healthFmt, final int healthCode,
                                    final int configCode, final String[] bodyOut) throws Exception {
        final HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final int port = s.getAddress().getPort();
        s.createContext("/health", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                String body = String.format(healthFmt, Integer.valueOf(port));
                send(x, healthCode, body);
            }
        });
        s.createContext("/config", new HttpHandler() {
            public void handle(HttpExchange x) throws java.io.IOException {
                String body = read(x);
                if (bodyOut != null) bodyOut[0] = body;
                send(x, configCode, "{\"ok\":true}");
            }
        });
        s.start();
        return s;
    }

    private static String read(HttpExchange x) throws java.io.IOException {
        InputStream in = x.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange x, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        x.sendResponseHeaders(code, b.length);
        OutputStream os = x.getResponseBody();
        os.write(b);
        os.close();
    }

    /** 拿一个当前没人监听的端口（连它必然 connection refused）。 */
    private static int freePort() throws Exception {
        ServerSocket s = new ServerSocket(0);
        int p = s.getLocalPort();
        s.close();
        return p;
    }

    private static boolean hasError(BridgePusher.Result r, int port, String needle) {
        for (String e : r.errors) {
            if (e.startsWith(port + ":") && e.contains(needle)) return true;
        }
        return false;
    }

    private static boolean lastErrorContains(BridgePusher.Result r, String needle) {
        return !r.errors.isEmpty() && r.errors.get(r.errors.size() - 1).contains(needle);
    }
}
