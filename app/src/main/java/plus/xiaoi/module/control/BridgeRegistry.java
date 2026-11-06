package plus.xiaoi.module.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App 侧实例表（纯 JVM，无 android 依赖）。
 *
 * 覆盖 WP1 需要的最小状态机：
 *  - register：同一进程重连时覆盖旧记录（幂等）；
 *  - reportState：可先于/晚于 register 到达，缺失时补建记录；
 *  - leader：PORT&gt;0 的记录中取最近更新的一条（同时间取端口大的，保证确定性）；
 *  - 最多保留 {@link #MAX_INSTANCES} 条，超出按 updatedAt 淘汰最旧，防止异常进程刷爆内存。
 */
public final class BridgeRegistry {

    public static final int MAX_INSTANCES = 8;

    private final Map<String, InstanceRecord> records = new LinkedHashMap<String, InstanceRecord>();

    public synchronized InstanceRecord register(InstanceRecord r) {
        if (r == null) return null;
        String key = r.key();
        evictIfNeeded(key);
        records.put(key, r);
        return r;
    }

    public synchronized InstanceRecord reportState(String process, int pid, String model,
                                                   boolean enabled, boolean hasKey,
                                                   String version, int port, long now,
                                                   long cfgRev) {
        String key = InstanceRecord.keyOf(process, pid);
        InstanceRecord old = records.get(key);
        if (old == null) {
            evictIfNeeded(key);
            old = InstanceRecord.registered(process, pid, port, "", now, cfgRev);
        }
        InstanceRecord next = old.withState(model, enabled, hasKey, version, pid, port, now,
                cfgRev);
        records.put(key, next);
        return next;
    }

    /** 数据面 leader；没有进程监听端口时返回 null。 */
    public synchronized InstanceRecord leader() {
        InstanceRecord best = null;
        for (InstanceRecord r : records.values()) {
            if (!r.leader()) continue;
            if (best == null) {
                best = r;
                continue;
            }
            if (r.updatedAt > best.updatedAt) {
                best = r;
            } else if (r.updatedAt == best.updatedAt && r.port > best.port) {
                best = r;
            }
        }
        return best;
    }

    public synchronized InstanceRecord get(String key) {
        return key == null ? null : records.get(key);
    }

    public synchronized List<InstanceRecord> snapshot() {
        return new ArrayList<InstanceRecord>(records.values());
    }

    public synchronized int size() {
        return records.size();
    }

    public synchronized void clear() {
        records.clear();
    }

    private void evictIfNeeded(String incomingKey) {
        if (records.containsKey(incomingKey) || records.size() < MAX_INSTANCES) return;
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, InstanceRecord> e : records.entrySet()) {
            long at = e.getValue().updatedAt;
            if (at < oldest) {
                oldest = at;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) records.remove(oldestKey);
    }
}
