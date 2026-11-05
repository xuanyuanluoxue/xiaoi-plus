package plus.xiaoi.module;

import java.util.List;
import java.util.Map;

/** 环形缓冲与 dump 输出测试（依赖 XSharedPreferences/XposedBridge 桩为 no-op）。 */
public final class LogsTest {

    private LogsTest() {}

    public static int run() {
        // 桩环境：add() 里的 XposedBridge.log 是 no-op，file() 反射失败后落盘跳过
        for (int i = 1; i <= 350; i++) {
            Logs.add(i % 10 == 0 ? "E" : "I", "msg-" + i);
        }
        eq("cap 300", 300, Logs.count());

        String dump = Logs.dump(80, 0);
        Object v = Json.read(dump);
        Map<?, ?> root = (Map<?, ?>) v;
        eq("dump count is lines returned", 80L, root.get("count"));
        eq("dump lastSeq", 350L, root.get("lastSeq"));
        List<?> lines = (List<?>) root.get("lines");
        eq("dump default limit", 80, lines.size());
        Map<?, ?> first = (Map<?, ?>) lines.get(0);
        eq("dump tail starts at 271", 271L, first.get("seq"));
        eq("dump msg preserved", "msg-271", first.get("msg"));
        eq("dump level preserved", "I", first.get("level"));
        ok("dump t format", ((String) first.get("t")).matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));

        // 字段顺序锁定（与 v0.7 的 org.json 输出一致）
        ok("dump field order", dump.startsWith("{\"count\":")
                && dump.contains(",\"lastSeq\":") && dump.contains(",\"lines\":["));

        // since 过滤
        root = (Map<?, ?>) Json.read(Logs.dump(300, 348));
        lines = (List<?>) root.get("lines");
        eq("since filter size", 2, lines.size());
        eq("since filter first", 349L, ((Map<?, ?>) lines.get(0)).get("seq"));

        // limit 边界
        root = (Map<?, ?>) Json.read(Logs.dump(1, 0));
        eq("limit 1", 1, ((List<?>) root.get("lines")).size());

        // 中文与特殊字符的消息
        Logs.add("I", "中文 \"引号\" 与\\反斜杠");
        root = (Map<?, ?>) Json.read(Logs.dump(1, 0));
        Map<?, ?> last = (Map<?, ?>) ((List<?>) root.get("lines")).get(0);
        eq("unicode msg round trip", "中文 \"引号\" 与\\反斜杠", last.get("msg"));

        return T.report("LogsTest");
    }

    private static void ok(String n, boolean c) { T.ok(n, c); }
    private static void eq(String n, Object e, Object a) { T.eq(n, e, a); }
}
