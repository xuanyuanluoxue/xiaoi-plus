package plus.xiaoi.module.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

import plus.xiaoi.module.HomeActivity;
import plus.xiaoi.module.Logs;
import plus.xiaoi.module.R;

/**
 * App 内闹钟（AlarmManager）到点后的提醒。
 *
 * <p>只在 {@code alarm.set mode=app}（或 auto 回退）时注册。收到广播后发一条
 * 高优先级、CATEGORY_ALARM 的通知，并尽力申请全屏提醒；系统不给全屏权限时
 * 至少保留通知和闹钟铃声。不做持久化、不做重试。</p>
 */
public final class AgentAlarmReceiver extends BroadcastReceiver {

    static final String ACTION_FIRE = "plus.xiaoi.module.agent.ALARM_FIRE";

    /** 接收 {@link android.app.AlarmManager} 的 PendingIntent；显式组件，不导出。 */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        try {
            String label = intent.getStringExtra("label");
            if (label == null || label.trim().length() == 0) label = "闹钟";
            int id = intent.getIntExtra("notificationId", 0x414C0001);

            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            ensureChannel(nm);

            Notification.Builder b = new Notification.Builder(context, AgentAlarmReceiver.CHANNEL_ALARM)
                    .setSmallIcon(R.drawable.ic_xiaoi)
                    .setContentTitle(label)
                    .setContentText("到点了")
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis())
                    .setVisibility(Notification.VISIBILITY_PUBLIC);
            try {
                Intent open = new Intent(context, HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent show = PendingIntent.getActivity(context, id, open,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
                b.setContentIntent(show);
                // 全屏提醒依赖 USE_FULL_SCREEN_INTENT；没有权限时系统会忽略，
                // 这里不因此失败，通知本身仍然会响。
                if (Build.VERSION.SDK_INT >= 21) {
                    b.setFullScreenIntent(show, true);
                }
            } catch (Throwable ignored) {
                // 通知仍然可以发出。
            }

            nm.notify("agent_alarm", id, b.build());
            Logs.i("Agent 闹钟已触发：" + label);
        } catch (Throwable t) {
            Logs.e("Agent 闹钟提醒失败（已忽略）: " + t.getClass().getSimpleName());
        }
    }

    static final String CHANNEL_ALARM = "agent_alarm";
    static final String CHANNEL_ALARM_NAME = "Agent 闹钟";

    /** 闹钟渠道：高优先级 + 闹钟铃声；只在本 App 闹钟触发时创建。 */
    static void ensureChannel(NotificationManager nm) {
        try {
            if (nm.getNotificationChannel(CHANNEL_ALARM) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ALARM,
                    CHANNEL_ALARM_NAME, NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Agent 设置的 App 内闹钟");
            ch.enableVibration(true);
            try {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs);
            } catch (Throwable ignored) {
                // 没有闹钟铃声时退回渠道默认提示音。
            }
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
            // notify 会自己失败；上层不需要因此中断。
        }
    }
}
