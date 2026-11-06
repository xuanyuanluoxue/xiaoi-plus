package plus.xiaoi.module;

import java.util.List;
import java.util.Map;

import plus.xiaoi.module.agent.ActionDescriptor;
import plus.xiaoi.module.agent.ActionExecutor;
import plus.xiaoi.module.agent.ActionRegistry;
import plus.xiaoi.module.agent.ActionRequest;
import plus.xiaoi.module.agent.ActionResult;
import plus.xiaoi.module.agent.ActionRisk;
import plus.xiaoi.module.agent.Actions;
import plus.xiaoi.module.agent.AgentError;

/**
 * WP1.1 纯 JVM 契约测试：描述、capabilities、参数校验、dryRun、确认状态、
 * 执行器可用性、异常/超时映射、请求透传。
 */
public final class AgentActionRegistryTest {

    private AgentActionRegistryTest() {}

    public static int run() {
        descriptorsAndCapabilities();
        validationAndAlternatives();
        dryRunAndConfirmation();
        availabilityAndPermission();
        failureMapping();
        passthroughAndParsing();
        errorsAndEnvelopes();
        return T.report("AgentActionRegistryTest");
    }

    // ------------------------------------------------------------- 描述 / 能力表

    private static void descriptorsAndCapabilities() {
        ActionRegistry reg = registry(new FakeExecutor("android"));

        List<ActionDescriptor> all = reg.list();
        T.eq("内置动作数量", 5, all.size());
        T.eq("动作顺序 0", "app.open", all.get(0).name);
        T.eq("动作顺序 1", "device.info", all.get(1).name);
        T.eq("动作顺序 2", "alarm.set", all.get(2).name);
        T.eq("动作顺序 3", "notification.send", all.get(3).name);
        T.eq("动作顺序 4", "media.control", all.get(4).name);
        T.ok("find(app.open) 命中", reg.find("app.open") != null);
        T.eq("find(unknown) 返回 null", null, reg.find("no.such.action"));

        T.eq("app.open 风险", ActionRisk.SAFE, reg.find("app.open").risk);
        T.eq("notification.send 风险", ActionRisk.CONFIRM,
                reg.find("notification.send").risk);
        T.eq("notification.send 幂等", false, reg.find("notification.send").idempotent);
        T.eq("alarm.set 幂等", false, reg.find("alarm.set").idempotent);
        T.eq("app.open 幂等", true, reg.find("app.open").idempotent);
        T.eq("app.open 能力", ActionDescriptor.CAP_NONE,
                reg.find("app.open").requiredCapability);
        T.ok("media.control 预留 shizuku",
                reg.find("media.control").executorPreference.contains("shizuku"));
        T.ok("app.open 超时 > 0", reg.find("app.open").timeoutMs > 0);
        T.ok("alarm.set 声明 hour/minute",
                requiredNames(reg.find("alarm.set").inputSchema).contains("hour")
                        && requiredNames(reg.find("alarm.set").inputSchema).contains("minute"));

        List<Object> caps = reg.capabilitiesJson(true);
        T.eq("capabilities 含全部 5 个", 5, caps.size());
        Map<String, Object> app = cap(caps, "app.open");
        T.ok("capabilities 有 app.open", app != null);
        T.eq("capabilities risk wire", "safe", app.get("risk"));
        T.eq("capabilities requiredCapability", "none", app.get("requiredCapability"));
        T.eq("capabilities available", Boolean.TRUE, app.get("available"));
        T.eq("capabilities idempotent", Boolean.TRUE, app.get("idempotent"));
        T.ok("capabilities inputSchema 是对象", app.get("inputSchema") instanceof Map);
        T.ok("capabilities outputSchema 是对象", app.get("outputSchema") instanceof Map);
        T.ok("capabilities examples 非空", app.get("examples") instanceof List
                && ((List<?>) app.get("examples")).size() > 0);

        List<?> execs = (List<?>) app.get("executors");
        T.eq("capabilities executors 数量", 1, execs.size());
        Map<?, ?> ex0 = (Map<?, ?>) execs.get(0);
        T.eq("capabilities executor 名", "android", ex0.get("name"));
        T.eq("capabilities executor 可用", Boolean.TRUE, ex0.get("available"));

        Map<String, Object> notify = cap(caps, "notification.send");
        T.eq("capabilities confirm 风险", "confirm", notify.get("risk"));
        T.ok("capabilities JSON 可序列化",
                reg.capabilitiesJsonString(true).contains("\"app.open\""));

        FakeExecutor offline = new FakeExecutor("android");
        offline.available = false;
        ActionRegistry noExec = registry(offline);
        T.eq("includeUnavailable=false 过滤不可用", 0,
                noExec.capabilitiesJson(false).size());
        T.eq("includeUnavailable=true 保留不可用", 5,
                noExec.capabilitiesJson(true).size());
    }

