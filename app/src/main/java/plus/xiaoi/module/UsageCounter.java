package plus.xiaoi.module;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 调用量统计的纯 JVM 层（无 android 依赖，桌面单测可直接跑）。
 *
 * 数据模型是一条调用一行、追加写入的文本日志（Android 侧由 {@link Usage} 落盘）：
 * <pre>  2026-11-04|T|M|your-model|1762312345678</pre>
 * 字段：日期(yyyy-MM-dd，本地时区) | T=三方 O=官方 | M=主对话 S=子调用 | 模型名 | 毫秒时间戳。
 *
 * 为什么用追加日志而不是计数器对象：小爱有多个进程（主进程 / :core / :voice_trigger），
 * 每个进程都可能发起模型请求。单行 O_APPEND 写入在 Linux 上是原子的，多进程并发追加
 * 不会互相覆盖；读取时按日期聚合即可拿到全进程总量。
 *
 * 只把「主对话」算使用量（含 tools 或消息数 ≥6），记忆抽取/意图分类这类子调用
 * 单独记在 sub 里——否则一次闲聊会被算成三五次。
 */
public final class UsageCounter {

    /** 落盘保留天数：超期行在写入侧清理。 */
    public static final int KEEP_DAYS = 7;
    /** 主对话判定：消息（role）条数达到该值。 */
    public static final int MAIN_CALL_MESSAGES = 6;

    private static final String SEP = "|";
    /** 模型名长度上限，防止异常长的字符串撑大日志。 */
    private static final int MODEL_MAX = 48;

    private UsageCounter() {}

    /** 本地时区的日期键（yyyy-MM-dd）。 */
    public static String day(long timeMillis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return f.format(new Date(timeMillis));
    }

    /** 生成一行记录；模型名中的分隔符与换行会被替换，保证可解析。 */
    public static String encode(long timeMillis, boolean third, boolean mainCall, String model) {
        String m = model == null ? "" : model.trim();
        if (m.length() > MODEL_MAX) m = m.substring(0, MODEL_MAX);
        m = m.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
        return day(timeMillis) + SEP
                + (third ? "T" : "O") + SEP
                + (mainCall ? "M" : "S") + SEP
                + m + SEP
                + timeMillis;
    }

    /**
     * 判断一次模型调用是不是「主对话」。
     *
     * 判据（满足其一）：带工具定义（agent 主循环）；或消息条数 ≥6。
     * 记忆抽取、意图分类、标题生成这类子调用消息少且不带 tools，单独计。
     */
    public static boolean mainCall(int messages, int tools) {
        return tools > 0 || messages >= MAIN_CALL_MESSAGES;
    }

    /**
     * 聚合统计（纯函数，便于单测）。
     *
     * @param lines     已落盘的记录行（可含超期行，本方法只统计窗口内）
     * @param nowMillis 当前时间（测试可注入）
     * @param days      窗口天数 1~90
     * @return JSON：days / generatedAt / today / total / sub / series / models
     */
    public static String stats(List<String> lines, long nowMillis, int days) {
        if (days < 1) days = 1;
        if (days > 90) days = 90;

        List<String> keys = new ArrayList<String>();
        Set<String> window = new LinkedHashSet<String>();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMillis);
        for (int i = days - 1; i >= 0; i--) {
            String k = day(cal.getTimeInMillis());
            keys.add(k);
            window.add(k);
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        // keys 目前是「今天往前」，反转为时间正序
        java.util.Collections.reverse(keys);

        Map<String, int[]> perDay = new LinkedHashMap<String, int[]>();
        for (String k : keys) perDay.put(k, new int[]{0, 0});
        Map<String, int[]> perModel = new LinkedHashMap<String, int[]>();

        int third = 0, official = 0, thirdSub = 0, officialSub = 0;
        if (lines != null) {
            for (String line : lines) {
                String[] f = parse(line);
                if (f == null || !window.contains(f[0])) continue;
                boolean isThird = "T".equals(f[1]);
                boolean isMain = "M".equals(f[2]);
                if (!isMain) {
                    if (isThird) thirdSub++; else officialSub++;
                    continue;
                }
                if (isThird) third++; else official++;
                int[] d = perDay.get(f[0]);
                if (d != null) {
                    if (isThird) d[0]++; else d[1]++;
                }
                String model = f[3];
                if (model.length() == 0) model = "(unknown)";
                int[] m = perModel.get(model);
                if (m == null) {
                    m = new int[]{0, 0};
                    perModel.put(model, m);
                }
                if (isThird) m[0]++; else m[1]++;
            }
        }

        String todayKey = keys.get(keys.size() - 1);
        int[] td = perDay.get(todayKey);

        Map<String, Object> root = Json.obj();
        root.put("days", (long) days);
        root.put("generatedAt", nowMillis);

        Map<String, Object> today = Json.obj();
        today.put("day", todayKey);
        today.put("third", (long) (td == null ? 0 : td[0]));
        today.put("official", (long) (td == null ? 0 : td[1]));
        root.put("today", today);

        Map<String, Object> total = Json.obj();
        total.put("third", (long) third);
        total.put("official", (long) official);
        root.put("total", total);

        Map<String, Object> sub = Json.obj();
        sub.put("third", (long) thirdSub);
        sub.put("official", (long) officialSub);
        root.put("sub", sub);

        List<Object> series = Json.arr();
        for (String k : keys) {
            int[] d = perDay.get(k);
            Map<String, Object> item = Json.obj();
            item.put("day", k);
            item.put("third", (long) (d == null ? 0 : d[0]));
            item.put("official", (long) (d == null ? 0 : d[1]));
            series.add(item);
        }
        root.put("series", series);

        List<Object> models = Json.arr();
        for (Map.Entry<String, int[]> e : perModel.entrySet()) {
            Map<String, Object> item = Json.obj();
            item.put("model", e.getKey());
            item.put("third", (long) e.getValue()[0]);
            item.put("official", (long) e.getValue()[1]);
            models.add(item);
        }
        root.put("models", models);

        return Json.write(root);
    }

    /** 只保留最近 keepDays 天（含今天）的记录行；解析失败的行直接丢弃。 */
    public static List<String> prune(List<String> lines, long nowMillis, int keepDays) {
        List<String> out = new ArrayList<String>();
        if (lines == null) return out;
        if (keepDays < 1) keepDays = 1;
        Set<String> keep = new LinkedHashSet<String>();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMillis);
        for (int i = 0; i < keepDays; i++) {
            keep.add(day(cal.getTimeInMillis()));
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        for (String line : lines) {
            String[] f = parse(line);
            if (f != null && keep.contains(f[0])) out.add(f[0] + SEP + f[1] + SEP + f[2] + SEP
                    + f[3] + SEP + f[4]);
        }
        return out;
    }

    // ------------------------------------------------------------- 内部工具

    /** 解析一行；非法行返回 null。字段：day|kind|main|model|ts */
    static String[] parse(String line) {
        if (line == null) return null;
        String[] f = line.trim().split("\\|", 5);
        if (f.length < 5) return null;
        if (f[0].length() != 10) return null;
        if (!"T".equals(f[1]) && !"O".equals(f[1])) return null;
        if (!"M".equals(f[2]) && !"S".equals(f[2])) return null;
        try {
            Long.parseLong(f[4]);
        } catch (Throwable t) {
            return null;
        }
        return f;
    }

}
