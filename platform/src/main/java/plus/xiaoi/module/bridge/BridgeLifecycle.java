package plus.xiaoi.module.bridge;

import android.content.Context;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import plus.xiaoi.module.Logs;
import plus.xiaoi.module.application.bridge.BridgeService;

/**
 * 数据面生命周期调度器（WP3）。
 *
 * <p>只依赖 {@link BridgeRuntime} 接口与纯决策 {@link BridgeService}；
 * 不再 import/调用 {@code Bridge} 静态方法，因此
 * {@code Bridge ↔ BridgeLifecycle} 的类型级双向依赖被切断。</p>
 *
 * <p>状态机（每 {@link #RETRY_MS} 毫秒跑一次，配置变化时也会立即
 * {@link #apply(BridgeRuntime)}）：</p>
 * <pre>
 *   shouldListen=false → 关闭 server + 释放 leader 锁
 *   没有 facade/CLI     → 不接管数据面
 *   shouldListen=true, 非 leader → acquireIfPossible()，失败等下一轮
 *   shouldListen=true, leader   → 确保 server 在监听
 * </pre>
 *
 * <p>失败即降级：Context 未就绪、文件锁不可用都只写日志并重试。
 * 重试线程是 daemon 的 ScheduledExecutorService。</p>
 */
public final class BridgeLifecycle {

    /** 非 leader 重试接管间隔（验收窗口 10~30s）。 */
    public static final long RETRY_MS = 10_000L;

    /**
     * 没有 facade 的进程（典型是 :core）先等这么久，让有 facade 的进程优先成为 leader：
     * /health 的 facade=true、MCP 工具能力更完整。宽限期过后仍允许接管。
     */
    public static final long FACADE_GRACE_MS = 30_000L;

    /** 锁文件放在 App 私有 files 目录；同包所有进程共享。 */
    static final String LOCK_FILE = "xiaoi_bridge.lock";

    private static final Object LOCK = new Object();

    private static volatile boolean sStarted;
    private static volatile Context sApp;
    private static volatile ScheduledExecutorService sScheduler;
    private static volatile LeaderElection sLeader;
    private static volatile BridgeRuntime sRuntime;
    private static volatile String sLastAcquireError = "";
    private static volatile boolean sLoggedStopped;
    private static volatile boolean sLoggedFacadeWait;
    private static volatile boolean sLoggedNoCapability;
    private static volatile long sStartedAt;

    private BridgeLifecycle() {}

