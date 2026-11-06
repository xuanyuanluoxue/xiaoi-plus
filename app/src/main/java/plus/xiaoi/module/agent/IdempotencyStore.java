package plus.xiaoi.module.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import plus.xiaoi.module.Json;

/**
 * 幂等键缓存（纯 JVM，内存 + 可选持久化）。
 *
 * <p>写动作带 {@code idempotencyKey} 时：执行前 {@link #check}，同一键 + 同一动作 +
 * 同一参数哈希直接返回首次的终态结果；同键但动作/参数不同返回 {@code CONFLICT}。
 * 成功和失败都会缓存，避免重试导致重复副作用。默认 TTL 24 小时、容量 500 条，
 * 超出按 LRU 淘汰。</p>
 *
 * <p><b>持久化（WP1.5）</b>：{@link #snapshotJson()} 导出未过期条目，
 * {@link #restoreJson(String)} 在服务启动时恢复；{@link #setPersistence} 注入写盘回调，
 * 只在内容确实变化（put / 淘汰 / 过期清理 / clear）时回调一次。回调在内部锁之外执行，
 * 文件 I/O 由调用方决定是否异步。损坏 JSON / 坏条目不会抛到协议层：能恢复的保留，
 * 其余丢弃；整份损坏时按空缓存启动。序列化复用 {@link ActionResult#toJson()}，
 * 不含 token / apiKey / context。</p>
 *
 * <p>该类仍是纯 JVM：不 import android，也不直接写文件。</p>
 */
public final class IdempotencyStore {

    /** 默认有效期：24 小时（PLAN 5.4）。 */
    public static final long DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L;
    /** 默认容量；超过后淘汰最久未使用的键。 */
    public static final int DEFAULT_CAPACITY = 500;
    /** 快照格式版本；只追加，不复用旧字段含义。 */
    public static final int SNAPSHOT_VERSION = 1;

    /** 可注入时钟，测试用。 */
    public interface Now {
        long nowMs();
    }

    /** 持久化回调；实现方负责落盘/忽略异常，store 会吞掉回调抛出的异常。 */
    public interface Persistence {
        void save(String json);
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
    /** 串行化写盘顺序，避免并发 put 时旧快照覆盖新快照。 */
    private final Object saveLock = new Object();

    /** 以下字段受 {@link #lock} 保护。 */
    private Persistence persistence;
    private String lastSaved = "";
    private long saveSeq;
    private String lastRestoreError = "";

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

    // ------------------------------------------------------------- 查询/写入

    /** 查询键；key 为空直接 MISS，不创建条目。 */
    public Lookup check(String key, String action, Map<String, Object> args) {
        if (key == null || key.trim().length() == 0) {
            return new Lookup(Status.MISS, null);
        }
        String argHash = CanonicalArgs.hash(args);
        long nowMs = now.nowMs();
        Lookup lookup;
        String changed = null;
        synchronized (lock) {
            boolean removed = purgeExpired(nowMs);
            Entry e = entries.get(key);
            if (e == null) {
                lookup = new Lookup(Status.MISS, null);
            } else if (!e.action.equals(action == null ? "" : action)
                    || !e.argHash.equals(argHash)) {
                lookup = new Lookup(Status.CONFLICT, null);
            } else {
                lookup = new Lookup(Status.HIT, copy(e.result));
            }
            // 只有清掉过期项才算内容变化；LRU 命中只改访问顺序，不写盘。
            if (removed) changed = snapshotLocked();
        }
        if (changed != null) persistIfChanged(changed);
        return lookup;
    }

    /**
     * 保存终态结果。key 为空或 result 为 null 时忽略；同键重复写入以后写为准。
     */
    public void put(String key, String action, Map<String, Object> args,
                    ActionResult result) {
        if (key == null || key.trim().length() == 0 || result == null) return;
        String argHash = CanonicalArgs.hash(args);
        long nowMs = now.nowMs();
        String snapshot;
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
            snapshot = snapshotLocked();
        }
        persistIfChanged(snapshot);
    }

    /** 当前条目数（未过期的）。 */
    public int size() {
        long nowMs = now.nowMs();
        int size;
        String changed = null;
        synchronized (lock) {
            boolean removed = purgeExpired(nowMs);
            size = entries.size();
            if (removed) changed = snapshotLocked();
        }
        if (changed != null) persistIfChanged(changed);
        return size;
    }

    public void clear() {
        String snapshot = null;
        synchronized (lock) {
            if (!entries.isEmpty()) {
                entries.clear();
                snapshot = snapshotLocked();
            }
        }
        if (snapshot != null) persistIfChanged(snapshot);
    }

    /**
     * 注入持久化回调。恢复流程应先 {@link #restoreJson(String)} 再设置回调，避免启动时
     * 把刚读到的文件原样再写一遍。传 null 表示关闭持久化。
     */
    public void setPersistence(Persistence value) {
        synchronized (lock) {
            this.persistence = value;
        }
    }

    /** 最近一次 {@link #restoreJson(String)} 的错误；成功或空输入为空串。 */
    public String lastRestoreError() {
        synchronized (lock) {
            return lastRestoreError;
        }
    }

