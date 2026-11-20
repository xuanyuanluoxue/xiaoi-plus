package plus.xiaoi.module.application.log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import plus.xiaoi.module.Json;

/**
 * 日志导出（纯 JVM，无 I/O）：把合并视图 JSON 按条件筛选，产出 meta/文本/JSON 三段文本。
 *
 * <p>为什么在应用层：筛选规则要能单测，而写文件（ZIP/SAF）属于 Android 侧。这里只做
 * 「给定 JSON + 条件 → 三段字符串」，调用方负责落盘。</p>
 *
 * <p>筛选维度都是在排障时真正会用到的：级别（只看错误）、进程（只看 :core）、
 * 时间窗（只看刚才那一次）、关键字（只看某个 tool 或某个模型名）。</p>
 */
public final class LogExport {

    /** 单次导出上限：再多也不适合贴到 issue 里。 */
    public static final int MAX_LINES = 2000;

    private LogExport() {}

    /** 最低级别阈值：与 {@link LogExport#rank} 同尺度（0=全部 2=警告 3=错误）。 */
    public static final class Filter {
        /** 0=全部；2=警告及以上；3=仅错误。 */
        public int minSeverity;
        /** 来源进程名；空串=全部。 */
        public String proc = "";
        /** 关键字，大小写不敏感，匹配 tag 与正文；空=不限。 */
        public String keyword = "";
        /** 起始时间（epoch 毫秒）；0=不限。 */
        public long sinceTs;
        /** 尾部最多导出多少条；<=0 用 {@link #MAX_LINES}。 */
        public int limit = 500;
        /** 仅用于 meta 描述，例如「最近 30 分钟」；不参与过滤。 */
        public String rangeLabel = "";
    }

    /** 导出结果：三段文本 + 统计。 */
    public static final class Result {
        /** 实际导出条数。 */
        public final int count;
        /** 筛选前的总条数。 */
        public final int total;
        /** merged / local。 */
        public final String scope;
        public final String meta;
        public final String text;
        public final String json;

        Result(int count, int total, String scope, String meta, String text, String json) {
            this.count = count;
            this.total = total;
            this.scope = scope;
            this.meta = meta;
            this.text = text;
            this.json = json;
        }
    }

    /** 级别权重：D < I < W < E。未知级别按 I 处理，避免被静默丢掉。 */
    public static int rank(String level) {
        if ("E".equals(level)) return 3;
        if ("W".equals(level)) return 2;
        if ("D".equals(level)) return 0;
        return 1;
    }

