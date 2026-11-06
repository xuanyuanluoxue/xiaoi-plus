package plus.xiaoi.module.agent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 幂等键缓存（纯 JVM，内存实现）。
 *
 * <p>写动作带 {@code idempotencyKey} 时：执行前 {@link #check}，同一键 + 同一动作 +
 * 同一参数哈希直接返回首次的终态结果；同键但动作/参数不同返回 {@code CONFLICT}。
 * 成功和失败都会缓存，避免重试导致重复副作用。默认 TTL 24 小时、容量 500 条，
 * 超出按 LRU 淘汰。</p>
 *
 * <p><b>重启丢失</b>：当前只存内存，App 进程/服务重启后幂等状态清零；持久化
 * （App 私有库 + 24h 清理）留后续工作包。参数哈希由 {@link CanonicalArgs} 生成，
 * 只保存哈希，不保存参数正文。</p>
 */
public final class IdempotencyStore {

    /** 默认有效期：24 小时（PLAN 5.4）。 */
    public static final long DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L;
    /** 默认容量；超过后淘汰最久未使用的键。 */
    public static final int DEFAULT_CAPACITY = 500;

    /** 可注入时钟，测试用。 */
    public interface Now {
        long nowMs();
    }

    public enum Status {
        /** 没有该键，或已过期。 */
        MISS,
        /** 命中首次结果。 */
        HIT,
        /** 同键但动作/参数不同。 */
        CONFLICT
    }

    /** 查询结果。MISS/CONFLICT 时 result 为 null。 */
    public static final class Lookup {
        public final Status status;
        public final ActionResult result;

        private Lookup(Status status, ActionResult result) {
            this.status = status;
            this.result = result;
        }
    }

    private static final class Entry {
        final String key;
        final String action;
        final String argHash;
        final ActionResult result;
        final long expiresAt;

        Entry(String key, String action, String argHash, ActionResult result, long expiresAt) {
            this.key = key;
            this.action = action;
            this.argHash = argHash;
            this.result = copy(result);
            this.expiresAt = expiresAt;
        }
    }

    private final long ttlMs;
    private final int capacity;
    private final Now now;
    /** accessOrder=true：命中会刷新 LRU 顺序。 */
    private final LinkedHashMap<String, Entry> entries =
            new LinkedHashMap<String, Entry>(16, 0.75f, true);
    private final Object lock = new Object();

    public IdempotencyStore() {
        this(DEFAULT_TTL_MS, DEFAULT_CAPACITY, null);
    }

    public IdempotencyStore(long ttlMs, int capacity, Now now) {
        this.ttlMs = ttlMs > 0 ? ttlMs : DEFAULT_TTL_MS;
        this.capacity = capacity > 0 ? capacity : DEFAULT_CAPACITY;
        this.now = now == null ? new Now() {
            @Override
            public long nowMs() {
                return System.currentTimeMillis();
            }
        } : now;
    }

    public long ttlMs() {
        return ttlMs;
    }

    /** 查询键；key 为空直接 MISS，不创建条目。 */
    public Lookup check(String key, String action, Map<String, Object> args) {
        if (key == null || key.trim().length() == 0) {
            return new Lookup(Status.MISS, null);
        }
        String argHash = CanonicalArgs.hash(args);
        long nowMs = now.nowMs();
        synchronized (lock) {
            purgeExpired(nowMs);
            Entry e = entries.get(key);
            if (e == null) return new Lookup(Status.MISS, null);
            if (!e.action.equals(action == null ? "" : action)
                    || !e.argHash.equals(argHash)) {
                return new Lookup(Status.CONFLICT, null);
            }
            return new Lookup(Status.HIT, copy(e.result));
        }
    }

    /**
     * 保存终态结果。key 为空或 result 为 null 时忽略；同键重复写入以后写为准。
     */
    public void put(String key, String action, Map<String, Object> args,
                    ActionResult result) {
        if (key == null || key.trim().length() == 0 || result == null) return;
        String argHash = CanonicalArgs.hash(args);
        long nowMs = now.nowMs();
        synchronized (lock) {
            purgeExpired(nowMs);
            while (entries.size() >= capacity) {
                Iterator<String> it = entries.keySet().iterator();
                if (!it.hasNext()) break;
                it.next();
                it.remove();
            }
            entries.put(key, new Entry(key, action == null ? "" : action, argHash,
                    result, nowMs + ttlMs));
        }
    }

    /** 当前条目数（未过期的）。 */
    public int size() {
        long nowMs = now.nowMs();
        synchronized (lock) {
            purgeExpired(nowMs);
            return entries.size();
        }
    }

    public void clear() {
        synchronized (lock) {
            entries.clear();
        }
    }

    private void purgeExpired(long nowMs) {
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt <= nowMs) it.remove();
        }
    }

    /** 返回结果深拷贝，避免调用方修改缓存中的 Map。 */
    private static ActionResult copy(ActionResult r) {
        if (r == null) return null;
        List<String> warnings = new ArrayList<String>(r.warnings);
        return new ActionResult(r.ok, r.action, r.executor,
                AgentValues.map(r.result), warnings, r.error,
                r.elapsedMs, r.requestId, r.auditId);
    }
}
