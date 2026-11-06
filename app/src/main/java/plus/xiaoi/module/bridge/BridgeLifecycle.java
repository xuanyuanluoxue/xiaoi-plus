package plus.xiaoi.module.bridge;

import android.content.Context;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import plus.xiaoi.module.Bridge;
import plus.xiaoi.module.Logs;

/**
 * 数据面生命周期（WP3）。
 *
 * 小爱每个进程都可以调用 {@link #start(Context)}；只有拿到
 * {@code files/xiaoi_bridge.lock} 的进程才会调用 {@link Bridge#startServerIfLeader()}。
 * 其他进程仍然正常 hook、拉配置，只是不监听任何端口。
 *
 * 状态机（每 {@link #RETRY_MS} 毫秒跑一次，配置变化时也会立即 {@link #apply()}）：
 * <pre>
 *   shouldListen=false → Bridge.stopServer() + 释放 leader 锁
 *   shouldListen=true, 非 leader → acquireIfPossible()，失败等下一轮
 *   shouldListen=true, leader   → 确保 server 在监听
 * </pre>
 *
 * 失败即降级：Context 未就绪、文件锁不可用都只写日志并重试，不抛到小爱主流程。
 * 本类不使用裸 {@code new Thread}，重试线程是 daemon 的 ScheduledExecutorService。
 */
public final class BridgeLifecycle {

    /** 非 leader 重试接管间隔（验收窗口 10~30s）。 */
    public static final long RETRY_MS = 10_000L;

    /**
     * 没有 facade 的进程（典型是 :core）先等这么久，让有 facade 的进程优先成为 leader：
     * /health 的 facade=true、MCP 工具能力更完整。宽限期过后仍允许接管，
     * 保证 leader 死亡后 :core 也能在 10~30s 内补上。
     */
    public static final long FACADE_GRACE_MS = 30_000L;

    /** 锁文件放在 App 私有 files 目录；同包所有进程共享。 */
    static final String LOCK_FILE = "xiaoi_bridge.lock";

    private static final Object LOCK = new Object();

    private static volatile boolean sStarted;
    private static volatile Context sApp;
    private static volatile ScheduledExecutorService sScheduler;
    private static volatile LeaderElection sLeader;
    private static volatile String sLastAcquireError = "";
    private static volatile boolean sLoggedStopped;
    private static volatile boolean sLoggedFacadeWait;
    private static volatile long sStartedAt;

    private BridgeLifecycle() {}

    /**
     * 幂等启动。Context 可以为 null（Bridge.install 早期）：后台任务会继续尝试
     * {@link Bridge#appContext()}，拿到后再建立 leader 锁。
     */
    public static void start(Context ctx) {
        if (ctx != null) sApp = ctx.getApplicationContext();
        boolean first;
        synchronized (LOCK) {
            first = !sStarted;
            if (first) {
                sStarted = true;
                sStartedAt = System.currentTimeMillis();
                sScheduler = newScheduler();
            }
        }
        if (first) {
            Logs.i("数据面生命周期启动 pid=" + android.os.Process.myPid()
                    + " process=" + Bridge.processName());
            scheduleTick();
        }
        apply();
    }

    /** 配置变化后调用：立即按当前 Bridge 配置重算 leader/监听状态。 */
    public static void apply() {
        if (!sStarted) {
            start(Bridge.appContext());
            return;
        }
        tick();
    }