    /**
     * 构建导出内容。
     *
     * @param dumpJson    {@code xiaoai_logs} / {@code ControlLogStore.dumpJson} 的输出
     * @param filter      筛选条件
     * @param headerLines 调用方补充的环境信息（版本、机型等），最多几行
     */
    public static Result build(String dumpJson, Filter filter, List<String> headerLines) {
        Filter f = filter == null ? new Filter() : filter;
        int limit = f.limit <= 0 ? MAX_LINES : Math.min(f.limit, MAX_LINES);

        List<Map<?, ?>> all = new ArrayList<Map<?, ?>>();
        String scope = "local";
        try {
            Object root = Json.read(dumpJson == null ? "" : dumpJson);
            if (root instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) root;
                scope = Json.optString(m, "scope", "local");
                Object lines = m.get("lines");
                if (lines instanceof List) {
                    for (Object v : (List<?>) lines) {
                        if (v instanceof Map) all.add((Map<?, ?>) v);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        List<Map<?, ?>> kept = new ArrayList<Map<?, ?>>();
        String kw = f.keyword == null ? "" : f.keyword.trim().toLowerCase(Locale.US);
        for (Map<?, ?> line : all) {
            String level = Json.optString(line, "level", "I");
            if (rank(level) < f.minSeverity) continue;
            if (f.proc != null && f.proc.length() > 0
                    && !f.proc.equals(Json.optString(line, "proc", ""))) continue;
            if (f.sinceTs > 0 && Json.optLong(line, "ts", 0L) < f.sinceTs) continue;
            if (kw.length() > 0) {
                String msg = Json.optString(line, "msg", "");
                String tag = Json.optString(line, "tag", "");
                if (!msg.toLowerCase(Locale.US).contains(kw)
                        && !tag.toLowerCase(Locale.US).contains(kw)) continue;
            }
            kept.add(line);
        }
        if (kept.size() > limit) {
            kept = new ArrayList<Map<?, ?>>(kept.subList(kept.size() - limit, kept.size()));
        }

        SimpleDateFormat full = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
        SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder text = new StringBuilder();
        for (Map<?, ?> line : kept) {
            if (text.length() > 0) text.append('\n');
            long ts = Json.optLong(line, "ts", 0L);
            String time = ts > 0 ? full.format(new Date(ts))
                    : Json.optString(line, "t", "");
            text.append(time).append(' ')
                    .append(Json.optString(line, "level", "I")).append(' ')
                    .append(sourceOf(line)).append(' ')
                    .append(Json.optString(line, "msg", ""));
        }

        StringBuilder meta = new StringBuilder();
        meta.append("# xiaoi-plus 运行日志\n");
        meta.append("# 导出时间: ").append(stamp.format(new Date())).append('\n');
        meta.append("# 数据来源: ").append(scope).append(scopeLine(scope)).append('\n');
        meta.append("# 条数: ").append(kept.size()).append(" / ").append(all.size()).append('\n');
        meta.append("# 筛选: ").append(describe(f)).append('\n');
        if (headerLines != null) {
            for (String h : headerLines) {
                if (h == null || h.length() == 0) continue;
                meta.append("# ").append(h).append('\n');
            }
        }
        meta.append("# 说明: apiKey/token 已脱敏为 ***；日志仍可能含设备信息与会话摘要片段，分享前请留意\n");

        List<Object> arr = Json.arr();
        arr.addAll(kept);
        Map<String, Object> out = Json.obj();
        out.put("count", arr.size());
        out.put("total", all.size());
        out.put("scope", scope);
        Map<String, Object> desc = Json.obj();
        desc.put("minSeverity", f.minSeverity);
        desc.put("proc", f.proc == null ? "" : f.proc);
        desc.put("keyword", f.keyword == null ? "" : f.keyword);
        desc.put("sinceTs", f.sinceTs);
        desc.put("limit", limit);
        out.put("filters", desc);
        out.put("lines", arr);

        return new Result(kept.size(), all.size(), scope,
                meta.toString(), text.toString(), Json.write(out));
    }

    /** 合并视图里出现过的进程名（去重排序），供界面生成筛选 chips。 */
    public static List<String> procs(String dumpJson) {
        List<String> out = new ArrayList<String>();
        try {
            Object root = Json.read(dumpJson == null ? "" : dumpJson);
            if (root instanceof Map) {
                Object lines = ((Map<?, ?>) root).get("lines");
                if (lines instanceof List) {
                    for (Object v : (List<?>) lines) {
                        if (!(v instanceof Map)) continue;
                        String proc = Json.optString((Map<?, ?>) v, "proc", "");
                        if (proc.length() > 0 && !out.contains(proc)) out.add(proc);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** 一行描述筛选条件，写进 meta 与界面提示。 */
    public static String describe(Filter f) {
        if (f == null) return "全部";
        StringBuilder sb = new StringBuilder();
        sb.append(f.minSeverity >= 3 ? "仅错误"
                : f.minSeverity >= 2 ? "警告及以上" : "全部级别");
        sb.append(" · ").append(f.proc == null || f.proc.length() == 0
                ? "全部进程" : f.proc);
        sb.append(" · ").append(f.rangeLabel == null || f.rangeLabel.length() == 0
                ? "全部时间" : f.rangeLabel);
        if (f.keyword != null && f.keyword.trim().length() > 0) {
            sb.append(" · 关键字=").append(f.keyword.trim());
        }
        return sb.toString();
    }

    /** 文本行里的一列：`:core pid=962` / `pid=962`；未知来源返回 `-`。 */
    private static String sourceOf(Map<?, ?> line) {
        String proc = Json.optString(line, "proc", "");
        long pid = Json.optLong(line, "pid", 0L);
        String shortProc = proc;
        int colon = proc.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < proc.length()) shortProc = proc.substring(colon + 1);
        if (shortProc.length() == 0 && pid <= 0) return "-";
        if (shortProc.length() == 0) return "pid=" + pid;
        return pid > 0 ? shortProc + " pid=" + pid : shortProc;
    }

    private static String scopeLine(String scope) {
        return "merged".equals(scope) ? "（已汇总全部小爱进程）" : "（仅当前进程，控制面不可达）";
    }
}
