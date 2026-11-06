package plus.xiaoi.module.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import plus.xiaoi.module.Json;

/**
 * 动作注册表与执行流水线（纯 JVM，无 android 依赖）。
 *
 * <p>WP1.1 的契约层：描述、参数校验、执行器选择、dryRun、确认状态、超时/异常映射、
 * capabilities 输出都在这里定死。后续 AndroidExecutor / RootExecutor 只实现
 * {@link ActionExecutor} 并注册，不再改协议。</p>
 *
 * <p>执行顺序：查动作 → 校验参数 → dryRun → danger 默认拒绝 → 选择可用执行器 →
 * confirm 状态 → 执行并映射异常。所有失败都返回 {@link ActionResult}，
 * 不向调用方抛异常。</p>
 */
public final class ActionRegistry {

    private static final AtomicLong SEQ = new AtomicLong();

    private final Map<String, Entry> actions = new LinkedHashMap<String, Entry>();

    /** 注册描述与执行器；同名描述只保留首次注册，同名执行器覆盖。 */
    public ActionRegistry register(ActionDescriptor descriptor, ActionExecutor executor) {
        if (descriptor == null) throw new IllegalArgumentException("descriptor 不能为空");
        Entry entry = actions.get(descriptor.name);
        if (entry == null) {
            entry = new Entry(descriptor);
            actions.put(descriptor.name, entry);
        }
        if (executor != null) {
            entry.executors.put(executor.name(), executor);
        }
        return this;
    }

    /** 按名字找描述；不存在返回 null。 */
    public ActionDescriptor find(String name) {
        Entry e = name == null ? null : actions.get(name);
        return e == null ? null : e.descriptor;
    }

    /** 已注册描述（注册顺序）。 */
    public List<ActionDescriptor> list() {
        List<ActionDescriptor> out = new ArrayList<ActionDescriptor>();
        for (Entry e : actions.values()) out.add(e.descriptor);
        return Collections.unmodifiableList(out);
    }

    /**
     * xiaoai_capabilities 的 actions 数组。
     *
     * @param includeUnavailable false 时过滤掉当前没有任何可用执行器的动作
     */
    public List<Object> capabilitiesJson(boolean includeUnavailable) {
        List<Object> out = Json.arr();
        for (Entry e : actions.values()) {
            boolean available = available(e);
            if (!includeUnavailable && !available) continue;

            Map<String, Object> m = e.descriptor.toJson();
            m.put("available", available);
            List<Object> executors = Json.arr();
            Set<String> seen = new HashSet<String>();
            for (String pref : e.descriptor.executorPreference) {
                ActionExecutor ex = e.executors.get(pref);
                if (ex == null || seen.contains(ex.name())
                        || !executorAllowed(e.descriptor, ex)) continue;
                seen.add(ex.name());
                executors.add(executorJson(ex));
            }
            for (ActionExecutor ex : e.executors.values()) {
                if (seen.contains(ex.name())
                        || !executorAllowed(e.descriptor, ex)) continue;
                seen.add(ex.name());
                executors.add(executorJson(ex));
            }
            m.put("executors", executors);
            out.add(m);
        }
        return out;
    }

    public String capabilitiesJsonString(boolean includeUnavailable) {
        return Json.write(capabilitiesJson(includeUnavailable));
    }

    /** 从 xiaoai_action 的 arguments 直接执行；解析失败也返回 INVALID_ARGUMENT。 */
    public ActionResult executePayload(Map<String, Object> payload) {
        try {
            return execute(ActionRequest.from(payload));
        } catch (Throwable t) {
            String msg = t.getMessage() == null ? "请求解析失败" : "请求解析失败：" + t.getMessage();
            return new ActionResult(false, "", "", null, null,
                    AgentError.of(AgentError.Code.INVALID_ARGUMENT, msg),
                    0, newId("req"), null);
        }
    }