    // ------------------------------------------------------------- 参数校验 / app.open

    private static void validationAndAlternatives() {
        FakeExecutor fake = new FakeExecutor("android");
        ActionRegistry reg = registry(fake);

        ActionResult unknown = reg.execute(ActionRequest.builder("no.such.action").build());
        T.eq("未知动作 ok=false", false, unknown.ok);
        T.eq("未知动作错误码", AgentError.Code.UNKNOWN_ACTION, unknown.error.code);

        ActionResult missing = reg.execute(ActionRequest.builder("notification.send")
                .arg("title", "hi").build());
        T.eq("缺必需参数 INVALID_ARGUMENT", AgentError.Code.INVALID_ARGUMENT,
                missing.error.code);
        T.ok("缺参提示包含 text", missing.error.message.contains("text"));

        ActionResult extra = reg.execute(ActionRequest.builder("device.info")
                .arg("bogus", true).build());
        T.eq("未知参数 INVALID_ARGUMENT", AgentError.Code.INVALID_ARGUMENT,
                extra.error.code);

        ActionResult badType = reg.execute(ActionRequest.builder("alarm.set")
                .arg("hour", "seven").arg("minute", 0L).build());
        T.eq("类型错误 INVALID_ARGUMENT", AgentError.Code.INVALID_ARGUMENT,
                badType.error.code);

        ActionResult badHour = reg.execute(ActionRequest.builder("alarm.set")
                .arg("hour", 24L).arg("minute", 0L).build());
        T.eq("hour 越界 INVALID_ARGUMENT", AgentError.Code.INVALID_ARGUMENT,
                badHour.error.code);

        ActionResult badMinute = reg.execute(ActionRequest.builder("alarm.set")
                .arg("hour", 7L).arg("minute", -1L).build());
        T.eq("minute 越界 INVALID_ARGUMENT", AgentError.Code.INVALID_ARGUMENT,
                badMinute.error.code);

        ActionResult badEnum = reg.execute(ActionRequest.builder("media.control")
                .arg("command", "stop").build());
        T.eq("enum 不合法 INVALID_ARGUMENT", AgentError.Code.INVALID_ARGUMENT,
                badEnum.error.code);

        List<Object> badFields = Json.arr();
        badFields.add(1L);
        ActionResult badItem = reg.execute(ActionRequest.builder("device.info")
                .arg("fields", badFields).build());
        T.eq("数组元素类型错误 INVALID_ARGUMENT", AgentError.Code.INVALID_ARGUMENT,
                badItem.error.code);

        ActionResult validAlarm = reg.execute(ActionRequest.builder("alarm.set")
                .arg("hour", 7L).arg("minute", 0L).build());
        T.eq("合法 alarm.set 成功", true, validAlarm.ok);

        ActionResult noOpenArg = reg.execute(ActionRequest.builder("app.open").build());
        T.eq("app.open 无参数 INVALID_ARGUMENT", AgentError.Code.INVALID_ARGUMENT,
                noOpenArg.error.code);
        T.ok("app.open 提示二选一", noOpenArg.error.message.contains("package")
                && noOpenArg.error.message.contains("intentUri"));

        ActionResult byPackage = reg.execute(ActionRequest.builder("app.open")
                .arg("package", "com.tencent.mm").build());
        T.eq("app.open package 可用", true, byPackage.ok);

        ActionResult byUri = reg.execute(ActionRequest.builder("app.open")
                .arg("intentUri", "intent://scan/#Intent;end").build());
        T.eq("app.open intentUri 可用", true, byUri.ok);

        ActionResult both = reg.execute(ActionRequest.builder("app.open")
                .arg("package", "com.tencent.mm")
                .arg("intentUri", "intent://scan/#Intent;end").build());
        T.eq("app.open 两者都给也可执行", true, both.ok);

        Map<String, Object> raw = Json.obj();
        raw.put("action", "device.info");
        raw.put("args", "not-an-object");
        ActionResult parseFail = reg.executePayload(raw);
        T.eq("payload args 非 object INVALID_ARGUMENT",
                AgentError.Code.INVALID_ARGUMENT, parseFail.error.code);
    }

