package plus.xiaoi.module;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import plus.xiaoi.module.agent.ActionResult;
import plus.xiaoi.module.agent.AgentError;
import plus.xiaoi.module.agent.CanonicalArgs;
import plus.xiaoi.module.agent.IdempotencyStore;

/**
 * WP1.5 纯 JVM 测试：ActionResult 序列化、幂等快照/恢复、持久化回调。
 *
 * <p>不依赖 Android，也不真正写文件；用可注入时钟与计数回调验证“只在内容变化时
 * 落盘”，并检查快照里没有 token / apiKey / context。</p>
 */
public final class AgentIdempotencyPersistenceTest {

    private static final long TTL = 60_000L;

    private AgentIdempotencyPersistenceTest() {}

    public static int run() {
        resultRoundTrip();
        snapshotRestore();
        persistenceCallback();
        return T.report("AgentIdempotencyPersistenceTest");
    }

    // ------------------------------------------------------------- ActionResult

    private static void resultRoundTrip() {
        Map<String, Object> result = args("notificationId", "n1");
        Map<String, Object> nested = args("launched", Boolean.TRUE);
        nested.put("count", 3L);
        result.put("nested", nested);
        List<String> warnings = names("w1", "w2");
        ActionResult original = new ActionResult(true, "notification.send", "android",
                result, warnings, null, 12L, "req-1", "aud_1");

        Map<String, Object> encoded = original.toJson();
        ActionResult decoded = ActionResult.fromJson(encoded);
        T.ok("成功结果可反序列化", decoded != null);
        T.eq("round-trip ok", Boolean.TRUE, decoded.ok);
        T.eq("round-trip action", "notification.send", decoded.action);
        T.eq("round-trip executor", "android", decoded.executor);
        T.eq("round-trip result", "n1", decoded.result.get("notificationId"));
        T.eq("round-trip 嵌套 result", Boolean.TRUE,
                ((Map<?, ?>) decoded.result.get("nested")).get("launched"));
        T.eq("round-trip 嵌套数字", 3L,
                ((Map<?, ?>) decoded.result.get("nested")).get("count"));
        T.eq("round-trip warnings 数量", 2, decoded.warnings.size());
        T.eq("round-trip warnings 内容", "w1", decoded.warnings.get(0));
        T.eq("round-trip error 为空", null, decoded.error);
        T.eq("round-trip elapsedMs", 12L, decoded.elapsedMs);
        T.eq("round-trip requestId", "req-1", decoded.requestId);
        T.eq("round-trip auditId", "aud_1", decoded.auditId);
        T.eq("round-trip 可再次序列化", Json.write(encoded), decoded.toJsonString());

        ActionResult failure = new ActionResult(false, "app.open", "root",
                null, names("partial"), AgentError.of(AgentError.Code.TIMEOUT, "执行超时"),
                9L, "req-2", null);
        ActionResult decodedFailure = ActionResult.fromJson(failure.toJson());
        T.ok("失败结果可反序列化", decodedFailure != null);
        T.eq("失败 round-trip ok=false", Boolean.FALSE, decodedFailure.ok);
        T.eq("失败 round-trip 错误码", "TIMEOUT", decodedFailure.error.code.wire);
        T.eq("失败 round-trip 文案", "执行超时", decodedFailure.error.message);
        T.eq("失败 round-trip retryable", Boolean.TRUE, decodedFailure.error.retryable);
        T.eq("失败 round-trip warnings", 1, decodedFailure.warnings.size());
        T.eq("失败 round-trip 无 result", 0, decodedFailure.result.size());
        T.eq("失败 round-trip auditId 为空", null, decodedFailure.auditId);
        T.eq("失败 round-trip requestId", "req-2", decodedFailure.requestId);

        ActionResult notRetryable = ActionResult.failure("device.info", "android",
                AgentError.of(AgentError.Code.NOT_AVAILABLE, "临时不可用", true));
        ActionResult decodedRetry = ActionResult.fromJson(notRetryable.toJson());
        T.eq("显式 retryable=true 保留", Boolean.TRUE, decodedRetry.error.retryable);
        T.eq("显式 retryable 错误码", "NOT_AVAILABLE", decodedRetry.error.code.wire);

        Map<String, Object> unknown = Json.obj();
        unknown.put("ok", Boolean.FALSE);
        unknown.put("action", "future.action");
        unknown.put("code", "FUTURE_CODE");
        unknown.put("message", "未来版本的错误");
        unknown.put("retryable", Boolean.FALSE);
        ActionResult unknownCode = ActionResult.fromJson(unknown);
        T.ok("未知错误码不抛异常", unknownCode != null);
        T.eq("未知错误码收敛为 INTERNAL", "INTERNAL", unknownCode.error.code.wire);
        T.eq("未知错误码保留文案", "未来版本的错误", unknownCode.error.message);
        T.eq("未知错误码保留 retryable", Boolean.FALSE, unknownCode.error.retryable);

        T.eq("null map -> null", null, ActionResult.fromJson(null));
        Map<String, Object> noOk = Json.obj();
        noOk.put("action", "x");
        T.eq("缺少 ok -> null", null, ActionResult.fromJson(noOk));
        Map<String, Object> badWarnings = ActionResult.ok("device.info", "android",
                args("model", "m")).toJson();
        badWarnings.put("warnings", "not-a-list");
        ActionResult lenient = ActionResult.fromJson(badWarnings);
        T.ok("warnings 类型错误不抛异常", lenient != null);
        T.eq("warnings 类型错误按空处理", 0, lenient.warnings.size());

        Map<String, Object> secretResult = args("value", "visible");
        secretResult.put("token", "tok-should-not-appear");
        secretResult.put("apiKey", "ak-should-not-appear");
        secretResult.put("context", "ctx-should-not-appear");
        secretResult.put("nested", args("apiKey", "nested-secret", "keep", "ok"));
        secretResult.put("list", list(args("password", "pw-secret", "visible", "kept")));
        ActionResult secret = ActionResult.ok("device.info", "android", secretResult);
        String secretJson = secret.toJsonString();
        T.ok("序列化不含 token 值", !secretJson.contains("tok-should-not-appear"));
        T.ok("序列化不含 apiKey 值", !secretJson.contains("ak-should-not-appear"));
        T.ok("序列化不含 context 值", !secretJson.contains("ctx-should-not-appear"));
        T.ok("序列化不含嵌套敏感值", !secretJson.contains("nested-secret"));
        T.ok("序列化不含列表内敏感值", !secretJson.contains("pw-secret"));
        T.ok("序列化保留普通结果", secretJson.contains("visible"));
        T.ok("序列化保留嵌套普通结果", secretJson.contains("keep"));
        ActionResult decodedSecret = ActionResult.fromJson(secret.toJson());
        T.ok("敏感键反序列化后不存在", !decodedSecret.result.containsKey("token")
                && !decodedSecret.result.containsKey("apiKey")
                && !decodedSecret.result.containsKey("context"));
        T.eq("普通结果仍可 round-trip", "visible", decodedSecret.result.get("value"));
    }

