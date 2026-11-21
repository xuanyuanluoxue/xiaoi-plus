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

    /** 导出结果：三段文本 + 统计 + 诊断结论。 */
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
        /** 一句话结论（也写进 meta），例如「正常：已改写并走三方模型」。 */
        public final String verdict;

        Result(int count, int total, String scope, String meta, String text, String json,
               String verdict) {
            this.count = count;
            this.total = total;
            this.scope = scope;
            this.meta = meta;
            this.text = text;
            this.json = json;
            this.verdict = verdict;
        }
    }

    /**
     * 从整份日志（不受筛选影响）推出的运行结论。
     *
     * <p>为什么要有它：一份 ZIP 交给别人时，对方第一句话永远是「所以到底成没成功」。
     * 把这些判断放在导出侧，收日志的人不用再逐行 grep，也不用回问。</p>
     */
    public static final class Diagnosis {
        /** 出现过日志的进程数。 */
        public final int procs;
        /** 出现「配置 hook 完成」的 pid 数。 */
        public final int hookPids;
        /** 「改写命中」行数。 */
        public final int rewriteHits;
        /** 「模型调用: 三方」行数。 */
        public final int thirdCalls;
        /** 「模型调用: 官方」行数。 */
        public final int officialCalls;
        /** 是否出现「getter 从未被读取」告警。 */
        public final boolean getterIgnored;
        public final String verdict;

        Diagnosis(int procs, int hookPids, int rewriteHits, int thirdCalls,
                  int officialCalls, boolean getterIgnored, String verdict) {
            this.procs = procs;
            this.hookPids = hookPids;
            this.rewriteHits = rewriteHits;
            this.thirdCalls = thirdCalls;
            this.officialCalls = officialCalls;
            this.getterIgnored = getterIgnored;
            this.verdict = verdict;
        }
    }

    /**
     * 判定规则（顺序即优先级）：
     * <ol>
     *   <li>没有任何 hook 装载记录 → 模块没进来</li>
     *   <li>三方调用且改写命中 → 正常</li>
     *   <li>有官方调用但没有改写命中 → 装上了但不生效（版本不兼容）</li>
     *   <li>一次模型调用都没有 → 还没测（本地技能应答不产生调用）</li>
     * </ol>
     */
    public static Diagnosis diagnose(String dumpJson) {
        return diagnose(lines(dumpJson));
    }

    /** 判定规则同上；调用方已有解析结果时用它，避免二次解析。 */
    public static Diagnosis diagnose(List<Map<?, ?>> all) {
        java.util.Set<Integer> pids = new java.util.HashSet<Integer>();
        java.util.Set<Integer> hookPids = new java.util.HashSet<Integer>();
        int rewrite = 0;
        int third = 0;
        int official = 0;
        boolean ignored = false;
        for (Map<?, ?> line : all) {
            int pid = (int) Json.optLong(line, "pid", 0L);
            if (pid > 0) pids.add(pid);
            String msg = Json.optString(line, "msg", "");
            if (msg.contains("配置 hook 完成") && pid > 0) hookPids.add(pid);
            if (msg.contains("改写命中")) rewrite++;
            if (msg.contains("模型调用: 三方")) third++;
            if (msg.contains("模型调用: 官方")) official++;
            if (msg.contains("getter 从未被读取")) ignored = true;
        }
        String verdict;
        if (hookPids.isEmpty()) {
            verdict = "未装载：这段日志里没有 hook 装载记录，模块可能没启用或没生效";
        } else if (third > 0 && rewrite > 0) {
            verdict = "正常：已改写并走三方模型";
        } else if (official > 0 && rewrite == 0) {
            verdict = "异常：已启用但请求仍走官方模型，该小爱版本可能不兼容";
        } else if (third == 0 && official == 0) {
            verdict = "尚未测到模型调用：先说一句开放式问题，再立即导出";
        } else if (rewrite == 0) {
            verdict = "未判定：有调用但没有改写命中记录，请检查日志档位是否过低";
        } else {
            verdict = "部分生效：有改写命中但没有三方调用记录";
        }
        if (ignored) verdict = verdict + "（日志含「getter 从未被读取」告警）";
        return new Diagnosis(pids.size(), hookPids.size(), rewrite, third, official,
                ignored, verdict);
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

        List<Map<?, ?>> all = lines(dumpJson);
        String scope = scopeOf(dumpJson);
        Diagnosis diag = diagnose(all);

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
        meta.append("# 结论: ").append(diag.verdict).append('\n');
        meta.append("# 进程: ").append(diag.procs).append(" 个")
                .append("，hook 装载 ").append(diag.hookPids).append(" 个\n");
        meta.append("# 改写命中: ").append(diag.rewriteHits)
                .append("；模型调用: 三方 ").append(diag.thirdCalls)
                .append(" / 官方 ").append(diag.officialCalls).append('\n');
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
        Map<String, Object> dg = Json.obj();
        dg.put("verdict", diag.verdict);
        dg.put("procs", diag.procs);
        dg.put("hookPids", diag.hookPids);
        dg.put("rewriteHits", diag.rewriteHits);
        dg.put("thirdCalls", diag.thirdCalls);
        dg.put("officialCalls", diag.officialCalls);
        dg.put("getterIgnored", diag.getterIgnored);
        out.put("diagnosis", dg);
        out.put("lines", arr);

        return new Result(kept.size(), all.size(), scope,
                meta.toString(), text.toString(), Json.write(out), diag.verdict);
    }

    /** 解析导出 JSON 里的 lines；坏输入返回空表，绝不抛。 */
    private static List<Map<?, ?>> lines(String dumpJson) {
        List<Map<?, ?>> out = new ArrayList<Map<?, ?>>();
        try {
            Object root = Json.read(dumpJson == null ? "" : dumpJson);
            if (!(root instanceof Map)) return out;
            Object list = ((Map<?, ?>) root).get("lines");
            if (!(list instanceof List)) return out;
            for (Object v : (List<?>) list) {
                if (v instanceof Map) out.add((Map<?, ?>) v);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String scopeOf(String dumpJson) {
        try {
            Object root = Json.read(dumpJson == null ? "" : dumpJson);
            if (root instanceof Map) return Json.optString((Map<?, ?>) root, "scope", "local");
        } catch (Throwable ignored) {
        }
        return "local";
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
