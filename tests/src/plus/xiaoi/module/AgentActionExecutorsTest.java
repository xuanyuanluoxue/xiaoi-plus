package plus.xiaoi.module;

import java.util.List;
import java.util.Map;

import plus.xiaoi.module.agent.ActionDescriptor;
import plus.xiaoi.module.agent.ActionRegistry;
import plus.xiaoi.module.agent.ActionRequest;
import plus.xiaoi.module.agent.ActionResult;
import plus.xiaoi.module.agent.Actions;
import plus.xiaoi.module.agent.AgentError;
import plus.xiaoi.module.agent.FallbackExecutor;
import plus.xiaoi.module.agent.RootCommands;

/**
 * WP1.2b 纯 JVM 测试：5 个动作的注册/能力表、FallbackExecutor 回退策略、
 * root 命令构造与包名白名单、root 结果映射、4 个新动作的 schema 边界。
 *
 * <p>AndroidExecutor / RootExecutor 依赖 android 或真实 su，不进入 tests/run.sh；
 * 这里只覆盖能脱离设备验证的纯逻辑，真机行为见 docs/agent/PHASE1.md。</p>
 */
public final class AgentActionExecutorsTest {

    private AgentActionExecutorsTest() {}

    public static int run() throws Exception {
        capabilitiesWithAllExecutors();
        fallbackBehaviour();
        rootCommandConstruction();
        rootResultMapping();
        actionArgumentBounds();
        return T.report("AgentActionExecutorsTest");
    }

    // ------------------------------------------------------------- 能力表

    private static void capabilitiesWithAllExecutors() {
        ActionRegistry reg = serviceLikeRegistry();

        List<Object> caps = reg.capabilitiesJson(true);
        T.eq("WP1.2b capabilities 5 个动作", 5, caps.size());

        String[] names = {"app.open", "device.info", "alarm.set",
                "notification.send", "media.control"};
        for (String name : names) {
            Map<String, Object> cap = cap(caps, name);
            T.ok(name + " 出现在能力表", cap != null);
            T.eq(name + " available=true", Boolean.TRUE, cap.get("available"));
        }

        T.eq("app.open 执行器", 1,
                executors(caps, "app.open").size());
        T.eq("app.open 执行器名", "android+root",
                executorName(caps, "app.open", 0));
        T.eq("media.control 执行器名", "android+root",
                executorName(caps, "media.control", 0));
        T.eq("device.info 执行器名", "android",
                executorName(caps, "device.info", 0));
        T.eq("alarm.set 执行器名", "android",
                executorName(caps, "alarm.set", 0));
        T.eq("notification.send 执行器名", "android",
                executorName(caps, "notification.send", 0));
        T.eq("全部可用时 includeUnavailable=false 仍 5 个", 5,
                reg.capabilitiesJson(false).size());

        // 5 个动作都能通过统一入口成功（notification 需要确认 token）。
        T.eq("device.info 可执行", true,
                reg.execute(ActionRequest.builder("device.info").build()).ok);
        T.eq("app.open 可执行", true,
                reg.execute(ActionRequest.builder("app.open")
                        .arg("package", "com.tencent.mm").build()).ok);
        T.eq("alarm.set 可执行", true,
                reg.execute(ActionRequest.builder("alarm.set")
                        .arg("hour", 7L).arg("minute", 0L).build()).ok);
        T.eq("notification.send 需确认", AgentError.Code.NEEDS_CONFIRMATION,
                reg.execute(ActionRequest.builder("notification.send")
                        .arg("title", "t").arg("text", "x").build()).error.code);
        T.eq("notification.send 带 token 可执行", true,
                reg.execute(ActionRequest.builder("notification.send")
                        .arg("title", "t").arg("text", "x")
                        .confirmToken("stub").build()).ok);
        T.eq("media.control 可执行", true,
                reg.execute(ActionRequest.builder("media.control")
                        .arg("command", "volume").arg("volume", 40L).build()).ok);

        // 组合执行器在 cap 里显示自己的可用性，而不是把 android/root 拆开。
        FallbackExecutor bothOffline = new FallbackExecutor(
                offline("android"), offline("root"));
        T.eq("主执行器与 root 都不可用 -> available=false", false,
                bothOffline.available());
        T.eq("组合执行器名", "android+root", bothOffline.name());
    }

    // ------------------------------------------------------------- Fallback

