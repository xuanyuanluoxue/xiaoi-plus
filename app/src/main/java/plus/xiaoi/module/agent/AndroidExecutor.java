package plus.xiaoi.module.agent;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import plus.xiaoi.module.Json;
import plus.xiaoi.module.R;

/**
 * App 进程内的 Android 执行器：普通 App 权限能完成的动作都在这里。
 *
 * <p>实现 {@code device.info} / {@code app.open} / {@code alarm.set} /
 * {@code notification.send} / {@code media.control}（音量与媒体键）。所有系统调用
 * 都做异常隔离：失败映射成稳定错误码，不把异常正文带回协议面；拿不到的字段
 * 直接省略，不把动作拖崩。</p>
 *
 * <p>不假装成功：应用找不到、没有时钟 App、通知权限缺失、没有活跃媒体会话、
 * 系统拒绝后台启动，都返回明确的 {@code NOT_AVAILABLE} / {@code PERMISSION_DENIED}；
 * {@code startActivity} 已返回但后台限制可能让界面不显示时，用 warnings 说明。</p>
 */
public final class AndroidExecutor implements ActionExecutor {

    private static final String NAME = "android";

    /** notification.send 专用渠道：默认重要性，按需创建。 */
    private static final String CHANNEL_ACTION = "agent_action";
    private static final String CHANNEL_ACTION_NAME = "Agent 动作";

    private static final String NOTIFICATION_TAG = "agent_action";
    private static final AtomicInteger NEXT_NOTIFICATION_ID = new AtomicInteger(0x414700);

    private static final int MAX_PACKAGE_CHARS = 200;
    private static final int MAX_URI_CHARS = 2048;
    private static final int MAX_TITLE_CHARS = 200;
    private static final int MAX_TEXT_CHARS = 4000;

    private static final String[] DEFAULT_FIELDS = {
            "model", "manufacturer", "android", "sdk",
            "battery", "charging", "network", "uptime"
    };

    private final Context ctx;

