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
        T.eq("密码键值", "password=***", Redact.redact("password=p@ss"));
        T.eq("secret 键值", "secret: ***", Redact.redact("secret: s3cr3t"));
        T.eq("手机号键值", "phone=***", Redact.redact("phone=13800138000"));
        T.eq("设备标识 oaid", "oaid=***", Redact.redact("oaid=abc-123"));
        T.eq("设备标识 imei", "imei=***", Redact.redact("imei=860000000000000"));
        T.eq("设备标识 device_id", "device_id=***", Redact.redact("device_id=deadbeef"));
        T.eq("设备标识 android_id", "android_id=***",
                Redact.redact("android_id=0123456789abcdef"));
        T.eq("普通文本不变", "tool=xiaoai_status ok=true",
                Redact.redact("tool=xiaoai_status ok=true"));
        T.eq("model/port 不误伤", "model=deepseek port=8771",
                Redact.redact("model=deepseek port=8771"));
        T.eq("mobile 键值", "mobile=***", Redact.redact("mobile=13900139000"));
        T.eq("summarize 截断", "abcde…", Redact.summarize("abcde fghij", 5));
        T.eq("summarize 折叠空白", "a b", Redact.summarize("a   b", 20));
        T.eq("summarize null", "", Redact.summarize(null, 10));

        // host：日志只能出现 host，路径里的租户 key 必须被丢掉
        T.eq("host 标准", "api.crashmail.ccwu.cc",
                Redact.host("https://api.crashmail.ccwu.cc/v1"));
        T.eq("host 带端口", "127.0.0.1:8771",
                Redact.host("http://127.0.0.1:8771/mcp"));
        T.eq("host 丢路径与查询", "a.com",
                Redact.host("https://a.com/v1/tenant-sk-123?k=1"));
        T.eq("host 丢 userinfo", "a.com",
                Redact.host("https://user:pass@a.com/v1"));
        T.eq("host 无 scheme", "a.com", Redact.host("a.com/v1"));
        T.eq("host 空", "?", Redact.host(""));
        T.eq("host null", "?", Redact.host(null));
        T.eq("host 只有 scheme", "?", Redact.host("https://"));

        return T.report("RedactTest");
    }
}
