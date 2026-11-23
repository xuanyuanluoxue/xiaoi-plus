package plus.xiaoi.module;

import plus.xiaoi.module.core.memory.ClawMemorySql;

/** claw_memory.db 的 SQL 构造器（纯 JVM）。 */
public final class ClawMemorySqlTest {

    private ClawMemorySqlTest() {}

    public static int run() {
        String stats = ClawMemorySql.stats();
        ok("stats 覆盖三张表",
                stats.contains("FROM conversation") && stats.contains("FROM memory_meta")
                        && stats.contains("FROM memory_file_sync_state"));
        ok("stats 只出计数与时间", !stats.contains("SELECT *")
                && stats.contains("COUNT(*)"));

        ClawMemorySql.Query all = ClawMemorySql.live("", 0L, 20);
        ok("live 无关键词时不带 LIKE", !all.sql.contains("LIKE"));
        ok("live 无 since 时不带时间过滤", !all.sql.contains("timestamp >="));
        ok("live 排序与上限", all.sql.contains("ORDER BY timestamp DESC")
                && all.sql.endsWith("LIMIT 20"));
        ok("live 空参为 null", all.args == null);
        ok("live 不查 conversation 正文", !all.sql.contains("conversation"));

        ClawMemorySql.Query kw = ClawMemorySql.live("羽毛球", 1700000000000L, 5);
        eq("live 关键词绑定两个占位", 3, kw.args.length);
        eq("live 关键词加通配", "%羽毛球%", kw.args[0]);
        eq("live since 绑定为字符串", "1700000000000", kw.args[2]);
        ok("live 用 ESCAPE 转义", kw.sql.contains("ESCAPE '\\'"));
        ok("live limit 内联", kw.sql.endsWith("LIMIT 5"));

        ClawMemorySql.Query injected = ClawMemorySql.live("100%' OR 1=1 --", 0L, 5);
        eq("LIKE 通配被转义", "%100\\%' OR 1=1 --%", injected.args[0]);
        ok("关键词不直接进 SQL", !injected.sql.contains("OR 1=1"));

        eq("typeDistribution 按数量排序",
                "SELECT mem_type AS t, COUNT(*) AS n FROM memory_meta"
                        + " GROUP BY mem_type ORDER BY n DESC, t ASC LIMIT 50",
                ClawMemorySql.typeDistribution());
        return T.report("ClawMemorySqlTest");
    }

    private static void ok(String n, boolean c) { T.ok(n, c); }
    private static void eq(String n, Object e, Object a) { T.eq(n, e, a); }
}
