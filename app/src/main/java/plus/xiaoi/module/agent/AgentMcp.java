package plus.xiaoi.module.agent;

import java.util.List;
import java.util.Map;

import plus.xiaoi.module.Json;

/**
 * App 侧 Agent 动作面的 MCP / JSON-RPC 路由（纯 JVM，无 android 依赖）。
 *
 * <p>工具：{@code xiaoai_capabilities} / {@code xiaoai_action} /
 * {@code xiaoai_confirm} / {@code xiaoai_audit}，以及 WP1.3b 的
 * {@code xiaoai_notify} / {@code xiaoai_ask}。动作列表、参数校验、执行器选择、
 * 错误码都复用 WP1.1 的 {@link ActionRegistry}；本类只负责 MCP 报文、工具结果信封，
 * 以及 WP1.3 的确认 token、幂等键和审计脱敏。</p>
 *
 * <p>notify/ask 不是 ActionRegistry 里的“动作”，它们走可注入的
 * {@link NotifyHost} / {@link AskHost}，这样协议层可以脱离 Android 单测。</p>
 *
 * <p>通知（没有 id 的报文）返回 {@code null}，由 HTTP 层回 202 空体。</p>
 */
public final class AgentMcp {

    /** MCP Streamable HTTP 最小子集的协议版本（与 8771 保持一致）。 */
    public static final String PROTOCOL = "2025-03-26";
    public static final String SERVER = "xiaoi-plus-agent";

    /** xiaoai_notify.text 的协议上限（超过直接拒绝，不静默吞掉）。 */
    public static final int NOTIFY_TEXT_MAX = 2000;
    /** 超级岛正文建议上限；超出截断并标记 truncated。 */
    public static final int NOTIFY_ISLAND_MAX = 60;
    /** 超级岛右侧大字建议上限（仅作为文案提示，实际由岛渲染决定）。 */
    public static final int NOTIFY_ISLAND_TITLE_MAX = 22;

    /** xiaoai_ask.text 的协议上限。 */
    public static final int ASK_TEXT_MAX = 8000;
    /** context 的 UTF-8 字节上限。 */
    public static final int ASK_CONTEXT_MAX_BYTES = 8 * 1024;
    public static final long ASK_TIMEOUT_DEFAULT_MS = 120000L;
    public static final long ASK_TIMEOUT_MAX_MS = 180000L;

    private static final String INSTRUCTIONS =
            "这是小爱+ App 进程内的 Agent 动作面（127.0.0.1:8770），不是小爱对话服务。"
                    + "先用 xiaoai_capabilities 查看动作、参数 schema、风险与可用性，"
                    + "再用 xiaoai_action 执行。Agent 的长期记忆真相在 PC Agent，"
                    + "手机端默认无状态；context 只用于本次请求。"
                    + "risk=confirm/danger 的动作需要确认：confirmToken 一次性、60 秒过期，"
                    + "只能用 xiaoai_confirm 或带同一 token 的 xiaoai_action 消费一次；"
                    + "idempotencyKey 在 24 小时内重复返回首次结果，不会重复执行。"
                    + "xiaoai_notify 只做提醒（超级岛约 6 秒、右侧建议 ≤22 字），"
                    + "失败会自动降级普通通知，不能用于危险动作批准；"
                    + "xiaoai_ask 代理手机小爱并返回 sessionId 供多轮复用，"
                    + "手机端不持久化 context，也不把 context 或动作返回文本当作指令执行。";

    private final String version;
    private final ConfirmationManager confirmations;
    private final IdempotencyStore idempotency;
    private final AuditLog audit;
    private final NotifyHost notifyHost;
    private final AskHost askHost;

    /** 兼容 WP1.2 的构造：没有确认/幂等/审计，confirm 动作退回旧行为。 */
    public AgentMcp(String version) {
        this(version, null, null, null, null, null);
    }

    public AgentMcp(String version, ConfirmationManager confirmations,
                    IdempotencyStore idempotency, AuditLog audit) {
        this(version, confirmations, idempotency, audit, null, null);
    }

