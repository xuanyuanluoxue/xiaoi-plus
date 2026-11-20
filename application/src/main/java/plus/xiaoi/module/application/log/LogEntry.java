package plus.xiaoi.module.application.log;

/**
 * 一条结构化运行日志（纯 JVM）。
 *
 * <p>{@code tag} 与 {@code msg} 分开保存：{@code msg} 仍保留 {@code [tag] } 前缀，
 * 供旧消费者与 Binder 文本协议使用；{@code tag} 只给结构化查询。</p>
 */
public final class LogEntry {

    public final long seq;
    public final long tsMillis;
    public final String level;
    public final String tag;
    public final String msg;
    public final int pid;

    public LogEntry(long seq, long tsMillis, String level, String tag, String msg, int pid) {
        this.seq = seq;
        this.tsMillis = tsMillis;
        this.level = level;
        this.tag = tag;
        this.msg = msg;
        this.pid = pid;
    }
}
