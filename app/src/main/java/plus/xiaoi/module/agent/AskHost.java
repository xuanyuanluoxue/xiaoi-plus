package plus.xiaoi.module.agent;

/**
 * 小爱对话代理宿主（纯 JVM 接口）。
 *
 * <p>{@code xiaoai_ask} 把一句话交给手机端小爱执行，并返回本次会话的
 * {@code sessionId}，供调用方后续多轮复用。实现 {@code BridgeAskHost} 只通过
 * 8771 的 {@code /cli} 访问小爱；手机端不持久化 {@code context}，也不把
 * {@code context} 当指令执行。</p>
 */
public interface AskHost {

    /**
     * @param text      要说给小爱的话（context 由实现决定是否前置拼接）
     * @param sessionId 已有会话；空串表示新建
     * @param context   本次上下文（string / object），可为 null
     * @param timeoutMs 本次调用的总预算
     */
    AskResult ask(String text, String sessionId, Object context, long timeoutMs);

    /** 回复文本 + 会话 id；失败时 {@code errorCode} 是稳定错误码。 */
    final class AskResult {
        public final boolean ok;
        public final String text;
        public final String sessionId;
        public final String errorCode;
        public final String message;

        public AskResult(boolean ok, String text, String sessionId,
                         String errorCode, String message) {
            this.ok = ok;
            this.text = text == null ? "" : text;
            this.sessionId = sessionId == null ? "" : sessionId;
            this.errorCode = errorCode == null ? "" : errorCode;
            this.message = message == null ? "" : message;
        }

        public static AskResult success(String text, String sessionId) {
            return new AskResult(true, text, sessionId, "", "");
        }

        public static AskResult failure(String errorCode, String message) {
            return new AskResult(false, "", "", errorCode, message);
        }
    }
}
