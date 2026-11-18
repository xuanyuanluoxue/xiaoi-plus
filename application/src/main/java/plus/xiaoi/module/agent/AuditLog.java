package plus.xiaoi.module.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import plus.xiaoi.module.Json;

/**
 * 动作审计环形缓冲（纯 JVM，内存实现）。
 *
 * <p>只记录“谁在什么时候请求了什么”：clientId、action、executor、risk、参数名、
 * 参数短哈希、结果、耗时、requestId、事件类型。**不记录**参数值、context、
 * confirm token、apiKey 或记忆正文。</p>
 *
 * <p>容量固定 1000 条，超过后覆盖最旧记录；服务重启丢失（持久化留后续）。
 * {@link #recent(int)} 返回最新在前的 JSON 数组，limit 限定在 1~200，默认 50。</p>
 */
public final class AuditLog {

    public static final int DEFAULT_CAPACITY = 1000;
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    /** 一次动作执行（含失败）。 */
    public static final String EVENT_ACTION = "action";
    /** 确认 token 签发/批准。 */
    public static final String EVENT_CONFIRM = "confirm";
    /** 显式拒绝确认。 */
    public static final String EVENT_DENY = "deny";
    /** 幂等键命中，未重复执行。 */
    public static final String EVENT_REPLAY = "replay";

    /** 可注入时钟，测试用。 */
    public interface Now {
        long nowMs();
    }

    /** 一条审计记录。 */
    public static final class Entry {
        public final String auditId;
        public final long time;
        public final String clientId;
        public final String action;
        public final String executor;
        public final String risk;
        public final List<String> argNames;
        public final String argHash;
        public final boolean ok;
        public final String errorCode;
        public final long elapsedMs;
        public final String requestId;
        public final String event;

        private Entry(String auditId, long time, String clientId, String action,
                      String executor, String risk, List<String> argNames, String argHash,
                      boolean ok, String errorCode, long elapsedMs, String requestId,
                      String event) {
            this.auditId = auditId;
            this.time = time;
            this.clientId = clientId;
            this.action = action;
            this.executor = executor;
            this.risk = risk;
            this.argNames = argNames;
            this.argHash = argHash;
            this.ok = ok;
            this.errorCode = errorCode;
            this.elapsedMs = elapsedMs;
            this.requestId = requestId;
            this.event = event;
        }

        /** 摘要 JSON：只有元数据，没有参数值/token/context。 */
        public Map<String, Object> toJson() {
            Map<String, Object> o = Json.obj();
            o.put("auditId", auditId);
            o.put("time", time);
            o.put("clientId", clientId);
            o.put("action", action);
            o.put("executor", executor);
            o.put("risk", risk);
            List<Object> names = Json.arr();
            for (String s : argNames) names.add(s);
            o.put("argNames", names);
            o.put("argHash", argHash);
            o.put("ok", ok);
            o.put("errorCode", errorCode);
            o.put("elapsedMs", elapsedMs);
            o.put("requestId", requestId);
            o.put("event", event);
            return o;
        }
    }

    private final Now now;
    private final Entry[] ring;
    private final AtomicLong seq = new AtomicLong();
    private int start;
    private int size;
    private final Object lock = new Object();

    public AuditLog() {
        this(DEFAULT_CAPACITY, null);
    }

    public AuditLog(int capacity, Now now) {
        int cap = capacity > 0 ? capacity : DEFAULT_CAPACITY;
        this.ring = new Entry[cap];
        this.now = now == null ? new Now() {
            @Override
            public long nowMs() {
                return System.currentTimeMillis();
            }
        } : now;
    }

    public int capacity() {
        return ring.length;
    }

    /**
     * 记录一条审计。argNames 只保留参数名；调用方必须保证不传参数值。
     */
    public Entry record(String clientId, String action, String executor, ActionRisk risk,
                        List<String> argNames, String argHash, boolean ok,
                        String errorCode, long elapsedMs, String requestId, String event) {
        List<String> names = new ArrayList<String>();
        if (argNames != null) {
            for (String n : argNames) {
                if (n != null) names.add(n);
            }
        }
        Collections.sort(names);
        String ev = event == null || event.length() == 0 ? EVENT_ACTION : event;
        String id = "aud_" + Long.toHexString(System.currentTimeMillis())
                + "_" + seq.incrementAndGet();
        long time = now.nowMs();
        Entry e = new Entry(id, time,
                clientId == null ? "" : clientId,
                action == null ? "" : action,
                executor == null ? "" : executor,
                risk == null ? "" : risk.wire,
                Collections.unmodifiableList(names),
                argHash == null ? "" : argHash,
                ok,
                errorCode == null || errorCode.length() == 0 ? null : errorCode,
                elapsedMs > 0 ? elapsedMs : 0,
                requestId == null ? "" : requestId,
                ev);
        synchronized (lock) {
            if (size == ring.length) {
                ring[start] = e;
                start = (start + 1) % ring.length;
            } else {
                ring[(start + size) % ring.length] = e;
                size++;
            }
        }
        return e;
    }

    /** 最新在前的记录快照；limit 会夹在 1..200（<=0 用默认 50）。 */
    public List<Object> recent(int limit) {
        int n = clampLimit(limit);
        List<Object> out = Json.arr();
        synchronized (lock) {
            int count = Math.min(n, size);
            for (int i = 0; i < count; i++) {
                int idx = (start + size - 1 - i + ring.length) % ring.length;
                Entry e = ring[idx];
                if (e != null) out.add(e.toJson());
            }
        }
        return out;
    }

    /** {@code {"entries":[...]}}，供 xiaoai_audit 直接返回。 */
    public Map<String, Object> recentJson(int limit) {
        Map<String, Object> out = Json.obj();
        out.put("entries", recent(limit));
        return out;
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < ring.length; i++) ring[i] = null;
            start = 0;
            size = 0;
        }
    }

    public static int clampLimit(int limit) {
        if (limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }
}