    private static void fallbackBehaviour() throws Exception {
        // android NOT_AVAILABLE -> root 被调用一次，结果成功。
        FakeExecutor android = new FakeExecutor("android");
        android.response = notAvailable("app.open");
        FakeExecutor root = new FakeExecutor("root");
        FallbackExecutor fb = new FallbackExecutor(android, root);

        ActionResult res = fb.execute(ActionRequest.builder("app.open")
                .arg("package", "com.tencent.mm").build());
        T.eq("android NOT_AVAILABLE 时回退 root", 1, root.calls);
        T.eq("回退后结果成功", true, res.ok);
        T.eq("回退后结果执行器", "android+root", res.executor);
        T.ok("回退成功带 warning", res.warnings.size() > 0);
        T.ok("回退 warning 说明 root", contains(rootWarnings(res), "root"));

        // android 成功 -> root 不调用。
        FakeExecutor androidOk = new FakeExecutor("android");
        FakeExecutor root2 = new FakeExecutor("root");
        ActionResult ok = new FallbackExecutor(androidOk, root2)
                .execute(ActionRequest.builder("app.open").arg("package", "a.b").build());
        T.eq("android 成功不回退", 0, root2.calls);
        T.eq("android 成功结果", true, ok.ok);
        T.eq("android 成功保留内层执行器名", "android", ok.executor);

        // android INTERNAL（抛异常）-> 不回退。
        FakeExecutor boom = new FakeExecutor("android");
        boom.failure = new IllegalStateException("boom");
        FakeExecutor root3 = new FakeExecutor("root");
        boolean threw = false;
        try {
            new FallbackExecutor(boom, root3)
                    .execute(ActionRequest.builder("app.open").arg("package", "a.b").build());
        } catch (Exception expected) {
            threw = true;
        }
        T.ok("INTERNAL 异常上抛给 registry 映射", threw);
        T.eq("INTERNAL 不回退 root", 0, root3.calls);

        // 执行器直接返回 INTERNAL 结果（不抛异常）-> 也不回退。
        FakeExecutor internal = new FakeExecutor("android");
        internal.response = ActionResult.failure("media.control", "android",
                AgentError.of(AgentError.Code.INTERNAL, "boom"));
        FakeExecutor root3b = new FakeExecutor("root");
        ActionResult internalRes = new FallbackExecutor(internal, root3b)
                .execute(ActionRequest.builder("media.control")
                        .arg("command", "play").build());
        T.eq("INTERNAL 结果不回退", 0, root3b.calls);
        T.eq("INTERNAL 结果原样返回", AgentError.Code.INTERNAL,
                internalRes.error.code);

        // android PERMISSION_DENIED -> 不回退（避免重复副作用）。
        FakeExecutor denied = new FakeExecutor("android");
        denied.response = ActionResult.failure("notification.send", "android",
                AgentError.of(AgentError.Code.PERMISSION_DENIED, "no perm"));
        FakeExecutor root4 = new FakeExecutor("root");
        ActionResult deniedRes = new FallbackExecutor(denied, root4)
                .execute(ActionRequest.builder("notification.send")
                        .arg("title", "t").arg("text", "x").build());
        T.eq("PERMISSION_DENIED 不回退", 0, root4.calls);
        T.eq("PERMISSION_DENIED 原样返回", AgentError.Code.PERMISSION_DENIED,
                deniedRes.error.code);

        // 两个都 NOT_AVAILABLE -> 失败，且 warning 说明两边都试过。
        FakeExecutor android5 = new FakeExecutor("android");
        android5.response = notAvailable("media.control");
        FakeExecutor root5 = new FakeExecutor("root");
        root5.response = notAvailable("media.control");
        ActionResult both = new FallbackExecutor(android5, root5)
                .execute(ActionRequest.builder("media.control")
                        .arg("command", "play").build());
        T.eq("两边都不可用 -> ok=false", false, both.ok);
        T.eq("两边都不可用 -> NOT_AVAILABLE", AgentError.Code.NOT_AVAILABLE,
                both.error.code);
        T.eq("root 在 NOT_AVAILABLE 时被调用", 1, root5.calls);
        T.ok("两边失败 warning 说明 root", contains(both.warnings, "root"));

        // root 执行抛异常 -> 返回主执行器的 NOT_AVAILABLE，并说明 root 也失败。
        FakeExecutor android6 = new FakeExecutor("android");
        android6.response = notAvailable("app.open");
        FakeExecutor root6 = new FakeExecutor("root");
        root6.failure = new IllegalStateException("su missing");
        boolean threw6 = false;
        ActionResult rootThrew = null;
        try {
            rootThrew = new FallbackExecutor(android6, root6)
                    .execute(ActionRequest.builder("app.open").arg("package", "a.b").build());
        } catch (Exception expected) {
            threw6 = true;
        }
        T.eq("root 异常不向调用方抛", false, threw6);
        T.ok("root 异常返回失败结果", rootThrew != null && !rootThrew.ok);
        T.eq("root 异常返回 NOT_AVAILABLE", AgentError.Code.NOT_AVAILABLE,
                rootThrew == null || rootThrew.error == null ? null : rootThrew.error.code);
        T.ok("root 异常 warning 说明 root", rootThrew != null
                && contains(rootThrew.warnings, "root"));

        // 可用性：只要一侧可用，组合执行器就可用。
        T.eq("android 可用 -> 组合可用", true,
                new FallbackExecutor(online("android"), offline("root")).available());
        T.eq("仅 root 可用 -> 组合可用", true,
                new FallbackExecutor(offline("android"), online("root")).available());

        // registry 集成：app.open 走组合执行器，执行器名保持 android+root。
        ActionRegistry reg = serviceLikeRegistry();
        ActionResult viaRegistry = reg.execute(ActionRequest.builder("app.open")
                .arg("package", "com.tencent.mm").build());
        T.eq("registry 走组合执行器", "android+root", viaRegistry.executor);
        T.eq("registry app.open 成功", true, viaRegistry.ok);
    }

