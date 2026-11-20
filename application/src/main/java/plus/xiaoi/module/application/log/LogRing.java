package plus.xiaoi.module.application.log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import plus.xiaoi.module.core.Contract;

/**
 * 运行日志环形缓冲（纯 JVM，无 I/O）。
 *
 * <p>容量固定为 {@link Contract#LOGS_CAPACITY}，满了丢最旧；{@code seq} 在进程内单调递增。
 * 本类只负责「保存 + 按 since/limit 取快照」，档位过滤、脱敏、logcat、落盘都由调用方负责，
 * 这样桌面单测不需要 Android / Xposed。</p>
 *
 * <p>注意：按级别保留（例如错误行永不淘汰）会破坏 {@code since} 增量语义——保留下来的
 * 旧错误行 {@code seq} 小于 {@code lastSeq}，增量拉取永远看不到。因此这里保持严格的
 * FIFO；需要保留错误时请提高档位或及时拉取。</p>
 */
public final class LogRing {

    private final ArrayDeque<LogEntry> buf;
    private final int capacity;
    private long seq;

    public LogRing() {
        this(Contract.LOGS_CAPACITY);
    }

    /** 单测用：允许更小的容量。小于 1 时按 1 处理。 */
    public LogRing(int capacity) {
        this.capacity = capacity < 1 ? 1 : capacity;
        this.buf = new ArrayDeque<LogEntry>(this.capacity);
    }

    public int capacity() {
        return capacity;
    }

    /**
     * 追加一条日志并返回落库后的条目。
     *
     * @param level    I/W/E/D；空值回落 I
     * @param tag      来源标签，可为 null/空
     * @param msg      正文（不负责加 {@code [tag]} 前缀）
     * @param tsMillis epoch 毫秒
     * @param pid      写入进程；未知填 0
     */
    public synchronized LogEntry add(String level, String tag, String msg, long tsMillis, int pid) {
        LogEntry e = new LogEntry(++seq, tsMillis,
                level == null || level.length() == 0 ? "I" : level,
                tag == null ? "" : tag,
                msg == null ? "" : msg,
                pid < 0 ? 0 : pid);
        buf.addLast(e);
        while (buf.size() > capacity) buf.removeFirst();
        return e;
    }

    /**
     * 取快照：{@code since>0} 时只返回 seq 更大的；{@code limit<=0} 返回空；否则取尾部 limit 条。
     * 返回值是副本，调用方可以安全遍历。
     */
    public synchronized List<LogEntry> snapshot(int limit, long since) {
        ArrayList<LogEntry> list = new ArrayList<LogEntry>();
        for (LogEntry e : buf) {
            if (since > 0 && e.seq <= since) continue;
            list.add(e);
        }
        if (limit <= 0) return new ArrayList<LogEntry>();
        if (list.size() > limit) {
            return new ArrayList<LogEntry>(list.subList(list.size() - limit, list.size()));
        }
        return list;
    }

    public synchronized long lastSeq() {
        return seq;
    }

    public synchronized int count() {
        return buf.size();
    }

    /** 清空缓冲区；{@code seq} 不回退，避免下游把新日志误判成旧序号。 */
    public synchronized void clear() {
        buf.clear();
    }
}
