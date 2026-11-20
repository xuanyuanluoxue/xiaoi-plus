package plus.xiaoi.module;

import java.util.List;
import java.util.Map;

import plus.xiaoi.module.control.ControlLogRing;
import plus.xiaoi.module.control.MergedLogEntry;

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

        // ---- 合并视图：三个进程、乱序到达、按事件时间排序 ----
        ControlLogRing m = new ControlLogRing();
        String core = "{\"proc\":\"com.miui.voiceassist:core\",\"lines\":["
                + "{\"seq\":1,\"ts\":1000,\"pid\":11,\"level\":\"I\",\"tag\":\"\",\"msg\":\"A1\"},"
                + "{\"seq\":2,\"ts\":1002,\"pid\":11,\"level\":\"I\",\"tag\":\"\",\"msg\":\"A2\"}]}";
        String main = "{\"proc\":\"com.miui.voiceassist\",\"lines\":["
                + "{\"seq\":1,\"ts\":1001,\"pid\":22,\"level\":\"W\",\"tag\":\"mcp\",\"msg\":\"B1\"}]}";
        T.eq("第一段结构化增量", 2, m.appendJson(core));
        T.eq("第二段结构化增量", 1, m.appendJson(main));
        T.eq("合并条数", 3, m.size());

        List<MergedLogEntry> snap = m.snapshot(10, 0);
        T.eq("按事件时间排序而不是到达顺序", "A1", snap.get(0).msg);
        T.eq("跨进程交错", "B1", snap.get(1).msg);
        T.eq("排序第三条", "A2", snap.get(2).msg);
        T.eq("来源进程", "com.miui.voiceassist:core", snap.get(0).proc);
        T.eq("来源 pid", 22, snap.get(1).pid);
        T.eq("来源 tag", "mcp", snap.get(1).tag);
        T.eq("合并 seq 全局递增", 3L, m.lastSeq());

        // 重复推送必须被去重（同一 pid 的同一 srcSeq）
        T.eq("重复推送被丢弃", 0, m.appendJson(core));
        // 来源进程重启：srcSeq 回退，必须接受而不是当重复
        String restarted = "{\"proc\":\"com.miui.voiceassist:core\",\"lines\":["
                + "{\"seq\":1,\"ts\":2000,\"pid\":11,\"level\":\"I\",\"msg\":\"A1-new\"}]}";
        T.eq("进程重启后重新接收", 1, m.appendJson(restarted));

        // 合并视图的 since/limit 用全局 seq
        long last = m.lastSeq();
        T.eq("since 游标过滤", 0, m.snapshot(10, last).size());
        T.eq("limit 取尾部", 1, m.snapshot(1, 0).size());
        T.eq("limit=0 空", 0, m.snapshot(0, 0).size());

        // JSON 视图：前四字段与进程内 dump 同构，追加 tag/ts/pid/proc + scope
        Map<?, ?> root = (Map<?, ?>) Json.read(m.dumpJson(10, 0));
        T.eq("合并 scope", "merged", root.get("scope"));
        List<?> jlines = (List<?>) root.get("lines");
        Map<?, ?> first = (Map<?, ?>) jlines.get(0);
        T.eq("json msg", "A1", first.get("msg"));
        T.eq("json ts", 1000L, first.get("ts"));
        T.eq("json pid", 11L, first.get("pid"));
        T.eq("json proc", "com.miui.voiceassist:core", first.get("proc"));
        T.ok("json t 是时间", ((String) first.get("t")).matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));

        // 结构化行的文本渲染必须能被 lastError 的定长解析识别
        ControlLogRing one2 = new ControlLogRing();
        one2.appendJson("{\"proc\":\"p\",\"lines\":["
                + "{\"seq\":1,\"ts\":1700000000000,\"pid\":7,\"level\":\"E\",\"msg\":\"boom\"}]}");
        String rendered = one2.lines().get(0);
        T.ok("渲染带时间与级别", rendered.charAt(12) == ' ' && rendered.charAt(13) == 'E');

        return T.report("ControlLogRingTest");
    }
}