    // ------------------------------------------------------------- root 命令

    private static void rootCommandConstruction() {
        T.eq("root 超时 3s", 3000L, RootCommands.TIMEOUT_MS);
        T.eq("root 输出上限", 1024, RootCommands.MAX_OUTPUT_CHARS);

        T.ok("包名 com.tencent.mm 合法", RootCommands.validPackage("com.tencent.mm"));
        T.ok("包名下划线/数字合法", RootCommands.validPackage("com.a_b.c9"));
        T.ok("单段包名合法", RootCommands.validPackage("settings"));
        T.ok("null 包名非法", !RootCommands.validPackage(null));
        T.ok("空包名非法", !RootCommands.validPackage(""));
        T.ok("空白包名非法", !RootCommands.validPackage("   "));
        T.ok("分号注入非法", !RootCommands.validPackage("com.a;id"));
        T.ok("空格注入非法", !RootCommands.validPackage("com.a b"));
        T.ok("换行注入非法", !RootCommands.validPackage("com.a\nid"));
        T.ok("路径穿越非法", !RootCommands.validPackage("com.a/../b"));
        T.ok("命令替换非法", !RootCommands.validPackage("$(id)"));
        T.ok("反引号非法", !RootCommands.validPackage("`id`"));
        T.ok("引号非法", !RootCommands.validPackage("com.a\"b"));
        T.ok("&& 非法", !RootCommands.validPackage("com.a&&id"));
        T.ok("管道非法", !RootCommands.validPackage("com.a|id"));
        T.ok("超长包名非法", !RootCommands.validPackage(repeat('a', 201)));
        T.ok("200 长度包名合法", RootCommands.validPackage(repeat('a', 200)));

        T.eq("app.open root 命令固定",
                "monkey -p com.tencent.mm -c android.intent.category.LAUNCHER 1",
                RootCommands.appOpenCommand("com.tencent.mm"));
        T.eq("非法包名不生成命令", null,
                RootCommands.appOpenCommand("com.a;id"));

        T.eq("media play 命令", "cmd media_session dispatch play",
                RootCommands.mediaCommand("play"));
        T.eq("media pause 命令", "cmd media_session dispatch pause",
                RootCommands.mediaCommand("pause"));
        T.eq("media next 命令", "cmd media_session dispatch next",
                RootCommands.mediaCommand("next"));
        T.eq("media prev 映射 previous", "cmd media_session dispatch previous",
                RootCommands.mediaCommand("prev"));
        T.eq("media 命令去空白", "cmd media_session dispatch play",
                RootCommands.mediaCommand(" play "));
        T.eq("root 不支持 volume", null, RootCommands.mediaCommand("volume"));
        T.eq("root 不支持 stop", null, RootCommands.mediaCommand("stop"));
        T.eq("root 不支持 null", null, RootCommands.mediaCommand(null));
        T.eq("root 拒绝注入命令", null, RootCommands.mediaCommand("play;id"));

        T.eq("clip(null) 为空", "", RootCommands.clip(null));
        String clipped = RootCommands.clip(repeat('x', 5000));
        T.eq("clip 截断到上限+省略号", RootCommands.MAX_OUTPUT_CHARS + 1, clipped.length());
        T.ok("clip 保留前缀", clipped.startsWith("xxxx"));
    }

    // ------------------------------------------------------------- root 结果