    /**
     * 幂等启动。Context 可以为 null；runtime 必须非空（由 Bridge 注入）。
     * 若 runtime 为空只记日志，等后续 {@link #apply(BridgeRuntime)} 再启动。
     */
    public static void start(Context ctx, BridgeRuntime runtime) {
        if (runtime != null) sRuntime = runtime;
        BridgeRuntime rt = sRuntime;
        if (rt == null) {
            Logs.i("数据面生命周期未启动：runtime 为空");
            return;
        }
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
                    + " process=" + rt.processName());
            scheduleTick();
        }
        apply(rt);
    }

    /** 兼容无参调用：使用已注入的 runtime；未注入则什么都不做。 */
    public static void apply() {
        apply(sRuntime);
    }

    /**
     * 配置变化/手动触发：立即按当前 runtime 重算。
     * runtime 非空时先更新静态引用；未启动则先 start。
     */
    public static void apply(BridgeRuntime runtime) {
        if (runtime != null) sRuntime = runtime;
        BridgeRuntime rt = sRuntime;
        if (rt == null) return;
        if (!sStarted) {
            start(null, rt);
            return;
        }
        tick();
    }

    /** 彻底停止：关闭 server、释放 leader 锁、关闭重试线程。可重复调用。 */
    public static void stop() {
        ScheduledExecutorService scheduler;
        synchronized (LOCK) {
            sStarted = false;
            stopServerAndReleaseLock(sRuntime, "生命周期 stop");
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

    /** 当前进程是否持有 leader 锁。 */
    public static boolean isLeader() {
        LeaderElection leader = sLeader;
        return leader != null && leader.isLeader();
    }

    // ------------------------------------------------------------- 状态机

    private static void tick() {
        synchronized (LOCK) {
            BridgeRuntime rt = sRuntime;
            if (rt == null) return;
            if (!ensureLeader(rt)) return;

            // 每轮都从共享配置刷新一次：旧推送写 target prefs，非 leader 也能看到关闭。
            // 同时经控制面主动拉一次，保证 MCP 关闭期间 App 重新开启也能生效。
            // 顺序不可变：刷新/拉取必须在计算 State 之前。
            rt.controlRefresh();
            rt.refreshPolicyFromConfig();
            rt.ensureCliBinding();

            BridgeService.State state = new BridgeService.State();
            state.shouldListen = rt.shouldListen();
            state.hasFacade = rt.hasFacade();
            state.hasCli = rt.hasCli();
            state.leader = sLeader.isLeader();
            state.facadeGraceElapsed =
                    System.currentTimeMillis() - sStartedAt >= FACADE_GRACE_MS;
            state.serverRunning = rt.isServerRunning();
            state.boundLan = rt.isBoundLan();
            state.bindsLan = rt.bindsLan();

            BridgeService.Decision decision = BridgeService.next(state);
            if (state.shouldListen) sLoggedStopped = false;

            if (decision.action == BridgeService.Action.ACQUIRE_LEADER) {
                // 非 leader 若有残留监听，先停掉；失败只记录并等下一轮，不得继续 START。
                if (state.serverRunning) {
                    rt.stopServer();
                    state.serverRunning = false;
                }
                if (!sLeader.acquireIfPossible()) {
                    logAcquireFailure(sLeader.lastError());
                    return;
                }
                sLastAcquireError = "";
                Logs.i("已获得数据面 leader 锁 pid=" + android.os.Process.myPid()
                        + " process=" + rt.processName());
                state.leader = true;
                decision = BridgeService.next(state);
            }

            if (decision.action == BridgeService.Action.CLOSE) {
                boolean noCapability = !state.hasFacade && !state.hasCli;
                stopServerAndReleaseLock(rt, decision.reason);
                if (noCapability) {
                    if (!sLoggedNoCapability) {
                        sLoggedNoCapability = true;
                        Logs.i("本进程没有 facade/CLI，不接管数据面 pid="
                                + android.os.Process.myPid()
                                + " process=" + rt.processName());
                    }
                } else {
                    sLoggedNoCapability = false;
                }
                return;
            }

            if (decision.action == BridgeService.Action.WAIT_FACADE) {
                if (!sLoggedFacadeWait) {
                    sLoggedFacadeWait = true;
                    Logs.i("本进程暂无 facade，先等待有 facade 的进程成为 leader（"
                            + FACADE_GRACE_MS + "ms 后允许接管）pid="
                            + android.os.Process.myPid()
                            + " process=" + rt.processName());
                }
                return;
            }
            sLoggedNoCapability = false;
            sLoggedFacadeWait = false;

            if (decision.action == BridgeService.Action.REBIND) {
                Logs.i("数据面绑定模式变化，重新绑定 pid=" + android.os.Process.myPid()
                        + " process=" + rt.processName());
                rt.stopServer();
                rt.controlOnBridgeStarted();
                // stop 后立即在同一个 tick 内尝试按新策略启动（与原实现一致）。
                decision = new BridgeService.Decision(BridgeService.Action.START, "");
            }

            if (decision.action == BridgeService.Action.START) {
                if (rt.startServerIfLeader()) {
                    Logs.i("数据面 leader 已监听 port=" + rt.port()
                            + " process=" + rt.processName());
                    // 接管/首次启动后让控制面刷新端口。
                    rt.controlOnBridgeStarted();
                } else {
                    // 端口被占/上下文未就绪：保持 leader 身份，下一轮重试绑定。
                    Logs.i("leader 暂未监听，10s 后重试 pid=" + android.os.Process.myPid());
                }
            }
        }
    }

    /** 关闭监听并释放锁（仅在需要时记录一次）。 */
    private static void stopServerAndReleaseLock(BridgeRuntime rt, String reason) {
        boolean wasRunning = rt != null && rt.isServerRunning();
        boolean wasLeader = sLeader != null && sLeader.isLeader();
        if (wasRunning) {
            rt.stopServer();
            rt.controlOnBridgeStarted();
        }
        if (wasLeader) {
            sLeader.release();
            Logs.i("已释放数据面 leader 锁（" + reason + "）pid="
                    + android.os.Process.myPid()
                    + " process=" + (rt == null ? "" : rt.processName()));
        }
        if ((wasRunning || wasLeader) && !sLoggedStopped) {
            sLoggedStopped = true;
            Logs.i("数据面已关闭（" + reason + "）：server="
                    + (rt != null && rt.isServerRunning())
                    + " port=" + (rt == null ? 0 : rt.port()));
        }
    }

    private static boolean ensureLeader(BridgeRuntime rt) {
        if (sLeader != null) return true;
        Context ctx = sApp;
        if (ctx == null) {
            ctx = rt.appContext();
            if (ctx != null) sApp = ctx.getApplicationContext();
        }
        if (ctx == null) return false;
        try {
            File dir = ctx.getFilesDir();
            if (dir == null) return false;
            sLeader = new LeaderElection(new File(dir, LOCK_FILE),
                    "pid=" + android.os.Process.myPid()
                            + " process=" + rt.processName());
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