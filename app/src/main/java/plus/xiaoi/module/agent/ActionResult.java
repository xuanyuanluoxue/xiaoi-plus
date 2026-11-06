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
            o.put("result", result);
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
}
