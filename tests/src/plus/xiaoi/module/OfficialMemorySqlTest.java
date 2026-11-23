package plus.xiaoi.module;

import plus.xiaoi.module.core.memory.OfficialMemorySql;

/** 官方记忆库（memory_data_*.db）的 SQL 构造器与表名白名单（纯 JVM）。 */
public final class OfficialMemorySqlTest {

    private OfficialMemorySqlTest() {}

    public static int run() {
        ok("白名单：已知表放行", OfficialMemorySql.allowed("memory_public_table")
                && OfficialMemorySql.allowed("memory_relation_table")
                && OfficialMemorySql.allowed("collect_data_table")
                && OfficialMemorySql.allowed("image_table"));
        ok("白名单：未知表拒绝", !OfficialMemorySql.allowed("sqlite_master")
                && !OfficialMemorySql.allowed("memory_meta")
                && !OfficialMemorySql.allowed(null)
                && !OfficialMemorySql.allowed(""));
        ok("白名单：注入串拒绝", !OfficialMemorySql.allowed("memory_public_table`; DROP TABLE x; --")
                && !OfficialMemorySql.allowed("memory_public_table)")
                && !OfficialMemorySql.allowed("memory_public table"));

        eq("count 带反引号", "SELECT COUNT(*) AS n FROM `memory_public_table`",
                OfficialMemorySql.count("memory_public_table"));
        eq("syncBreakdown 按 s 排序",
                "SELECT syncStatus AS s, COUNT(*) AS n FROM `memory_relation_table`"
                        + " GROUP BY syncStatus ORDER BY s",
                OfficialMemorySql.syncBreakdown("memory_relation_table"));

        String pub = OfficialMemorySql.pendingRows("memory_public_table", 5);
        ok("public 待同步只取 syncStatus=2", pub.contains("WHERE syncStatus = 2"));
        ok("public 待同步不选 meta", !pub.contains("meta"));
        ok("public 待同步带上限", pub.endsWith("LIMIT 5"));

        String rel = OfficialMemorySql.pendingRows("memory_relation_table", 999);
        ok("relation 待同步夹到 100", rel.endsWith("LIMIT 100"));
        ok("relation 用 entityId 当 id", rel.contains("entityId AS id"));

        boolean threw = false;
        try {
            OfficialMemorySql.pendingRows("memory_private_table", 5);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        ok("私有表不出待同步行", threw);

        ok("listTables 只挑记忆表", OfficialMemorySql.listTables().contains("image_table")
                && OfficialMemorySql.listTables().contains("collect_data_table")
                && OfficialMemorySql.listTables().contains("sqlite_master"));
        return T.report("OfficialMemorySqlTest");
    }

    private static void ok(String n, boolean c) { T.ok(n, c); }

    private static void eq(String n, Object e, Object a) { T.eq(n, e, a); }
}
