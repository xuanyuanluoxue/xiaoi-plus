package plus.xiaoi.module.core;

/**
 * 当前数据面 leader 端口的进程内登记处（纯 JVM，无 android 依赖）。
 *
 * <p>WP4 起 App 不再扫描 8771~8775，也不向未认领端口发送 Bearer：
 * 控制面 {@code BridgeControlService} 在 register/reportState 时把 leader 上报的端口写进这里，
 * {@code BridgeClient.discover()} 直接读取；没有 leader 时端口为 0。</p>
 *
 * <p>{@link #setReloadAction(Runnable)} 只应在设置 App 进程使用：配置保存后由
 * {@code ConfigRepository}/{@code ConfigSync} 调用 {@link #requestReload()}，
 * 经控制面通知小爱进程重新拉配置。控制面销毁时必须 {@link #clearReloadAction()}，
 * 避免静态字段持有已销毁的 Service。</p>
 */
public final class BridgeEndpoint {

    private static volatile int sPort;
    private static volatile Runnable sReloadAction;

    private BridgeEndpoint() {}

    /** 登记 leader 端口；0 或负数表示当前没有 leader。 */
    public static void setPort(int port) {
        sPort = port > 0 ? port : 0;
    }

    /** 当前 leader 端口；没有 leader / 控制面未上报时为 0。 */
    public static int port() {
        return sPort;
    }

    /** 设置配置重载回调（仅 App 进程）；重复设置覆盖旧值。 */
    public static void setReloadAction(Runnable action) {
        sReloadAction = action;
    }

    /** 清理回调（控制面 onDestroy）。 */
    public static void clearReloadAction() {
        sReloadAction = null;
    }

    /**
     * 请求小爱进程重新拉配置。
     *
     * @return true = 回调存在且已触发；false = App 控制面未运行（等待小爱 10s 轮询兜底）
     */
    public static boolean requestReload() {
        Runnable action = sReloadAction;
        if (action == null) return false;
        try {
            action.run();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
