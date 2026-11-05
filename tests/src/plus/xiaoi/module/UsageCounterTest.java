package plus.xiaoi.module;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/** 调用量统计（纯 JVM）测试：分类判据、行编码、按天聚合、超期清理。 */
public final class UsageCounterTest {

    private UsageCounterTest() {}

    public static int run() {
        // ---------------------------------------------------------- 主对话判定
        T.ok("无工具、两条消息：不算主对话", !UsageCounter.mainCall(2, 0));
        T.ok("无工具、六条消息：算主对话", UsageCounter.mainCall(6, 0));
        T.ok("有工具、单条消息：算主对话", UsageCounter.mainCall(1, 3));
        T.ok("无消息无工具：不算", !UsageCounter.mainCall(0, 0));
        T.ok("工具为空数组：不算", !UsageCounter.mainCall(2, 0));

        // ---------------------------------------------------------- 行编码
        long now = baseTime();
        String line = UsageCounter.encode(now, true, true, "your-model");
        String[] f = line.split("\\|", 5);
        T.eq("行字段数", 5, f.length);
        T.eq("行日期", UsageCounter.day(now), f[0]);
        T.eq("行类型=三方", "T", f[1]);
        T.eq("行类型=主对话", "M", f[2]);
        T.eq("行模型", "your-model", f[3]);
        T.eq("官方子调用编码", "O|S", UsageCounter.encode(now, false, false, "m")
                .split("\\|", 5)[1] + "|" + UsageCounter.encode(now, false, false, "m")
                .split("\\|", 5)[2]);
        String sanitized = UsageCounter.encode(now, true, true, "a|b\nc");
        T.eq("模型名里的分隔符被清洗", "a/b c", sanitized.split("\\|", 5)[3]);
        T.ok("超长模型名被截断", UsageCounter.encode(now, true, true, repeat("x", 200))
                .split("\\|", 5)[3].length() <= 48);

        // ---------------------------------------------------------- 按天聚合
        List<String> lines = new ArrayList<String>();
        lines.add(UsageCounter.encode(now, true, true, "glm"));          // 今天 三方 主
        lines.add(UsageCounter.encode(now, true, true, "glm"));          // 今天 三方 主
        lines.add(UsageCounter.encode(now, false, true, "official"));    // 今天 官方 主
        lines.add(UsageCounter.encode(now, true, false, "glm"));         // 今天 三方 子
        lines.add(UsageCounter.encode(daysAgo(now, 1), false, true, "official")); // 昨天 官方 主
        lines.add(UsageCounter.encode(daysAgo(now, 8), true, true, "glm"));       // 窗口外
        lines.add("garbage line");                                        // 非法行

        Map<?, ?> r = (Map<?, ?>) Json.read(UsageCounter.stats(lines, now, 7));
        T.eq("days 回显", Long.valueOf(7), r.get("days"));
        T.eq("generatedAt 回显", now, r.get("generatedAt"));

        Map<?, ?> today = (Map<?, ?>) r.get("today");
        T.eq("今日日期", UsageCounter.day(now), today.get("day"));
        T.eq("今日三方主调用", Long.valueOf(2), today.get("third"));
        T.eq("今日官方主调用", Long.valueOf(1), today.get("official"));

        Map<?, ?> total = (Map<?, ?>) r.get("total");
        T.eq("窗口内三方主调用", Long.valueOf(2), total.get("third"));
        T.eq("窗口内官方主调用", Long.valueOf(2), total.get("official"));

        Map<?, ?> sub = (Map<?, ?>) r.get("sub");
        T.eq("子调用单独计（三方）", Long.valueOf(1), sub.get("third"));
        T.eq("子调用不入总量", Long.valueOf(0), sub.get("official"));

        List<?> series = (List<?>) r.get("series");
        T.eq("series 天数", 7, series.size());
        Map<?, ?> last = (Map<?, ?>) series.get(6);
        T.eq("series 最后一天是今天", UsageCounter.day(now), last.get("day"));
        T.eq("series 今天三方", Long.valueOf(2), last.get("third"));
        Map<?, ?> first = (Map<?, ?>) series.get(0);
        T.eq("series 稀疏日补零", Long.valueOf(0), first.get("third"));

        List<?> models = (List<?>) r.get("models");
        T.eq("模型种类数", 2, models.size());
        Map<?, ?> m0 = (Map<?, ?>) models.get(0);
        T.eq("模型统计", "glm", m0.get("model"));
        T.eq("模型三方计数", Long.valueOf(2), m0.get("third"));

        // 空输入不炸
        Map<?, ?> empty = (Map<?, ?>) Json.read(
                UsageCounter.stats(new ArrayList<String>(), now, 1));
        T.eq("空账本 today.third", Long.valueOf(0),
                ((Map<?, ?>) empty.get("today")).get("third"));
        T.eq("空账本 series 长度", 1,
                ((List<?>) empty.get("series")).size());

        // ---------------------------------------------------------- 清理
        List<String> pruned = UsageCounter.prune(lines, now, 7);
        T.eq("清理后只剩窗口内有效行", 5, pruned.size());
        T.ok("清理丢弃非法行", !pruned.get(0).startsWith("garbage"));

        return T.report("UsageCounterTest");
    }

    /** 固定到某天中午，避免测试在午夜附近跨天抖动。 */
    private static long baseTime() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long daysAgo(long base, int n) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(base);
        c.add(Calendar.DAY_OF_YEAR, -n);
        return c.getTimeInMillis();
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