    // ------------------------------------------------------------- 快照/恢复

    /**
     * 导出未过期条目：{@code {"version":1,"entries":[...]}}。
     * 每条含 key / action / argHash / expiresAt / result（ActionResult 信封）。
     */
    public String snapshotJson() {
        String snapshot;
        String changed = null;
        long nowMs = now.nowMs();
        synchronized (lock) {
            boolean removed = purgeExpired(nowMs);
            snapshot = snapshotLocked();
            if (removed) changed = snapshot;
        }
        if (changed != null) persistIfChanged(changed);
        return snapshot;
    }

    /**
     * 从 {@link #snapshotJson()} 恢复。损坏 JSON / 结构不符时清空并按空缓存继续；
     * 单条坏数据或已过期条目直接丢弃；超过容量时保留列表末尾（最新）的条目。
     * 恢复本身不触发 {@link Persistence#save}。
     */
    public void restoreJson(String json) {
        String text = json == null ? "" : json.trim();
        if (text.length() == 0) {
            replaceAll(Collections.<Entry>emptyList(), "");
            return;
        }
        Object parsed;
        try {
            parsed = Json.read(text);
        } catch (Throwable t) {
            replaceAll(Collections.<Entry>emptyList(), "JSON 解析失败");
            return;
        }
        if (!(parsed instanceof Map)) {
            replaceAll(Collections.<Entry>emptyList(), "快照不是对象");
            return;
        }
        Object rawEntries = ((Map<?, ?>) parsed).get("entries");
        if (!(rawEntries instanceof List)) {
            replaceAll(Collections.<Entry>emptyList(), "缺少 entries 数组");
            return;
        }
        long nowMs = now.nowMs();
        List<Entry> parsedEntries = new ArrayList<Entry>();
        for (Object o : (List<?>) rawEntries) {
            Entry e = parseEntry(o, nowMs);
            if (e != null) parsedEntries.add(e);
        }
        int start = parsedEntries.size() > capacity ? parsedEntries.size() - capacity : 0;
        List<Entry> keep = parsedEntries.subList(start, parsedEntries.size());
        replaceAll(keep, "");
    }

    private void replaceAll(List<Entry> restored, String error) {
        synchronized (lock) {
            entries.clear();
            for (Entry e : restored) entries.put(e.key, e);
            // 恢复后的内容就是文件里的内容；即使有坏条目被丢弃，也不在启动时回写。
            lastSaved = snapshotLocked();
            lastRestoreError = error == null ? "" : error;
        }
    }

    private Entry parseEntry(Object raw, long nowMs) {
        if (!(raw instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) raw;
        String key = Json.optString(m, "key", "").trim();
        if (key.length() == 0) return null;
        String argHash = Json.optString(m, "argHash", "");
        if (argHash.length() == 0) return null;
        Object expiresValue = m.get("expiresAt");
        if (!(expiresValue instanceof Number)) return null;
        long expiresAt = ((Number) expiresValue).longValue();
        if (expiresAt <= nowMs) return null;
        Object resultValue = m.get("result");
        if (!(resultValue instanceof Map)) return null;
        ActionResult result = ActionResult.fromJson((Map<?, ?>) resultValue);
        if (result == null) return null;
        return new Entry(key, Json.optString(m, "action", ""), argHash, result, expiresAt);
    }

    /** 在持有 {@link #lock} 时调用。 */
    private String snapshotLocked() {
        Map<String, Object> root = Json.obj();
        root.put("version", (long) SNAPSHOT_VERSION);
        List<Object> list = Json.arr();
        for (Entry e : entries.values()) {
            Map<String, Object> o = Json.obj();
            o.put("key", e.key);
            o.put("action", e.action);
            o.put("argHash", e.argHash);
            o.put("expiresAt", e.expiresAt);
            o.put("result", e.result.toJson());
            list.add(o);
        }
        root.put("entries", list);
        return Json.write(root);
    }

    // ------------------------------------------------------------- 内部

    /** 仅在内容变化时回调一次；回调抛异常不会冒泡到调用方。 */
    private void persistIfChanged(String snapshot) {
        if (snapshot == null) return;
        final Persistence p;
        final long seq;
        synchronized (lock) {
            if (snapshot.equals(lastSaved)) return;
            p = persistence;
            if (p == null) return;
            lastSaved = snapshot;
            seq = ++saveSeq;
        }
        synchronized (saveLock) {
            synchronized (lock) {
                // 已有更新的快照在排队：让更新的一次写盘，跳过这份旧内容。
                if (seq != saveSeq) return;
            }
            try {
                p.save(snapshot);
            } catch (Throwable ignored) {
                // 写盘失败只影响持久化，不影响动作结果；允许下次变化时重试。
                synchronized (lock) {
                    if (seq == saveSeq && snapshot.equals(lastSaved)) lastSaved = "";
                }
            }
        }
    }

    private boolean purgeExpired(long nowMs) {
        boolean removed = false;
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt <= nowMs) {
                it.remove();
                removed = true;
            }
        }
        return removed;
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