    // ------------------------------------------------------------- dryRun / 确认

    private static void dryRunAndConfirmation() {
        FakeExecutor fake = new FakeExecutor("android");
        ActionRegistry reg = registry(fake);

        ActionResult plan = reg.execute(ActionRequest.builder("app.open")
                .arg("package", "com.tencent.mm").dryRun(true).build());
        T.eq("dryRun ok", true, plan.ok);
        T.eq("dryRun 不调用执行器", 0, fake.calls);
        T.eq("dryRun 结果标记", Boolean.TRUE, plan.result.get("dryRun"));
        T.eq("dryRun 计划执行器", "android", plan.result.get("executor"));
        T.eq("dryRun 保留 args", "com.tencent.mm",
                ((Map<?, ?>) plan.result.get("args")).get("package"));
        T.eq("dryRun 不需要确认", Boolean.FALSE,
                plan.result.get("wouldRequireConfirmation"));
        T.ok("dryRun 有 warning", plan.warnings.size() > 0);

        ActionResult confirmPlan = reg.execute(ActionRequest.builder("notification.send")
                .arg("title", "t").arg("text", "x").dryRun(true).build());
        T.eq("confirm 动作 dryRun 不要求 token", true, confirmPlan.ok);
        T.eq("confirm 动作 dryRun 标记", Boolean.TRUE,
                confirmPlan.result.get("wouldRequireConfirmation"));
        T.eq("confirm dryRun 仍不调用", 0, fake.calls);

        ActionResult needConfirm = reg.execute(ActionRequest.builder("notification.send")
                .arg("title", "t").arg("text", "x").build());
        T.eq("CONFIRM 无 token", AgentError.Code.NEEDS_CONFIRMATION,
                needConfirm.error.code);
        T.eq("CONFIRM 无 token 不调用", 0, fake.calls);

        ActionResult confirmed = reg.execute(ActionRequest.builder("notification.send")
                .arg("title", "t").arg("text", "x")
                .confirmToken("tok_1").build());
        T.eq("CONFIRM 带 token 执行", true, confirmed.ok);
        T.eq("CONFIRM 带 token 调用一次", 1, fake.calls);

        FakeExecutor dangerExec = new FakeExecutor("android");
        ActionRegistry dangerReg = new ActionRegistry();
        dangerReg.register(ActionDescriptor.builder("test.danger")
                .executorPreference("android")
                .risk(ActionRisk.DANGER)
                .build(), dangerExec);
        ActionResult danger = dangerReg.execute(ActionRequest.builder("test.danger")
                .confirmToken("tok").build());
        T.eq("DANGER 默认拒绝", AgentError.Code.PERMISSION_DENIED, danger.error.code);
        T.eq("DANGER 不调用执行器", 0, dangerExec.calls);
    }

    // ------------------------------------------------------------- 可用性 / 权限