    // ------------------------------------------------------------- 快照/恢复

    private static void snapshotRestore() {
        Clock clock = new Clock();
        IdempotencyStore store = new IdempotencyStore(TTL, 10, clock);
        Map<String, Object> args1 = args("title", "a");
        ActionResult ok = new ActionResult(true, "notification.send", "android",
                args("notificationId", "n1"), names("w1"), null, 5L, "req-a", "aud-a");
        ActionResult fail = ActionResult.failure("app.open", "root",
                AgentError.of(AgentError.Code.TIMEOUT, "超时"));

        store.put("k1", "notification.send", args1, ok);
        store.put("k2", "app.open", args("package", "com.x"), fail);
        String snap = store.snapshotJson();
        T.ok("快照含 version", snap.contains("\"version\":1"));
        T.ok("快照含 entries", snap.contains("\"entries\":["));
        T.ok("快照含 key", snap.contains("\"key\":\"k1\""));
        T.ok("快照含 action", snap.contains("\"action\":\"notification.send\""));
        T.ok("快照含 argHash", snap.contains("\"argHash\":\""));
        T.ok("快照含 expiresAt", snap.contains("\"expiresAt\":"));
        T.ok("快照含结果正文", snap.contains("\"notificationId\":\"n1\""));
        T.ok("快照不含 token 字样", !snap.contains("token"));
        T.ok("快照不含 apiKey 字样", !snap.contains("apiKey"));
        T.ok("快照不含 context 字样", !snap.contains("context"));

        IdempotencyStore loaded = new IdempotencyStore(TTL, 10, clock);
        loaded.restoreJson(snap);
        T.eq("恢复后 size", 2, loaded.size());
        T.eq("恢复无错误", "", loaded.lastRestoreError());
        IdempotencyStore.Lookup hit = loaded.check("k1", "notification.send", args1);
        T.eq("恢复后命中", IdempotencyStore.Status.HIT, hit.status);
        T.eq("恢复后结果值", "n1", hit.result.result.get("notificationId"));
        T.eq("恢复后 executor", "android", hit.result.executor);
        T.eq("恢复后 warnings", "w1", hit.result.warnings.get(0));
        T.eq("恢复后 elapsedMs", 5L, hit.result.elapsedMs);
        T.eq("恢复后 requestId", "req-a", hit.result.requestId);
        T.eq("恢复后 auditId", "aud-a", hit.result.auditId);
        IdempotencyStore.Lookup failed = loaded.check("k2", "app.open",
                args("package", "com.x"));
        T.eq("失败结果也可恢复", IdempotencyStore.Status.HIT, failed.status);
        T.eq("恢复后失败错误码", "TIMEOUT", failed.result.error.code.wire);
        T.eq("恢复后失败文案", "超时", failed.result.error.message);
        T.eq("同 key 不同参数 -> CONFLICT", IdempotencyStore.Status.CONFLICT,
                loaded.check("k1", "notification.send", args("title", "b")).status);
        T.eq("同 key 不同 action -> CONFLICT", IdempotencyStore.Status.CONFLICT,
                loaded.check("k1", "device.info", args1).status);

        CountingPersistence cp = new CountingPersistence();
        IdempotencyStore restoredWithSink = new IdempotencyStore(TTL, 10, clock);
        restoredWithSink.setPersistence(cp);
        restoredWithSink.restoreJson(snap);
        T.eq("恢复不触发持久化回调", 0, cp.count);
        T.eq("恢复后 size 可供诊断", 2, restoredWithSink.size());
        T.eq("恢复后 size 不触发写盘", 0, cp.count);

        // 过期条目不恢复。
        clock.advance(TTL + 1L);
        IdempotencyStore expired = new IdempotencyStore(TTL, 10, clock);
        expired.restoreJson(snap);
        T.eq("过期条目不恢复", 0, expired.size());
        T.eq("过期恢复不报错", "", expired.lastRestoreError());
        T.eq("过期 key 仍 MISS", IdempotencyStore.Status.MISS,
                expired.check("k1", "notification.send", args1).status);

        // 坏 JSON / 错误结构：按空缓存继续，不抛异常。
        IdempotencyStore corrupt = new IdempotencyStore(TTL, 10, clock);
        corrupt.restoreJson("{not-json");
        T.eq("坏 JSON -> 空缓存", 0, corrupt.size());
        T.ok("坏 JSON 记录错误", corrupt.lastRestoreError().length() > 0);
        corrupt.restoreJson("[]");
        T.ok("非对象快照记录错误", corrupt.lastRestoreError().length() > 0);
        corrupt.restoreJson("{\"version\":1}");
        T.ok("缺少 entries 记录错误", corrupt.lastRestoreError().length() > 0);
        corrupt.restoreJson("");
        T.eq("空输入清空缓存", 0, corrupt.size());
        T.eq("空输入清除错误", "", corrupt.lastRestoreError());

        // 单条坏数据忽略，好条目保留。
        Map<String, Object> badEntry = entry("bad", "device.info",
                CanonicalArgs.hash(args("i", "x")), clock.now + TTL, ok);
        badEntry.remove("argHash");
        String mixed = manualSnapshot(
                entry("k3", "notification.send", CanonicalArgs.hash(args("i", "x")),
                        clock.now + TTL, ok),
                "not-an-entry",
                badEntry);
        IdempotencyStore partial = new IdempotencyStore(TTL, 10, clock);
        partial.restoreJson(mixed);
        T.eq("坏条目被忽略，好条目保留", 1, partial.size());
        T.eq("保留条目可命中", IdempotencyStore.Status.HIT,
                partial.check("k3", "notification.send", args("i", "x")).status);
        T.eq("忽略坏条目不记为整体损坏", "", partial.lastRestoreError());

        // 容量上限：保留列表末尾（最新）的条目。
        Map<String, Object> common = args("i", "x");
        String hash = CanonicalArgs.hash(common);
        List<Object> many = new ArrayList<Object>();
        for (int i = 0; i < 5; i++) {
            many.add(entry("c" + i, "notification.send", hash,
                    clock.now + TTL, ok));
        }
        String big = manualSnapshot(many.toArray());
        IdempotencyStore small = new IdempotencyStore(TTL, 3, clock);
        small.restoreJson(big);
        T.eq("恢复遵守容量上限", 3, small.size());
        T.eq("保留最新条目 c4", IdempotencyStore.Status.HIT,
                small.check("c4", "notification.send", common).status);
        T.eq("保留最新条目 c2", IdempotencyStore.Status.HIT,
                small.check("c2", "notification.send", common).status);
        T.eq("丢弃最旧条目 c0", IdempotencyStore.Status.MISS,
                small.check("c0", "notification.send", common).status);
        T.eq("丢弃最旧条目 c1", IdempotencyStore.Status.MISS,
                small.check("c1", "notification.send", common).status);

        // snapshotJson 只导出未过期项。
        Clock clock2 = new Clock();
        IdempotencyStore timed = new IdempotencyStore(TTL, 10, clock2);
        timed.put("old", "notification.send", common, ok);
        clock2.advance(TTL + 1L);
        T.ok("snapshotJson 丢弃过期条目", timed.snapshotJson().contains("\"entries\":[]"));
    }

