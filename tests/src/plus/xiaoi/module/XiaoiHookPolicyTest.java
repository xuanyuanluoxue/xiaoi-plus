package plus.xiaoi.module;

import plus.xiaoi.module.core.hook.UsageSignal;
import plus.xiaoi.module.core.hook.XiaoiOverridePolicy;

/** WP2：Hook 策略与调用信号的纯 JVM 测试。 */
public final class XiaoiHookPolicyTest {

    private XiaoiHookPolicyTest() {}

    public static int run() {
        // getter 覆盖：暂停/空值不改写
        T.eq("override 暂停不覆盖", null,
                XiaoiOverridePolicy.override(false, "https://x"));
        T.eq("override 空串不覆盖", null,
                XiaoiOverridePolicy.override(true, ""));
        T.eq("override null 不覆盖", null,
                XiaoiOverridePolicy.override(true, null));
        T.eq("override 启用覆盖", "https://x",
                XiaoiOverridePolicy.override(true, "https://x"));

        // vision：暂停不覆盖小爱原生结论
        T.ok("vision 暂停 false", !XiaoiOverridePolicy.supportsVision(false));
        T.ok("vision 启用 true", XiaoiOverridePolicy.supportsVision(true));

        // provider id：enabled + marker 同时满足
        T.eq("provider 暂停", null,
                XiaoiOverridePolicy.providerId(false, true, "xiaoi-plus"));
        T.eq("provider marker 关", null,
                XiaoiOverridePolicy.providerId(true, false, "xiaoi-plus"));
        T.eq("provider 空 id", null,
                XiaoiOverridePolicy.providerId(true, true, ""));
        T.eq("provider 正常", "xiaoi-plus",
                XiaoiOverridePolicy.providerId(true, true, "xiaoi-plus"));

        // 调用信号：tools 非空或消息数 >=6 视为主对话
        T.ok("usage tools 非空为主", UsageSignal.mainCall(2, 1));
        T.ok("usage 消息 >=6 为主", UsageSignal.mainCall(6, 0));
        T.ok("usage 子调用", !UsageSignal.mainCall(2, 0));

        // 模型回退：reported 优先，空时用 fallback
        T.eq("usage model 优先 reported", "m-reported",
                UsageSignal.modelOrFallback("m-reported", "m-config"));
        T.eq("usage model 回退 config", "m-config",
                UsageSignal.modelOrFallback("", "m-config"));
        T.eq("usage model 都空", "",
                UsageSignal.modelOrFallback(null, null));

        return T.report("XiaoiHookPolicyTest");
    }
}