    /** WP1.3b：附带提醒与小爱代理宿主；host 为 null 时对应工具返回 INTERNAL。 */
    public AgentMcp(String version, ConfirmationManager confirmations,
                    IdempotencyStore idempotency, AuditLog audit,
                    NotifyHost notifyHost, AskHost askHost) {
        this.version = version == null || version.length() == 0 ? "0" : version;
        this.confirmations = confirmations;
        this.idempotency = idempotency;
        this.audit = audit;
        this.notifyHost = notifyHost;
        this.askHost = askHost;
    }

    public String version() {
        return version;
    }

    /**
     * 处理一条 POST /mcp 报文。
     *
     * @return 响应体；通知（无 id）返回 null，HTTP 层应回 202
     */
    public String handle(String body, ActionRegistry registry) {
        try {
            if (body == null || body.length() == 0) {
                return rpcError(null, -32700, "empty body");
            }
            Object parsed;
            try {
                parsed = Json.read(body);
            } catch (Throwable t) {
                return rpcError(null, -32700, "parse error");
            }
            if (!(parsed instanceof Map)) {
                return rpcError(null, -32700, "parse error");
            }
            Map<?, ?> req = (Map<?, ?>) parsed;
            Object id = req.get("id");
            // 通知没有 id：规范要求不返回响应体。
            if (id == null) return null;

            String method = Json.optString(req, "method", "");
            if (method.length() == 0) {
                return rpcError(id, -32600, "missing method");
            }
            if ("initialize".equals(method)) {
                return rpcResult(id, initializeResult());
            }
            if ("ping".equals(method)) {
                return rpcResult(id, Json.obj());
            }
            if ("tools/list".equals(method)) {
                return rpcResult(id, toolsList());
            }
            if ("tools/call".equals(method)) {
                if (registry == null) {
                    return rpcError(id, -32603, "internal error");
                }
                Object p = req.get("params");
                Map<?, ?> params = p instanceof Map ? (Map<?, ?>) p : null;
                return rpcResult(id, toolsCall(params, registry));
            }
            return rpcError(id, -32601, "method not found: " + method);
        } catch (Throwable t) {
            return rpcError(null, -32603, "internal error");
        }
    }

    // ------------------------------------------------------------- 方法实现

    public Map<String, Object> initializeResult() {
        Map<String, Object> toolsCap = Json.obj();
        toolsCap.put("listChanged", Boolean.FALSE);
        Map<String, Object> caps = Json.obj();
        caps.put("tools", toolsCap);

        Map<String, Object> info = Json.obj();
        info.put("name", SERVER);
        info.put("version", version);

        Map<String, Object> r = Json.obj();
        r.put("protocolVersion", PROTOCOL);
        r.put("capabilities", caps);
        r.put("serverInfo", info);
        r.put("instructions", INSTRUCTIONS);
        return r;
    }

