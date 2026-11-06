package plus.xiaoi.module.agent;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import plus.xiaoi.module.BridgeClient;
import plus.xiaoi.module.HomeActivity;
import plus.xiaoi.module.Json;
import plus.xiaoi.module.R;

/**
 * 提醒宿主（Android 实现）：超级岛优先，失败自动降级普通通知。
 *
 * <p>island 走 8771 的 MCP {@code xiaoai_island}：端口来自
 * {@link BridgeClient#discover(String)}，调用需要 Bearer token。返回非 null 且
 * 没有明确 {@code ok=false}（例如 alreadyShown）视为已显示。小爱不在、调用失败、
 * 岛被系统忽略时降级到 {@link NotificationManager}，渠道与
 * {@code notification.send} 共用 {@code agent_action}。</p>
 *
 * <p>不打印 token、不回传异常正文；结果里的 {@code channel} / {@code fallback}
 * 如实反映实际送达路径。超级岛只做提醒，不承担批准/拒绝。</p>
 */
public final class AndroidNotifyHost implements NotifyHost {

    private static final String TITLE = "小爱+ Agent";
    private static final AtomicInteger NEXT_NOTIFICATION_ID =
            new AtomicInteger(0x414800);

    private final Context ctx;
    private final String token;

    public AndroidNotifyHost(Context context, String token) {
        this.ctx = context == null ? null : context.getApplicationContext();
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public NotifyResult notify(String channel, String text, boolean force) {
        String value = text == null ? "" : text;
        if ("island".equals(channel)) {
            NotifyResult island = island(value, force);
            if (island.ok) return island;
            NotifyResult notification = notification(value);
            if (notification.ok) {
                return NotifyResult.success("notification", true);
            }
            return NotifyResult.failure("notification", notification.error, true);
        }
        if ("notification".equals(channel)) {
            return notification(value);
        }
        return NotifyResult.failure(channel, "INVALID_ARGUMENT", false);
    }

    // ------------------------------------------------------------- 超级岛

    private NotifyResult island(String text, boolean force) {
        int port = BridgeClient.discover(token);
        if (port <= 0) {
            return NotifyResult.failure("island", "XIAOI_OFFLINE", false);
        }
        Map<String, Object> args = Json.obj();
        args.put("text", text);
        args.put("force", force);
        String out = BridgeClient.mcpCall(port, token, "xiaoai_island", Json.write(args));
        if (out == null) {
            return NotifyResult.failure("island", "XIAOI_OFFLINE", false);
        }
        try {
            Object v = Json.read(out);
            if (v instanceof Map) {
                Object ok = ((Map<?, ?>) v).get("ok");
                if (Boolean.FALSE.equals(ok)) {
                    // force=true 仍可能被系统限制；如实降级，不假装岛已显示。
                    return NotifyResult.failure("island", "NOT_AVAILABLE", false);
                }
            }
        } catch (Throwable ignored) {
        }
        return NotifyResult.success("island", false);
    }

    // ------------------------------------------------------------- 普通通知

    private NotifyResult notification(String text) {
        if (ctx == null) {
            return NotifyResult.failure("notification", "NOT_AVAILABLE", false);
        }
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return NotifyResult.failure("notification", "NOT_AVAILABLE", false);
        }
        if (!notificationsAllowed(nm)) {
            return NotifyResult.failure("notification", "PERMISSION_DENIED", false);
        }
        try {
            ensureChannel(nm);
            nm.notify(NEXT_NOTIFICATION_ID.incrementAndGet(), buildNotification(text));
        } catch (SecurityException t) {
            return NotifyResult.failure("notification", "PERMISSION_DENIED", false);
        } catch (Throwable t) {
            return NotifyResult.failure("notification", "NOT_AVAILABLE", false);
        }
        return NotifyResult.success("notification", false);
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

    private void ensureChannel(NotificationManager nm) {
        if (nm.getNotificationChannel(AndroidExecutor.CHANNEL_ACTION) != null) return;
        NotificationChannel ch = new NotificationChannel(AndroidExecutor.CHANNEL_ACTION,
                AndroidExecutor.CHANNEL_ACTION_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = new Notification.Builder(ctx,
                AndroidExecutor.CHANNEL_ACTION)
                .setSmallIcon(R.drawable.ic_xiaoi)
                .setContentTitle(TITLE)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_REMINDER);
        try {
            Intent open = new Intent(ctx, HomeActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            builder.setContentIntent(PendingIntent.getActivity(ctx, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } catch (Throwable ignored) {
        }
        return builder.build();
    }
}
