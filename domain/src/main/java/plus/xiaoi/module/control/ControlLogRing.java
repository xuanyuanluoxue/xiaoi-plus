package plus.xiaoi.module.control;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import plus.xiaoi.module.Json;
import plus.xiaoi.module.core.Redact;

/**
 * 控制面日志环形缓冲（纯 JVM，无 android 依赖）：**合并视图**。
 *
 * <p>三个小爱进程各自把增量日志推到这里，App 侧给每条分配一个全局 {@code seq}，
 * 于是跨进程的 {@code since} 游标才有意义——这是各进程自己的 ring 做不到的。</p>
 *
 * <p>与进程内 ring 的分工：那边保留「本进程最近 300 条」，这边保留「所有进程最近
 * {@link #MAX_LINES} 条」。两边都做 FIFO，不做按级别保留（保留会让 {@code since}
 * 永远看不到被保留下来的旧行）。</p>
 *
 * <p>去重：按 {@code (pid, srcSeq)} 记录水位。来源进程重启后 srcSeq 从 1 重新开始，
 * 检测到回退就把水位重置并照常接收，不把重启后的日志当重复丢掉。</p>
 *
 * <p>不落盘是有意的：日志内容由小爱进程决定，落盘可能把密钥/对话正文写进 App 数据
 * 目录。展示前还会过一次 {@link Redact}。</p>
 */
public final class ControlLogRing {

    /** 合并后保留的总条数（三个进程共享）。 */
    public static final int MAX_LINES = 600;
    public static final int MAX_LINE_CHARS = 512;
    /** 合并视图对外的 scope 标记，便于调用方区分「合并」与「本进程」。 */
    public static final String SCOPE_MERGED = "merged";

    private static final Comparator<MergedLogEntry> BY_TIME =
            new Comparator<MergedLogEntry>() {
                @Override
                public int compare(MergedLogEntry a, MergedLogEntry b) {
                    // ts=0 是纯文本兜底行，没有可信时间，排在最后但保持相对次序。
                    long ta = a.ts <= 0 ? Long.MAX_VALUE : a.ts;
                    long tb = b.ts <= 0 ? Long.MAX_VALUE : b.ts;
                    if (ta != tb) return ta < tb ? -1 : 1;
                    if (a.seq != b.seq) return a.seq < b.seq ? -1 : 1;
                    return 0;
                }
            };

    private final ArrayDeque<MergedLogEntry> entries = new ArrayDeque<MergedLogEntry>();
    /** pid → 已接收的水位（最大 srcSeq + 该行时间），用于去重与重启检测。 */
    private final Map<Integer, Cursor> srcCursor = new HashMap<Integer, Cursor>();
    private long seq;

    private static final class Cursor {
        long srcSeq;
        long ts;

        Cursor(long srcSeq, long ts) {
            this.srcSeq = srcSeq;
            this.ts = ts;
        }
    }

    /**
     * 追加多行纯文本（旧协议 / 兜底路径）：逐行脱敏、裁剪，超出容量丢弃最旧。
     * 这些行没有可信时间与 pid，渲染时按原文输出。
     */
    public synchronized void append(String text) {
        if (text == null || text.length() == 0) return;
        String[] parts = text.split("\n", -1);
        for (String part : parts) {
            String line = redact(part);
            if (line.length() == 0) continue;
            add(new MergedLogEntry(++seq, 0L, 0, "", "I", "", clip(line), 0L));
        }
    }

    /**
     * 追加结构化增量：{@code {proc, lines:[{seq,t?,level,msg,tag,ts,pid}]}}。
     *
     * <p>只接受解析得出的行；任何一条不合法就跳过该条，不影响其余行。</p>
     *
     * @return 实际新增的条数（去重与解析失败都不计）
     */
    public synchronized int appendJson(String json) {
        if (json == null || json.length() == 0) return 0;
        Object rootObj;
        try {
            rootObj = Json.read(json);
        } catch (Throwable t) {
            return 0;
        }
        if (!(rootObj instanceof Map)) return 0;
        Map<?, ?> root = (Map<?, ?>) rootObj;
        String proc = clip(Json.optString(root, "proc", ""));
        Object linesObj = root.get("lines");
        if (!(linesObj instanceof List)) return 0;
        int added = 0;
        for (Object value : (List<?>) linesObj) {
            if (!(value instanceof Map)) continue;
            Map<?, ?> line = (Map<?, ?>) value;
            int pid = (int) Json.optLong(line, "pid", 0L);
            long srcSeq = Json.optLong(line, "seq", 0L);
            long ts = Json.optLong(line, "ts", 0L);
            if (!accept(pid, srcSeq, ts)) continue;
            add(new MergedLogEntry(++seq,
                    ts,
                    pid,
                    proc,
                    Json.optString(line, "level", "I"),
                    Json.optString(line, "tag", ""),
                    clip(redact(Json.optString(line, "msg", ""))),
                    srcSeq));
            added++;
        }
        return added;
    }

