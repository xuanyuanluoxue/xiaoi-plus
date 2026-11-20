package plus.xiaoi.module.control;

/**
 * 合并视图里的一条日志（纯 JVM）。
 *
 * <p>与小爱进程内 {@code LogEntry} 的区别：这里多一个 {@code proc}（来源进程名）和
 * {@code srcSeq}（来源进程内序号）。{@code seq} 是 App 侧全局单调序号，只有它能做
 * 跨进程的 {@code since} 游标；{@code srcSeq} 只用于去重与识别进程重启。</p>
 */
public final class MergedLogEntry {

    /** App 侧全局序号（合并视图的增量游标）。 */
    public final long seq;
    /** epoch 毫秒。 */
    public final long ts;
    /** 来源进程 pid；未知为 0。 */
    public final int pid;
    /** 来源进程名（com.miui.voiceassist:core 等）；未知为空串。 */
    public final String proc;
    public final String level;
    public final String tag;
    public final String msg;
    /** 来源进程内的 seq；0 表示未知（纯文本兜底路径）。 */
    public final long srcSeq;

    public MergedLogEntry(long seq, long ts, int pid, String proc,
                          String level, String tag, String msg, long srcSeq) {
        this.seq = seq;
        this.ts = ts;
        this.pid = pid;
        this.proc = proc == null ? "" : proc;
        this.level = level == null || level.length() == 0 ? "I" : level;
        this.tag = tag == null ? "" : tag;
        this.msg = msg == null ? "" : msg;
        this.srcSeq = srcSeq;
    }
}
