package plus.xiaoi.module;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import plus.xiaoi.module.agent.ActionDescriptor;
import plus.xiaoi.module.agent.ActionRegistry;
import plus.xiaoi.module.agent.ActionRequest;
import plus.xiaoi.module.agent.ActionResult;
import plus.xiaoi.module.agent.ActionRisk;
import plus.xiaoi.module.agent.AgentError;
import plus.xiaoi.module.agent.AgentMcp;
import plus.xiaoi.module.agent.Actions;
import plus.xiaoi.module.agent.AuditLog;
import plus.xiaoi.module.agent.CanonicalArgs;
import plus.xiaoi.module.agent.ConfirmationManager;
import plus.xiaoi.module.agent.IdempotencyStore;

/**
 * WP1.3a 纯 JVM 测试：确认 token、幂等键、审计脱敏，以及 AgentMcp 的安全流程。
 *
 * <p>不依赖 Android，也不启动 HTTP；全部通过 {@link AgentMcp#handle} 的
 * tools/call 报文验证协议面行为。</p>
 */
public final class AgentSecurityTest {

    private static final String VERSION = "0.13.0-security-test";

    private AgentSecurityTest() {}

    public static int run() {
        canonicalArgs();
        confirmation();
        idempotency();
        auditRing();
        mcpSecurity();
        return T.report("AgentSecurityTest");
    }

    // ------------------------------------------------------------- canonical hash

    private static void canonicalArgs() {
        Map<String, Object> a = args("a", 1L, "b", "x");
        Map<String, Object> b = args("b", "x", "a", 1L);
        T.eq("canonical 键顺序不影响 hash", CanonicalArgs.hash(a), CanonicalArgs.hash(b));

        Map<String, Object> nested1 = args("outer", args("x", 1L, "y", 2L));
        Map<String, Object> nested2 = args("outer", args("y", 2L, "x", 1L));
        T.eq("canonical 递归排序", CanonicalArgs.hash(nested1), CanonicalArgs.hash(nested2));

        Map<String, Object> list1 = args("list", list("a", "b"));
        Map<String, Object> list2 = args("list", list("b", "a"));
        T.ok("canonical 数组保持顺序（不同 hash）",
                !CanonicalArgs.hash(list1).equals(CanonicalArgs.hash(list2)));

        T.eq("canonical 排除 token", CanonicalArgs.hash(args("a", 1L)),
                CanonicalArgs.hash(args("a", 1L, "token", "secret")));
        T.eq("canonical 排除 confirmToken", CanonicalArgs.hash(args("a", 1L)),
                CanonicalArgs.hash(args("a", 1L, "confirmToken", "secret")));
        T.eq("canonical 排除 apiKey", CanonicalArgs.hash(args("a", 1L)),
                CanonicalArgs.hash(args("a", 1L, "apiKey", "secret")));
        T.eq("canonical hash 长度 64", 64, CanonicalArgs.hash(a).length());
        T.eq("canonical shortHash 长度 16", 16, CanonicalArgs.shortHash(a).length());
        T.eq("null 与空参数 hash 一致", CanonicalArgs.hash(null),
                CanonicalArgs.hash(args()));
        T.ok("不同参数值 hash 不同",
                !CanonicalArgs.hash(args("a", 1L)).equals(CanonicalArgs.hash(args("a", 2L))));
        T.ok("敏感键判定大小写不敏感", CanonicalArgs.isSensitive("APIKEY")
                && CanonicalArgs.isSensitive("confirmToken"));
        T.ok("普通键不是敏感键", !CanonicalArgs.isSensitive("title"));
    }

    // ------------------------------------------------------------- confirmation