    /** 工具表（顺序固定：capabilities / action / confirm / audit）。 */
    public Map<String, Object> toolsList() {
        List<Object> tools = Json.arr();

        Map<String, Object> capsProps = Json.obj();
        capsProps.put("includeUnavailable", prop("boolean",
                "是否包含当前不可用的动作，默认 true；能力表应保留不可用项以便 Agent 判断"));
        Map<String, Object> caps = Json.obj();
        caps.put("name", "xiaoai_capabilities");
        caps.put("description", "列出 App 侧动作：name / schema / risk / requiredCapability / "
                + "executor 与当前可用性。不执行动作，不消耗权限。");
        caps.put("inputSchema", objectSchema(capsProps, list()));
        tools.add(caps);

        Map<String, Object> actionProps = Json.obj();
        actionProps.put("action", prop("string", "动作名，例如 device.info"));
        Map<String, Object> args = prop("object", "动作参数；结构由 xiaoai_capabilities 的 inputSchema 定义");
        actionProps.put("args", args);
        Map<String, Object> context = Json.obj();
        context.put("description", "PC Agent 传入的本次上下文（string 或 object，建议 ≤8KB；手机端不持久化）");
        actionProps.put("context", context);
        actionProps.put("sessionId", prop("string", "可选；复用小爱会话，不传则由动作自行决定"));
        actionProps.put("idempotencyKey", prop("string",
                "写动作的幂等键；24 小时内同键同参数返回首次结果"));
        actionProps.put("dryRun", prop("boolean", "只校验并返回计划，不执行"));
        actionProps.put("async", prop("boolean", "长动作异步执行；当前版本仍同步"));
        actionProps.put("timeoutMs", prop("integer", "客户端期望超时；不得超过动作声明上限"));
        actionProps.put("clientId", prop("string", "审计标签，不作为安全边界"));
        actionProps.put("confirmToken", prop("string",
                "确认令牌；一次性、60 秒过期，由 NEEDS_CONFIRMATION 返回"));
        actionProps.put("requestId", prop("string", "调用方请求 id，用于关联日志"));
        Map<String, Object> action = Json.obj();
        action.put("name", "xiaoai_action");
        action.put("description", "执行一个 App 侧动作。成功返回 ok=true 与 result；"
                + "失败返回稳定错误码；需要确认时返回 NEEDS_CONFIRMATION 与 confirmToken。");
        action.put("inputSchema", objectSchema(actionProps, list("action")));
        tools.add(action);

        Map<String, Object> confirmProps = Json.obj();
        confirmProps.put("confirmToken", prop("string", "NEEDS_CONFIRMATION 返回的一次性 token"));
        confirmProps.put("approve", prop("boolean", "true 批准并执行，false 拒绝"));
        Map<String, Object> confirm = Json.obj();
        confirm.put("name", "xiaoai_confirm");
        confirm.put("description", "批准或拒绝一个待确认动作。token 一次性，"
                + "批准后立即执行且不能再次使用；已用返回 CONFLICT，过期返回 CONFIRMATION_EXPIRED。");
        confirm.put("inputSchema", objectSchema(confirmProps,
                list("confirmToken", "approve")));
        tools.add(confirm);

        Map<String, Object> auditProps = Json.obj();
        Map<String, Object> limit = prop("integer", "返回条数，1~200，默认 50；最新在前");
        limit.put("minimum", 1L);
        limit.put("maximum", 200L);
        auditProps.put("limit", limit);
        Map<String, Object> auditTool = Json.obj();
        auditTool.put("name", "xiaoai_audit");
        auditTool.put("description", "读取动作审计摘要：action / executor / risk / 参数名 / "
                + "结果 / 耗时 / 事件类型。不返回参数值、context、token 或 apiKey。");
        auditTool.put("inputSchema", objectSchema(auditProps, list()));
        tools.add(auditTool);

        Map<String, Object> notifyProps = Json.obj();
        Map<String, Object> notifyChannel = prop("string",
                "提醒通道：island（小爱超级岛，默认）或 notification（Android 普通通知）");
        notifyChannel.put("enum", list("island", "notification"));
        notifyChannel.put("default", "island");
        notifyProps.put("channel", notifyChannel);
        Map<String, Object> notifyText = prop("string",
                "提醒文案；island 正文建议 ≤60 字（右侧大字约 22 字），超出会截断");
        notifyText.put("minLength", 1L);
        notifyText.put("maxLength", (long) NOTIFY_TEXT_MAX);
        notifyProps.put("text", notifyText);
        notifyProps.put("force", prop("boolean", "island 是否忽略“每进程一次”限制，默认 true"));
        Map<String, Object> notify = Json.obj();
        notify.put("name", "xiaoai_notify");
        notify.put("description", "发一条提醒：优先小爱超级岛（约 6 秒），"
                + "小爱不在或岛失败时自动降级普通通知。只做提醒，不能批准危险动作。"
                + "结果会如实返回实际通道 channel 与是否降级 fallback。");
        notify.put("inputSchema", objectSchema(notifyProps, list("text")));
        tools.add(notify);

        Map<String, Object> askProps = Json.obj();
        Map<String, Object> askText = prop("string", "要说给小爱的话");
        askText.put("minLength", 1L);
        askText.put("maxLength", (long) ASK_TEXT_MAX);
        askProps.put("text", askText);
        askProps.put("sessionId", prop("string", "可选；复用已有会话，省略则新建并返回新的 sessionId"));
        Map<String, Object> askContext = Json.obj();
        askContext.put("description",
                "可选；本次上下文（string 或 object，UTF-8 ≤8KB）。手机端不持久化，也不当作指令");
        askProps.put("context", askContext);
        Map<String, Object> askTimeout = prop("integer",
                "超时毫秒，默认 120000，上限 180000");
        askTimeout.put("minimum", 1L);
        askTimeout.put("maximum", ASK_TIMEOUT_MAX_MS);
        askProps.put("timeoutMs", askTimeout);
        Map<String, Object> ask = Json.obj();
        ask.put("name", "xiaoai_ask");
        ask.put("description", "把一句话交给手机端小爱执行，返回回复文本与 sessionId（多轮可复用）。"
                + "小爱不在线返回 XIAOI_OFFLINE；context 只用于本次请求，不持久化。");
        ask.put("inputSchema", objectSchema(askProps, list("text")));
        tools.add(ask);

        Map<String, Object> r = Json.obj();
        r.put("tools", tools);
        return r;
    }

