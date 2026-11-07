package plus.xiaoi.module;

import java.util.Calendar;
import java.util.TimeZone;

import plus.xiaoi.module.agent.AlarmTimes;

/**
 * alarm.set 的触发时间计算（纯 JVM）。
 *
 * <p>固定使用 GMT+8，避免测试机时区/夏令时影响；覆盖当天、跨天、月末、
 * 秒级边界与非法参数。</p>
 */
public final class AlarmTimesTest {

    private AlarmTimesTest() {}

    private static final TimeZone TZ = TimeZone.getTimeZone("GMT+8");

    public static int run() {
        sameDay();
        crossDay();
        leadWindow();
        boundary();
        sameMinute();
        invalid();
        return T.report("AlarmTimesTest");
    }

    private static long at(int year, int month, int day, int hour, int minute, int second) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(year, month - 1, day, hour, minute, second);
        return c.getTimeInMillis();
    }

    private static void sameDay() {
        long now = at(2026, 11, 6, 6, 0, 0);
        long trigger = AlarmTimes.nextTriggerAt(7, 0, now, TZ, 5000L);
        T.eq("当天 07:00 触发", at(2026, 11, 6, 7, 0, 0), trigger);
        T.ok("触发时间在 now 之后", trigger > now);
        T.eq("触发秒归零", 0, secondOf(trigger));
    }

    private static void crossDay() {
        long now = at(2026, 11, 6, 8, 0, 0);
        T.eq("已过 07:00 -> 次日", at(2026, 11, 7, 7, 0, 0),
                AlarmTimes.nextTriggerAt(7, 0, now, TZ, 5000L));

        long monthEnd = at(2026, 11, 30, 23, 0, 0);
        T.eq("月末跨到下月", at(2026, 12, 1, 6, 30, 0),
                AlarmTimes.nextTriggerAt(6, 30, monthEnd, TZ, 5000L));

        long yearEnd = at(2026, 12, 31, 23, 0, 0);
        T.eq("年末跨到次年", at(2027, 1, 1, 6, 30, 0),
                AlarmTimes.nextTriggerAt(6, 30, yearEnd, TZ, 5000L));

        long leap = at(2028, 2, 29, 12, 0, 0);
        T.eq("闰日次日", at(2028, 3, 1, 7, 0, 0),
                AlarmTimes.nextTriggerAt(7, 0, leap, TZ, 5000L));
    }

    private static void leadWindow() {
        long now = at(2026, 11, 6, 6, 59, 59);
        T.eq("距触发 1 秒且提前量 5 秒 -> 次日", at(2026, 11, 7, 7, 0, 0),
                AlarmTimes.nextTriggerAt(7, 0, now, TZ, 5000L));
        T.eq("无提前量时 1 秒后仍算当天", at(2026, 11, 6, 7, 0, 0),
                AlarmTimes.nextTriggerAt(7, 0, now, TZ, 0L));
        T.eq("负提前量按 0 处理", at(2026, 11, 6, 7, 0, 0),
                AlarmTimes.nextTriggerAt(7, 0, now, TZ, -1000L));
    }

    private static void boundary() {
        T.eq("00:00 从 23:59:30 起算 -> 次日", at(2026, 11, 7, 0, 0, 0),
                AlarmTimes.nextTriggerAt(0, 0, at(2026, 11, 6, 23, 59, 30), TZ, 5000L));
        T.eq("23:59 从 23:58:30 起算 -> 当天", at(2026, 11, 6, 23, 59, 0),
                AlarmTimes.nextTriggerAt(23, 59, at(2026, 11, 6, 23, 58, 30), TZ, 5000L));
        T.eq("恰好等于 now 且有提前量 -> 次日", at(2026, 11, 7, 7, 0, 0),
                AlarmTimes.nextTriggerAt(7, 0, at(2026, 11, 6, 7, 0, 0), TZ, 1000L));
    }

    private static void sameMinute() {
        long a = at(2026, 11, 6, 7, 0, 10);
        long b = at(2026, 11, 6, 7, 0, 50);
        T.ok("同分钟不同秒", AlarmTimes.sameMinute(a, b, TZ));
        T.ok("不同分钟", !AlarmTimes.sameMinute(a, at(2026, 11, 6, 7, 1, 0), TZ));
        T.ok("不同日期同时间", !AlarmTimes.sameMinute(a, at(2026, 11, 7, 7, 0, 0), TZ));
        T.ok("0 不匹配", !AlarmTimes.sameMinute(0L, b, TZ));
    }

    private static void invalid() {
        long now = at(2026, 11, 6, 6, 0, 0);
        T.ok("hour 24 抛错", throwsIae(24, 0, now));
        T.ok("hour -1 抛错", throwsIae(-1, 0, now));
        T.ok("minute 60 抛错", throwsIae(7, 60, now));
        T.ok("minute -1 抛错", throwsIae(7, -1, now));
    }

    private static boolean throwsIae(int hour, int minute, long now) {
        try {
            AlarmTimes.nextTriggerAt(hour, minute, now, TZ, 0L);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static int secondOf(long millis) {
        Calendar c = Calendar.getInstance(TZ);
        c.setTimeInMillis(millis);
        return c.get(Calendar.SECOND);
    }
}