    private static void confirmation() {
        Clock clock = new Clock();
        ConfirmationManager cm = new ConfirmationManager(60_000L, clock);
        ActionRequest r = request("notification.send",
                args("title", "t", "text", "body"), "clientA");

        ConfirmationManager.Issue issue = cm.issue(r);
        T.eq("token 长度 = 24 字节 hex", ConfirmationManager.TOKEN_BYTES * 2,
                issue.token.length());
        T.eq("默认 TTL 60 秒", 60_000L, issue.expiresInMs);
        T.eq("ttlMs() 暴露 TTL", 60_000L, cm.ttlMs());

        ConfirmationManager.Result ok = cm.consume(issue.token, r);
        T.ok("consume 成功", ok.ok);
        T.eq("consume 返回 pending action", "notification.send", ok.request.action);
        T.eq("重复 consume -> CONFLICT", "CONFLICT",
                cm.consume(issue.token, r).code());
        T.eq("已用 token takeApproved -> CONFLICT", "CONFLICT",
                cm.takeApproved(issue.token).code());

        ConfirmationManager.Issue issue2 = cm.issue(r);
        ConfirmationManager.Result approved = cm.takeApproved(issue2.token);
        T.ok("takeApproved 成功", approved.ok);
        T.eq("takeApproved 返回 pending action", "notification.send", approved.request.action);
        T.eq("takeApproved 后 consume -> CONFLICT", "CONFLICT",
                cm.consume(issue2.token, r).code());
        T.eq("takeApproved 一次性（再次批准）", "CONFLICT",
                cm.takeApproved(issue2.token).code());

        ConfirmationManager.Issue issue3 = cm.issue(r);
        ConfirmationManager.Result denied = cm.deny(issue3.token);
        T.eq("deny 返回 CONFIRMATION_DENIED", "CONFIRMATION_DENIED", denied.code());
        T.ok("deny 带 pending 请求供审计", denied.request != null);
        T.eq("deny 返回 pending action", "notification.send", denied.request.action);
        T.eq("deny 后 consume -> CONFIRMATION_DENIED", "CONFIRMATION_DENIED",
                cm.consume(issue3.token, r).code());
        T.eq("重复 deny -> CONFIRMATION_DENIED", "CONFIRMATION_DENIED",
                cm.deny(issue3.token).code());

        T.eq("未知 token -> CONFIRMATION_DENIED", "CONFIRMATION_DENIED",
                cm.consume("nope", r).code());
        T.eq("空 token -> CONFIRMATION_DENIED", "CONFIRMATION_DENIED",
                cm.consume("", r).code());

        ConfirmationManager.Issue issue4 = cm.issue(r);
        ActionRequest otherClient = request("notification.send",
                args("title", "t", "text", "body"), "clientB");
        T.eq("错 clientId -> CONFIRMATION_DENIED", "CONFIRMATION_DENIED",
                cm.consume(issue4.token, otherClient).code());
        T.ok("错 clientId 后正确 client 仍可用", cm.consume(issue4.token, r).ok);

        ActionRequest noClient = request("notification.send",
                args("title", "t", "text", "body"), "");
        ConfirmationManager.Issue issue5 = cm.issue(noClient);
        T.ok("空 clientId 归一化为 default，可消费",
                cm.consume(issue5.token, request("notification.send",
                        args("title", "t", "text", "body"), "default")).ok);
        ConfirmationManager.Issue issue6 = cm.issue(noClient);
        T.eq("default 与显式 client 不混用", "CONFIRMATION_DENIED",
                cm.consume(issue6.token, request("notification.send",
                        args("title", "t", "text", "body"), "clientA")).code());

        ConfirmationManager.Issue issue7 = cm.issue(r);
        T.eq("错 action -> CONFIRMATION_DENIED", "CONFIRMATION_DENIED",
                cm.consume(issue7.token, request("device.info", args(), "clientA")).code());
        ConfirmationManager.Issue issue8 = cm.issue(r);
        T.eq("错 args -> CONFIRMATION_DENIED", "CONFIRMATION_DENIED",
                cm.consume(issue8.token, request("notification.send",
                        args("title", "different", "text", "body"), "clientA")).code());

        ConfirmationManager.Issue issue9 = cm.issue(r);
        ActionRequest reordered = request("notification.send",
                args("text", "body", "title", "t"), "clientA");
        T.ok("args Map 顺序不影响确认绑定", cm.consume(issue9.token, reordered).ok);

        ConfirmationManager.Issue issue10 = cm.issue(r);
        clock.advance(60_001L);
        T.eq("过期 token -> CONFIRMATION_EXPIRED", "CONFIRMATION_EXPIRED",
                cm.consume(issue10.token, r).code());
        T.eq("过期项被清理", 0, cm.size());

        ConfirmationManager.Issue u1 = cm.issue(r);
        ConfirmationManager.Issue u2 = cm.issue(r);
        T.ok("每次签发 token 不同", !u1.token.equals(u2.token));

        ConfirmationManager shortLived = new ConfirmationManager(1_000L, clock);
        T.eq("自定义 TTL 生效", 1_000L, shortLived.issue(r).expiresInMs);

        clock.advance(10_000L);
        ConfirmationManager capped = new ConfirmationManager(60_000L, clock);
        String first = null;
        for (int i = 0; i < ConfirmationManager.MAX_PENDING + 10; i++) {
            ConfirmationManager.Issue x = capped.issue(r);
            if (first == null) first = x.token;
        }
        T.ok("pending 容量有上限", capped.size() <= ConfirmationManager.MAX_PENDING);
        T.eq("最早的 pending 被淘汰", "CONFIRMATION_DENIED",
                capped.consume(first, r).code());

        capped.clear();
        T.eq("clear 清空 pending", 0, capped.size());
    }

