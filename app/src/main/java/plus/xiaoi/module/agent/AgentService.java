package plus.xiaoi.module.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicBoolean;

import plus.xiaoi.module.AppConfig;
import plus.xiaoi.module.AppExecutors;
import plus.xiaoi.module.ConfigRepository;
import plus.xiaoi.module.HomeActivity;
import plus.xiaoi.module.Logs;
import plus.xiaoi.module.R;

/**
 * App 进程内的 Agent 前台服务。
 *
 * <p>WP1.2a 负责生命周期与 8770 的挂载：配置 {@code mcpEnabled=true} 且
 * {@code mcpToken} 非空时监听 loopback；否则不监听并 stopSelf。服务本身不执行
 * 动作，动作由 {@link AndroidExecutor} / {@link RootExecutor} 与
 * {@link ActionRegistry} 提供。WP1.2b 注册全部 5 个动作，其中 app.open 与
 * media.control 使用 {@link FallbackExecutor}（android 优先，NOT_AVAILABLE 才回退 root）。</p>
 *
 * <p>所有配置读取都在 {@link AppExecutors#io} 上完成，不在主线程碰文件。
 * 绑定失败只记录日志并保留服务，避免把 App 主进程拖崩。</p>
 */
public final class AgentService extends Service {

    public static final String CHANNEL = "agent_service";
    public static final int PORT = AgentHttpServer.PORT;

    private static final String CHANNEL_NAME = "Agent 能力";
    private static final int NOTIF_ID = 0x4147454e; // "AGEN"
    private static final String TEXT_RUNNING = "8770 运行中 · 点击打开";
    private static final String TEXT_FAILED = "8770 启动失败 · 点击打开";

    private final AtomicBoolean bootstrapping = new AtomicBoolean(false);
    private volatile AgentHttpServer server;
    private volatile String runningToken = "";

    // ------------------------------------------------------------- 静态入口

    /**
     * 按当前配置启动或停止服务。配置读取与启动都在 IO 线程完成，可安全从 UI
     * 或 BOOT_COMPLETED 调用。
     */
    public static void startIfEnabled(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        AppExecutors.io(new Runnable() {
            @Override
            public void run() {
                try {
                    AppConfig c = ConfigRepository.get(app).load();
                    String token = c.mcpToken == null ? "" : c.mcpToken.trim();
                    if (c.mcpEnabled && token.length() > 0) {
                        start(app);
                    } else {
                        stop(app);
                    }
                } catch (Throwable t) {
                    Logs.e("AgentService 读取配置失败（已忽略）: "
                            + t.getClass().getSimpleName());
                }
            }
        });
    }

    /** 停止服务（不改变配置；下次调用 startIfEnabled 时按配置决定是否恢复）。 */
    public static void stop(Context context) {
        if (context == null) return;
        try {
            context.getApplicationContext().stopService(
                    new Intent(context.getApplicationContext(), AgentService.class));
        } catch (Throwable t) {
            Logs.e("AgentService 停止失败（已忽略）: " + t.getClass().getSimpleName());
        }
    }

    private static void start(Context app) {
        try {
            Intent i = new Intent(app, AgentService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(i);
            } else {
                app.startService(i);
            }
        } catch (Throwable t) {
            Logs.e("AgentService 启动失败（已忽略）: " + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------- 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ensureChannel();
            startForeground(NOTIF_ID, buildNotification(TEXT_RUNNING));
        } catch (Throwable t) {
            Logs.e("AgentService startForeground 失败: " + t.getClass().getSimpleName());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        bootstrap();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        AgentHttpServer s = server;
        server = null;
        runningToken = "";
        if (s != null) s.stop();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        Logs.i("AgentService 已停止");
        super.onDestroy();
    }

    // ------------------------------------------------------------- 启动/停止

    private void bootstrap() {
        if (!bootstrapping.compareAndSet(false, true)) return;
        AppExecutors.io(new Runnable() {
            @Override
            public void run() {
                try {
                    startOrStop();
                } catch (Throwable t) {
                    Logs.e("AgentService 启动流程失败: " + t.getClass().getSimpleName());
                    updateNotification(TEXT_FAILED);
                } finally {
                    bootstrapping.set(false);
                }
            }
        });
    }

    private void startOrStop() {
        AppConfig c = ConfigRepository.get(this).load();
        String token = c.mcpToken == null ? "" : c.mcpToken.trim();
        if (!c.mcpEnabled || token.length() == 0) {
            Logs.i("AgentService: MCP 未启用或 token 为空，停止 8770");
            stopSelf();
            return;
        }

        AgentHttpServer current = server;
        if (current != null && current.isRunning() && token.equals(runningToken)) {
            updateNotification(TEXT_RUNNING);
            return;
        }
        if (current != null) {
            current.stop();
            server = null;
            runningToken = "";
        }

        ActionRegistry registry = new ActionRegistry();
        AndroidExecutor android = new AndroidExecutor(this);
        FallbackExecutor androidOrRoot = new FallbackExecutor(android, new RootExecutor());
        for (ActionDescriptor d : Actions.builtins()) {
            registry.register(d, null);
        }
        // 普通权限能完成的动作只走 android；app.open / media.control 在
        // android 返回 NOT_AVAILABLE 时才尝试 root（不重复副作用）。
        registry.register(Actions.deviceInfo(), android);
        registry.register(Actions.alarmSet(), android);
        registry.register(Actions.notificationSend(), android);
        registry.register(Actions.appOpen(), androidOrRoot);
        registry.register(Actions.mediaControl(), androidOrRoot);

        AgentHttpServer next = new AgentHttpServer(token,
                new AgentMcp(versionName()), registry);
        String err = next.start();
        if (err.length() > 0) {
            Logs.e("AgentService: " + err);
            updateNotification(TEXT_FAILED);
            return;
        }
        server = next;
        runningToken = token;
        Logs.i("AgentService: 8770 已启动");
        updateNotification(TEXT_RUNNING);
    }

    // ------------------------------------------------------------- 通知

    private void ensureChannel() {
        NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, HomeActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_xiaoi)
                .setContentTitle("小爱+ Agent 能力")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Throwable t) {
            Logs.e("AgentService 更新通知失败（已忽略）: " + t.getClass().getSimpleName());
        }
    }

    private String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null || v.length() == 0 ? "0" : v;
        } catch (Throwable t) {
            return "0";
        }
    }
}
