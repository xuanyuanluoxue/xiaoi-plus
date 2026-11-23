package plus.xiaoi.module.core.memory;

import java.util.Arrays;
import java.util.List;

/**
 * 官方记忆库 {@code memory_data_<uid>.db} 的只读查询构造器。
 *
 * <p>库是 SQLCipher 加密的，密钥由小爱自己的 JCEKS 保管，所以只在 voiceassist 进程内
 * 通过 {@code MemoryDbHelper.createDb(Context)} 打开（见 {@code platform/agent/OfficialMemoryDb}）。
 * 这里只做纯 JVM 的 SQL 拼接与表名白名单，桌面单测可逐字校验。</p>
 *
 * <p>表（Room，见 {@code MemoryDbHelper}）：{@code memory_public_table}（人物档案）、
 * {@code memory_private_table}（隐私条目）、{@code memory_relation_table}（关系）、
 * {@code image_table}（配图）、{@code collect_data_table}（收藏，迁移脚本建）。</p>
 */
public final class OfficialMemorySql {

    /** 有 {@code syncStatus} 列、需要看同步分布的表。 */
    public static final List<String> SYNC_TABLES = Arrays.asList(
            "memory_public_table", "memory_relation_table", "collect_data_table");

    /** 只统计条数的表。 */
    public static final List<String> COUNT_TABLES = Arrays.asList(
            "memory_private_table", "image_table");

    /** 小爱自己标记的「待同步」值（{@code changeDbStatusToNeedSync} 写 2）。 */
    public static final String PENDING_STATUS = "2";

    private OfficialMemorySql() {}

    /** 表名白名单校验：只允许字母数字下划线，避免拼接注入。 */
    public static boolean allowed(String table) {
        if (table == null || table.length() == 0) return false;
        if (!(SYNC_TABLES.contains(table) || COUNT_TABLES.contains(table))) return false;
        for (int i = 0; i < table.length(); i++) {
            char c = table.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /** 库里实际有哪些记忆表（判断版本差异，不猜）。 */
    public static String listTables() {
        return "SELECT name FROM sqlite_master WHERE type = 'table'"
                + " AND (name LIKE 'memory\\_%' ESCAPE '\\' OR name = 'image_table'"
                + " OR name = 'collect_data_table') ORDER BY name";
    }

    /** 单表条数。 */
    public static String count(String table) {
        return "SELECT COUNT(*) AS n FROM `" + require(table) + "`";
    }

    /** 单表 {@code syncStatus} 分布。 */
    public static String syncBreakdown(String table) {
        return "SELECT syncStatus AS s, COUNT(*) AS n FROM `" + require(table) + "`"
                + " GROUP BY syncStatus ORDER BY s";
    }

    /**
     * 待同步的行（只给身份与时间，不含 {@code meta} 正文）。
     *
     * @param table 只允许 {@code memory_public_table} / {@code memory_relation_table}
     */
    public static String pendingRows(String table, int limit) {
        int n = Math.max(1, Math.min(limit, 100));
        if ("memory_public_table".equals(table)) {
            return "SELECT id AS id, name AS name, nickName AS nickName,"
                    + " relationship AS relationship, category AS category,"
                    + " syncStatus AS syncStatus, updateTime AS updateTime"
                    + " FROM `memory_public_table` WHERE syncStatus = " + PENDING_STATUS
                    + " ORDER BY updateTime DESC LIMIT " + n;
        }
        if ("memory_relation_table".equals(table)) {
            return "SELECT entityId AS id, name AS name, nickName AS nickName,"
                    + " relationship AS relationship, syncStatus AS syncStatus,"
                    + " updateTime AS updateTime FROM `memory_relation_table`"
                    + " WHERE syncStatus = " + PENDING_STATUS
                    + " ORDER BY updateTime DESC LIMIT " + n;
        }
        throw new IllegalArgumentException("不支持的表: " + table);
    }

    private static String require(String table) {
        if (!allowed(table)) throw new IllegalArgumentException("不支持的表: " + table);
        return table;
    }
}
