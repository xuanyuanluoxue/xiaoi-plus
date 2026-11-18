package plus.xiaoi.module.agent;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 闹钟触发时间计算（纯 JVM）。
 *
 * <p>只回答“下一次 hour:minute 是什么时候”，不碰 Android AlarmManager，
 * 方便桌面单测覆盖跨天、跨月、闰年和最小提前量。时区由调用方传入，
 * 生产代码使用设备默认时区。</p>
 */
public final class AlarmTimes {

    private AlarmTimes() {}

    /**
     * 下一次 {@code hour:minute} 的时间戳（epoch millis）。
     *
     * <p>如果今天的时间点已经过去，或距离 {@code nowMillis} 不足
     * {@code minLeadMs}，顺延到明天。秒和毫秒归零。</p>
     */
    public static long nextTriggerAt(int hour, int minute, long nowMillis,
                                     TimeZone tz, long minLeadMs) {
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("hour 需在 0~23");
        }
        if (minute < 0 || minute > 59) {
            throw new IllegalArgumentException("minute 需在 0~59");
        }
        Calendar c = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        c.setTimeInMillis(nowMillis);
        // 先归零秒/毫秒再设时分，避免 23:59:59 落到 23:59 之后的边界抖动。
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        long trigger = c.getTimeInMillis();
        long lead = minLeadMs > 0 ? minLeadMs : 0;
        if (trigger - nowMillis < lead) {
            c.add(Calendar.DAY_OF_YEAR, 1);
            trigger = c.getTimeInMillis();
        }
        return trigger;
    }

    /** 两个时间戳是否落在同一个“分钟”（用于核对时钟 App 是否接受请求）。 */
    public static boolean sameMinute(long a, long b, TimeZone tz) {
        if (a <= 0 || b <= 0) return false;
        Calendar ca = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        Calendar cb = Calendar.getInstance(tz == null ? TimeZone.getDefault() : tz);
        ca.setTimeInMillis(a);
        cb.setTimeInMillis(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
                && ca.get(Calendar.MONTH) == cb.get(Calendar.MONTH)
                && ca.get(Calendar.DAY_OF_MONTH) == cb.get(Calendar.DAY_OF_MONTH)
                && ca.get(Calendar.HOUR_OF_DAY) == cb.get(Calendar.HOUR_OF_DAY)
                && ca.get(Calendar.MINUTE) == cb.get(Calendar.MINUTE);
    }
}
