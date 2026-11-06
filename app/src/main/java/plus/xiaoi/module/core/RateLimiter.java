package plus.xiaoi.module.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 固定窗口限流器（纯 JVM，无 android / 第三方依赖）。
 *
 * <p>只用于 {@code /health}、{@code /island} 这类无 body、低信任端点：
 * 每个来源 IP 每 {@code windowMs} 最多 {@code limit} 次，超限由调用方回 429。
 * {@code /mcp} 有 Bearer，不做全局限流，避免误伤合法 Agent。</p>
 *
 * <p>状态表有上限（默认 256 个 key）。被大量伪造来源刷满时直接清空计数，
 * 保证内存有界；限流精度退让可接受（端点本身无副作用）。</p>
 */
public final class RateLimiter {

    public static final int DEFAULT_LIMIT = 30;
    public static final long DEFAULT_WINDOW_MS = 10_000L;
    public static final int DEFAULT_MAX_KEYS = 256;

    private final int limit;
    private final long windowMs;
    private final int maxKeys;
    private final Map<String, Window> windows = new HashMap<String, Window>();

    private static final class Window {
        long start;
        int count;
    }

    public RateLimiter() {
        this(DEFAULT_LIMIT, DEFAULT_WINDOW_MS, DEFAULT_MAX_KEYS);
    }

    public RateLimiter(int limit, long windowMs) {
        this(limit, windowMs, DEFAULT_MAX_KEYS);
    }

    public RateLimiter(int limit, long windowMs, int maxKeys) {
        this.limit = limit > 0 ? limit : 1;
        this.windowMs = windowMs > 0 ? windowMs : DEFAULT_WINDOW_MS;
        this.maxKeys = maxKeys > 0 ? maxKeys : DEFAULT_MAX_KEYS;
    }

    /** 使用系统时钟判断是否放行。 */
    public boolean allow(String key) {
        return allow(key, System.currentTimeMillis());
    }

    /** 可注入时钟的版本（桌面单测）。 */
    public synchronized boolean allow(String key, long now) {
        String k = key == null ? "" : key;
        Window w = windows.get(k);
        if (w == null || now - w.start >= windowMs || now < w.start) {
            if (w == null && windows.size() >= maxKeys) {
                prune(now);
                if (windows.size() >= maxKeys) windows.clear();
            }
            w = new Window();
            w.start = now;
            windows.put(k, w);
        }
        if (w.count >= limit) return false;
        w.count++;
        return true;
    }

    /** 当前记录的 key 数量（测试/诊断）。 */
    public synchronized int size() {
        return windows.size();
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Window w = it.next().getValue();
            if (now - w.start >= windowMs || now < w.start) it.remove();
        }
    }
}
