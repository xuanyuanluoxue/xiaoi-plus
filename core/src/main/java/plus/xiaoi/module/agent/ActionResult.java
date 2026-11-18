package plus.xiaoi.module.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import plus.xiaoi.module.Json;

/**
 * 统一的动作返回信封（纯 JVM）。
 *
 * <p>成功：{@code ok=true, result=..., warnings=[]}。
 * 部分成功：{@code ok=true} 且 warnings 非空，不把部分成功伪装成完全成功。
 * 失败：{@code ok=false}，code / message / retryable 直接放在顶层，与 PLAN 5.2 一致。</p>
 *
 * <p>WP1.5：{@link #toJson()} / {@link #fromJson(Map)} 可逆，幂等缓存用它们把终态结果
 * 写进 App 私有文件。result 里名为 token / apiKey / context 等敏感键在序列化时递归剔除，
 * 不落盘、不回传。</p>
 */
public final class ActionResult {

    public final boolean ok;
    public final String action;
    public final String executor;
    public final Map<String, Object> result;
    public final List<String> warnings;
    public final AgentError error;
    public final long elapsedMs;
    public final String requestId;
    /** WP1.3 接审计后填充；WP1.1 允许为 null。 */
    public final String auditId;

    public ActionResult(boolean ok, String action, String executor,
                        Map<String, Object> result, List<String> warnings,
                        AgentError error, long elapsedMs, String requestId,
                        String auditId) {
        this.ok = ok && error == null;
        this.action = AgentValues.text(action);
        this.executor = AgentValues.text(executor);
        this.result = AgentValues.map(result);
        this.warnings = AgentValues.strings(warnings);
        this.error = error != null ? error
                : (ok ? null : AgentError.of(AgentError.Code.INTERNAL, "动作失败但未给出错误"));
        this.elapsedMs = elapsedMs > 0 ? elapsedMs : 0;
        this.requestId = AgentValues.text(requestId);
        this.auditId = auditId == null || auditId.length() == 0 ? null : auditId;
    }

    public static ActionResult ok(String action, Map<String, Object> result) {
        return new ActionResult(true, action, "", result, null, null, 0, "", null);
    }

    public static ActionResult ok(String action, String executor, Map<String, Object> result) {
        return new ActionResult(true, action, executor, result, null, null, 0, "", null);
    }

    public static ActionResult failure(String action, AgentError error) {
        return new ActionResult(false, action, "", null, null, error, 0, "", null);
    }

    public static ActionResult failure(String action, String executor, AgentError error) {
        return new ActionResult(false, action, executor, null, null, error, 0, "", null);
    }

    /** 结果信封。error 非空时只输出稳定错误码，不把异常细节带回协议面。 */
    public Map<String, Object> toJson() {
        Map<String, Object> o = Json.obj();
        o.put("ok", ok);
        if (action.length() > 0) o.put("action", action);
        if (executor.length() > 0) o.put("executor", executor);
        if (error != null) {
            o.put("code", error.code.wire);
            o.put("message", error.message);
            o.put("retryable", error.retryable);
        } else {
            o.put("result", redactMap(result));
        }
        List<Object> w = new ArrayList<Object>();
        for (String s : warnings) w.add(s);
        o.put("warnings", w);
        o.put("elapsedMs", elapsedMs);
        o.put("requestId", requestId.length() > 0 ? requestId : null);
        o.put("auditId", auditId);
        return o;
    }

    public String toJsonString() {
        return Json.write(toJson());
    }

    /**
     * 从 {@link #toJson()} 的 Map 还原信封（幂等缓存恢复用）。
     *
     * <p>不抛异常：{@code m} 为空、缺少 boolean {@code ok}、result 类型不对等结构问题返回
     * {@code null}，由调用方丢弃该条目；未知错误码收敛为 {@code INTERNAL}，保留原 message。
     * 不读取也不反序列化 token / apiKey / context（这些键在序列化时已剔除）。</p>
     */
    public static ActionResult fromJson(Map<?, ?> m) {
        if (m == null) return null;
        Object okValue = m.get("ok");
        if (!(okValue instanceof Boolean)) return null;
        boolean ok = ((Boolean) okValue).booleanValue();

        Object resultValue = m.get("result");
        // 旧快照里若残留敏感键也在这里剔除，不进入内存。
        Map<String, Object> parsedResult = resultValue instanceof Map
                ? redactMap((Map<?, ?>) resultValue) : null;

        List<String> parsedWarnings = new ArrayList<String>();
        Object warningsValue = m.get("warnings");
        if (warningsValue instanceof List) {
            for (Object o : (List<?>) warningsValue) {
                if (o != null) parsedWarnings.add(String.valueOf(o));
            }
        }

        AgentError parsedError = null;
        if (!ok) {
            AgentError.Code code = codeOf(Json.optString(m, "code", ""));
            if (code == null) code = AgentError.Code.INTERNAL;
            String message = Json.optString(m, "message", code.defaultMessage);
            Object retryableValue = m.get("retryable");
            boolean retryable = retryableValue instanceof Boolean
                    ? ((Boolean) retryableValue).booleanValue() : code.retryable;
            parsedError = AgentError.of(code, message, retryable);
        }

        String auditId = text(m.get("auditId"));
        return new ActionResult(ok,
                Json.optString(m, "action", ""),
                Json.optString(m, "executor", ""),
                parsedResult,
                parsedWarnings,
                parsedError,
                Json.optLong(m, "elapsedMs", 0),
                Json.optString(m, "requestId", ""),
                auditId.length() == 0 ? null : auditId);
    }

    /** wire 错误码 → 枚举；未知码返回 null（调用方决定收敛策略）。 */
    private static AgentError.Code codeOf(String wire) {
        if (wire == null) return null;
        for (AgentError.Code c : AgentError.Code.values()) {
            if (c.wire.equals(wire)) return c;
        }
        return null;
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 递归剔除敏感键：token / confirmToken / apiKey / password / secret / context。 */
    static Map<String, Object> redactMap(Map<?, ?> m) {
        Map<String, Object> out = Json.obj();
        if (m == null) return out;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() == null) continue;
            String key = String.valueOf(e.getKey());
            if (isSensitive(key)) continue;
            out.put(key, redactValue(e.getValue()));
        }
        return out;
    }

    private static Object redactValue(Object v) {
        if (v instanceof Map) return redactMap((Map<?, ?>) v);
        if (v instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object o : (List<?>) v) out.add(redactValue(o));
            return out;
        }
        return v;
    }

    private static boolean isSensitive(String key) {
        return CanonicalArgs.isSensitive(key) || "context".equalsIgnoreCase(key);
    }
}