    private static void availabilityAndPermission() {
        FakeExecutor offline = new FakeExecutor("android");
        offline.available = false;
        ActionRegistry noAndroid = registry(offline);

        ActionResult unavailable = noAndroid.execute(ActionRequest.builder("device.info").build());
        T.eq("执行器不可用 NOT_AVAILABLE", AgentError.Code.NOT_AVAILABLE,
                unavailable.error.code);

        ActionRegistry rootReg = new ActionRegistry();
        FakeExecutor rootOffline = new FakeExecutor("root");
        rootOffline.available = false;
        rootReg.register(ActionDescriptor.builder("test.root")
                .executorPreference("root")
                .requiredCapability(ActionDescriptor.CAP_ROOT)
                .build(), rootOffline);
        ActionResult denied = rootReg.execute(ActionRequest.builder("test.root").build());
        T.eq("root 不可用 PERMISSION_DENIED", AgentError.Code.PERMISSION_DENIED,
                denied.error.code);

        FakeExecutor rootOnline = new FakeExecutor("root");
        ActionRegistry rootOkReg = new ActionRegistry();
        rootOkReg.register(ActionDescriptor.builder("test.root")
                .executorPreference("root")
                .requiredCapability(ActionDescriptor.CAP_ROOT)
                .build(), rootOnline);
        ActionResult rootOk = rootOkReg.execute(ActionRequest.builder("test.root").build());
        T.eq("root 可用时执行", true, rootOk.ok);
        T.eq("root 执行器名透传", "root", rootOk.executor);

        FakeExecutor android = new FakeExecutor("android");
        FakeExecutor root = new FakeExecutor("root");
        ActionRegistry prefReg = new ActionRegistry();
        ActionDescriptor pref = ActionDescriptor.builder("test.pref")
                .executorPreference("root", "android")
                .build();
        prefReg.register(pref, android);
        prefReg.register(pref, root);
        ActionResult prefTop = prefReg.execute(ActionRequest.builder("test.pref").build());
        T.eq("executorPreference 首选 root", "root", prefTop.executor);
        T.eq("首选 root 时 android 未调用", 0, android.calls);
        root.available = false;
        ActionResult prefFallback = prefReg.execute(ActionRequest.builder("test.pref").build());
        T.eq("首选不可用回退 android", "android", prefFallback.executor);
        T.eq("回退后 android 被调用", 1, android.calls);

        ActionRegistry emptyReg = new ActionRegistry();
        emptyReg.register(ActionDescriptor.builder("test.empty")
                .executorPreference("android").build(), null);
        ActionResult noExecutor = emptyReg.execute(
                ActionRequest.builder("test.empty").build());
        T.eq("无执行器 NOT_AVAILABLE", AgentError.Code.NOT_AVAILABLE,
                noExecutor.error.code);

        // capability 是准入条件：root 动作不能被 android 执行器顶替
        FakeExecutor androidOnly = new FakeExecutor("android");
        ActionRegistry rootMismatch = new ActionRegistry();
        ActionDescriptor rootOnly = ActionDescriptor.builder("test.root.only")
                .executorPreference("root", "android")
                .requiredCapability(ActionDescriptor.CAP_ROOT)
                .build();
        rootMismatch.register(rootOnly, androidOnly);
        ActionResult wrongExec = rootMismatch.execute(
                ActionRequest.builder("test.root.only").build());
        T.eq("root 动作不能用 android 执行", AgentError.Code.PERMISSION_DENIED,
                wrongExec.error.code);
        T.eq("root 动作 android 未调用", 0, androidOnly.calls);
        Map<String, Object> rootCap = cap(rootMismatch.capabilitiesJson(true),
                "test.root.only");
        T.eq("root 动作 capabilities 不可用", Boolean.FALSE, rootCap.get("available"));
        T.eq("root 动作不展示 android 执行器", 0,
                ((List<?>) rootCap.get("executors")).size());

        FakeExecutor shizuku = new FakeExecutor("shizuku");
        ActionRegistry shellReg = new ActionRegistry();
        ActionDescriptor shellOnly = ActionDescriptor.builder("test.shell")
                .executorPreference("android", "shizuku")
                .requiredCapability(ActionDescriptor.CAP_SHELL)
                .build();
        shellReg.register(shellOnly, androidOnly);
        shellReg.register(shellOnly, shizuku);
        ActionResult shellOk = shellReg.execute(
                ActionRequest.builder("test.shell").build());
        T.eq("shell 动作可用 shizuku", "shizuku", shellOk.executor);
        T.eq("shell 动作 android 未调用", 0, androidOnly.calls);
    }

    // ------------------------------------------------------------- 异常 / 超时

