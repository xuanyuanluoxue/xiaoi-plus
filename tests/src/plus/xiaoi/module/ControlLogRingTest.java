package plus.xiaoi.module;

import java.util.List;

import plus.xiaoi.module.control.ControlLogRing;

/** 控制面日志：容量上限、逐行裁剪与密钥脱敏（不落盘）。 */
public final class ControlLogRingTest {

    private ControlLogRingTest() {}

    public static int run() {
        ControlLogRing ring = new ControlLogRing();
        ring.append("line1\n\nline2\n");
        T.eq("多行追加并跳过空行", 2, ring.size());
        T.eq("dump 用换行连接", "line1\nline2", ring.dump());

        for (int i = 0; i < ControlLogRing.MAX_LINES + 50; i++) {
            ring.append("l" + i);
        }
        T.eq("环形缓冲上限", ControlLogRing.MAX_LINES, ring.size());
        List<String> lines = ring.lines();
        T.eq("淘汰最旧", "l50", lines.get(0));

        String longLine = new String(new char[ControlLogRing.MAX_LINE_CHARS + 100]).replace('\0', 'x');
        ControlLogRing one = new ControlLogRing();
        one.append(longLine);
        T.eq("单行裁剪", ControlLogRing.MAX_LINE_CHARS, one.lines().get(0).length());

        String keyJson = ControlLogRing.redact("{\"apiKey\":\"sk-secret-123\"}");
        T.ok("apiKey 脱敏", keyJson.indexOf("sk-secret-123") < 0 && keyJson.indexOf("***") >= 0);

        String tokenQuery = ControlLogRing.redact("GET /mcp?token=abc123&x=1");
        T.ok("token 查询参数脱敏", tokenQuery.indexOf("abc123") < 0);

        String auth = ControlLogRing.redact("Authorization: Bearer deadbeef");
        T.ok("Bearer 脱敏", auth.indexOf("deadbeef") < 0);

        String plain = ControlLogRing.redact("model=deepseek port=8771");
        T.eq("普通日志不误伤", "model=deepseek port=8771", plain);
        return T.report("ControlLogRingTest");
    }
}
