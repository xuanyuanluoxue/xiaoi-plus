package plus.xiaoi.module.core.hook;

import plus.xiaoi.module.UsageCounter;

/**
 * 模型调用信号的纯判定（WP2）。
 *
 * <p>{@link UsageCounter#mainCall} 是统计口径的唯一来源；这里只做空值归一，
 * 避免 Hook 内联“reported 为空再取配置”的分支各自演化。</p>
 */
public final class UsageSignal {

    private UsageSignal() {}

    /** tools 非空或消息数 ≥6 视为一次主对话，其余算子调用。 */
    public static boolean mainCall(int messageCount, int toolCount) {
        return UsageCounter.mainCall(messageCount, toolCount);
    }

    /** 上报的 model 为空时回退到配置模型；两者都空返回空串。 */
    public static String modelOrFallback(String reported, String fallback) {
        if (reported != null && reported.length() > 0) return reported;
        return fallback == null ? "" : fallback;
    }
}