    public AndroidExecutor(Context context) {
        this.ctx = context == null ? null : context.getApplicationContext();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean available() {
        return ctx != null;
    }

    @Override
    public ActionResult execute(ActionRequest request) {
        if (request == null) {
            return failure("", AgentError.Code.INVALID_ARGUMENT, "request 不能为空");
        }
        String action = request.action;
        try {
            if ("device.info".equals(action)) return deviceInfo(request);
            if ("app.open".equals(action)) return appOpen(request);
            if ("alarm.set".equals(action)) return alarmSet(request);
            if ("notification.send".equals(action)) return notificationSend(request);
            if ("media.control".equals(action)) return mediaControl(request);
            return failure(action, AgentError.Code.NOT_AVAILABLE,
                    "AndroidExecutor 未实现动作：" + action);
        } catch (Throwable t) {
            // 兜底：任何未预期的系统异常都不上抛，也不把异常正文带回协议面。
            return failure(action, AgentError.Code.INTERNAL,
                    "系统调用失败（" + t.getClass().getSimpleName() + "）");
        }
    }

    // ------------------------------------------------------------- app.open

    private ActionResult appOpen(ActionRequest request) {
        if (ctx == null) {
            return failure("app.open", AgentError.Code.NOT_AVAILABLE, "App 上下文不可用");
        }
        String pkg = request.stringArg("package", "").trim();
        String uriText = request.stringArg("intentUri", "").trim();
        if (pkg.length() == 0 && uriText.length() == 0) {
            return failure("app.open", AgentError.Code.INVALID_ARGUMENT,
                    "package 与 intentUri 至少提供一个");
        }

        PackageManager pm = ctx.getPackageManager();
        Intent intent;
        if (pkg.length() > 0) {
            if (pkg.length() > MAX_PACKAGE_CHARS || !RootCommands.validPackage(pkg)) {
                return failure("app.open", AgentError.Code.INVALID_ARGUMENT,
                        "package 不合法（只允许字母、数字、点和下划线，≤"
                                + MAX_PACKAGE_CHARS + " 字符）");
            }
            try {
                pm.getApplicationInfo(pkg, 0);
            } catch (Throwable t) {
                return failure("app.open", AgentError.Code.NOT_AVAILABLE,
                        "应用未安装：" + pkg);
            }
            try {
                intent = pm.getLaunchIntentForPackage(pkg);
            } catch (Throwable t) {
                intent = null;
            }
            if (intent == null) {
                return failure("app.open", AgentError.Code.NOT_AVAILABLE,
                        "应用没有可启动的入口：" + pkg);
            }
        } else {
            if (uriText.length() > MAX_URI_CHARS) {
                return failure("app.open", AgentError.Code.INVALID_ARGUMENT,
                        "intentUri 过长");
            }
            try {
                intent = Intent.parseUri(uriText, 0);
            } catch (Throwable t) {
                return failure("app.open", AgentError.Code.INVALID_ARGUMENT,
                        "intentUri 无法解析");
            }
            if (intent == null) {
                return failure("app.open", AgentError.Code.INVALID_ARGUMENT,
                        "intentUri 无法解析");
            }
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        ComponentName resolved = null;
        try {
            resolved = intent.resolveActivity(pm);
        } catch (Throwable ignored) {
        }
        String actualPackage = intent.getPackage();
        if (actualPackage == null || actualPackage.length() == 0) {
            actualPackage = resolved == null ? pkg : resolved.getPackageName();
        }

        try {
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException t) {
            return failure("app.open", AgentError.Code.NOT_AVAILABLE,
                    "没有 Activity 可以处理该 intent");
        } catch (SecurityException t) {
            return failure("app.open", AgentError.Code.PERMISSION_DENIED,
                    "系统拒绝启动（后台启动限制或权限不足）");
        } catch (Throwable t) {
            return failure("app.open", AgentError.Code.NOT_AVAILABLE,
                    "系统未能接受启动请求");
        }

        Map<String, Object> result = Json.obj();
        result.put("launched", Boolean.TRUE);
        result.put("package", actualPackage == null ? "" : actualPackage);
        result.put("component", resolved == null ? "" : resolved.flattenToShortString());

        List<String> warnings = new ArrayList<String>();
        warnings.add("startActivity 已返回；HyperOS/后台启动限制可能让界面未显示，无 root 时无法确认");
        return new ActionResult(true, "app.open", NAME, result, warnings, null, 0, "", null);
    }

    // ------------------------------------------------------------- alarm.set

    private ActionResult alarmSet(ActionRequest request) {
        if (ctx == null) {
            return failure("alarm.set", AgentError.Code.NOT_AVAILABLE, "App 上下文不可用");
        }
        long hour = request.longArg("hour", -1);
        long minute = request.longArg("minute", -1);
        // schema 已经校验范围；这里再挡一次，避免执行器被直接调用时越界。
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return failure("alarm.set", AgentError.Code.INVALID_ARGUMENT,
                    "hour 需在 0~23、minute 需在 0~59");
        }
        boolean skipUi = request.booleanArg("skipUi", false);
        String label = request.stringArg("label", "");

        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM);
        intent.putExtra(AlarmClock.EXTRA_HOUR, (int) hour);
        intent.putExtra(AlarmClock.EXTRA_MINUTES, (int) minute);
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi);
        if (label.length() > 0) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            ResolveInfo target = ctx.getPackageManager().resolveActivity(intent, 0);
            if (target == null) {
                return failure("alarm.set", AgentError.Code.NOT_AVAILABLE,
                        "系统没有可用的时钟应用");
            }
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException t) {
            return failure("alarm.set", AgentError.Code.NOT_AVAILABLE,
                    "系统没有可用的时钟应用");
        } catch (SecurityException t) {
            return failure("alarm.set", AgentError.Code.PERMISSION_DENIED,
                    "系统拒绝打开闹钟界面");
        } catch (Throwable t) {
            return failure("alarm.set", AgentError.Code.NOT_AVAILABLE,
                    "闹钟界面未能打开");
        }

        Map<String, Object> result = Json.obj();
        result.put("scheduled", Boolean.TRUE);
        // 无法确认时钟 App 是否真的跳过了界面：默认如实返回 false。
        result.put("skippedUi", Boolean.FALSE);

        List<String> warnings = new ArrayList<String>();
        if (skipUi) {
            warnings.add("已请求 skipUi，但时钟 App 是否跳过界面无法确认；请以系统闹钟列表为准");
        }
        warnings.add("闹钟由系统时钟 App 创建；本动作只负责打开/提交请求");
        return new ActionResult(true, "alarm.set", NAME, result, warnings, null, 0, "", null);
    }

    // ------------------------------------------------------------- notification.send

    private ActionResult notificationSend(ActionRequest request) {
        if (ctx == null) {
            return failure("notification.send", AgentError.Code.NOT_AVAILABLE,
                    "App 上下文不可用");
        }
        String title = request.stringArg("title", "").trim();
        String text = request.stringArg("text", "").trim();
        if (title.length() == 0 || text.length() == 0) {
            return failure("notification.send", AgentError.Code.INVALID_ARGUMENT,
                    "title 与 text 不能为空");
        }
        if (title.length() > MAX_TITLE_CHARS) {
            return failure("notification.send", AgentError.Code.INVALID_ARGUMENT,
                    "title 过长（上限 " + MAX_TITLE_CHARS + " 字符）");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            return failure("notification.send", AgentError.Code.INVALID_ARGUMENT,
                    "text 过长（上限 " + MAX_TEXT_CHARS + " 字符）");
        }

        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return failure("notification.send", AgentError.Code.NOT_AVAILABLE,
                    "系统通知服务不可用");
        }
        if (!notificationsAllowed(nm)) {
            return failure("notification.send", AgentError.Code.PERMISSION_DENIED,
                    "缺少 POST_NOTIFICATIONS 权限或通知被系统关闭");
        }

        ensureActionChannel(nm);
        String requestedId = request.stringArg("id", "").trim();
        int notificationId = notificationId(requestedId);
        try {
            nm.notify(NOTIFICATION_TAG, notificationId,
                    buildActionNotification(title, text));
        } catch (SecurityException t) {
            return failure("notification.send", AgentError.Code.PERMISSION_DENIED,
                    "系统拒绝发送通知");
        } catch (Throwable t) {
            return failure("notification.send", AgentError.Code.NOT_AVAILABLE,
                    "通知发送失败");
        }

        Map<String, Object> result = Json.obj();
        result.put("notificationId", String.valueOf(notificationId));
        return new ActionResult(true, "notification.send", NAME, result,
                null, null, 0, "", null);
    }

    private boolean notificationsAllowed(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            } catch (Throwable t) {
                return false;
            }
        }
        try {
            return nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private void ensureActionChannel(NotificationManager nm) {
        try {
            if (nm.getNotificationChannel(CHANNEL_ACTION) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ACTION,
                    CHANNEL_ACTION_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
            // 渠道创建失败时 notify 会自己降级/抛错，由上层映射。
        }
    }

    /** 同 id 覆盖同一条通知；没给 id 时分配递增 id。 */
    private static int notificationId(String requestedId) {
        if (requestedId == null || requestedId.length() == 0) {
            return NEXT_NOTIFICATION_ID.incrementAndGet();
        }
        int hash = requestedId.hashCode() & 0x7fffffff;
        return 0x100000 + (hash % 0x100000);
    }

    private Notification buildActionNotification(String title, String text) {
        return new Notification.Builder(ctx, CHANNEL_ACTION)
                .setSmallIcon(R.drawable.ic_xiaoi)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setShowWhen(true)
                .build();
    }

    // ------------------------------------------------------------- media.control

    private ActionResult mediaControl(ActionRequest request) {
        if (ctx == null) {
            return failure("media.control", AgentError.Code.NOT_AVAILABLE,
                    "App 上下文不可用");
        }
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return failure("media.control", AgentError.Code.NOT_AVAILABLE,
                    "系统音频服务不可用");
        }
        String command = request.stringArg("command", "").trim();
        if ("volume".equals(command)) {
            return volume(am, request);
        }
        int keyCode;
        if ("play".equals(command)) keyCode = KeyEvent.KEYCODE_MEDIA_PLAY;
        else if ("pause".equals(command)) keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE;
        else if ("next".equals(command)) keyCode = KeyEvent.KEYCODE_MEDIA_NEXT;
        else if ("prev".equals(command)) keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        else {
            return failure("media.control", AgentError.Code.INVALID_ARGUMENT,
                    "不支持的 command：" + command);
        }

        if (!isMusicActive(am)) {
            return failure("media.control", AgentError.Code.NOT_AVAILABLE,
                    "当前没有活跃的媒体会话");
        }

        try {
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        } catch (SecurityException t) {
            // 系统不允许普通 App 派发媒体键：交给 root 兜底，而不是伪造成功。
            return failure("media.control", AgentError.Code.NOT_AVAILABLE,
                    "系统不允许直接派发媒体键");
        } catch (Throwable t) {
            return failure("media.control", AgentError.Code.NOT_AVAILABLE,
                    "媒体键派发失败");
        }

        Map<String, Object> result = Json.obj();
        result.put("command", command);
        result.put("volume", volumePercent(am));
        List<String> warnings = new ArrayList<String>();
        warnings.add("媒体键已派发；实际响应取决于当前前台 MediaSession");
        return new ActionResult(true, "media.control", NAME, result, warnings, null, 0, "", null);
    }

    private ActionResult volume(AudioManager am, ActionRequest request) {
        if (request.hasArg("volume")) {
            long requested = request.longArg("volume", -1);
            if (requested < 0 || requested > 100) {
                return failure("media.control", AgentError.Code.INVALID_ARGUMENT,
                        "volume 需在 0~100");
            }
            int max = maxVolume(am);
            if (max <= 0) {
                return failure("media.control", AgentError.Code.NOT_AVAILABLE,
                        "系统没有可写的主流媒体音量");
            }
            int index = (int) Math.round(requested * max / 100.0);
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
            } catch (SecurityException t) {
                return failure("media.control", AgentError.Code.PERMISSION_DENIED,
                        "系统拒绝修改媒体音量（勿扰/设备策略）");
            } catch (Throwable t) {
                return failure("media.control", AgentError.Code.NOT_AVAILABLE,
                        "媒体音量修改失败");
            }
        }

        Map<String, Object> result = Json.obj();
        result.put("command", "volume");
        result.put("volume", volumePercent(am));
        return new ActionResult(true, "media.control", NAME, result, null, null, 0, "", null);
    }

    private static int maxVolume(AudioManager am) {
        try {
            return Math.max(0, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int volumePercent(AudioManager am) {
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) return 0;
            int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            long pct = Math.round(current * 100.0 / max);
            if (pct < 0) return 0;
            return pct > 100 ? 100 : (int) pct;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isMusicActive(AudioManager am) {
        try {
            return am.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------- device.info

    private ActionResult deviceInfo(ActionRequest request) {
        List<String> requested = request.stringListArg("fields");
        if (requested == null || requested.isEmpty()) {
            requested = new ArrayList<String>();
            for (String f : DEFAULT_FIELDS) requested.add(f);
        }

        Map<String, Object> fields = Json.obj();
        for (String field : requested) {
            putField(fields, field);
        }

        Map<String, Object> result = Json.obj();
        result.put("fields", fields);
        return ActionResult.ok("device.info", NAME, result);
    }

    /** 按请求名写字段；未知/读不到的字段保持省略。 */
    private void putField(Map<String, Object> out, String field) {
        if (field == null || field.length() == 0) return;
        String f = field.trim();
        try {
            if ("model".equals(f)) {
                out.put(f, safe(Build.MODEL));
            } else if ("manufacturer".equals(f)) {
                out.put(f, safe(Build.MANUFACTURER));
            } else if ("brand".equals(f)) {
                out.put(f, safe(Build.BRAND));
            } else if ("device".equals(f)) {
                out.put(f, safe(Build.DEVICE));
            } else if ("android".equals(f)) {
                out.put(f, safe(Build.VERSION.RELEASE));
            } else if ("sdk".equals(f) || "sdkInt".equals(f)) {
                out.put(f, (long) Build.VERSION.SDK_INT);
            } else if ("battery".equals(f) || "batteryLevel".equals(f)) {
                int level = batteryLevel();
                if (level >= 0) out.put(f, (long) level);
            } else if ("charging".equals(f)) {
                Boolean charging = charging();
                if (charging != null) out.put(f, charging);
            } else if ("network".equals(f) || "networkType".equals(f)) {
                String type = networkType();
                if (type != null) out.put(f, type);
            } else if ("uptime".equals(f)) {
                out.put(f, SystemClock.elapsedRealtime() / 1000L);
            } else if ("uptimeMs".equals(f)) {
                out.put(f, SystemClock.elapsedRealtime());
            } else if ("memory".equals(f) || "foregroundApp".equals(f)) {
                // 需要 root / usage stats，WP1.2 明确不提供；按 schema 省略。
            }
        } catch (Throwable ignored) {
            // 单个字段失败不阻塞其他字段。
        }
    }

    private int batteryLevel() {
        if (ctx == null) return -1;
        Intent battery = ctx.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return -1;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return -1;
        return Math.max(0, Math.min(100, level * 100 / scale));
    }

    private Boolean charging() {
        if (ctx == null) return null;
        Intent battery = ctx.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return null;
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (status < 0) return null;
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    private String networkType() {
        if (ctx == null) return null;
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        if (Build.VERSION.SDK_INT >= 23) {
            Network network = cm.getActiveNetwork();
            if (network == null) return "none";
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return "none";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "bluetooth";
            return "other";
        }
        return "unknown";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private ActionResult failure(String action, AgentError.Code code, String message) {
        return ActionResult.failure(action, NAME, AgentError.of(code, message));
    }
}