    /** 彻底停止：关闭 server、释放 leader 锁、关闭重试线程。可重复调用。 */
    public static void stop() {
        ScheduledExecutorService scheduler;
        synchronized (LOCK) {
            sStarted = false;
            stopServerAndReleaseLock("生命周期 stop");
            scheduler = sScheduler;
            sScheduler = null;
        }
        if (scheduler != null) {
            try {
                scheduler.shutdownNow();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 当前进程是否持有 leader 锁。非 leader 的 {@link Bridge#port()} 返回 0。 */
    public static boolean isLeader() {
        LeaderElection leader = sLeader;
        return leader != null && leader.isLeader();
    }

    // ------------------------------------------------------------- 状态机

    private static void tick() {
        synchronized (LOCK) {
            if (!ensureLeader()) return;

            // 每轮都从共享配置刷新一次：旧推送写 target prefs，非 leader 也能看到关闭。
            // 同时经控制面主动拉一次，保证 MCP 关闭期间 App 重新开启也能生效。
            BridgeControlClient.refresh();
            Bridge.refreshPolicyFromConfig();
            Bridge.ensureCliBinding();

            final boolean listen = Bridge.shouldListen();
            if (!listen) {
                stopServerAndReleaseLock("MCP 关闭或 token 为空");
                return;
            }
            sLoggedStopped = false;

            if (!sLeader.isLeader()) {
                // 没有 facade 的进程先让位，避免 :core 抢在带 CLI/facade 的主进程之前监听。
                if (!Bridge.hasFacade()
                        && System.currentTimeMillis() - sStartedAt < FACADE_GRACE_MS) {
                    if (!sLoggedFacadeWait) {
                        sLoggedFacadeWait = true;
                        Logs.i("本进程暂无 facade，先等待有 facade 的进程成为 leader（"
                                + FACADE_GRACE_MS + "ms 后允许接管）pid="
                                + android.os.Process.myPid()
                                + " process=" + Bridge.processName());
                    }
                    return;
                }
                // 理论上非 leader 不会有 server；这里兜底清掉残留监听。
                if (Bridge.isServerRunning()) Bridge.stopServer();
                if (!sLeader.acquireIfPossible()) {
                    logAcquireFailure(sLeader.lastError());
                    return;
                }
                sLastAcquireError = "";
                Logs.i("已获得数据面 leader 锁 pid=" + android.os.Process.myPid()
                        + " process=" + Bridge.processName());
            }

            if (!Bridge.isServerRunning()) {
                if (Bridge.startServerIfLeader()) {
                    Logs.i("数据面 leader 已监听 port=" + Bridge.port()
                            + " process=" + Bridge.processName());
                    // 接管/首次启动后让控制面刷新端口。
                    BridgeControlClient.onBridgeStarted();
                } else {
                    // 端口被占/上下文未就绪：保持 leader 身份，下一轮重试绑定。
                    Logs.i("leader 暂未监听，10s 后重试 pid=" + android.os.Process.myPid());
                }
            }
        }
    }

    /** 关闭监听并释放锁（仅在需要时记录一次）。 */
    private static void stopServerAndReleaseLock(String reason) {
        boolean wasRunning = Bridge.isServerRunning();
        boolean wasLeader = sLeader != null && sLeader.isLeader();
        if (wasRunning) {
            Bridge.stopServer();
            BridgeControlClient.onBridgeStarted();
        }
        if (wasLeader) {
            sLeader.release();
            Logs.i("已释放数据面 leader 锁（" + reason + "）pid="
                    + android.os.Process.myPid() + " process=" + Bridge.processName());
        }
        if ((wasRunning || wasLeader) && !sLoggedStopped) {
            sLoggedStopped = true;
            Logs.i("数据面已关闭（" + reason + "）：server=" + Bridge.isServerRunning()
                    + " port=" + Bridge.port());
        }
    }

    private static boolean ensureLeader() {
        if (sLeader != null) return true;
        Context ctx = sApp;
        if (ctx == null) {
            ctx = Bridge.appContext();
            if (ctx != null) sApp = ctx.getApplicationContext();
        }
        if (ctx == null) return false;
        try {
            File dir = ctx.getFilesDir();
            if (dir == null) return false;
            sLeader = new LeaderElection(new File(dir, LOCK_FILE),
                    "pid=" + android.os.Process.myPid()
                            + " process=" + Bridge.processName());
            return true;
        } catch (Throwable t) {
            Logs.i("leader 锁初始化失败（10s 后重试）: " + t.getClass().getSimpleName());
            return false;
        }
    }

    private static void logAcquireFailure(String error) {
        if (error == null || error.length() == 0) return;
        if (error.equals(sLastAcquireError)) return;
        sLastAcquireError = error;
        Logs.i("获取 leader 锁失败（10s 后重试）: " + error);
    }

    private static void scheduleTick() {
        ScheduledExecutorService scheduler = sScheduler;
        if (scheduler == null) return;
        try {
            scheduler.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        tick();
                    } catch (Throwable t) {
                        Logs.i("数据面生命周期 tick 失败（已忽略）: "
                                + t.getClass().getSimpleName());
                    }
                }
            }, RETRY_MS, RETRY_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            Logs.i("数据面重试任务启动失败（已忽略）: " + t.getClass().getSimpleName());
        }
    }

    private static ScheduledExecutorService newScheduler() {
        final ThreadFactory base = Executors.defaultThreadFactory();
        return Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            private int n;

            @Override
            public Thread newThread(Runnable r) {
                Thread t = base.newThread(r);
                t.setDaemon(true);
                t.setName("xiaoi-bridge-lifecycle-" + (n++));
                return t;
            }
        });
    }
}