    private static void rootResultMapping() {
        T.eq("root 超时 -> TIMEOUT", AgentError.Code.TIMEOUT,
                code(RootCommands.evaluate(true, 0, "")));
        T.eq("TIMEOUT 可重试", true,
                RootCommands.evaluate(true, 0, "").retryable);
        T.eq("exit=0 无错误输出 -> 成功", null,
                RootCommands.evaluate(false, 0, ""));
        T.eq("exit=0 正常输出 -> 成功", null,
                RootCommands.evaluate(false, 0, "Events injected: 1"));
        T.eq("媒体派发成功 -> 成功", null,
                RootCommands.evaluate(false, 0, "sent media key event"));
        T.eq("非 0 退出 -> NOT_AVAILABLE", AgentError.Code.NOT_AVAILABLE,
                code(RootCommands.evaluate(false, 1, "")));
        T.eq("permission denied -> PERMISSION_DENIED", AgentError.Code.PERMISSION_DENIED,
                code(RootCommands.evaluate(false, 1, "su: permission denied")));
        T.eq("not allowed -> PERMISSION_DENIED", AgentError.Code.PERMISSION_DENIED,
                code(RootCommands.evaluate(false, 0, "Not allowed to su")));
        T.eq("no activities -> NOT_AVAILABLE", AgentError.Code.NOT_AVAILABLE,
                code(RootCommands.evaluate(false, 0,
                        "** No activities found to run, monkey aborted.")));
        T.eq("no active sessions -> NOT_AVAILABLE", AgentError.Code.NOT_AVAILABLE,
                code(RootCommands.evaluate(false, 0, "No active sessions")));
        T.eq("no active media session -> NOT_AVAILABLE", AgentError.Code.NOT_AVAILABLE,
                code(RootCommands.evaluate(false, 0, "No active media session")));
        T.ok("错误文案不含命令输出", !RootCommands.evaluate(false, 1, "secret")
                .message.contains("secret"));
    }

    // ------------------------------------------------------------- 参数边界

    private static void actionArgumentBounds() {
        ActionRegistry reg = serviceLikeRegistry();

        T.eq("app.open 超长 package 拒绝", AgentError.Code.INVALID_ARGUMENT,
                reg.execute(ActionRequest.builder("app.open")
                        .arg("package", repeat('a', 201)).build()).error.code);
        T.eq("app.open 超长 intentUri 拒绝", AgentError.Code.INVALID_ARGUMENT,
                reg.execute(ActionRequest.builder("app.open")
                        .arg("intentUri", "intent://" + repeat('a', 2049)).build()).error.code);
        T.eq("app.open 两个参数都接受", true,
                reg.execute(ActionRequest.builder("app.open")
                        .arg("package", "com.tencent.mm")
                        .arg("intentUri", "intent://scan/#Intent;end").build()).ok);

        T.eq("alarm 缺 minute 拒绝", AgentError.Code.INVALID_ARGUMENT,
                reg.execute(ActionRequest.builder("alarm.set")
                        .arg("hour", 7L).build()).error.code);
        T.eq("alarm label 超长拒绝", AgentError.Code.INVALID_ARGUMENT,
                reg.execute(ActionRequest.builder("alarm.set")
                        .arg("hour", 7L).arg("minute", 0L)
                        .arg("label", repeat('a', 201)).build()).error.code);
        T.eq("alarm skipUi 类型错拒绝", AgentError.Code.INVALID_ARGUMENT,
                reg.execute(ActionRequest.builder("alarm.set")
                        .arg("hour", 7L).arg("minute", 0L)
                        .arg("skipUi", "yes").build()).error.code);
        T.eq("alarm 未知参数拒绝", AgentError.Code.INVALID_ARGUMENT,
                reg.execute(ActionRequest.builder("alarm.set")
                        .arg("hour", 7L).arg("minute", 0L)
                        .arg("repeat", true).build()).error.code);

        T.eq("notification 缺 title 拒绝", AgentError.Code.INVALID_ARGUMENT,
                reg.execute(ActionRequest.builder("notification.send")
                        .arg("text", "x").build()).error.code);
        T.eq("notification title 超长拒绝", AgentError.Code.INVALID_ARGUMENT,
                reg.execute(ActionRequest.builder("notification.send")
                        .arg("title", repeat('a', 201)).arg("text", "x")
                        .confirmToken("stub").build()).error.code);
        T.eq("notification text 超长拒绝", AgentError.Code.INVALID_ARGUMENT,
                reg.execute(ActionRequest.builder("notification.send")
                        .arg("title", "t").arg("text", repeat('a', 4001))
                        .confirmToken("stub").build()).error.code);
        T.eq("notification id 超长拒绝", AgentError.Code.INVALID_ARGUMENT,
                reg.execute(ActionRequest.builder("notification.send")
                        .arg("title", "t").arg("text", "x")
                        .arg("id", repeat('a', 65))
                        .confirmToken("stub").build()).error.code);
        T.eq("notification 合法参数执行", true,
                reg.execute(ActionRequest.builder("notification.send")
                        .arg("title", "t").arg("text", "x")
                        .arg("id", "job_1").confirmToken("stub").build()).ok);

        T.eq("media 缺 command 拒绝", AgentError.Code.INVALID_ARGUMENT,
                reg.execute(ActionRequest.builder("media.control").build()).error.code);
        T.eq("media 大写命令拒绝", AgentError.Code.INVALID_ARGUMENT,
                reg.execute(ActionRequest.builder("media.control")
                        .arg("command", "PLAY").build()).error.code);
        T.eq("media volume 越界拒绝", AgentError.Code.INVALID_ARGUMENT,
                reg.execute(ActionRequest.builder("media.control")
                        .arg("command", "volume").arg("volume", 101L).build()).error.code);
        T.eq("media volume 负数拒绝", AgentError.Code.INVALID_ARGUMENT,
                reg.execute(ActionRequest.builder("media.control")
                        .arg("command", "volume").arg("volume", -1L).build()).error.code);
        T.eq("media volume 0 合法", true,
                reg.execute(ActionRequest.builder("media.control")
                        .arg("command", "volume").arg("volume", 0L).build()).ok);
        T.eq("media volume 100 合法", true,
                reg.execute(ActionRequest.builder("media.control")
                        .arg("command", "volume").arg("volume", 100L).build()).ok);
        T.eq("media volume 只读（不带 volume）合法", true,
                reg.execute(ActionRequest.builder("media.control")
                        .arg("command", "volume").build()).ok);
        T.eq("media play 合法", true,
                reg.execute(ActionRequest.builder("media.control")
                        .arg("command", "play").build()).ok);
        T.eq("media 未知参数拒绝", AgentError.Code.INVALID_ARGUMENT,
                reg.execute(ActionRequest.builder("media.control")
                        .arg("command", "play").arg("bogus", 1L).build()).error.code);
    }