    // ------------------------------------------------------------- idempotency

    private static void idempotency() {
        Clock clock = new Clock();
        IdempotencyStore store = new IdempotencyStore(24L * 60L * 60L * 1000L, 3, clock);
        Map<String, Object> a = args("title", "t", "text", "x");
        ActionResult ok = ActionResult.ok("notification.send", "android",
                args("notificationId", "n1"));

        T.eq("空 key -> MISS", IdempotencyStore.Status.MISS,
                store.check("", "notification.send", a).status);
        T.eq("空白 key -> MISS", IdempotencyStore.Status.MISS,
                store.check("   ", "notification.send", a).status);
        T.eq("未知 key -> MISS", IdempotencyStore.Status.MISS,
                store.check("k1", "notification.send", a).status);

        store.put("k1", "notification.send", a, ok);
        IdempotencyStore.Lookup hit = store.check("k1", "notification.send", a);
        T.eq("已存 key -> HIT", IdempotencyStore.Status.HIT, hit.status);
        T.ok("命中返回缓存 ok", hit.result != null && hit.result.ok);
        T.eq("命中返回缓存结果值", "n1", hit.result.result.get("notificationId"));
        T.eq("同 key 不同 action -> CONFLICT", IdempotencyStore.Status.CONFLICT,
                store.check("k1", "device.info", a).status);
        T.eq("同 key 不同 args -> CONFLICT", IdempotencyStore.Status.CONFLICT,
                store.check("k1", "notification.send", args("title", "other")).status);

        store.put("k2", "notification.send", a, ok);
        T.eq("args 顺序不影响幂等命中", IdempotencyStore.Status.HIT,
                store.check("k2", "notification.send",
                        args("text", "x", "title", "t")).status);
        store.put("k3", "notification.send", a, ok);
        T.eq("confirmToken 不参与幂等 hash", IdempotencyStore.Status.HIT,
                store.check("k3", "notification.send",
                        args("title", "t", "text", "x", "confirmToken", "zzz")).status);

        IdempotencyStore.Lookup mutable = store.check("k2", "notification.send", a);
        IdempotencyStore.Lookup mutable2 = store.check("k2", "notification.send", a);
        T.ok("缓存命中返回结果副本",
                mutable.result != null && mutable2.result != null
                        && mutable.result.result != mutable2.result.result);
        T.eq("重复命中结果值一致", "n1", mutable.result.result.get("notificationId"));

        T.eq("默认 TTL 24 小时", IdempotencyStore.DEFAULT_TTL_MS,
                new IdempotencyStore().ttlMs());
        T.eq("默认容量 500", 500, IdempotencyStore.DEFAULT_CAPACITY);

        // 容量 3：依次写 k4/k5，最早 k1 应被淘汰。
        Map<String, Object> b = args("title", "t2");
        store.put("k4", "notification.send", b, ok);
        store.put("k5", "notification.send", b, ok);
        T.ok("容量上限生效", store.size() <= 3);
        T.eq("最久未使用的 key 被淘汰", IdempotencyStore.Status.MISS,
                store.check("k1", "notification.send", a).status);
        T.eq("新 key 仍命中", IdempotencyStore.Status.HIT,
                store.check("k5", "notification.send", b).status);

        ActionResult failure = ActionResult.failure("notification.send", "android",
                AgentError.of(AgentError.Code.INTERNAL, "boom"));
        store.put("fail", "notification.send", a, failure);
        IdempotencyStore.Lookup failed = store.check("fail", "notification.send", a);
        T.eq("失败结果也缓存", IdempotencyStore.Status.HIT, failed.status);
        T.ok("失败结果保持 ok=false", !failed.result.ok);
        T.eq("失败错误码保持", "INTERNAL", failed.result.error.code.wire);

        clock.advance(24L * 60L * 60L * 1000L + 1L);
        T.eq("过期 key -> MISS", IdempotencyStore.Status.MISS,
                store.check("k2", "notification.send", a).status);
        T.eq("过期项被清理", 0, store.size());

        store.clear();
        T.eq("clear 后 MISS", IdempotencyStore.Status.MISS,
                store.check("k5", "notification.send", b).status);
    }

    // ------------------------------------------------------------- audit ring

