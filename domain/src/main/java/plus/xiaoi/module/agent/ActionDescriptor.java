package plus.xiaoi.module.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import plus.xiaoi.module.Json;

/**
 * 动作描述（纯 JVM，不可变）。
 *
 * <p>描述是 Agent 可见的协议：name / schema / risk / capability / executor 偏好。
 * 用 {@link #builder(String)} 构造；所有集合在 build 时深拷贝并包成 unmodifiable。</p>
 */
public final class ActionDescriptor {

    public static final String CAP_NONE = "none";
    public static final String CAP_SHELL = "shell";
    public static final String CAP_ROOT = "root";

    public final String name;
    public final String version;
    public final String description;
    public final Map<String, Object> inputSchema;
    public final Map<String, Object> outputSchema;
    /** 执行器优先级，例如 android / shizuku / root。 */
    public final List<String> executorPreference;
    /** none / shell / root。 */
    public final String requiredCapability;
    public final ActionRisk risk;
    public final boolean idempotent;
    public final long timeoutMs;
    public final List<Object> examples;

    private ActionDescriptor(Builder b) {
        this.name = b.name == null ? "" : b.name;
        this.version = b.version == null ? "1" : b.version;
        this.description = b.description == null ? "" : b.description;
        this.inputSchema = AgentValues.map(b.inputSchema);
        this.outputSchema = AgentValues.map(b.outputSchema);
        this.executorPreference = AgentValues.strings(b.executorPreference);
        this.requiredCapability = b.requiredCapability == null ? CAP_NONE : b.requiredCapability;
        this.risk = b.risk == null ? ActionRisk.SAFE : b.risk;
        this.idempotent = b.idempotent;
        this.timeoutMs = b.timeoutMs > 0 ? b.timeoutMs : 0;
        this.examples = AgentValues.list(b.examples);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** 是否需要 root / shell 等特权。 */
    public boolean needsCapability() {
        return requiredCapability.length() > 0 && !CAP_NONE.equals(requiredCapability);
    }

    /** 描述本身；capabilities 会在此基础上追加 available / executors。 */
    public Map<String, Object> toJson() {
        Map<String, Object> o = Json.obj();
        o.put("name", name);
        o.put("version", version);
        o.put("description", description);
        o.put("risk", risk.wire);
        o.put("requiredCapability", requiredCapability);
        o.put("idempotent", idempotent);
        o.put("timeoutMs", timeoutMs);
        o.put("executorPreference", new ArrayList<Object>(executorPreference));
        o.put("inputSchema", inputSchema);
        o.put("outputSchema", outputSchema);
        o.put("examples", examples);
        return o;
    }

    public String toJsonString() {
        return Json.write(toJson());
    }

    public static final class Builder {
        private String name;
        private String version = "1";
        private String description = "";
        private Map<String, Object> inputSchema = new LinkedHashMap<String, Object>();
        private Map<String, Object> outputSchema = new LinkedHashMap<String, Object>();
        private List<String> executorPreference = new ArrayList<String>();
        private String requiredCapability = CAP_NONE;
        private ActionRisk risk = ActionRisk.SAFE;
        private boolean idempotent = true;
        private long timeoutMs = 5000;
        private List<Object> examples = new ArrayList<Object>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder version(String value) {
            this.version = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder inputSchema(Map<String, Object> value) {
            this.inputSchema = value;
            return this;
        }

        public Builder outputSchema(Map<String, Object> value) {
            this.outputSchema = value;
            return this;
        }

        public Builder executorPreference(String... names) {
            this.executorPreference = new ArrayList<String>();
            if (names != null) {
                for (String n : names) {
                    if (n != null && n.length() > 0) this.executorPreference.add(n);
                }
            }
            return this;
        }

        public Builder requiredCapability(String value) {
            this.requiredCapability = value;
            return this;
        }

        public Builder risk(ActionRisk value) {
            this.risk = value;
            return this;
        }

        public Builder idempotent(boolean value) {
            this.idempotent = value;
            return this;
        }

        public Builder timeoutMs(long value) {
            this.timeoutMs = value;
            return this;
        }

        public Builder examples(List<Object> value) {
            this.examples = value;
            return this;
        }

        public ActionDescriptor build() {
            if (name == null || name.length() == 0) {
                throw new IllegalArgumentException("action name 不能为空");
            }
            return new ActionDescriptor(this);
        }
    }
}
