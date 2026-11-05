package plus.xiaoi.module;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * 小米超级岛（焦点通知）单次提示。
 *
 * 逆向结论（8.2.65.4714）：
 *  - 超级岛走焦点通知：channel = "super_island"（importance 4）
 *  - 参数放在 extras["miui.focus.param"]，结构 {"param_v2":{...}}
 *  - 图标放 extras["miui.focus.pics"]
 * 本类只做一件事：用第三方模型时，弹一次"当前模型"提示，然后自动消失。
 */
public final class Island {

    private static final String CHANNEL = "super_island";
    private static final String CHANNEL_NAME = "小爱+ 超级岛";
    private static final int NOTIF_ID = 0x58414f49; // "XAOI"
    private static final long DURATION_MS = 6000L;

    private static volatile boolean sShown;

    private Island() {}

    /** 单次提示：每个进程只弹一次。返回是否成功发出。 */
    public static boolean showOnce(String model) {
        return show(model, false);
    }

    public static synchronized boolean show(String model, boolean force) {
        if (sShown && !force) return false;
        sShown = true;
        try {
            Context ctx = appContext();
            if (ctx == null) {
                Logs.e("超级岛：拿不到 Context");
                return false;
            }
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                Logs.e("超级岛：NotificationManager 不可用");
                return false;
            }
            if (!nm.areNotificationsEnabled()) {
                Logs.e("超级岛：应用通知被关闭，跳过");
                return false;
            }
            ensureChannel(nm, ctx);

            String text = "第三方模型 " + (model == null || model.length() == 0 ? "已启用" : model);
            Logs.i("超级岛诊断: " + diag(ctx));
            Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(ctx.getApplicationInfo().icon)
                    .setContentTitle("小爱+")
                    .setContentText(text)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true);
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString("miui.focus.param", paramJson(text, model, ctx));
            extras.putBundle("miui.focus.pics", pics(ctx));
            b.addExtras(extras);
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
            Notification n = b.build();
            nm.notify(NOTIF_ID, n);
            Logs.i("超级岛 extras: focusParam=" + extras.getString("miui.focus.param").length()
                    + "B pics=" + extras.getBundle("miui.focus.pics").size());

            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        NotificationManager m = (NotificationManager) appContext()
                                .getSystemService(Context.NOTIFICATION_SERVICE);
                        if (m != null) m.cancel(NOTIF_ID);
                    } catch (Throwable ignored) {
                    }
                }
            }, DURATION_MS);

            Logs.i("超级岛提示已发出: " + text);
            return true;
        } catch (Throwable t) {
            Logs.e("超级岛提示失败（已忽略）: " + t);
            return false;
        }
    }

    /** 在应用进程内才能问 systemui：是否支持岛 / 协议版本 / 本应用能否显示焦点通知。 */
    private static String diag(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("getBoolean", String.class, boolean.class)
                    .invoke(null, "persist.sys.feature.island", false);
            sb.append("island=").append(v);
        } catch (Throwable t) {
            sb.append("island=?");
        }
        try {
            int p = android.provider.Settings.System.getInt(
                    ctx.getContentResolver(), "notification_focus_protocol", 0);
            sb.append(" protocol=").append(p);
        } catch (Throwable t) {
            sb.append(" protocol=?");
        }
        try {
            android.os.Bundle b = new android.os.Bundle();
            b.putString("package", ctx.getPackageName());
            android.os.Bundle r = ctx.getContentResolver().call(
                    android.net.Uri.parse("content://miui.statusbar.notification.public"),
                    "canShowFocus", null, b);
            sb.append(" canShowFocus=").append(r != null && r.getBoolean("canShowFocus", false));
        } catch (Throwable t) {
            sb.append(" canShowFocus=err(").append(t.getClass().getSimpleName()).append(")");
        }
        return sb.toString();
    }

    private static void ensureChannel(NotificationManager nm, Context ctx) {
        try {
            if (nm.getNotificationChannel(CHANNEL) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL, CHANNEL_NAME, 4);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            Logs.i("超级岛：已创建 channel " + CHANNEL);
        } catch (Throwable t) {
            Logs.e("超级岛：建 channel 失败 " + t);
        }
    }

    /** 岛右侧空间有限：只留主型号。 */
    private static String shortModel(String model) {
        if (model == null || model.length() == 0) return "第三方模型";
        String m = model;
        int dash = m.indexOf("-1017");
        if (dash > 0) m = m.substring(0, dash);
        return m.length() > 22 ? m.substring(0, 22) : m;
    }

    private static String paramJson(String text, String model, Context ctx) {
        try {
            String icon = "miui.focus.pic_app_icon_small";
            String appIcon = "miui.focus.pic_app_icon";

            // iconTextInfo：锁屏/胶囊上的标题+内容（gp1.c.getFocusNotificationTemplateJson）
            JSONObject anim = new JSONObject();
            anim.put("type", 0);
            anim.put("src", appIcon);
            JSONObject info = new JSONObject();
            info.put("title", "小爱+");
            info.put("colorTitle", "#000000");
            info.put("colorTitleDark", "#FFFFFF");
            info.put("content", text);
            info.put("animIconInfo", anim);

            // 大字岛：左侧只放图标，右侧放模型名——避开中间摄像头挖孔
            JSONObject leftPic = new JSONObject();
            leftPic.put("type", 1);
            leftPic.put("pic", appIcon);
            JSONObject left = new JSONObject();
            left.put("type", 1);
            left.put("picInfo", leftPic);

            JSONObject rightPic = new JSONObject();
            rightPic.put("type", 1);
            rightPic.put("pic", "miui.focus.pic_ticker");
            JSONObject rightText = new JSONObject();
            rightText.put("title", shortModel(model));
            rightText.put("showHighlightColor", true);
            JSONObject right = new JSONObject();
            right.put("type", 2);
            right.put("picInfo", rightPic);
            right.put("textInfo", rightText);

            JSONObject big = new JSONObject();
            big.put("imageTextInfoLeft", left);
            big.put("imageTextInfoRight", right);

            // 小字岛：只有图标
            JSONObject smallPic = new JSONObject();
            smallPic.put("type", 1);
            smallPic.put("pic", icon);
            JSONObject small = new JSONObject();
            small.put("picInfo", smallPic);

            JSONObject island = new JSONObject();
            island.put("islandProperty", 1);
            island.put("bigIslandArea", big);
            island.put("smallIslandArea", small);

            JSONObject v2 = new JSONObject();
            v2.put("protocol", 1);
            v2.put("updatable", true);
            v2.put("islandFirstFloat", false);
            v2.put("business", "RealTimeStandby");
            v2.put("enableFloat", false);
            v2.put("aodTitle", text);
            v2.put("aodPic", icon);
            v2.put("iconTextInfo", info);
            v2.put("reopen", "reopen");
            v2.put("actions", new org.json.JSONArray());
            v2.put("outEffectSrc", "outer_glow");
            v2.put("param_island", island);

            JSONObject root = new JSONObject();
            root.put("param_v2", v2);
            return root.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    private static android.os.Bundle pics(Context ctx) {
        android.os.Bundle b = new android.os.Bundle();
        try {
            android.graphics.drawable.Icon icon = android.graphics.drawable.Icon.createWithResource(
                    ctx, ctx.getApplicationInfo().icon);
            b.putParcelable("miui.focus.pic_app_icon_small", icon);
            b.putParcelable("miui.focus.pic_app_icon", icon);
        } catch (Throwable ignored) {
        }
        return b;
    }

    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