    private static void auditRing() {
        Clock clock = new Clock();
        AuditLog log = new AuditLog(3, clock);
        T.eq("审计默认容量 1000", 1000, AuditLog.DEFAULT_CAPACITY);
        T.eq("审计默认 limit 50", 50, AuditLog.DEFAULT_LIMIT);
        T.eq("审计最大 limit 200", 200, AuditLog.MAX_LIMIT);

        log.record("c1", "a1", "android", ActionRisk.CONFIRM,
                names("title", "text"), "0123456789abcdef", true, null, 5, "req1",
                AuditLog.EVENT_ACTION);
        clock.advance(1L);
        log.record("c2", "a2", "android", ActionRisk.SAFE,
                names("title", "command", "text"), "0123456789abcdee", false, "TIMEOUT", 9, "req2",
                AuditLog.EVENT_CONFIRM);
        clock.advance(1L);
        log.record("c3", "a3", "root", ActionRisk.DANGER,
                names("package"), "0123456789abcded", true, null, 12, "req3", AuditLog.EVENT_DENY);
        clock.advance(1L);
        log.record("c4", "a4", "", null, names(), "", true, null, 0, "req4",
                AuditLog.EVENT_REPLAY);

        T.eq("环形容量生效", 3, log.size());
        List<Object> recent = log.recent(3);
        T.eq("recent 返回 3 条", 3, recent.size());
        Map<?, ?> newest = (Map<?, ?>) recent.get(0);
        T.eq("最新在前：a4", "a4", newest.get("action"));
        T.eq("第二新：a3", "a3", ((Map<?, ?>) recent.get(1)).get("action"));
        T.eq("最旧保留：a2", "a2", ((Map<?, ?>) recent.get(2)).get("action"));

        Map<?, ?> e1 = (Map<?, ?>) recent.get(1);
        T.ok("审计 time 是数字", e1.get("time") instanceof Long);
        T.eq("审计 clientId", "c3", e1.get("clientId"));
        T.eq("审计 executor", "root", e1.get("executor"));
        T.eq("审计 risk", "danger", e1.get("risk"));
        T.eq("审计 argNames", "[package]", String.valueOf(e1.get("argNames")));
        T.eq("审计 ok", Boolean.TRUE, e1.get("ok"));
        T.eq("审计 errorCode 为空", null, e1.get("errorCode"));
        T.eq("审计 elapsedMs", 12L, e1.get("elapsedMs"));
        T.eq("审计 requestId", "req3", e1.get("requestId"));
        T.eq("审计 event", "deny", e1.get("event"));
        T.eq("审计 argHash 长度", 16, String.valueOf(e1.get("argHash")).length());
        T.ok("auditId 前缀", String.valueOf(e1.get("auditId")).startsWith("aud_"));

        Map<?, ?> e2 = (Map<?, ?>) log.recent(3).get(2);
        T.eq("失败审计 errorCode", "TIMEOUT", e2.get("errorCode"));
        T.eq("失败审计 ok=false", Boolean.FALSE, e2.get("ok"));
        T.eq("argNames 排序", "[command, text, title]",
                String.valueOf(e2.get("argNames")));

        String json = Json.write(log.recentJson(10));
        T.ok("recentJson 含 entries", json.contains("\"entries\""));
        T.ok("审计不含参数值明文", !json.contains("s3cr3t-value"));
        T.ok("审计不含 token", !json.contains("tok_should_not_appear"));
        T.ok("审计不含 context 正文", !json.contains("private-context"));

        T.eq("limit=1 只返回 1 条", 1, log.recent(1).size());
        T.eq("limit<=0 用默认 50", 3, log.recent(0).size());
        T.eq("limit 过大按容量截断", 3, log.recent(999).size());
        T.eq("clampLimit 上限 200", 200, AuditLog.clampLimit(500));
        T.eq("clampLimit 默认 50", 50, AuditLog.clampLimit(-1));
        T.eq("事件类型 action", "action", AuditLog.EVENT_ACTION);
        T.eq("事件类型 confirm", "confirm", AuditLog.EVENT_CONFIRM);
        T.eq("事件类型 deny", "deny", AuditLog.EVENT_DENY);
        T.eq("事件类型 replay", "replay", AuditLog.EVENT_REPLAY);

        log.clear();
        T.eq("审计 clear", 0, log.size());
        T.eq("审计 clear 后 recent 空", 0, log.recent(10).size());
    }

    // ------------------------------------------------------------- AgentMcp

