package plus.xiaoi.module;

/**
 * 小爱进程宿主的进程内持有者（WP2）。
 *
 * <p>由 {@code Hook.handleLoadPackage} 首次注入；之后 Bridge/BridgeControlClient
 * 通过本类取同一个 host，避免各自维护静态引用。</p>
 */
public final class XiaoiProcess {

    private static volatile XiaoiProcessHost sHost;

    private XiaoiProcess() {}

    public static synchronized void install(XiaoiProcessHost host) {
        if (host != null) sHost = host;
    }

    /** 只安装一次；返回当前 host（可能为之前已安装的实例）。 */
    public static synchronized XiaoiProcessHost installIfAbsent(XiaoiProcessHost host) {
        if (sHost == null && host != null) sHost = host;
        return sHost;
    }

    /** 当前 host；未安装时为 null。 */
    public static XiaoiProcessHost host() {
        return sHost;
    }

    /** 仅测试/调试使用：清除注入，回到未安装状态。 */
    static void clearForTest() {
        sHost = null;
    }
}