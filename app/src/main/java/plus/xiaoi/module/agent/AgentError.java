package plus.xiaoi.module.agent;

import java.util.Map;

import plus.xiaoi.module.Json;

/**
 * 稳定错误码 + 可读文案 + 是否可重试（纯 JVM）。
 *
 * <p>错误码是 Agent 的协议面：只能追加，不能复用旧码表达新含义。
 * {@link #toJson()} 输出 code / message / retryable，可直接嵌进返回信封。</p>
 */
public final class AgentError {

    /** 稳定错误码。wire 值原样出现在 JSON 里。 */
    public enum Code {
        /** 动作不存在。 */
        UNKNOWN_ACTION("UNKNOWN_ACTION", false, "未知动作"),
        /** 参数缺失、类型错误、越界或未知字段。 */
        INVALID_ARGUMENT("INVALID_ARGUMENT", false, "参数不合法"),
        /** 当前设备/后端暂不支持。 */
        NOT_AVAILABLE("NOT_AVAILABLE", false, "当前设备不支持该动作"),
        /** 缺少所需权限（root / shell / Android 运行时权限）。 */
        PERMISSION_DENIED("PERMISSION_DENIED", false, "缺少执行该动作的权限"),
        /** 风险动作需要确认，当前没有有效 confirmToken。 */
        NEEDS_CONFIRMATION("NEEDS_CONFIRMATION", false, "该动作需要确认"),
        /** 执行超时；不假装成功。 */
        TIMEOUT("TIMEOUT", true, "动作执行超时"),
        /** 未分类的内部失败；异常详情只进日志/审计。 */
        INTERNAL("INTERNAL", false, "动作执行失败"),
        /** 队列/并发已满，稍后可重试。 */
        BUSY("BUSY", true, "服务忙，请稍后重试"),
        /** 用户拒绝了确认。 */
        CONFIRMATION_DENIED("CONFIRMATION_DENIED", false, "确认被拒绝"),
        /** confirm token 过期。 */
        CONFIRMATION_EXPIRED("CONFIRMATION_EXPIRED", false, "确认已过期"),
        /** 状态冲突（重复确认、幂等键参数不一致等）。 */
        CONFLICT("CONFLICT", false, "状态冲突"),
        /** 小爱进程不在线（代理类能力）。 */
        XIAOI_OFFLINE("XIAOI_OFFLINE", true, "小爱不可用"),
        /** 小爱记忆文件损坏。 */
        MEMORY_CORRUPT("MEMORY_CORRUPT", false, "记忆内容损坏");

        public final String wire;
        public final boolean retryable;
        public final String defaultMessage;

        Code(String wire, boolean retryable, String defaultMessage) {
            this.wire = wire;
            this.retryable = retryable;
            this.defaultMessage = defaultMessage;
        }
    }

    public final Code code;
    public final String message;
    public final boolean retryable;

    private AgentError(Code code, String message, boolean retryable) {
        this.code = code == null ? Code.INTERNAL : code;
        this.message = message == null || message.length() == 0
                ? this.code.defaultMessage : message;
        this.retryable = retryable;
    }

    /** 使用错误码的默认文案与默认可重试性。 */
    public static AgentError of(Code code) {
        Code c = code == null ? Code.INTERNAL : code;
        return new AgentError(c, c.defaultMessage, c.retryable);
    }

    /** 使用自定义文案，可重试性取错误码默认值。 */
    public static AgentError of(Code code, String message) {
        Code c = code == null ? Code.INTERNAL : code;
        return new AgentError(c, message, c.retryable);
    }

    /** 显式覆盖可重试性（例如临时不可用的 NOT_AVAILABLE）。 */
    public static AgentError of(Code code, String message, boolean retryable) {
        return new AgentError(code, message, retryable);
    }

    public AgentError withMessage(String message) {
        return new AgentError(code, message, retryable);
    }

    /** 错误码 + 文案 + 可重试性。 */
    public Map<String, Object> toJson() {
        Map<String, Object> o = Json.obj();
        o.put("code", code.wire);
        o.put("message", message);
        o.put("retryable", retryable);
        return o;
    }

    public String toJsonString() {
        return Json.write(toJson());
    }

    @Override
    public String toString() {
        return code.wire + ": " + message;
    }
}
