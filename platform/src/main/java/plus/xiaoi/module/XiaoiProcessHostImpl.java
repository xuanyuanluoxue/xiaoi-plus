package plus.xiaoi.module;

/**
 * 小爱进程宿主的 Android 组装实现（WP2）。
 *
 * <p>只做委托：Bridge 负责 hook/控制面/数据面，Island 负责超级岛。
 * “配置热更新”通过 {@link ConfigReloader} 回调注入，避免本类直接依赖 Hook，
 * 从而让 {@code BridgeControlClient} 只看到 {@link XiaoiProcessHost} 接口。</p>
 */
public final class XiaoiProcessHostImpl implements XiaoiProcessHost {

    public interface ConfigReloader {
        void reload(Config config);
    }

    private final ConfigReloader reloader;

    public XiaoiProcessHostImpl(ConfigReloader reloader) {
        if (reloader == null) throw new IllegalArgumentException("reloader == null");
        this.reloader = reloader;
    }

    @Override
    public void onProcessStart(Config config, ClassLoader classLoader) {
        Bridge.onProcessStart(config, classLoader, this);
    }

    @Override
    public void onFacadeReady(ClassLoader classLoader, Object facade) {
        Bridge.install(classLoader, facade, this);
    }

    @Override
    public void onConfigReload(Config config) {
        reloader.reload(config);
    }

    @Override
    public void updateToken(String token) {
        Bridge.updateToken(token);
    }

    @Override
    public void updateAccess(boolean lan, boolean bridge) {
        Bridge.updateAccess(lan, bridge);
    }

    @Override
    public void updateNoAuth(boolean noAuth) {
        Bridge.updateNoAuth(noAuth);
    }

    @Override
    public void updateWeakToken(boolean weakToken) {
        Bridge.updateWeakToken(weakToken);
    }

    @Override
    public void showIsland(String text, boolean force) {
        Island.show(text, force);
    }

    @Override
    public int port() {
        return Bridge.port();
    }

    @Override
    public boolean isBoundLan() {
        return Bridge.isBoundLan();
    }

    @Override
    public String versionName() {
        return Bridge.versionName();
    }

    @Override
    public Object appContext() {
        return Bridge.appContext();
    }
}