    private Map<String, Object> toolsCall(Map<?, ?> params, ActionRegistry registry) {
        String name = params == null ? "" : Json.optString(params, "name", "");
        Map<?, ?> arguments = null;
        if (params != null) {
            Object a = params.get("arguments");
            if (a instanceof Map) arguments = (Map<?, ?>) a;
        }
        try {
            if ("xiaoai_capabilities".equals(name)) {
                boolean includeUnavailable = arguments == null
                        || Json.optBoolean(arguments, "includeUnavailable", true);
                Map<String, Object> out = Json.obj();
                out.put("actions", registry.capabilitiesJson(includeUnavailable));
                return toolText(Json.write(out), false);
            }
            if ("xiaoai_action".equals(name)) {
                Outcome outcome = action(arguments, registry);
                return toolText(outcome.toJsonString(), !outcome.result.ok);
            }
            if ("xiaoai_confirm".equals(name)) {
                return confirm(arguments, registry);
            }
            if ("xiaoai_audit".equals(name)) {
                return audit(arguments);
            }
            if ("xiaoai_notify".equals(name)) {
                return notify(arguments);
            }
            if ("xiaoai_ask".equals(name)) {
                return ask(arguments);
            }
            return toolText("unknown tool: " + name, true);
        } catch (Throwable t) {
            return toolText("tool failed: " + t.getClass().getSimpleName(), true);
        }
    }

    // ------------------------------------------------------------- xiaoai_action

    private Outcome action(Map<?, ?> arguments, ActionRegistry registry) {
        ActionRequest request;
        try {
            request = ActionRequest.from(arguments);
        } catch (Throwable t) {
            ActionResult r = ActionResult.failure("", AgentError.of(
                    AgentError.Code.INVALID_ARGUMENT,
                    "请求解析失败：" + (t.getMessage() == null ? "参数不合法" : t.getMessage())));
            record(null, null, r, AuditLog.EVENT_ACTION, 0);
            return new Outcome(r);
        }
        return executeRequest(request, registry);
    }

