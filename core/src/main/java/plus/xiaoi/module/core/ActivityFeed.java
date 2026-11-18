package plus.xiaoi.module.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 首页/日志页共用的「最近活动」环形缓冲（纯 JVM，无 android 依赖）。
 *
 * <p>只存内存，最多 {@link #capacity()} 条；876? 两个进程的事件由调用方合并后
 * 写入。所有方法线程安全，读取始终返回按时间从旧到新的快照。</p>
 */
public final class ActivityFeed {

    public enum Level { INFO, WARN, ERROR }

    public enum Domain { XIAOAI, AGENT, ACCESS }

    public static final int DEFAULT_CAPACITY = 20;

    public static final class Event {
        public final long timestamp;
        public final Level level;
        public final Domain domain;
        public final String text;

        public Event(long timestamp, Level level, Domain domain, String text) {
            this.timestamp = timestamp;
            this.level = level == null ? Level.INFO : level;
            this.domain = domain == null ? Domain.ACCESS : domain;
            this.text = text == null ? "" : text;
        }
    }

    private final Object lock = new Object();
    private final Event[] buffer;
    private int start;
    private int size;

    public ActivityFeed() {
        this(DEFAULT_CAPACITY);
    }

    /** capacity &lt; 1 时按 1 处理，避免零长数组。 */
    public ActivityFeed(int capacity) {
        int cap = capacity < 1 ? 1 : capacity;
        buffer = new Event[cap];
    }

    public int capacity() {
        return buffer.length;
    }

    /** 用当前时间写入；返回写入的事件，便于调用方直接展示。 */
    public Event add(Level level, Domain domain, String text) {
        return add(System.currentTimeMillis(), level, domain, text);
    }

    /** 指定时间写入（合并 8771 镜像事件或测试时使用）。 */
    public Event add(long timestamp, Level level, Domain domain, String text) {
        Event event = new Event(timestamp, level, domain, text);
        synchronized (lock) {
            int cap = buffer.length;
            if (size < cap) {
                buffer[(start + size) % cap] = event;
                size++;
            } else {
                buffer[start] = event;
                start = (start + 1) % cap;
            }
        }
        return event;
    }

    /** 最近 n 条，按时间从旧到新；n &lt;= 0 返回空，超出实际条数则返回全部。 */
    public List<Event> last(int n) {
        synchronized (lock) {
            if (n <= 0 || size == 0) return Collections.emptyList();
            int count = Math.min(n, size);
            int cap = buffer.length;
            ArrayList<Event> out = new ArrayList<>(count);
            for (int i = size - count; i < size; i++) {
                out.add(buffer[(start + i) % cap]);
            }
            return Collections.unmodifiableList(out);
        }
    }

    public void clear() {
        synchronized (lock) {
            Arrays.fill(buffer, null);
            start = 0;
            size = 0;
        }
    }

    public int count() {
        synchronized (lock) {
            return size;
        }
    }
}