    // ------------------------------------------------------------- 持久化回调

    private static void persistenceCallback() {
        Clock clock = new Clock();
        CountingPersistence p = new CountingPersistence();
        IdempotencyStore store = new IdempotencyStore(TTL, 2, clock);
        store.restoreJson("");
        store.setPersistence(p);
        T.eq("设置回调本身不写盘", 0, p.count);

        Map<String, Object> a = args("title", "t");
        Map<String, Object> b = args("title", "u");
        ActionResult ok = ActionResult.ok("notification.send", "android",
                args("notificationId", "n1"));

        store.put("k1", "notification.send", a, ok);
        T.eq("put 触发一次持久化", 1, p.count);
        T.eq("回调内容 = snapshotJson", store.snapshotJson(), p.last);
        T.ok("回调内容含 key", p.last.contains("\"key\":\"k1\""));
        T.ok("回调内容含结果正文", p.last.contains("\"notificationId\":\"n1\""));

        store.put("k1", "notification.send", a, ok);
        T.eq("相同内容不重复写盘", 1, p.count);
        clock.advance(1_000L);
        store.put("k1", "notification.send", a, ok);
        T.eq("expiresAt 变化触发写盘", 2, p.count);

        store.put("k2", "notification.send", b, ok);
        T.eq("新增 key 触发写盘", 3, p.count);
        T.eq("容量上限 2", 2, store.size());
        T.eq("size 未清理过期项时不写盘", 3, p.count);

        store.put("k3", "notification.send", b, ok);
        T.eq("淘汰旧条目触发写盘", 4, p.count);
        T.eq("淘汰后仍是 2 条", 2, store.size());
        T.eq("淘汰最旧 k1", IdempotencyStore.Status.MISS,
                store.check("k1", "notification.send", a).status);
        T.eq("命中不写盘", 4, p.count);

        clock.advance(TTL + 1L);
        T.eq("过期后 size=0", 0, store.size());
        T.eq("过期清理触发写盘", 5, p.count);
        T.ok("清理后快照为空", p.last.contains("\"entries\":[]"));

        store.clear();
        T.eq("空缓存 clear 不写盘", 5, p.count);
        store.put("k4", "notification.send", a, ok);
        T.eq("clear 后 put 写盘", 6, p.count);
        store.clear();
        T.eq("非空 clear 触发写盘", 7, p.count);
        T.ok("clear 写入空 entries", p.last.contains("\"entries\":[]"));

        store.setPersistence(null);
        store.put("k5", "notification.send", a, ok);
        T.eq("关闭持久化后不再写盘", 7, p.count);

        // 恢复流程先 restore 再 setPersistence（生产顺序）也不应写盘。
        CountingPersistence cp2 = new CountingPersistence();
        IdempotencyStore restored = new IdempotencyStore(TTL, 10, clock);
        String snap = store.snapshotJson();
        restored.restoreJson(snap);
        restored.setPersistence(cp2);
        T.eq("恢复后设置回调不写盘", 0, cp2.count);
        T.eq("恢复后查询正常", IdempotencyStore.Status.HIT,
                restored.check("k5", "notification.send", a).status);
        T.eq("恢复后纯查询不写盘", 0, cp2.count);

        // 坏文件恢复不清空后误写；回调保持 0。
        IdempotencyStore broken = new IdempotencyStore(TTL, 10, clock);
        CountingPersistence cp3 = new CountingPersistence();
        broken.setPersistence(cp3);
        broken.restoreJson("{\"entries\":");
        T.ok("坏文件记录错误", broken.lastRestoreError().length() > 0);
        T.eq("坏文件恢复不触发写盘", 0, cp3.count);

        // 持久化回调抛异常不影响 put / check。
        IdempotencyStore boom = new IdempotencyStore(TTL, 2, clock);
        boom.setPersistence(new IdempotencyStore.Persistence() {
            @Override
            public void save(String json) {
                throw new IllegalStateException("disk full");
            }
        });
        boolean threw = false;
        try {
            boom.put("k1", "notification.send", a, ok);
        } catch (Throwable t) {
            threw = true;
        }
        T.ok("持久化异常不冒泡到 put", !threw);
        T.eq("持久化异常后内存仍命中", IdempotencyStore.Status.HIT,
                boom.check("k1", "notification.send", a).status);

        // 回调内读 snapshotJson 不会死锁/递归写。
        final boolean[] reentrantOk = new boolean[1];
        final IdempotencyStore reentrant = new IdempotencyStore(TTL, 2, clock);
        reentrant.setPersistence(new IdempotencyStore.Persistence() {
            @Override
            public void save(String json) {
                String now = reentrant.snapshotJson();
                reentrantOk[0] = now.equals(json);
            }
        });
        reentrant.put("r1", "notification.send", a, ok);
        T.ok("回调内 snapshotJson 安全且内容一致", reentrantOk[0]);
    }

