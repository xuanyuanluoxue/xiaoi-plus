package plus.xiaoi.module.agent;

import plus.xiaoi.module.core.AgentRuntimeSnapshot;
import plus.xiaoi.module.core.BridgeSnapshot;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import plus.xiaoi.module.AppConfig;
import plus.xiaoi.module.AppLaunch;
import plus.xiaoi.module.AppExecutors;
import plus.xiaoi.module.ConfigRepository;
import plus.xiaoi.module.Json;
import plus.xiaoi.module.application.bridge.BridgeStatus;
import plus.xiaoi.module.application.log.LogSettings;
import plus.xiaoi.module.Logs;
import plus.xiaoi.module.platform.R;
import plus.xiaoi.module.core.ActivityFeed;
import plus.xiaoi.module.core.ActivityHub;

/**
 * App 进程内的 Agent 前台服务。
 *
 * <p>0.16.0 起是**单端口**：本服务不再监听任何端口。配置 {@code mcpEnabled=true} 且
 * {@code mcpToken} 非空（或开了免鉴权）时，只把工具实现准备就绪；8771（小爱进程）
 * 通过控制面 Binder（{@code C2S_AGENT_TOOLS} / {@code C2S_AGENT_CALL}）调用
 * {@link #toolsJson()} 与 {@link #callJson}，对外仍只有一个 MCP 端点。</p>
 *
 * <p>服务本身不执行动作，动作由 {@link AndroidExecutor} / {@link RootExecutor} 与
 * {@link ActionRegistry} 提供。WP1.2b 注册全部 5 个动作，其中 app.open 与
 * media.control 使用 {@link FallbackExecutor}（android 优先，NOT_AVAILABLE 才回退 root）。
 * WP1.3b 注入 {@link AndroidNotifyHost}（超级岛优先，失败降级通知）与
 * {@link BridgeAskHost}（8771 /cli 代理小爱）。</p>
 *
 * <p>所有配置读取都在 {@link AppExecutors#io} 上完成，不在主线程碰文件。
 * 绑定失败只记录日志并保留服务，避免把 App 主进程拖崩。</p>
 */
public final class AgentService extends Service {

    public static final String CHANNEL = "agent_service";
    /** 对外唯一端口 = 8771（0.16.0 单端口）；保留常量供旧引用编译。 */
    public static final int PORT = plus.xiaoi.module.core.Contract.BRIDGE_PORT;

    private static final String CHANNEL_NAME = "Agent 能力";
    private static final int NOTIF_ID = 0x4147454e; // "AGEN"
    private static final String TEXT_RUNNING = "设备操作已就绪（经 8771 MCP）· 点击打开";
    private static final String TEXT_FAILED = "设备操作接口启动失败 · 点击打开";

    private final AtomicBoolean bootstrapping = new AtomicBoolean(false);
    /** 上次构建时的「token + 开关」指纹；变化才重建 AgentMcp（不重开端口）。 */
    private volatile String runningKey = "";

    /** 单端口：8771 进程通过控制面 IPC 调用这两个入口。 */
    private static volatile AgentMcp sMcp;
    private static volatile ActionRegistry sRegistry;

    /** 幂等缓存文件：App 私有目录，不需要 chmod。 */
    static final String IDEMPOTENCY_FILE = "agent_idempotency.json";
    /** 读取上限；超过视为损坏并空启动，避免异常大文件拖慢服务。 */
    static final long IDEMPOTENCY_MAX_BYTES = 256L * 1024L;