    private Outcome executeRequest(ActionRequest request, ActionRegistry registry) {
        ActionDescriptor d = registry.find(request.action);
        if (d == null) {
            ActionResult r = registry.execute(request);
            record(request, d, r, AuditLog.EVENT_ACTION, r.elapsedMs);
            return new Outcome(r);
        }

        // DANGER 默认拒绝，不签发 token、不执行。
        if (d.risk == ActionRisk.DANGER) {
            ActionResult r = ActionResult.failure(d.name, AgentError.of(
                    AgentError.Code.PERMISSION_DENIED,
                    "risk=danger 默认拒绝：" + d.name));
            record(request, d, r, AuditLog.EVENT_ACTION, 0);
            return new Outcome(r);
        }

        // dryRun 只返回计划，不进入确认/幂等，避免占用 token 与缓存。
        if (request.dryRun) {
            ActionResult r = registry.execute(request);
            record(request, d, r, AuditLog.EVENT_ACTION, r.elapsedMs);
            return new Outcome(r);
        }

        String token = request.confirmToken.trim();
        ActionRequest effective = request;
        if (d.risk == ActionRisk.CONFIRM && token.length() == 0) {
            // 同一个 idempotencyKey 已经执行过：直接返回首次结果，避免再次弹确认。
            if (idempotency != null && request.idempotencyKey.trim().length() > 0) {
                IdempotencyStore.Lookup lookup = idempotency.check(
                        request.idempotencyKey, request.action, request.args);
                if (lookup.status == IdempotencyStore.Status.CONFLICT) {
                    ActionResult conflict = ActionResult.failure(d.name,
                            AgentError.of(AgentError.Code.CONFLICT,
                                    "idempotencyKey 已用于不同的动作或参数"));
                    record(request, d, conflict, AuditLog.EVENT_ACTION, 0);
                    return new Outcome(conflict);
                }
                if (lookup.status == IdempotencyStore.Status.HIT) {
                    record(request, d, lookup.result, AuditLog.EVENT_REPLAY, 0);
                    return new Outcome(lookup.result);
                }
            }
            // 先让 registry 校验参数与执行器；只有“确实可执行”才签发 token。
            ActionResult probe = registry.execute(request);
            if (probe.error == null
                    || probe.error.code != AgentError.Code.NEEDS_CONFIRMATION) {
                record(request, d, probe, AuditLog.EVENT_ACTION, probe.elapsedMs);
                return new Outcome(probe);
            }
            if (confirmations == null) {
                record(request, d, probe, AuditLog.EVENT_CONFIRM, probe.elapsedMs);
                return new Outcome(probe);
            }
            ConfirmationManager.Issue issue = confirmations.issue(request);
            record(request, d, probe, AuditLog.EVENT_CONFIRM, probe.elapsedMs);

            Map<String, Object> extra = Json.obj();
            extra.put("confirmToken", issue.token);
            extra.put("expiresInMs", issue.expiresInMs);
            Map<String, Object> summary = Json.obj();
            summary.put("action", d.name);
            summary.put("risk", d.risk.wire);
            List<Object> names = Json.arr();
            for (String n : request.argNames()) names.add(n);
            summary.put("argNames", names);
            extra.put("summary", summary);
            return new Outcome(probe, extra);
        }

        if (d.risk == ActionRisk.CONFIRM) {
            if (confirmations == null) {
                ActionResult r = ActionResult.failure(d.name, AgentError.of(
                        AgentError.Code.INTERNAL, "确认管理器不可用"));
                record(request, d, r, AuditLog.EVENT_CONFIRM, 0);
                return new Outcome(r);
            }
            ConfirmationManager.Result consumed = confirmations.consume(token, request);
            if (!consumed.ok) {
                ActionResult r = ActionResult.failure(d.name, consumed.error);
                record(request, d, r, AuditLog.EVENT_CONFIRM, 0);
                return new Outcome(r);
            }
            // 使用签发时保存的 pending 请求，保证执行的就是被确认的那一份。
            effective = consumed.request.withConfirmToken(token);
        }

        return runWithIdempotency(effective, d, registry);
    }

    /**
     * 幂等检查 → 执行 → 缓存终态 → 审计。确认通过后也走这里。
     */
    private Outcome runWithIdempotency(ActionRequest request, ActionDescriptor d,
                                       ActionRegistry registry) {
        String key = request.idempotencyKey;
        if (idempotency != null && key.trim().length() > 0) {
            IdempotencyStore.Lookup lookup =
                    idempotency.check(key, request.action, request.args);
            if (lookup.status == IdempotencyStore.Status.CONFLICT) {
                ActionResult r = ActionResult.failure(d.name, AgentError.of(
                        AgentError.Code.CONFLICT,
                        "idempotencyKey 已用于不同的动作或参数"));
                record(request, d, r, AuditLog.EVENT_ACTION, 0);
                return new Outcome(r);
            }
            if (lookup.status == IdempotencyStore.Status.HIT) {
                record(request, d, lookup.result, AuditLog.EVENT_REPLAY, 0);
                return new Outcome(lookup.result);
            }
        }

        ActionResult r = registry.execute(request);
        if (idempotency != null && key.trim().length() > 0 && isTerminal(r)) {
            idempotency.put(key, request.action, request.args, r);
        }
        record(request, d, r, AuditLog.EVENT_ACTION, r.elapsedMs);
        return new Outcome(r);
    }

    /** NEEDS_CONFIRMATION 不是终态，不写幂等缓存。 */
    private static boolean isTerminal(ActionResult r) {
        return r != null && (r.error == null
                || r.error.code != AgentError.Code.NEEDS_CONFIRMATION);
    }

    // ------------------------------------------------------------- xiaoai_confirm

