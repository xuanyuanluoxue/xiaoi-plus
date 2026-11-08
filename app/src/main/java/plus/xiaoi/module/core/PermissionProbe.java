package plus.xiaoi.module.core;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;

/**
 * Android 侧权限探针：把系统 API 的结论翻译成纯模型
 * {@link PermissionSnapshot}。
 *
 * <p>每项独立 try/catch：某个 ROM 没有标准 API 或调用抛异常时只降级为
 * {@link PermissionSnapshot.State#UNKNOWN}，既不崩溃，也不伪造 GRANTED。
 * 本类不修改权限、不申请权限、不写配置。</p>
 */
public final class PermissionProbe {

    private static final String OP_GET_USAGE_STATS = AppOpsManager.OPSTR_GET_USAGE_STATS;

    private PermissionProbe() {}

    public static PermissionSnapshot capture(Context context) {
        PermissionSnapshot.Builder b = PermissionSnapshot.builder();
        if (context == null) return b.build();
        Context app;
        try {
            Context ac = context.getApplicationContext();
            app = ac == null ? context : ac;
        } catch (Throwable t) {
            return b.build();
        }
        String pkg = "";
        try {
            pkg = app.getPackageName();
        } catch (Throwable ignored) {
        }

        b.notifications(notifications(app));
        b.exactAlarm(exactAlarm(app));
        b.fullScreenIntent(fullScreenIntent(app));
        b.batteryOptimization(batteryOptimization(app, pkg));
        b.usageAccess(usageAccess(app, pkg));
        b.notificationListener(notificationListener(app, pkg));
        return b.build();
    }

    private static PermissionSnapshot.State notifications(Context context) {
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return PermissionSnapshot.State.UNKNOWN;
            return state(nm.areNotificationsEnabled());
        } catch (Throwable t) {
            return PermissionSnapshot.State.UNKNOWN;
        }
    }

    /** API 31+ 才有 canScheduleExactAlarms；低版本按系统默认可用处理。 */
    private static PermissionSnapshot.State exactAlarm(Context context) {
        try {
            if (Build.VERSION.SDK_INT < 31) return PermissionSnapshot.State.GRANTED;
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return PermissionSnapshot.State.UNKNOWN;
            return state(am.canScheduleExactAlarms());
        } catch (Throwable t) {
            return PermissionSnapshot.State.UNKNOWN;
        }
    }

    /** API 34+ 才有 canUseFullScreenIntent；低版本无法确认，保持 UNKNOWN。 */
    private static PermissionSnapshot.State fullScreenIntent(Context context) {
        try {
            if (Build.VERSION.SDK_INT < 34) return PermissionSnapshot.State.UNKNOWN;
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return PermissionSnapshot.State.UNKNOWN;
            return state(nm.canUseFullScreenIntent());
        } catch (Throwable t) {
            return PermissionSnapshot.State.UNKNOWN;
        }
    }

    /** 已忽略电池优化 = GRANTED；未忽略 = DENIED（需要用户手动加入白名单）。 */
    private static PermissionSnapshot.State batteryOptimization(Context context, String pkg) {
        try {
            if (pkg == null || pkg.length() == 0) return PermissionSnapshot.State.UNKNOWN;
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return PermissionSnapshot.State.UNKNOWN;
            return state(pm.isIgnoringBatteryOptimizations(pkg));
        } catch (Throwable t) {
            return PermissionSnapshot.State.UNKNOWN;
        }
    }

    /** usage access 没有普通权限 API，只能查 AppOps。 */
    private static PermissionSnapshot.State usageAccess(Context context, String pkg) {
        try {
            if (pkg == null || pkg.length() == 0) return PermissionSnapshot.State.UNKNOWN;
            AppOpsManager ops = (AppOpsManager)
                    context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return PermissionSnapshot.State.UNKNOWN;
            int uid = Process.myUid();
            int mode;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = ops.unsafeCheckOpNoThrow(OP_GET_USAGE_STATS, uid, pkg);
            } else {
                mode = ops.checkOpNoThrow(OP_GET_USAGE_STATS, uid, pkg);
            }
            return mode == AppOpsManager.MODE_ALLOWED
                    ? PermissionSnapshot.State.GRANTED
                    : PermissionSnapshot.State.DENIED;
        } catch (Throwable t) {
            return PermissionSnapshot.State.UNKNOWN;
        }
    }

    /** 通知监听：ENABLED_NOTIFICATION_LISTENERS 中包含本包才算出授权。 */
    private static PermissionSnapshot.State notificationListener(Context context, String pkg) {
        try {
            if (pkg == null || pkg.length() == 0) return PermissionSnapshot.State.UNKNOWN;
            // Settings.Secure.ENABLED_NOTIFICATION_LISTENERS 是 @hide 常量，只能用字面量。
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    "enabled_notification_listeners");
            if (enabled == null || enabled.length() == 0) {
                return PermissionSnapshot.State.DENIED;
            }
            String[] parts = enabled.split(":");
            for (String part : parts) {
                if (part == null || part.length() == 0) continue;
                int slash = part.indexOf('/');
                String owner = slash >= 0 ? part.substring(0, slash) : part;
                if (pkg.equals(owner)) return PermissionSnapshot.State.GRANTED;
            }
            return PermissionSnapshot.State.DENIED;
        } catch (Throwable t) {
            return PermissionSnapshot.State.UNKNOWN;
        }
    }

    private static PermissionSnapshot.State state(boolean granted) {
        return granted ? PermissionSnapshot.State.GRANTED : PermissionSnapshot.State.DENIED;
    }
}