    // WP1.5：
    // - 幂等结果持久化到 files/agent_idempotency.json，服务/进程重启后同 key 仍返回首次结果。
    // - 确认 token 仍然只在内存（一次性、60 秒）。重启即失效是安全设计，不持久化。
    // - 审计仍为内存环形缓冲，轮转落盘留给后续工作包。
    private final ConfirmationManager confirmations = new ConfirmationManager();
    private final AuditLog audit = new AuditLog();
    /** 在 IO 线程首次创建并恢复；重建工具时复用同一实例，不清空。 */
    private volatile IdempotencyStore idempotency;

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
        ActivityHub.info(ActivityFeed.Domain.AGENT, "Agent 服务已创建");
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
        sMcp = null;
        sRegistry = null;
        runningKey = "";
        AgentRuntimeStatus.setStopped();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        Logs.i("AgentService 已停止");
        ActivityHub.info(ActivityFeed.Domain.AGENT, "Agent 服务已停止");
        super.onDestroy();
    }

    // ------------------------------------------------------------- 单端口 IPC 入口

    /**
     * 动作面工具表（JSON 数组）；服务未就绪返回空串。
     *
     * <p>由控制面 {@code C2S_AGENT_TOOLS} 在 Binder 线程调用，只读内存快照。</p>
     */
    public static String toolsJson() {
        AgentMcp m = sMcp;
        if (m == null) return "";
        try {
            Map<String, Object> list = m.toolsList();
            Object tools = list.get("tools");
            return Json.write(tools == null ? Json.arr() : tools);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 执行一个动作面工具（JSON 入 / JSON 出）；服务未就绪返回空串。
     *
     * <p>由控制面 {@code C2S_AGENT_CALL} 调用；结果与 MCP tools/call 的 result 同构。</p>
     */
    public static String callJson(String name, String argsJson) {
        AgentMcp m = sMcp;
        ActionRegistry registry = sRegistry;
        if (m == null || registry == null) return "";
        Map<?, ?> args = null;
        if (argsJson != null && argsJson.trim().length() > 0) {
            try {
                Object parsed = Json.read(argsJson);
                if (parsed instanceof Map) args = (Map<?, ?>) parsed;
            } catch (Throwable ignored) {
            }
        }
        try {
            return Json.write(m.callTool(name, args, registry));
        } catch (Throwable t) {
            return "";
        }
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
                    AgentRuntimeStatus.setError("启动流程失败：" + t.getClass().getSimpleName());
                    ActivityHub.error(ActivityFeed.Domain.AGENT, "设备操作接口启动流程失败");
                } finally {
                    bootstrapping.set(false);
                }
            }
        });
    }

    private void startOrStop() {
        AppConfig c = ConfigRepository.get(this).load();
        LogSettings.setWire(c.logLevel);
        String token = c.mcpToken == null ? "" : c.mcpToken.trim();
        if (!c.mcpEnabled || (token.length() == 0 && !c.mcpNoAuth)) {
            Logs.i("AgentService: MCP 未启用或既无 token 也未开免鉴权，停止动作面");
            sMcp = null;
            sRegistry = null;
            runningKey = "";
            AgentRuntimeStatus.setStopped();
            ActivityHub.info(ActivityFeed.Domain.AGENT,
                    "设备操作接口未启动（MCP 关闭或既无令牌也未开免鉴权）");
            stopSelf();
            return;
        }

        // 单端口（0.16.0）：不再监听任何端口，只重建内存里的工具实现。
        String key = token + "|" + c.mcpNoAuth + "|" + c.mcpWeakToken;
        if (key.equals(runningKey) && sMcp != null) {
            updateNotification(TEXT_RUNNING);
            markServedBy();
            return;
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

        // 启动顺序：读文件 → restoreJson → 再挂持久化回调，避免刚恢复就回写一遍。
        IdempotencyStore idempotencyStore = idempotencyStore();
        AgentMcp mcp = new AgentMcp(versionName(), confirmations, idempotencyStore, audit,
                new AndroidNotifyHost(this, token), new BridgeAskHost(token));
        sRegistry = registry;
        sMcp = mcp;
        runningKey = key;
        Logs.i("AgentService: 动作面已就绪（单端口，经 8771 暴露）");
        markServedBy();
        updateNotification(TEXT_RUNNING);
    }

    /**
     * 把「动作面挂在 8771 上」写进快照。
     *
     * <p>端口/绑定优先取桥的实时快照；桥还没上报时用首选端口 8771，
     * 不猜 bind 地址（空串 = 未知，由 UI 显示未确认）。</p>
     */
    private static void markServedBy() {
        BridgeSnapshot b = BridgeStatus.get();
        int port = b != null && b.port > 0 ? b.port : plus.xiaoi.module.core.Contract.BRIDGE_PORT;
        String host = b == null || b.bindHost == null ? "" : b.bindHost;
        boolean lan = b != null && Boolean.TRUE.equals(b.lanEnabled);
        AgentRuntimeSnapshot prev = AgentRuntimeStatus.get();
        if (prev.running && prev.port == port && prev.bindHost.equals(host)) {
            return;
        }
        AgentRuntimeStatus.setServedBy(port, host, lan);
        ActivityHub.info(ActivityFeed.Domain.AGENT, "设备操作面就绪（经 " + port + " MCP）");
    }

    // ------------------------------------------------------------- 幂等持久化

    /**
     * 首次调用时读取 App 私有文件并恢复；之后重绑端口复用同一实例，不清空缓存。
     * 由 {@link #startOrStop()} 在 IO 线程调用，不做主线程文件 I/O。
     */
    private IdempotencyStore idempotencyStore() {
        IdempotencyStore current = idempotency;
        if (current != null) return current;

        File file = idempotencyFile();
        IdempotencyStore created = new IdempotencyStore();
        if (file != null && file.isFile()) {
            String text = readSmallFile(file, IDEMPOTENCY_MAX_BYTES);
            created.restoreJson(text);
            if (created.lastRestoreError().length() > 0) {
                // 不打印文件内容；损坏时按空缓存启动，下一次写入会自动覆盖。
                Logs.e("AgentService: 幂等缓存恢复失败，按空缓存启动（"
                        + created.lastRestoreError() + "）");
            }
        }
        if (file != null) created.setPersistence(new FilePersistence(file));
        idempotency = created;
        return created;
    }

    private File idempotencyFile() {
        try {
            File dir = getFilesDir();
            return dir == null ? null : new File(dir, IDEMPOTENCY_FILE);
        } catch (Throwable t) {
            Logs.e("AgentService 无法定位私有目录（幂等缓存不落盘）: "
                    + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 读小文件；不存在/超限/读失败返回空串（调用方按空缓存处理）。 */
    private static String readSmallFile(File file, long maxBytes) {
        if (file == null || !file.isFile()) return "";
        try {
            long length = file.length();
            if (length <= 0) return "";
            if (length > maxBytes) {
                Logs.e("AgentService: 幂等缓存超过读取上限，已忽略（不打印内容）");
                return "";
            }
            FileInputStream in = new FileInputStream(file);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) length);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) {
                        Logs.e("AgentService: 幂等缓存读取超限，已忽略（不打印内容）");
                        return "";
                    }
                }
                return new String(out.toByteArray(), "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            Logs.e("AgentService 读取幂等缓存失败（按空缓存启动）: "
                    + t.getClass().getSimpleName());
            return "";
        }
    }

    /**
     * 幂等快照落盘：调用线程只入队，实际写文件在 IO 线程串行执行。
     * 同一时刻只保留最新快照，避免频繁 put 时写盘排队。
     */
    private static final class FilePersistence implements IdempotencyStore.Persistence {
        private final File file;
        private final Object lock = new Object();
        private String pending;
        private boolean scheduled;

        FilePersistence(File file) {
            this.file = file;
        }

        @Override
        public void save(String json) {
            if (json == null || file == null) return;
            synchronized (lock) {
                pending = json;
                if (scheduled) return;
                scheduled = true;
            }
            AppExecutors.io(new Runnable() {
                @Override
                public void run() {
                    drain();
                }
            });
        }

        private void drain() {
            while (true) {
                String json;
                synchronized (lock) {
                    json = pending;
                    pending = null;
                    if (json == null) {
                        scheduled = false;
                        return;
                    }
                }
                writeAtomic(file, json);
            }
        }

        private static void writeAtomic(File file, String json) {
            File tmp = new File(file.getPath() + ".tmp");
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(tmp);
                out.write(json.getBytes("UTF-8"));
                out.flush();
                out.close();
                out = null;
                if (!tmp.renameTo(file)) {
                    // 某些文件系统不允许覆盖式 rename；删旧文件后重试一次。
                    if (file.exists()) file.delete();
                    if (!tmp.renameTo(file)) {
                        Logs.e("AgentService: 幂等缓存写入失败（rename 未成功）");
                    }
                }
            } catch (Throwable t) {
                // 写盘失败只记日志，不影响动作结果。
                Logs.e("AgentService 幂等缓存写入失败: " + t.getClass().getSimpleName());
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
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
        Intent open = AppLaunch.intent(this);
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