    // ------------------------------------------------------------- 测试夹具

    private static final class Clock implements IdempotencyStore.Now {
        long now = 1_700_000_000_000L;

        @Override
        public long nowMs() {
            return now;
        }

        void advance(long ms) {
            now += ms;
        }
    }

    private static final class CountingPersistence implements IdempotencyStore.Persistence {
        int count;
        String last = "";

        @Override
        public void save(String json) {
            count++;
            last = json;
        }
    }

    private static Map<String, Object> entry(String key, String action, String argHash,
                                             long expiresAt, ActionResult result) {
        Map<String, Object> e = Json.obj();
        e.put("key", key);
        e.put("action", action);
        e.put("argHash", argHash);
        e.put("expiresAt", expiresAt);
        e.put("result", result.toJson());
        return e;
    }

    private static String manualSnapshot(Object... entries) {
        Map<String, Object> root = Json.obj();
        root.put("version", (long) IdempotencyStore.SNAPSHOT_VERSION);
        List<Object> list = Json.arr();
        if (entries != null) {
            for (Object e : entries) list.add(e);
        }
        root.put("entries", list);
        return Json.write(root);
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = Json.obj();
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                m.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return m;
    }

    private static List<Object> list(Object... values) {
        List<Object> l = Json.arr();
        if (values != null) {
            for (Object v : values) l.add(v);
        }
        return l;
    }

    private static List<String> names(String... values) {
        List<String> l = new ArrayList<String>();
        if (values != null) {
            for (String v : values) l.add(v);
        }
        return l;
    }
}