    private static void failureMapping() {
        FakeExecutor fake = new FakeExecutor("android");
        ActionRegistry reg = registry(fake);

        fake.failure = new IllegalStateException("boom");
        ActionResult internal = reg.execute(ActionRequest.builder("device.info").build());
        T.eq("执行器异常 INTERNAL", AgentError.Code.INTERNAL, internal.error.code);
        T.ok("INTERNAL 不泄漏异常详情", !internal.error.message.contains("boom"));

        FakeExecutor timeoutFake = new FakeExecutor("android");
        timeoutFake.failure = new ActionExecutor.TimeoutException("budget");
        ActionRegistry timeoutReg = registry(timeoutFake);
        ActionResult timeout = timeoutReg.execute(ActionRequest.builder("device.info").build());
        T.eq("TimeoutException → TIMEOUT", AgentError.Code.TIMEOUT, timeout.error.code);
        T.eq("TIMEOUT 可重试", true, timeout.error.retryable);

        FakeExecutor slow = new FakeExecutor("android");
        slow.delayMs = 80;
        ActionRegistry slowReg = new ActionRegistry();
        slowReg.register(ActionDescriptor.builder("test.slow")
                .executorPreference("android")
                .timeoutMs(20)
                .build(), slow);
        ActionResult slowResult = slowReg.execute(ActionRequest.builder("test.slow").build());
        T.eq("sleep 超过描述超时 TIMEOUT", AgentError.Code.TIMEOUT,
                slowResult.error.code);
        T.ok("超时 elapsedMs 已记录", slowResult.elapsedMs >= 20);

        FakeExecutor nullFake = new FakeExecutor("android");
        nullFake.returnNull = true;
        ActionResult nullResult = registry(nullFake).execute(
                ActionRequest.builder("device.info").build());
        T.eq("执行器返回 null INTERNAL", AgentError.Code.INTERNAL,
                nullResult.error.code);

        FakeExecutor partial = new FakeExecutor("android");
        Map<String, Object> partialData = Json.obj();
        partialData.put("model", "test");
        List<String> warnings = new java.util.ArrayList<String>();
        warnings.add("partial");
        partial.response = new ActionResult(true, "device.info", "android",
                partialData, warnings, null, 0, "", "aud_1");
        ActionResult ok = registry(partial).execute(
                ActionRequest.builder("device.info").build());
        T.eq("部分成功 ok=true", true, ok.ok);
        T.eq("部分成功保留 warning 数", 1, ok.warnings.size());
        T.eq("部分成功保留 auditId", "aud_1", ok.auditId);
        T.eq("结果 action 归一", "device.info", ok.action);
        T.eq("结果 executor 归一", "android", ok.executor);
    }

    // ------------------------------------------------------------- 透传 / 解析

    private static void passthroughAndParsing() {
        FakeExecutor fake = new FakeExecutor("android");
        ActionRegistry reg = registry(fake);

        Map<String, Object> context = Json.obj();
        context.put("city", "Beijing");
        ActionRequest req = ActionRequest.builder("device.info")
                .arg("fields", list("model"))
                .context(context)
                .sessionId("ses_1")
                .idempotencyKey("idem_1")
                .clientId("cli_1")
                .requestId("req_custom")
                .build();
        ActionResult res = reg.execute(req);

        T.eq("sessionId 透传", "ses_1", fake.last.sessionId);
        T.eq("idempotencyKey 透传", "idem_1", fake.last.idempotencyKey);
        T.eq("clientId 透传", "cli_1", fake.last.clientId);
        T.eq("requestId 透传", "req_custom", res.requestId);
        T.eq("context 透传", "Beijing",
                ((Map<?, ?>) fake.last.context).get("city"));
        T.ok("args 透传", fake.last.args.containsKey("fields"));
        T.eq("args 内容保留", 1, ((List<?>) fake.last.args.get("fields")).size());

        Map<String, Object> raw = Json.obj();
        raw.put("action", "app.open");
        Map<String, Object> args = Json.obj();
        args.put("package", "com.tencent.mm");
        raw.put("args", args);
        raw.put("context", "remember: deliver flowers");
        raw.put("sessionId", "ses_2");
        raw.put("idempotencyKey", "idem_2");
        raw.put("dryRun", true);
        raw.put("async", true);
        raw.put("timeoutMs", 1234L);
        raw.put("clientId", "cli_2");
        raw.put("confirmToken", "tok_2");
        raw.put("requestId", "req_2");
        ActionRequest parsed = ActionRequest.from(raw);

        T.eq("解析 action", "app.open", parsed.action);
        T.eq("解析 args", "com.tencent.mm", parsed.args.get("package"));
        T.eq("解析 context 字符串", "remember: deliver flowers", parsed.context);
        T.eq("解析 sessionId", "ses_2", parsed.sessionId);
        T.eq("解析 idempotencyKey", "idem_2", parsed.idempotencyKey);
        T.eq("解析 dryRun", true, parsed.dryRun);
        T.eq("解析 async", true, parsed.async);
        T.eq("解析 timeoutMs", 1234L, parsed.timeoutMs);
        T.eq("解析 clientId", "cli_2", parsed.clientId);
        T.eq("解析 confirmToken", "tok_2", parsed.confirmToken);
        T.eq("解析 requestId", "req_2", parsed.requestId);

        ActionRequest defaults = ActionRequest.from(Json.obj());
        T.eq("默认 dryRun false", false, defaults.dryRun);
        T.eq("默认 async false", false, defaults.async);
        T.eq("默认 timeoutMs 0", 0L, defaults.timeoutMs);
        T.eq("默认 args 为空", 0, defaults.args.size());
    }