    private static void mcpSecurity() {
        Clock clock = new Clock();
        ConfirmationManager cm = new ConfirmationManager(60_000L, clock);
        IdempotencyStore idem = new IdempotencyStore(
                IdempotencyStore.DEFAULT_TTL_MS, 100, clock);
        AuditLog log = new AuditLog(1000, clock);
        AgentMcp mcp = new AgentMcp(VERSION, cm, idem, log);
        Rig rig = rig();

        Map<String, Object> secrets = args("title", "s3cr3t-title", "text", "s3cr3t-body");
        Map<String, Object> first = action(mcp, rig.registry, "notification.send", secrets);
        T.eq("CONFIRM 无 token isError=true", Boolean.TRUE, first.get("isError"));
        Map<String, Object> firstEnv = envelope(first);
        T.eq("CONFIRM 无 token -> NEEDS_CONFIRMATION", "NEEDS_CONFIRMATION",
                firstEnv.get("code"));
        String token = Json.optString(firstEnv, "confirmToken", "");
        T.ok("返回 confirmToken", token.length() >= 32);
        T.eq("返回 expiresInMs", 60_000L, firstEnv.get("expiresInMs"));
        Map<?, ?> summary = (Map<?, ?>) firstEnv.get("summary");
        T.eq("summary.action", "notification.send", summary.get("action"));
        T.eq("summary.risk", "confirm", summary.get("risk"));
        T.ok("summary 只含参数名", String.valueOf(summary.get("argNames")).contains("title")
                && !String.valueOf(summary).contains("s3cr3t"));
        T.eq("签发确认不执行", 0, rig.notify.calls);

        Map<String, Object> denied = confirm(mcp, rig.registry, token, Boolean.FALSE);
        T.eq("deny isError=true", Boolean.TRUE, denied.get("isError"));
        T.eq("deny -> CONFIRMATION_DENIED", "CONFIRMATION_DENIED",
                envelope(denied).get("code"));
        T.eq("deny 不执行", 0, rig.notify.calls);
        T.eq("重复 deny -> CONFIRMATION_DENIED", "CONFIRMATION_DENIED",
                envelope(confirm(mcp, rig.registry, token, Boolean.FALSE)).get("code"));

        Map<String, Object> tokenEnv = actionEnvelope(mcp, rig.registry,
                "notification.send", secrets, null, null);
        String token2 = Json.optString(tokenEnv, "confirmToken", "");
        Map<String, Object> approved = confirm(mcp, rig.registry, token2, Boolean.TRUE);
        T.eq("approve isError=false", Boolean.FALSE, approved.get("isError"));
        T.eq("approve 执行一次", 1, rig.notify.calls);
        T.eq("approve 返回 ok", Boolean.TRUE, envelope(approved).get("ok"));
        Map<String, Object> replayApprove = confirm(mcp, rig.registry, token2, Boolean.TRUE);
        T.eq("同一 token 再次 approve -> CONFLICT", "CONFLICT",
                envelope(replayApprove).get("code"));
        T.eq("重复 approve 不重复执行", 1, rig.notify.calls);

        Map<String, Object> direct = actionEnvelope(mcp, rig.registry,
                "notification.send", secrets, token2, null);
        T.eq("已用 token 走 xiaoai_action -> CONFLICT", "CONFLICT", direct.get("code"));
        T.eq("已用 token 不执行", 1, rig.notify.calls);

        Map<String, Object> token3Env = actionEnvelope(mcp, rig.registry,
                "notification.send", secrets, null, null);
        String token3 = Json.optString(token3Env, "confirmToken", "");
        Map<String, Object> directOk = actionEnvelope(mcp, rig.registry,
                "notification.send", secrets, token3, null);
        T.eq("xiaoai_action 直接消费 token 成功", Boolean.TRUE, directOk.get("ok"));
        T.eq("直接消费只执行一次", 2, rig.notify.calls);
        T.eq("token 重放 -> CONFLICT", "CONFLICT", actionEnvelope(mcp, rig.registry,
                "notification.send", secrets, token3, null).get("code"));
        T.eq("token 重放不执行", 2, rig.notify.calls);

        Map<String, Object> token4Env = actionEnvelope(mcp, rig.registry,
                "notification.send", secrets, null, null);
        String token4 = Json.optString(token4Env, "confirmToken", "");
        confirm(mcp, rig.registry, token4, Boolean.FALSE);
        T.eq("拒绝后的 token 走 action -> CONFIRMATION_DENIED", "CONFIRMATION_DENIED",
                actionEnvelope(mcp, rig.registry, "notification.send",
                        secrets, token4, null).get("code"));
        T.eq("拒绝后不执行", 2, rig.notify.calls);

        // 错 clientId：绑定 token 的 client 才能消费。
        Map<String, Object> token5Env = actionEnvelope(mcp, rig.registry,
                "notification.send", secrets, null, "clientA");
        String token5 = Json.optString(token5Env, "confirmToken", "");
        T.eq("错 clientId 消费 -> CONFIRMATION_DENIED", "CONFIRMATION_DENIED",
                actionEnvelope(mcp, rig.registry, "notification.send",
                        secrets, token5, "clientB").get("code"));
        T.eq("错 clientId 不执行", 2, rig.notify.calls);
        T.eq("正确 clientId 可消费", Boolean.TRUE,
                actionEnvelope(mcp, rig.registry, "notification.send",
                        secrets, token5, "clientA").get("ok"));
        T.eq("正确 clientId 执行一次", 3, rig.notify.calls);

        // 过期：同一 clock 前进 60 秒。
        Map<String, Object> token6Env = actionEnvelope(mcp, rig.registry,
                "notification.send", secrets, null, null);
        String token6 = Json.optString(token6Env, "confirmToken", "");
        clock.advance(60_001L);
        T.eq("过期 token approve -> CONFIRMATION_EXPIRED", "CONFIRMATION_EXPIRED",
                envelope(confirm(mcp, rig.registry, token6, Boolean.TRUE)).get("code"));
        T.eq("过期不执行", 3, rig.notify.calls);

        // 幂等：Ssafe 动作重复 key 只执行一次。
        Map<String, Object> idemArgs = args("fields", list("model"));
        Map<String, Object> one = actionEnvelope(mcp, rig.registry,
                "device.info", idemArgs, null, "clientA", "idem-1", "req-1");
        T.eq("幂等首次 ok", Boolean.TRUE, one.get("ok"));
        T.eq("幂等首次执行", 1, rig.device.calls);
        Map<String, Object> two = actionEnvelope(mcp, rig.registry,
                "device.info", idemArgs, null, "clientA", "idem-1", "req-2");
        T.eq("幂等重复 ok", Boolean.TRUE, two.get("ok"));
        T.eq("幂等重复不执行", 1, rig.device.calls);
        T.eq("幂等重复返回首次 requestId", "req-1", two.get("requestId"));

        Map<String, Object> conflict = actionEnvelope(mcp, rig.registry,
                "device.info", args("fields", list("battery")), null, "clientA",
                "idem-1", "req-3");
        T.eq("同 key 不同 args -> CONFLICT", "CONFLICT", conflict.get("code"));
        T.eq("CONFLICT 不执行", 1, rig.device.calls);

        // CONFIRM 动作的幂等命中应在“再次弹确认”之前：同 key 重试直接返回首次结果。
        int notifyBefore = rig.notify.calls;
        Map<String, Object> idemConfirmFirst = actionEnvelope(mcp, rig.registry,
                "notification.send", secrets, null, null, "idem-confirm-1", "ic1");
        String icToken = Json.optString(idemConfirmFirst, "confirmToken", "");
        T.ok("CONFIRM 幂等首次发 token", icToken.length() >= 32);
        Map<String, Object> icApproved = confirm(mcp, rig.registry, icToken, Boolean.TRUE);
        T.eq("CONFIRM 幂等首次执行", notifyBefore + 1, rig.notify.calls);
        T.eq("CONFIRM 幂等首次成功", Boolean.TRUE, envelope(icApproved).get("ok"));
        Map<String, Object> idemConfirmSecond = actionEnvelope(mcp, rig.registry,
                "notification.send", secrets, null, null, "idem-confirm-1", "ic2");
        T.eq("CONFIRM 幂等重试直接成功", Boolean.TRUE, idemConfirmSecond.get("ok"));
        T.ok("CONFIRM 幂等重试不再发 token",
                !idemConfirmSecond.containsKey("confirmToken"));
        T.eq("CONFIRM 幂等重试不重复执行", notifyBefore + 1, rig.notify.calls);
        T.eq("CONFIRM 幂等重试返回首次 requestId", "ic1",
                idemConfirmSecond.get("requestId"));

        // 失败也进入幂等缓存，重试不重复副作用。
        Rig failing = failingRig();
        Map<String, Object> f1 = actionEnvelope(mcp, failing.registry,
                "test.fail", args(), null, null, "idem-fail", "f1");
        Map<String, Object> f2 = actionEnvelope(mcp, failing.registry,
                "test.fail", args(), null, null, "idem-fail", "f2");
        T.eq("失败首次错误码", "INTERNAL", f1.get("code"));
        T.eq("失败结果也缓存", "INTERNAL", f2.get("code"));
        T.eq("失败重试不重复执行", 1, failing.fail.calls);

        // DANGER 直接拒绝。
        Map<String, Object> danger = actionEnvelope(mcp, rig.registry,
                "test.danger", args(), null, null);
        T.eq("DANGER -> PERMISSION_DENIED", "PERMISSION_DENIED", danger.get("code"));
        T.eq("DANGER 不执行", 0, rig.danger.calls);

        // 参数错误不签发 token。
        Map<String, Object> invalid = actionEnvelope(mcp, rig.registry,
                "notification.send", args("title", "only-title"), null, null);
        T.eq("缺参数 -> INVALID_ARGUMENT", "INVALID_ARGUMENT", invalid.get("code"));
        T.ok("参数错误不返回 token", !invalid.containsKey("confirmToken"));

        // xiaoai_confirm 参数边界。
        T.eq("confirm 缺 token -> INVALID_ARGUMENT", "INVALID_ARGUMENT",
                envelope(confirm(mcp, rig.registry, "", Boolean.TRUE)).get("code"));
        T.eq("confirm approve 非 boolean -> INVALID_ARGUMENT", "INVALID_ARGUMENT",
                envelope(confirm(mcp, rig.registry, "whatever", "yes")).get("code"));
        T.eq("confirm 未知 token -> CONFIRMATION_DENIED", "CONFIRMATION_DENIED",
                envelope(confirm(mcp, rig.registry, "unknown-token", Boolean.TRUE)).get("code"));

        // 审计：事件类型齐全，且不含参数值/token。
        Map<String, Object> auditTool = call(mcp, rig.registry, "xiaoai_audit",
                args("limit", 200L));
        T.eq("audit isError=false", Boolean.FALSE, auditTool.get("isError"));
        String auditJson = toolText(auditTool);
        T.ok("审计含 entries", auditJson.contains("\"entries\""));
        T.ok("审计记录 action 事件", auditJson.contains("\"event\":\"action\""));
        T.ok("审计记录 confirm 事件", auditJson.contains("\"event\":\"confirm\""));
        T.ok("审计记录 deny 事件", auditJson.contains("\"event\":\"deny\""));
        T.ok("审计记录 replay 事件", auditJson.contains("\"event\":\"replay\""));
        T.ok("审计不含标题正文", !auditJson.contains("s3cr3t-title"));
        T.ok("审计不含通知正文", !auditJson.contains("s3cr3t-body"));
        T.ok("审计不含 confirm token", !auditJson.contains(token));
        T.ok("审计不含 apiKey 字段", !auditJson.contains("apiKey"));

        List<Object> entries = auditEntries(auditTool);
        T.ok("审计条目非空", entries.size() > 0);
        T.ok("审计条目字段完整", entries.get(0) instanceof Map
                && ((Map<?, ?>) entries.get(0)).containsKey("argNames")
                && ((Map<?, ?>) entries.get(0)).containsKey("event")
                && ((Map<?, ?>) entries.get(0)).containsKey("argHash"));

        Map<String, Object> oneEntry = call(mcp, rig.registry, "xiaoai_audit",
                args("limit", 1L));
        T.eq("audit limit=1", 1, auditEntries(oneEntry).size());
        Map<String, Object> defaultEntry = call(mcp, rig.registry, "xiaoai_audit", args());
        T.ok("audit 默认 limit 返回多条", auditEntries(defaultEntry).size() > 1);

        // 依赖缺失时保持兼容：旧构造无确认/审计。
        AgentMcp legacy = new AgentMcp(VERSION);
        Map<String, Object> legacyConfirm = call(legacy, rig.registry, "xiaoai_confirm",
                args("confirmToken", "x", "approve", Boolean.TRUE));
        T.eq("legacy confirm -> INTERNAL", "INTERNAL",
                envelope(legacyConfirm).get("code"));
        Map<String, Object> legacyAudit = call(legacy, rig.registry, "xiaoai_audit", args());
        T.eq("legacy audit -> INTERNAL", "INTERNAL", envelope(legacyAudit).get("code"));
        Map<String, Object> legacyAction = action(legacy, rig.registry,
                "notification.send", secrets);
        T.eq("legacy confirm 动作仍返回 NEEDS_CONFIRMATION", "NEEDS_CONFIRMATION",
                envelope(legacyAction).get("code"));
        T.ok("legacy 无 token（向后兼容）",
                !envelope(legacyAction).containsKey("confirmToken"));
    }