    /**
     * 去重与重启检测。
     *
     * <p>只看 srcSeq 不够：重发一段旧日志和「进程重启后从 1 重来」长得一样。用时间区分——
     * 回退且**时间更新**才算重启（真重启发的是新日志），否则当重复丢弃。</p>
     *
     * <p>srcSeq<=0 的行（纯文本兜底/旧客户端）不参与去重。</p>
     */
    private boolean accept(int pid, long srcSeq, long ts) {
        if (srcSeq <= 0) return true;
        Cursor c = srcCursor.get(pid);
        if (c == null) {
            srcCursor.put(pid, new Cursor(srcSeq, ts));
            return true;
        }
        if (srcSeq > c.srcSeq) {
            c.srcSeq = srcSeq;
            if (ts > c.ts) c.ts = ts;
            return true;
        }
        if (srcSeq < c.srcSeq) {
            // 序号回退：时间也更新 → 进程重启；时间没变新 → 旧行重发，丢掉。
            if (ts > c.ts) {
                c.srcSeq = srcSeq;
                c.ts = ts;
                return true;
            }
            return false;
        }
        // 同序号：重复。
        if (ts > c.ts) c.ts = ts;
        return false;
    }

    private void add(MergedLogEntry e) {
        entries.addLast(e);
        while (entries.size() > MAX_LINES) entries.removeFirst();
    }

    /**
     * 取合并视图：{@code since>0} 只返回全局 seq 更大的；{@code limit<=0} 返回空。
     *
     * <p>窗口内按时间排序（各进程的推送节奏不同，到达顺序可能晚于事件时间）；
     * 游标仍按全局 seq 过滤，两者互不干扰。</p>
     */
    public synchronized List<MergedLogEntry> snapshot(int limit, long since) {
        ArrayList<MergedLogEntry> list = new ArrayList<MergedLogEntry>();
        for (MergedLogEntry e : entries) {
            if (since > 0 && e.seq <= since) continue;
            list.add(e);
        }
        if (limit <= 0) return new ArrayList<MergedLogEntry>();
        Collections.sort(list, BY_TIME);
        if (list.size() > limit) {
            return new ArrayList<MergedLogEntry>(list.subList(list.size() - limit, list.size()));
        }
        return list;
    }

    public synchronized long lastSeq() {
        return seq;
    }

    public synchronized int size() {
        return entries.size();
    }

    /** 文本视图：结构化行渲染成 {@code HH:mm:ss.SSS L msg}，兜底行原样输出。 */
    public synchronized List<String> lines() {
        List<String> out = new ArrayList<String>(entries.size());
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        for (MergedLogEntry e : entries) {
            out.add(render(f, e));
        }
        return out;
    }

    public synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : lines()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * JSON 视图（与进程内 {@code Logs.dump} 同构，额外带 {@code scope} 与 {@code proc}）。
     *
     * <p>{@code seq/t/level/msg} 在前，{@code tag/ts/pid/proc} 在后：旧消费者只读前四个。</p>
     */
    public synchronized String dumpJson(int limit, long since) {
        List<MergedLogEntry> list = snapshot(limit, since);
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        List<Object> arr = Json.arr();
        for (MergedLogEntry e : list) {
            Map<String, Object> o = Json.obj();
            o.put("seq", e.seq);
            o.put("t", f.format(new Date(e.ts <= 0 ? System.currentTimeMillis() : e.ts)));
            o.put("level", e.level);
            o.put("msg", e.msg);
            o.put("tag", e.tag);
            o.put("ts", e.ts);
            o.put("pid", (long) e.pid);
            o.put("proc", e.proc);
            arr.add(o);
        }
        Map<String, Object> root = Json.obj();
        root.put("count", arr.size());
        root.put("lastSeq", seq);
        root.put("scope", SCOPE_MERGED);
        root.put("lines", arr);
        return Json.write(root);
    }

    public synchronized void clear() {
        entries.clear();
        srcCursor.clear();
    }

    private static String render(SimpleDateFormat f, MergedLogEntry e) {
        if (e.ts <= 0) return e.msg;
        return f.format(new Date(e.ts)) + " " + e.level + " " + e.msg;
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > MAX_LINE_CHARS ? s.substring(0, MAX_LINE_CHARS) : s;
    }

    /** 秘密脱敏：委托 {@link Redact}，行为与历史实现保持一致。 */
    public static String redact(String line) {
        return Redact.redact(line);
    }
}
