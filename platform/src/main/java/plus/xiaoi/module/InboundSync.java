package plus.xiaoi.module;

/**
 * 双向枢纽的同步入口：把 8771 端点写进小爱的 mcp_servers.json。
 *
 * <p>从 Hook 里拆出来的原因：{@code :app} 有壳行数上限，而这段逻辑与 Xposed 无关，
 * 只依赖 Config / 端口 / 文件写入。端口由调用方注入，避免平台层反向依赖 Bridge。</p>
 *
 * <p>实测时序：配置热更新早于 leader 绑定端口，此时 {@code port()==0}；直接写会把 url
 * 写成 {@code 127.0.0.1:0}。所以端口为 0 时进入后台重试（10 次 × 2s）。</p>
 */
public final class InboundSync {

    /** 端口来源（通常是 XiaoiProcessHost#port()）。 */
    public interface Port {
        int port();
    }

    private static final java.util.concurrent.atomic.AtomicBoolean RETRY =
            new java.util.concurrent.atomic.AtomicBoolean();

    private InboundSync() {}

    /** 同步一次；端口未就绪且需要写入时转入后台重试。 */
    public static void sync(Config c, Port portSource) {
        try {
            if (c == null || portSource == null) return;
            boolean on = c.inboundEnabled();
            int port = portSource.port();
            Logs.i("双向枢纽: inbound=" + on + " port=" + port);
            if (on && port <= 0) {
                retry(c, portSource);
                return;
            }
            boolean ok = McpServerConfig.sync(on, port, c.token());
            if (on) {
                Logs.i("双向枢纽: mcp_servers.json " + (ok ? "已写入" : "写入失败")
                        + "（重启小爱后生效）");
            }
        } catch (Throwable t) {
            Logs.i("双向枢纽接线失败: " + t);
        }
    }

    private static void retry(final Config c, final Port portSource) {
        if (!RETRY.compareAndSet(false, true)) return;
        AppExecutors.io(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 10; i++) {
                        int p = portSource.port();
                        if (p > 0) {
                            boolean ok = McpServerConfig.sync(true, p, c.token());
                            Logs.i("双向枢纽: 延迟写入 port=" + p + " ok=" + ok);
                            return;
                        }
                        try {
                            Thread.sleep(2000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    Logs.i("双向枢纽: 端口一直未就绪，本次放弃");
                } catch (Throwable t) {
                    Logs.i("双向枢纽: 延迟写入失败 " + t);
                } finally {
                    RETRY.set(false);
                }
            }
        });
    }
}
