package plus.xiaoi.module;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 单端口统一的本机转发客户端：端口发现（/health 自证身份）、Bearer、tools/call 往返。
 *
 * <p>用裸 ServerSocket 起一个假对端，验证请求行/头/正文格式与响应解析，
 * 不依赖真机或 Android。
 */
public final class LoopbackMcpClientTest {

    private static final String TOKEN = "tok-test";
    private static final String HEALTH = "ok agent=true port=9 bind=0.0.0.0";

    private LoopbackMcpClientTest() {}

    public static int run() throws Exception {
        FakeServer fake = new FakeServer();
        try {
            int port = fake.start();
            T.ok("发现端口", port > 0);

            LoopbackMcpClient c = new LoopbackMcpClient(TOKEN, "ok agent=true", port, port + 1);
            T.ok("available", c.available());
            T.eq("port 命中", port, c.port());

            Map<String, Object> tool = c.callTool("xiaoai_status", new HashMap<String, Object>());
            T.ok("tools/call 返回 result", tool != null && tool.containsKey("content"));
            T.eq("tools/call isError", Boolean.FALSE, tool.get("isError"));
            T.eq("带上了 Bearer", "Bearer " + TOKEN, fake.lastAuthorization);
            T.ok("请求路径 /mcp", "/mcp".equals(fake.lastPath));
            T.ok("请求方法是 POST", "POST".equals(fake.lastMethod));
            T.ok("正文是 tools/call", fake.lastBody.contains("\"method\":\"tools/call\""));
            T.ok("工具名透传", fake.lastBody.contains("\"name\":\"xiaoai_status\""));

            Map<String, Object> tools = c.tools();
            T.ok("tools/list 解析", tools != null && ((java.util.List<?>) tools.get("tools")).size() == 2);
            T.ok("合并请求带 own=true（防递归）", fake.lastBody.contains("\"own\":true"));

            LoopbackMcpClient wrong = new LoopbackMcpClient(TOKEN, "ok facade=", port);
            T.ok("needle 不匹配则不认这个端口", !wrong.available());
            T.eq("needle 不匹配 port=0", 0, wrong.port());

            LoopbackMcpClient noToken = new LoopbackMcpClient("", "ok agent=true", port);
            T.ok("空 token 也能发现（对端免鉴权）", noToken.available());
            noToken.callTool("xiaoai_status", new HashMap<String, Object>());
            T.eq("空 token 不发 Authorization", null, fake.lastAuthorization);

            // 对端消失后：调用返回 null，端口缓存被清掉
            fake.stop();
            T.eq("停止后 tools/call 返回 null", null,
                    c.callTool("xiaoai_status", new HashMap<String, Object>()));
            T.eq("失败后端口缓存清零", 0, c.port());
        } finally {
            fake.stop();
        }
        return T.report("LoopbackMcpClientTest");
    }

    /** 假对端：GET /health + POST /mcp，严格校验 Bearer。 */
    private static final class FakeServer {
        private ServerSocket server;
        private Thread thread;
        private volatile boolean running;
        volatile String lastAuthorization;
        volatile String lastPath;
        volatile String lastMethod;
        volatile String lastBody = "";

        int start() throws Exception {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            running = true;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "fake-mcp");
            thread.setDaemon(true);
            thread.start();
            return server.getLocalPort();
        }

        void stop() {
            running = false;
            try {
                if (server != null) server.close();
            } catch (Throwable ignored) {
            }
            if (thread != null) {
                try {
                    thread.join(1000);
                } catch (Throwable ignored) {
                }
            }
        }

        private void acceptLoop() {
            while (running) {
                Socket s = null;
                try {
                    s = server.accept();
                    handle(s);
                } catch (Throwable t) {
                    if (!running) return;
                } finally {
                    try {
                        if (s != null) s.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private void handle(Socket s) throws Exception {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = in.readLine();
            if (requestLine == null) return;
            String[] first = requestLine.split(" ");
            String method = first.length > 0 ? first[0] : "";
            String path = first.length > 1 ? first[1] : "";
            Map<String, String> headers = new HashMap<String, String>();
            while (true) {
                String line = in.readLine();
                if (line == null || line.length() == 0) break;
                int j = line.indexOf(':');
                if (j > 0) {
                    headers.put(line.substring(0, j).trim().toLowerCase(),
                            line.substring(j + 1).trim());
                }
            }
            int len = 0;
            String cl = headers.get("content-length");
            if (cl != null) {
                try {
                    len = Integer.parseInt(cl.trim());
                } catch (Throwable ignored) {
                }
            }
            char[] buf = new char[len];
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) break;
                off += n;
            }

            lastMethod = method;
            lastPath = path;
            lastAuthorization = headers.get("authorization");
            lastBody = new String(buf, 0, off);

            if (lastAuthorization != null && !("Bearer " + TOKEN).equals(lastAuthorization)) {
                respond(s, 401, "unauthorized");
                return;
            }
            if ("GET".equals(method) && "/health".equals(path)) {
                respond(s, 200, HEALTH);
                return;
            }
            if ("POST".equals(method) && "/mcp".equals(path)) {
                String result = lastBody.contains("\"tools/list\"")
                        ? "{\"tools\":[{\"name\":\"xiaoai_action\"},{\"name\":\"xiaoai_ask\"}]}"
                        : "{\"content\":[{\"type\":\"text\",\"text\":\"pong\"}],\"isError\":false}";
                respond(s, 200, "{\"jsonrpc\":\"2.0\",\"id\":\"proxy\",\"result\":" + result + "}");
                return;
            }
            respond(s, 404, "not found");
        }

        private static void respond(Socket s, int code, String body) throws Exception {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + code + " x\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + out.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream os = s.getOutputStream();
            os.write(head.getBytes(StandardCharsets.UTF_8));
            os.write(out);
            os.flush();
        }
    }
}