    // ------------------------------------------------------------- 测试夹具

    private static final class Clock
            implements ConfirmationManager.Now, IdempotencyStore.Now, AuditLog.Now {
        long now = 1_700_000_000_000L;

        @Override
        public long nowMs() {
            return now;
        }

        void advance(long ms) {
            now += ms;
        }
    }

    private static final class Rig {
        ActionRegistry registry;
        FakeExecutor device;
        FakeExecutor notify;
        FakeExecutor danger;
        FakeExecutor fail;
    }

    private static Rig rig() {
        Rig rig = new Rig();
        ActionRegistry reg = new ActionRegistry();
        for (ActionDescriptor d : Actions.builtins()) reg.register(d, null);
        rig.device = new FakeExecutor("android");
        reg.register(Actions.deviceInfo(), rig.device);
        rig.notify = new FakeExecutor("android");
        reg.register(Actions.notificationSend(), rig.notify);
        rig.danger = new FakeExecutor("android");
        reg.register(ActionDescriptor.builder("test.danger")
                .risk(ActionRisk.DANGER)
                .executorPreference("android")
                .build(), rig.danger);
        rig.registry = reg;
        return rig;
    }

    private static Rig failingRig() {
        Rig rig = new Rig();
        ActionRegistry reg = new ActionRegistry();
        rig.fail = new FakeExecutor("android");
        rig.fail.response = ActionResult.failure("test.fail", "android",
                AgentError.of(AgentError.Code.INTERNAL, "boom"));
        reg.register(ActionDescriptor.builder("test.fail")
                .executorPreference("android")
                .build(), rig.fail);
        rig.registry = reg;
        return rig;
    }