    private Map<String, Object> confirm(Map<?, ?> arguments, ActionRegistry registry) {
        String token = arguments == null
                ? "" : Json.optString(arguments, "confirmToken", "");
        if (token.trim().length() == 0) {
            return errorTool(AgentError.Code.INVALID_ARGUMENT, "缺少 confirmToken");
        }
        if (arguments == null || !(arguments.get("approve") instanceof Boolean)) {
            return errorTool(AgentError.Code.INVALID_ARGUMENT,
                    "approve 必须是 boolean（true 批准 / false 拒绝）");
        }
        boolean approve = (Boolean) arguments.get("approve");
        if (confirmations == null) {
            return errorTool(AgentError.Code.INTERNAL, "确认管理器不可用");
        }

        if (!approve) {
            ConfirmationManager.Result denied = confirmations.deny(token);
            if (denied.request == null) {
                recordConfirm(null, null, false, denied.code(), AuditLog.EVENT_DENY);
                return errorTool(denied.error);
            }
            ActionDescriptor d = registry.find(denied.request.action);
            recordConfirm(denied.request, d, false,
                    AgentError.Code.CONFIRMATION_DENIED.wire, AuditLog.EVENT_DENY);
            return toolText(ActionResult.failure(denied.request.action,
                    AgentError.of(AgentError.Code.CONFIRMATION_DENIED,
                            "确认已被拒绝：" + denied.request.action)).toJsonString(), true);
        }

        ConfirmationManager.Result approved = confirmations.takeApproved(token);
        if (!approved.ok) {
            recordConfirm(null, null, false, approved.code(), AuditLog.EVENT_CONFIRM);
            return errorTool(approved.error);
        }
        ActionRequest pending = approved.request;
        ActionDescriptor d = registry.find(pending.action);
        record(pending, d, null, AuditLog.EVENT_CONFIRM, 0);
        if (d == null) {
            return errorTool(AgentError.Code.UNKNOWN_ACTION, "未知动作：" + pending.action);
        }
        if (d.risk == ActionRisk.DANGER) {
            return errorTool(AgentError.Code.PERMISSION_DENIED,
                    "risk=danger 默认拒绝：" + d.name);
        }
        Outcome outcome = runWithIdempotency(pending.withConfirmToken(token), d, registry);
        return toolText(outcome.toJsonString(), !outcome.result.ok);
    }

    // ------------------------------------------------------------- xiaoai_audit

