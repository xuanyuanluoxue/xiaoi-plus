package plus.xiaoi.module.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import plus.xiaoi.module.Json;

/**
 * 一次动作调用的请求（纯 JVM）。
 *
 * <p>公共字段与 PLAN 5.1.2 对齐：context / sessionId / idempotencyKey / dryRun /
 * async / timeoutMs / clientId。另外保留 confirmToken 与 requestId：
 * 前者供 WP1.3 确认流程使用（WP1.1 只校验是否存在），后者用于调用方关联日志。</p>
 *
 * <p>不可变；args / context 在构造时深拷贝。用 {@link #builder(String)} 构造，
 * 或用 {@link #from(Map)} 从 xiaoai_action 的 arguments 解析。</p>
 */
public final class ActionRequest {

    public final String action;
    public final Map<String, Object> args;
    public final Object context;
    public final String sessionId;
    public final String idempotencyKey;
    public final boolean dryRun;
    public final boolean async;
    public final long timeoutMs;
    public final String clientId;
    public final String confirmToken;
    public final String requestId;

    private ActionRequest(String action, Map<String, Object> args, Object context,
                          String sessionId, String idempotencyKey, boolean dryRun,
                          boolean async, long timeoutMs, String clientId,
                          String confirmToken, String requestId) {
        this.action = AgentValues.text(action);
        this.args = args;
        this.context = context;
        this.sessionId = AgentValues.text(sessionId);
        this.idempotencyKey = AgentValues.text(idempotencyKey);
        this.dryRun = dryRun;
        this.async = async;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 0;
        this.clientId = AgentValues.text(clientId);
        this.confirmToken = AgentValues.text(confirmToken);
        this.requestId = AgentValues.text(requestId);
    }

    public static Builder builder(String action) {
        return new Builder(action);
    }

    /**
     * 解析 xiaoai_action / CLI 的 arguments。
     *
     * @throws IllegalArgumentException args 不是 object
     */
    public static ActionRequest from(Map<?, ?> raw) {
        Map<?, ?> m = raw == null ? Collections.emptyMap() : raw;
        Object args = m.get("args");
        if (args != null && !(args instanceof Map)) {
            throw new IllegalArgumentException("args 必须是 object");
        }
        Builder b = builder(Json.optString(m, "action", ""))
                .context(AgentValues.deep(m.get("context")))
                .sessionId(Json.optString(m, "sessionId", ""))
                .idempotencyKey(Json.optString(m, "idempotencyKey", ""))
                .dryRun(Json.optBoolean(m, "dryRun", false))
                .async(Json.optBoolean(m, "async", false))
                .timeoutMs(Json.optLong(m, "timeoutMs", 0))
                .clientId(Json.optString(m, "clientId", ""))
                .confirmToken(Json.optString(m, "confirmToken", ""))
                .requestId(Json.optString(m, "requestId", ""));
        if (args instanceof Map) b.args((Map<?, ?>) args);
        return b.build();
    }

    public boolean hasArg(String name) {
        return name != null && args.containsKey(name) && args.get(name) != null;
    }

    public Object arg(String name) {
        return name == null ? null : args.get(name);
    }

    public String stringArg(String name, String fallback) {
        return Json.optString(args, name, fallback);
    }

    public long longArg(String name, long fallback) {
        return Json.optLong(args, name, fallback);
    }

    public boolean booleanArg(String name, boolean fallback) {
        return Json.optBoolean(args, name, fallback);
    }

    public List<String> stringListArg(String name) {
        return AgentValues.strings(args.get(name));
    }

    /** 参数名列表（不含值），用于 dryRun 摘要与审计脱敏。 */
    public List<String> argNames() {
        List<String> names = new java.util.ArrayList<String>();
        for (String k : args.keySet()) names.add(k);
        return Collections.unmodifiableList(names);
    }

    public static final class Builder {
        private String action;
        private Map<String, Object> args = new LinkedHashMap<String, Object>();
        private Object context;
        private String sessionId = "";
        private String idempotencyKey = "";
        private boolean dryRun;
        private boolean async;
        private long timeoutMs;
        private String clientId = "";
        private String confirmToken = "";
        private String requestId = "";

        private Builder(String action) {
            this.action = action;
        }

        public Builder args(Map<?, ?> value) {
            this.args = new LinkedHashMap<String, Object>();
            if (value != null) {
                for (Map.Entry<?, ?> e : value.entrySet()) {
                    if (e.getKey() == null) continue;
                    this.args.put(String.valueOf(e.getKey()), AgentValues.deep(e.getValue()));
                }
            }
            return this;
        }

        public Builder arg(String name, Object value) {
            if (name != null && value != null) args.put(name, AgentValues.deep(value));
            return this;
        }

        public Builder context(Object value) {
            this.context = AgentValues.deep(value);
            return this;
        }

        public Builder sessionId(String value) {
            this.sessionId = value;
            return this;
        }

        public Builder idempotencyKey(String value) {
            this.idempotencyKey = value;
            return this;
        }

        public Builder dryRun(boolean value) {
            this.dryRun = value;
            return this;
        }

        public Builder async(boolean value) {
            this.async = value;
            return this;
        }

        public Builder timeoutMs(long value) {
            this.timeoutMs = value;
            return this;
        }

        public Builder clientId(String value) {
            this.clientId = value;
            return this;
        }

        public Builder confirmToken(String value) {
            this.confirmToken = value;
            return this;
        }

        public Builder requestId(String value) {
            this.requestId = value;
            return this;
        }

        public ActionRequest build() {
            return new ActionRequest(action,
                    AgentValues.map(args), context,
                    sessionId, idempotencyKey, dryRun, async, timeoutMs,
                    clientId, confirmToken, requestId);
        }
    }
}
