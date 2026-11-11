package plus.xiaoi.module;

import plus.xiaoi.module.core.Redact;

/** 日志脱敏：键值、Bearer、摘要截断。 */
public final class RedactTest {

    private RedactTest() {}

    public static int run() {
        T.eq("token 键值", "token=***", Redact.redact("token=abc123"));
        T.eq("apiKey 键值", "apiKey: ***", Redact.redact("apiKey: sk-xyz"));
        T.eq("Bearer", "Authorization: *** ***",
                Redact.redact("Authorization: Bearer abc.def"));
        T.eq("普通文本不变", "tool=xiaoai_status ok=true",
                Redact.redact("tool=xiaoai_status ok=true"));
        T.eq("summarize 截断", "abcde…", Redact.summarize("abcde fghij", 5));
        T.eq("summarize 折叠空白", "a b", Redact.summarize("a   b", 20));
        T.eq("summarize null", "", Redact.summarize(null, 10));

        return T.report("RedactTest");
    }
}