    private Map<String, Object> audit(Map<?, ?> arguments) {
        if (audit == null) {
            return errorTool(AgentError.Code.INTERNAL, "审计不可用");
        }
        long raw = arguments == null ? AuditLog.DEFAULT_LIMIT
                : Json.optLong(arguments, "limit", AuditLog.DEFAULT_LIMIT);
        int limit = raw > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) raw;
        return toolText(Json.write(audit.recentJson(limit)), false);
    }

    // ------------------------------------------------------------- xiaoai_notify

    private Map<String, Object> notify(Map<?, ?> arguments) {
        if (notifyHost == null) {
            return errorTool(AgentError.Code.INTERNAL, "提醒宿主不可用");
        }
        String channel = arguments == null
                ? "island" : Json.optString(arguments, "channel", "island").trim();
        if (channel.length() == 0) channel = "island";
        if (!"island".equals(channel) && !"notification".equals(channel)) {
            return errorTool(AgentError.Code.INVALID_ARGUMENT,
                    "channel 只能是 island 或 notification");
        }
        String original = arguments == null ? "" : Json.optString(arguments, "text", "").trim();
        if (original.length() == 0) {
            return errorTool(AgentError.Code.INVALID_ARGUMENT, "text 不能为空");
        }
        if (original.length() > NOTIFY_TEXT_MAX) {
            return errorTool(AgentError.Code.INVALID_ARGUMENT,
                    "text 过长（上限 " + NOTIFY_TEXT_MAX + " 字符）");
        }
        boolean force = arguments == null || Json.optBoolean(arguments, "force", true);

        NotifyHost.NotifyResult first = safeNotify(channel,
                "island".equals(channel) ? truncate(original, NOTIFY_ISLAND_MAX) : original,
                force);
        boolean islandAttempt = "island".equals(channel);

        if (first != null && first.ok) {
            boolean truncated = islandAttempt
                    ? original.length() > NOTIFY_ISLAND_MAX
                    : original.length() > NOTIFY_TEXT_MAX;
            return notifyResult(first.channel, first.fallback, truncated, original);
        }

        // 岛失败/小爱不在：自动尝试普通通知。宿主自己已经降级过（fallback=true）时
        // 不重复发第二条，避免同一条提醒失败两次。
        if (islandAttempt && (first == null || !first.fallback)) {
            NotifyHost.NotifyResult second = safeNotify("notification", original, force);
            if (second != null && second.ok) {
                return notifyResult("notification", true, false, original);
            }
            return errorTool(AgentError.of(AgentError.Code.NOT_AVAILABLE,
                    "无法送达提醒（island=" + errorOf(first)
                            + ", notification=" + errorOf(second) + "）"));
        }
        return errorTool(AgentError.of(AgentError.Code.NOT_AVAILABLE,
                "无法送达提醒（" + errorOf(first) + "）"));
    }

    private NotifyHost.NotifyResult safeNotify(String channel, String text, boolean force) {
        try {
            return notifyHost.notify(channel, text, force);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 宿主错误短语；只用于拼装稳定文案，不包含异常正文。 */
    private static String errorOf(NotifyHost.NotifyResult r) {
        if (r == null) return "INTERNAL";
        return r.error.length() == 0 ? "NOT_AVAILABLE" : r.error;
    }

    private static Map<String, Object> notifyResult(String channel, boolean fallback,
                                                    boolean truncated, String original) {
        Map<String, Object> o = Json.obj();
        o.put("ok", true);
        o.put("channel", channel == null || channel.length() == 0 ? "notification" : channel);
        o.put("fallback", fallback);
        o.put("truncated", truncated);
        o.put("textLength", (long) original.length());
        return toolText(Json.write(o), false);
    }

    // ------------------------------------------------------------- xiaoai_ask

    private Map<String, Object> ask(Map<?, ?> arguments) {
        if (askHost == null) {
            return errorTool(AgentError.Code.INTERNAL, "小爱代理宿主不可用");
        }
        String text = arguments == null ? "" : Json.optString(arguments, "text", "").trim();
        if (text.length() == 0) {
            return errorTool(AgentError.Code.INVALID_ARGUMENT, "text 不能为空");
        }
        if (text.length() > ASK_TEXT_MAX) {
            return errorTool(AgentError.Code.INVALID_ARGUMENT,
                    "text 过长（上限 " + ASK_TEXT_MAX + " 字符）");
        }
        String sessionId = arguments == null
                ? "" : Json.optString(arguments, "sessionId", "").trim();

        Object context = arguments == null ? null : arguments.get("context");
        if (context != null && !(context instanceof String) && !(context instanceof Map)) {
            return errorTool(AgentError.Code.INVALID_ARGUMENT,
                    "context 只能是 string 或 object");
        }
        if (context != null) {
            int bytes = contextBytes(context);
            if (bytes > ASK_CONTEXT_MAX_BYTES) {
                return errorTool(AgentError.Code.INVALID_ARGUMENT,
                        "context 超过 8KB（" + bytes + " 字节）");
            }
        }

        long timeoutMs = arguments == null
                ? ASK_TIMEOUT_DEFAULT_MS
                : Json.optLong(arguments, "timeoutMs", ASK_TIMEOUT_DEFAULT_MS);
        if (timeoutMs <= 0) timeoutMs = ASK_TIMEOUT_DEFAULT_MS;
        if (timeoutMs > ASK_TIMEOUT_MAX_MS) timeoutMs = ASK_TIMEOUT_MAX_MS;

        AskHost.AskResult r;
        try {
            r = askHost.ask(text, sessionId, context, timeoutMs);
        } catch (Throwable t) {
            r = null;
        }
        if (r == null) {
            return errorTool(AgentError.Code.INTERNAL, "小爱代理没有返回结果");
        }
        if (!r.ok) {
            AgentError.Code code = askErrorCode(r.errorCode);
            String message = r.message.length() == 0
                    ? code.defaultMessage : r.message;
            return errorTool(AgentError.of(code, message));
        }

        String sid = r.sessionId.length() > 0 ? r.sessionId : sessionId;
        Map<String, Object> o = Json.obj();
        o.put("ok", true);
        o.put("text", r.text);
        o.put("sessionId", sid);
        o.put("via", "xiaoi");
        o.put("timeoutMs", timeoutMs);
        return toolText(Json.write(o), false);
    }

    private static int contextBytes(Object context) {
        try {
            String s = context instanceof String
                    ? (String) context : Json.write(context);
            return s.getBytes("UTF-8").length;
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }

    /** 只接受稳定错误码；未知码统一内部错误，避免把后端细节带到协议面。 */
    private static AgentError.Code askErrorCode(String wire) {
        if (wire != null) {
            for (AgentError.Code c : AgentError.Code.values()) {
                if (c.wire.equals(wire)) return c;
            }
        }
        return AgentError.Code.INTERNAL;
    }

    // ------------------------------------------------------------- 审计辅助

    private void record(ActionRequest request, ActionDescriptor d, ActionResult r,
                        String event, long elapsedMs) {
        if (audit == null) return;
        audit.record(request == null ? "" : request.clientId,
                request == null ? "" : request.action,
                r == null ? "" : r.executor,
                d == null ? null : d.risk,
                request == null ? null : request.argNames(),
                request == null ? "" : CanonicalArgs.shortHash(request.args),
                r == null || r.ok,
                r == null || r.error == null ? null : r.error.code.wire,
                elapsedMs, request == null ? "" : request.requestId, event);
    }

    /** token 校验失败时没有 request；只记录事件与错误码，不记录 token。 */
    private void recordConfirm(ActionRequest request, ActionDescriptor d,
                               boolean ok, String errorCode, String event) {
        if (audit == null) return;
        audit.record(request == null ? "" : request.clientId,
                request == null ? "" : request.action,
                "", d == null ? null : d.risk,
                request == null ? null : request.argNames(),
                request == null ? "" : CanonicalArgs.shortHash(request.args),
                ok, errorCode, 0, request == null ? "" : request.requestId, event);
    }

    // ------------------------------------------------------------- 响应装配

    /** ActionResult + 可选的向后兼容扩展字段（例如 NEEDS_CONFIRMATION 的 token）。 */
    private static final class Outcome {
        final ActionResult result;
        final Map<String, Object> extra;

        Outcome(ActionResult result) {
            this(result, null);
        }

        Outcome(ActionResult result, Map<String, Object> extra) {
            this.result = result;
            this.extra = extra;
        }

        Map<String, Object> toJson() {
            Map<String, Object> o = result.toJson();
            if (extra != null) o.putAll(extra);
            return o;
        }

        String toJsonString() {
            return Json.write(toJson());
        }
    }

    public static String rpcResult(Object id, Object result) {
        try {
            Map<String, Object> o = Json.obj();
            o.put("jsonrpc", "2.0");
            o.put("id", id);
            o.put("result", result);
            return Json.write(o);
        } catch (Throwable t) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,"
                    + "\"error\":{\"code\":-32603,\"message\":\"encode failed\"}}";
        }
    }

    public static String rpcError(Object id, int code, String message) {
        try {
            Map<String, Object> err = Json.obj();
            err.put("code", (long) code);
            err.put("message", message);
            Map<String, Object> o = Json.obj();
            o.put("jsonrpc", "2.0");
            o.put("id", id);
            o.put("error", err);
            return Json.write(o);
        } catch (Throwable t) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,"
                    + "\"error\":{\"code\":-32603,\"message\":\"encode failed\"}}";
        }
    }

    private static Map<String, Object> errorTool(AgentError.Code code, String message) {
        return errorTool(AgentError.of(code, message));
    }

    private static Map<String, Object> errorTool(AgentError error) {
        return toolText(ActionResult.failure("", error).toJsonString(), true);
    }

    private static Map<String, Object> toolText(String text, boolean error) {
        Map<String, Object> part = Json.obj();
        part.put("type", "text");
        part.put("text", text == null ? "" : text);
        List<Object> content = Json.arr();
        content.add(part);
        Map<String, Object> r = Json.obj();
        r.put("content", content);
        r.put("isError", error);
        return r;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> props,
                                                    List<Object> required) {
        Map<String, Object> s = Json.obj();
        s.put("type", "object");
        s.put("properties", props);
        s.put("required", required);
        s.put("additionalProperties", Boolean.FALSE);
        return s;
    }

    /** 按字符（不切断代理对）截断；超长时结果长度不超过 max。 */
    static String truncate(String text, int max) {
        String s = text == null ? "" : text;
        if (max <= 0) return "";
        if (s.length() <= max) return s;
        int end = max;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = Json.obj();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    private static List<Object> list(String... items) {
        List<Object> l = Json.arr();
        if (items != null) {
            for (String s : items) l.add(s);
        }
        return l;
    }
}
