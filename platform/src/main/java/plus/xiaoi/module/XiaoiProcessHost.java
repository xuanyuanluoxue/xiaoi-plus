package plus.xiaoi.module;

/**
 * 小爱进程宿主端口（WP2）。
 *
 * <p>Hook（Xposed 入站适配器）与控制面客户端只依赖本接口；
 * Android 侧实现见 {@link XiaoiProcessHostImpl}。这样
 * {@code Hook ↔ BridgeControlClient} 之间不再有直接的类型依赖。</p>
 *
 * <p>{@code appContext()} 返回 {@link Object}，避免端口本身依赖
 * {@code android.content.Context}，便于后续把 holder/契约下沉到纯 JVM 层。</p>
 */
public interface XiaoiProcessHost {

    /** 每个小爱进程加载后调用：同步策略、启动控制面与数据面生命周期。 */
    void onProcessStart(Config config, ClassLoader classLoader);

    /** 抓到 OsbotFacadeImpl 实例后调用；幂等。 */
    void onFacadeReady(ClassLoader classLoader, Object facade);

    /** 控制面拉到新配置 / 远程 MCP 改配置后：热更新 hook 与内存快照。 */
    void onConfigReload(Config config);

    /** 配置热更新后同步 token，并交给生命周期重算。 */
    void updateToken(String token);

    /** 配置热更新后同步局域网/MCP 开关。 */
    void updateAccess(boolean lan, boolean bridge);

    /** 配置热更新后同步「免鉴权」开关。 */
    void updateNoAuth(boolean noAuth);

    /** 配置热更新后同步「允许弱密码用于局域网」开关。 */
    void updateWeakToken(boolean weakToken);

    /** 超级岛：force=true 时不受“每进程一次”限制（控制面按钮使用）。 */
    void showIsland(String text, boolean force);

    /** 实际监听端口；非 leader / 未运行时为 0。 */
    int port();

    /** 当前 socket 是否真的绑在 0.0.0.0。 */
    boolean isBoundLan();

    String versionName();

    /** 小爱进程 Application；早期可能为 null。 */
    Object appContext();
}