    /** 执行一个动作；任何失败都映射成稳定错误码，不抛到调用方。 */
    public ActionResult execute(ActionRequest request) {
        long started = System.nanoTime();
        if (request == null) {
            return new ActionResult(false, "", "", null, null,
                    AgentError.of(AgentError.Code.INVALID_ARGUMENT, "request 不能为空"),
                    0, newId("req"), null);
        }
        String requestId = request.requestId.length() > 0 ? request.requestId : newId("req");

        Entry entry = actions.get(request.action);
        if (entry == null) {
            return failure(request.action, "", AgentError.of(AgentError.Code.UNKNOWN_ACTION,
                    "未知动作：" + request.action), requestId, started);
        }
        ActionDescriptor d = entry.descriptor;

        AgentError invalid = validate(d, request.args);
        if (invalid != null) return failure(d.name, "", invalid, requestId, started);

        if (request.dryRun) return dryRun(entry, request, requestId, started);

        // 危险动作默认拒绝；WP1.3 接入显式授权前不执行。
        if (d.risk == ActionRisk.DANGER) {
            return failure(d.name, "", AgentError.of(AgentError.Code.PERMISSION_DENIED,
                    "risk=danger 默认拒绝：" + d.name), requestId, started);
        }

        ActionExecutor executor = choose(entry);
        if (executor == null) {
            AgentError err = d.needsCapability()
                    ? AgentError.of(AgentError.Code.PERMISSION_DENIED,
                    "需要 " + d.requiredCapability + " 权限：" + d.name)
                    : AgentError.of(AgentError.Code.NOT_AVAILABLE,
                    "当前没有可用的执行器：" + d.name);
            return failure(d.name, "", err, requestId, started);
        }

        if (d.risk == ActionRisk.CONFIRM && request.confirmToken.trim().length() == 0) {
            return failure(d.name, executor.name(), AgentError.of(
                    AgentError.Code.NEEDS_CONFIRMATION,
                    "需要确认后才能执行：" + d.name), requestId, started);
        }

        long timeoutMs = effectiveTimeout(request.timeoutMs, d.timeoutMs);
        try {
            ActionResult r = executor.execute(request);
            long elapsedMs = elapsedMs(started);
            if (r == null) {
                return new ActionResult(false, d.name, executor.name(), null,
                        null, AgentError.of(AgentError.Code.INTERNAL,
                        "执行器返回 null：" + executor.name()),
                        elapsedMs, requestId, null);
            }
            if (timeoutMs > 0 && elapsedMs > timeoutMs) {
                List<String> warnings = new ArrayList<String>(r.warnings);
                warnings.add("执行器返回时已超过 " + timeoutMs + "ms，本次结果作废");
                return new ActionResult(false, d.name, executor.name(), null, warnings,
                        AgentError.of(AgentError.Code.TIMEOUT,
                                "动作执行超时（" + elapsedMs + "ms > " + timeoutMs + "ms）"),
                        elapsedMs, requestId, r.auditId);
            }
            AgentError err = r.error;
            if (err == null && !r.ok) {
                err = AgentError.of(AgentError.Code.INTERNAL,
                        "执行器返回失败但未给出错误：" + executor.name());
            }
            String action = r.action.length() > 0 ? r.action : d.name;
            return new ActionResult(r.ok && err == null, action, executor.name(),
                    r.result, r.warnings, err, elapsedMs, requestId, r.auditId);
        } catch (ActionExecutor.TimeoutException t) {
            return timeoutError(d, executor, t.getMessage(), requestId, started);
        } catch (java.util.concurrent.TimeoutException t) {
            return timeoutError(d, executor, t.getMessage(), requestId, started);
        } catch (InterruptedException t) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, d.name, executor.name(), null, null,
                    AgentError.of(AgentError.Code.INTERNAL, "执行被中断"),
                    elapsedMs(started), requestId, null);
        } catch (Throwable t) {
            return new ActionResult(false, d.name, executor.name(), null, null,
                    AgentError.of(AgentError.Code.INTERNAL,
                            "执行器异常：" + t.getClass().getSimpleName()),
                    elapsedMs(started), requestId, null);
        }
    }

    // ------------------------------------------------------------- 执行辅助

    private ActionResult dryRun(Entry entry, ActionRequest request, String requestId,
                                long started) {
        ActionDescriptor d = entry.descriptor;
        ActionExecutor planned = plannedExecutor(entry);
        String executorName = planned == null ? "" : planned.name();

        Map<String, Object> plan = Json.obj();
        plan.put("dryRun", true);
        plan.put("action", d.name);
        plan.put("executor", executorName);
        plan.put("executorAvailable", planned != null && isAvailable(planned));
        plan.put("risk", d.risk.wire);
        plan.put("wouldRequireConfirmation", d.risk.needsConfirmation());
        plan.put("args", request.args);
        List<Object> argNames = Json.arr();
        for (String name : request.argNames()) argNames.add(name);
        plan.put("argNames", argNames);

        List<String> warnings = new ArrayList<String>();
        warnings.add("dryRun：未调用执行器");
        return new ActionResult(true, d.name, executorName, plan, warnings, null,
                elapsedMs(started), requestId, null);
    }

    private ActionResult timeoutError(ActionDescriptor d, ActionExecutor executor,
                                      String detail, String requestId, long started) {
        String msg = "动作执行超时：" + d.name;
        if (detail != null && detail.length() > 0) msg = msg + "（" + detail + "）";
        return new ActionResult(false, d.name, executor.name(), null, null,
                AgentError.of(AgentError.Code.TIMEOUT, msg),
                elapsedMs(started), requestId, null);
    }

    private ActionResult failure(String action, String executor, AgentError error,
                                 String requestId, long started) {
        return new ActionResult(false, action, executor, null, null, error,
                elapsedMs(started), requestId, null);
    }

    private static long elapsedMs(long startedNanos) {
        long ms = (System.nanoTime() - startedNanos) / 1000000L;
        return ms > 0 ? ms : 0;
    }

    private static long effectiveTimeout(long requested, long descriptorTimeout) {
        if (descriptorTimeout <= 0) return requested > 0 ? requested : 0;
        if (requested <= 0) return descriptorTimeout;
        return Math.min(requested, descriptorTimeout);
    }

    private static String newId(String prefix) {
        return prefix + Long.toHexString(System.currentTimeMillis())
                + "_" + SEQ.incrementAndGet();
    }

    private static boolean available(Entry entry) {
        for (ActionExecutor ex : entry.executors.values()) {
            if (executorAllowed(entry.descriptor, ex) && isAvailable(ex)) return true;
        }
        return false;
    }

    /**
     * requiredCapability 是执行器准入条件，不只是展示字段。
     * root 动作只能由 root 执行器执行；shell 动作允许 shizuku/root；
     * none 动作不限制执行器。
     */
    private static boolean executorAllowed(ActionDescriptor d, ActionExecutor ex) {
        if (d == null || ex == null) return false;
        if (!d.needsCapability()) return true;
        if (ActionDescriptor.CAP_ROOT.equals(d.requiredCapability)) {
            return "root".equals(ex.name());
        }
        if (ActionDescriptor.CAP_SHELL.equals(d.requiredCapability)) {
            return "shizuku".equals(ex.name()) || "root".equals(ex.name());
        }
        return true;
    }

    private static boolean isAvailable(ActionExecutor ex) {
        if (ex == null) return false;
        try {
            return ex.available();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 按 executorPreference 选第一个可用执行器；没有偏好时按注册顺序。 */
    private static ActionExecutor choose(Entry entry) {
        if (!entry.descriptor.executorPreference.isEmpty()) {
            for (String pref : entry.descriptor.executorPreference) {
                ActionExecutor ex = entry.executors.get(pref);
                if (ex != null && executorAllowed(entry.descriptor, ex)
                        && isAvailable(ex)) {
                    return ex;
                }
            }
            return null;
        }
        for (ActionExecutor ex : entry.executors.values()) {
            if (executorAllowed(entry.descriptor, ex) && isAvailable(ex)) return ex;
        }
        return null;
    }

    /** dryRun 的计划执行器：优先偏好里已注册的，即使当前不可用也标出来。 */
    private static ActionExecutor plannedExecutor(Entry entry) {
        if (!entry.descriptor.executorPreference.isEmpty()) {
            for (String pref : entry.descriptor.executorPreference) {
                ActionExecutor ex = entry.executors.get(pref);
                if (executorAllowed(entry.descriptor, ex)) return ex;
            }
            return null;
        }
        for (ActionExecutor ex : entry.executors.values()) {
            if (executorAllowed(entry.descriptor, ex)) return ex;
        }
        return null;
    }

    private static Map<String, Object> executorJson(ActionExecutor ex) {
        Map<String, Object> m = Json.obj();
        m.put("name", ex.name());
        m.put("available", isAvailable(ex));
        return m;
    }

    // ------------------------------------------------------------- 参数校验

    /**
     * 校验 args 对 schema：required、additionalProperties、基本类型、enum、
     * minimum/maximum、array items、简单 anyOf(required)。
     *
     * @return null 表示通过
     */
    static AgentError validate(ActionDescriptor descriptor, Map<String, Object> args) {
        Map<String, Object> schema = descriptor.inputSchema;
        if (schema == null || schema.isEmpty()) return null;
        return validateObject(schema, args == null ? Collections.<String, Object>emptyMap()
                : args, "", 0);
    }

    private static AgentError validateObject(Map<String, Object> schema,
                                             Map<String, Object> value,
                                             String path, int depth) {
        if (depth > 8) return invalid(path, "嵌套层级过深");
        String type = Json.optString(schema, "type", "object");
        if (type.length() > 0 && !"object".equals(type)) {
            return invalid(path, "schema 不是 object");
        }

        Map<String, Object> props = asMap(schema.get("properties"));
        List<?> required = asList(schema.get("required"));
        if (required != null) {
            for (Object o : required) {
                String name = String.valueOf(o);
                if (!value.containsKey(name) || value.get(name) == null) {
                    return invalid(path, "缺少必需参数 " + name);
                }
            }
        }

        Object ap = schema.get("additionalProperties");
        boolean additional = !(ap instanceof Boolean) || ((Boolean) ap).booleanValue();
        if (!additional) {
            for (String key : value.keySet()) {
                if (props == null || !props.containsKey(key)) {
                    return invalid(path, "未知参数 " + key);
                }
            }
        }

        if (props != null) {
            for (Map.Entry<String, Object> e : value.entrySet()) {
                Map<String, Object> prop = asMap(props.get(e.getKey()));
                if (prop == null) continue;
                AgentError err = validateValue(prop, e.getValue(),
                        path.length() == 0 ? e.getKey() : path + "." + e.getKey(), depth);
                if (err != null) return err;
            }
        }

        List<?> anyOf = asList(schema.get("anyOf"));
        if (anyOf != null && anyOf.size() > 0) {
            boolean matched = false;
            for (Object sub : anyOf) {
                Map<String, Object> s = asMap(sub);
                if (s != null && matchesRequired(s, value)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return invalid(path, "至少需要提供 " + anyOfNames(anyOf) + " 之一");
            }
        }
        return null;
    }

    private static AgentError validateValue(Map<String, Object> schema, Object value,
                                            String path, int depth) {
        if (value == null) return null;
        String type = Json.optString(schema, "type", "");
        if (type.length() > 0 && !matchesType(type, value)) {
            return invalid(path, "必须是 " + type);
        }

        List<?> enums = asList(schema.get("enum"));
        if (enums != null && enums.size() > 0 && !enumContains(enums, value)) {
            return invalid(path, "必须是 " + enumNames(enums) + " 之一");
        }

        if (value instanceof Number) {
            double n = ((Number) value).doubleValue();
            Object min = schema.get("minimum");
            Object max = schema.get("maximum");
            if (min instanceof Number && n < ((Number) min).doubleValue()) {
                return invalid(path, "不能小于 " + min);
            }
            if (max instanceof Number && n > ((Number) max).doubleValue()) {
                return invalid(path, "不能大于 " + max);
            }
        }

        if (value instanceof String) {
            int len = ((String) value).length();
            Object min = schema.get("minLength");
            Object max = schema.get("maxLength");
            if (min instanceof Number && len < ((Number) min).intValue()) {
                return invalid(path, "长度不能小于 " + min);
            }
            if (max instanceof Number && len > ((Number) max).intValue()) {
                return invalid(path, "长度不能大于 " + max);
            }
        }

        if (value instanceof Map) {
            return validateObject(schema, asMap(value), path, depth + 1);
        }

        if (value instanceof List) {
            Map<String, Object> items = asMap(schema.get("items"));
            if (items != null) {
                List<?> list = (List<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    AgentError err = validateValue(items, list.get(i),
                            path + "[" + i + "]", depth + 1);
                    if (err != null) return err;
                }
            }
        }
        return null;
    }

    private static boolean matchesType(String type, Object value) {
        if ("string".equals(type)) return value instanceof String;
        if ("integer".equals(type)) {
            return value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long;
        }
        if ("number".equals(type)) return value instanceof Number;
        if ("boolean".equals(type)) return value instanceof Boolean;
        if ("object".equals(type)) return value instanceof Map;
        if ("array".equals(type)) return value instanceof List;
        if ("null".equals(type)) return value == null;
        return true;
    }

    private static boolean matchesRequired(Map<String, Object> schema,
                                           Map<String, Object> value) {
        List<?> required = asList(schema.get("required"));
        if (required == null) return true;
        for (Object o : required) {
            String name = String.valueOf(o);
            if (!value.containsKey(name) || value.get(name) == null) return false;
        }
        return true;
    }

    private static boolean enumContains(List<?> values, Object value) {
        String s = String.valueOf(value);
        for (Object o : values) {
            if (o != null && String.valueOf(o).equals(s)) return true;
        }
        return false;
    }

    private static String enumNames(List<?> values) {
        StringBuilder sb = new StringBuilder();
        for (Object o : values) {
            if (o == null) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(o);
            if (sb.length() > 80) break;
        }
        return sb.toString();
    }

    private static String anyOfNames(List<?> anyOf) {
        StringBuilder sb = new StringBuilder();
        for (Object sub : anyOf) {
            Map<String, Object> schema = asMap(sub);
            List<?> required = schema == null ? null : asList(schema.get("required"));
            if (required == null) continue;
            for (Object o : required) {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(o);
            }
        }
        return sb.length() == 0 ? "指定参数" : sb.toString();
    }

    private static AgentError invalid(String path, String message) {
        String prefix = path == null || path.length() == 0 ? "" : path + ": ";
        return AgentError.of(AgentError.Code.INVALID_ARGUMENT, prefix + message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static List<?> asList(Object v) {
        return v instanceof List ? (List<?>) v : null;
    }

    // ------------------------------------------------------------- 内部结构

    private static final class Entry {
        final ActionDescriptor descriptor;
        final Map<String, ActionExecutor> executors =
                new LinkedHashMap<String, ActionExecutor>();

        Entry(ActionDescriptor descriptor) {
            this.descriptor = descriptor;
        }
    }
}