    private static ActionRequest request(String action, Map<String, Object> args,
                                         String clientId) {
        return ActionRequest.builder(action).args(args).clientId(clientId).build();
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

    private static Map<String, Object> action(AgentMcp mcp, ActionRegistry reg,
                                              String action, Map<String, Object> actionArgs) {
        return call(mcp, reg, "xiaoai_action",
                args("action", action, "args", actionArgs));
    }

    private static Map<String, Object> actionEnvelope(AgentMcp mcp, ActionRegistry reg,
                                                      String action,
                                                      Map<String, Object> actionArgs,
                                                      String confirmToken,
                                                      String clientId) {
        return actionEnvelope(mcp, reg, action, actionArgs, confirmToken, clientId,
                null, null);
    }

    private static Map<String, Object> actionEnvelope(AgentMcp mcp, ActionRegistry reg,
                                                      String action,
                                                      Map<String, Object> actionArgs,
                                                      String confirmToken,
                                                      String clientId,
                                                      String idempotencyKey,
                                                      String requestId) {
        Map<String, Object> arguments = args("action", action, "args", actionArgs);
        if (confirmToken != null) arguments.put("confirmToken", confirmToken);
        if (clientId != null) arguments.put("clientId", clientId);
        if (idempotencyKey != null) arguments.put("idempotencyKey", idempotencyKey);
        if (requestId != null) arguments.put("requestId", requestId);
        return envelope(call(mcp, reg, "xiaoai_action", arguments));
    }

    private static Map<String, Object> confirm(AgentMcp mcp, ActionRegistry reg,
                                               String token, Object approve) {
        return call(mcp, reg, "xiaoai_confirm",
                args("confirmToken", token, "approve", approve));
    }

    private static Map<String, Object> call(AgentMcp mcp, ActionRegistry reg,
                                            String tool, Map<String, Object> arguments) {
        Map<String, Object> params = Json.obj();
        params.put("name", tool);
        params.put("arguments", arguments);
        Map<String, Object> req = Json.obj();
        req.put("jsonrpc", "2.0");
        req.put("id", 1L);
        req.put("method", "tools/call");
        req.put("params", params);
        String response = mcp.handle(Json.write(req), reg);
        Map<?, ?> parsed = (Map<?, ?>) Json.read(response);
        return asMap(parsed.get("result"));
    }

    private static Map<String, Object> envelope(Map<String, Object> tool) {
        return asMap(Json.read(toolText(tool)));
    }

    private static String toolText(Map<String, Object> tool) {
        List<?> content = (List<?>) tool.get("content");
        Map<?, ?> part = (Map<?, ?>) content.get(0);
        return Json.optString(part, "text", "");
    }

    private static List<Object> auditEntries(Map<String, Object> tool) {
        Map<String, Object> env = envelope(tool);
        List<Object> out = new ArrayList<Object>();
        Object entries = env.get("entries");
        if (entries instanceof List) {
            for (Object o : (List<?>) entries) out.add(o);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : Json.obj();
    }
}