    // ------------------------------------------------------------- 错误 / 信封

    private static void errorsAndEnvelopes() {
        T.eq("AgentError wire 稳定", "UNKNOWN_ACTION",
                AgentError.of(AgentError.Code.UNKNOWN_ACTION).code.wire);
        T.eq("AgentError 默认文案", "参数不合法",
                AgentError.of(AgentError.Code.INVALID_ARGUMENT).message);
        T.eq("AgentError TIMEOUT 可重试", true,
                AgentError.of(AgentError.Code.TIMEOUT).retryable);
        T.eq("AgentError INTERNAL 不可重试", false,
                AgentError.of(AgentError.Code.INTERNAL).retryable);

        Map<String, Object> errJson = AgentError.of(AgentError.Code.BUSY, "排队中").toJson();
        T.eq("AgentError JSON code", "BUSY", errJson.get("code"));
        T.eq("AgentError JSON message", "排队中", errJson.get("message"));
        T.eq("AgentError JSON retryable", Boolean.TRUE, errJson.get("retryable"));

        Map<String, Object> okJson = new ActionResult(true, "device.info", "android",
                Json.obj(), null, null, 12, "req_1", null).toJson();
        T.eq("ActionResult ok", Boolean.TRUE, okJson.get("ok"));
        T.eq("ActionResult action", "device.info", okJson.get("action"));
        T.eq("ActionResult executor", "android", okJson.get("executor"));
        T.eq("ActionResult requestId", "req_1", okJson.get("requestId"));
        T.ok("ActionResult warnings 是数组", okJson.get("warnings") instanceof List);
        T.eq("ActionResult elapsedMs", 12L, okJson.get("elapsedMs"));

        Map<String, Object> failJson = new ActionResult(false, "app.open", "android",
                null, null, AgentError.of(AgentError.Code.PERMISSION_DENIED),
                3, "req_2", null).toJson();
        T.eq("失败信封 ok=false", Boolean.FALSE, failJson.get("ok"));
        T.eq("失败信封 code", "PERMISSION_DENIED", failJson.get("code"));
        T.ok("失败信封无 result", !failJson.containsKey("result"));
        T.eq("失败信封 retryable", Boolean.FALSE, failJson.get("retryable"));
    }

    // ------------------------------------------------------------- 辅助

    private static ActionRegistry registry(FakeExecutor fake) {
        ActionRegistry reg = new ActionRegistry();
        for (ActionDescriptor d : Actions.builtins()) reg.register(d, fake);
        return reg;
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

    private static List<String> requiredNames(Map<String, Object> schema) {
        List<String> out = new java.util.ArrayList<String>();
        Object v = schema.get("required");
        if (v instanceof List) {
            for (Object o : (List<?>) v) out.add(String.valueOf(o));
        }
        return out;
    }

    private static List<Object> list(Object... values) {
        List<Object> l = Json.arr();
        if (values != null) {
            for (Object v : values) l.add(v);
        }
        return l;
    }
}