    // ------------------------------------------------------------- 辅助

    /** 与 AgentService 相同的注册结构，但执行器是 fake（可观测调用）。 */
    private static ActionRegistry serviceLikeRegistry() {
        ActionRegistry reg = new ActionRegistry();
        FakeExecutor android = new FakeExecutor("android");
        FakeExecutor fallback = new FakeExecutor("android+root");
        for (ActionDescriptor d : Actions.builtins()) reg.register(d, null);
        reg.register(Actions.deviceInfo(), android);
        reg.register(Actions.alarmSet(), android);
        reg.register(Actions.notificationSend(), android);
        reg.register(Actions.appOpen(), fallback);
        reg.register(Actions.mediaControl(), fallback);
        return reg;
    }

    private static FakeExecutor online(String name) {
        return new FakeExecutor(name);
    }

    private static FakeExecutor offline(String name) {
        FakeExecutor f = new FakeExecutor(name);
        f.available = false;
        return f;
    }

    private static ActionResult notAvailable(String action) {
        return ActionResult.failure(action, "android",
                AgentError.of(AgentError.Code.NOT_AVAILABLE, "no backend"));
    }

    private static AgentError.Code code(AgentError error) {
        return error == null ? null : error.code;
    }

    private static boolean contains(List<String> values, String needle) {
        for (String v : values) {
            if (v != null && v.contains(needle)) return true;
        }
        return false;
    }

    private static List<String> rootWarnings(ActionResult r) {
        return r.warnings;
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

    private static Map<String, Object> cap(List<Object> caps, String name) {
        for (Object o : caps) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            if (name.equals(m.get("name"))) return castMap(m);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static List<?> executors(List<Object> caps, String name) {
        Map<String, Object> c = cap(caps, name);
        Object v = c == null ? null : c.get("executors");
        return v instanceof List ? (List<?>) v : Json.arr();
    }

    private static String executorName(List<Object> caps, String name, int index) {
        List<?> list = executors(caps, name);
        if (index >= list.size()) return null;
        Object o = list.get(index);
        if (!(o instanceof Map)) return null;
        Object n = ((Map<?, ?>) o).get("name");
        return n == null ? null : String.valueOf(n);
    }
}
