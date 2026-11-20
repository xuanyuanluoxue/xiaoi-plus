package plus.xiaoi.module;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 滚动日志落盘（纯 JVM，infra）：批量拼接，按行数/字节阈值 flush，超过上限轮转为 {@code .1}。
 *
 * <p>为什么要批量：旧实现每行一次 {@code open/write/close}，在 hook getter 与请求热路径上
 * 是明显开销。这里只在内存里追加，达到阈值或显式 {@link #flush()} 时写一次。写失败静默
 * 丢弃（保留有限 pending 供下次重试），绝不影响业务。</p>
 */
public final class LogFileSink {

    private final File file;
    private final long maxBytes;
    private final int flushLines;
    private final int flushBytes;
    private final StringBuilder pending = new StringBuilder();
    private int pendingLines;

    public LogFileSink(File file, long maxBytes, int flushLines, int flushBytes) {
        if (file == null) throw new IllegalArgumentException("file == null");
        this.file = file;
        this.maxBytes = maxBytes > 0 ? maxBytes : 1;
        this.flushLines = flushLines > 0 ? flushLines : 1;
        this.flushBytes = flushBytes > 0 ? flushBytes : 1;
    }

    /** 追加一行（自动补换行）；达到阈值时立即写盘。 */
    public synchronized void append(String line) {
        pending.append(line == null ? "" : line).append('\n');
        pendingLines++;
        if (pendingLines >= flushLines || pending.length() >= flushBytes) flushLocked();
    }

    /** 立即把 pending 写入文件；没有 pending 时是 no-op。 */
    public synchronized void flush() {
        flushLocked();
    }

    /** 单测/诊断：还有多少行没落盘。 */
    public synchronized int pendingLines() {
        return pendingLines;
    }

    public File file() {
        return file;
    }

    private void flushLocked() {
        if (pending.length() == 0) return;
        String data = pending.toString();
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        try {
            if (file.length() + bytes.length > maxBytes) rotateLocked();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(file, true);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            pending.setLength(0);
            pendingLines = 0;
        } catch (Throwable ignored) {
            // 保留 pending 等下次重试；但避免磁盘长期不可写时无限增长。
            if (pending.length() > flushBytes * 4) {
                pending.setLength(0);
                pendingLines = 0;
            }
        }
    }

    private void rotateLocked() {
        try {
            File old = new File(file.getPath() + ".1");
            if (old.exists()) old.delete();
            file.renameTo(old);
        } catch (Throwable ignored) {
        }
    }
}
