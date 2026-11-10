package plus.xiaoi.module;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

import plus.xiaoi.module.agent.ActionRegistry;
import plus.xiaoi.module.agent.AgentHttpServer;
import plus.xiaoi.module.agent.AgentMcp;

/**
 * AgentHttpServer 的 /health 报文：port 必须是实际绑定端口，不是首选端口。
 * 真机验收时实际监听 8772，报文却写 8770；这里占住 8770 强制顺延来复现。
 */
public final class AgentHttpServerTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private AgentHttpServerTest() {}

    public static int run() throws Exception {
        ServerSocket blocker = null;
        AgentHttpServer server = null;
        try {
            // 占住首选端口，强制服务顺延。
            try {
                blocker = new ServerSocket();
                blocker.setReuseAddress(true);
                blocker.bind(new InetSocketAddress("127.0.0.1", AgentHttpServer.DEFAULT_PORT));
            } catch (Throwable occupied) {
                // 首选端口已被别的进程占用：同样会顺延，继续。
            }

            server = new AgentHttpServer(TOKEN, new AgentMcp("health-test"), new ActionRegistry());
            String err = server.start();
            T.eq("Agent 服务启动", "", err);
            int actual = server.port();
            T.ok("8770 被占用时实际端口顺延", actual > AgentHttpServer.DEFAULT_PORT);

            BridgeClient.Response r = BridgeClient.get(actual, "/health", TOKEN);
            T.ok("/health 可达", r != null && r.ok());
            String body = r == null ? "" : r.body;
            T.ok("/health 报实际端口", body.contains("port=" + actual + " "));
            T.ok("/health 不再报配置端口 8770",
                    !body.contains("port=" + AgentHttpServer.DEFAULT_PORT + " "));
            T.ok("/health 报实际 bind", body.contains("bind=127.0.0.1"));
        } finally {
            if (server != null) server.stop();
            if (blocker != null) {
                try { blocker.close(); } catch (Throwable ignored) {}
            }
        }
        return T.report("AgentHttpServerTest");
    }
}
