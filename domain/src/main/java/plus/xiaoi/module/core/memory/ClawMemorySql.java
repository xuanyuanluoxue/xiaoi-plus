package plus.xiaoi.module.core.memory;

/**
 * 小爱加密记忆库（{@code claw_memory.db}）的只读查询构造器。
 *
 * <p>库是 SQLCipher 加密的，口令由小爱自己的 AndroidKeyStore 条目解开，因此只能在
 * voiceassist 进程内打开（见 {@code platform/agent/ClawMemoryDb}）。这里只做纯 JVM
 * 的 SQL 拼接，桌面单测可逐字校验。</p>
 *
 * <p>结构（Room，版本见 {@code ClawMemoryDatabase_Impl}）：
 * {@code conversation}（会话问答原文）、{@code memory_meta}（抽出的记忆条目，
 * {@code MEMORY.md} 的原料）、{@code memory_file_sync_state}（落盘同步状态）。</p>
 */
public final class ClawMemorySql {

    /** 库文件名（位于 {@code databases/} 下）。 */
    public static final String DB_NAME = "claw_memory.db";

    private ClawMemorySql() {}

    /** 一条待执行的语句；{@code args} 可为 null。 */
    public static final class Query {
        public final String sql;
        public final String[] args;

        Query(String sql, String[] args) {
            this.sql = sql;
            this.args = args;
        }
    }

    /** 各表行数与时间范围（epoch ms，空表为 0）。 */
    public static String stats() {
        return "SELECT 'conversation' AS t, COUNT(*) AS n,"
                + " COALESCE(MIN(timestamp), 0) AS lo, COALESCE(MAX(timestamp), 0) AS hi"
                + " FROM conversation"
                + " UNION ALL SELECT 'memory_meta', COUNT(*),"
                + " COALESCE(MIN(timestamp), 0), COALESCE(MAX(timestamp), 0) FROM memory_meta"
                + " UNION ALL SELECT 'memory_file_sync_state', COUNT(*),"
                + " COALESCE(MIN(updated_at), 0), COALESCE(MAX(updated_at), 0)"
                + " FROM memory_file_sync_state";
    }

    /** 按 {@code mem_type} 统计条数。 */
    public static String typeDistribution() {
        return "SELECT mem_type AS t, COUNT(*) AS n FROM memory_meta"
                + " GROUP BY mem_type ORDER BY n DESC, t ASC LIMIT 50";
    }

    /**
     * 查记忆条目（{@code memory_meta}）。
     *
     * @param q     关键词，空串表示不限；匹配 {@code mem_value} / {@code mem_key}
     * @param since 起始时间（epoch ms），0 表示不限
     * @param limit 返回条数（调用方已夹到 1..200）
     */
    public static Query live(String q, long since, int limit) {
        String kw = q == null ? "" : q.trim();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT id, mem_key, mem_value, mem_type, mem_filename, mem_source,")
                .append(" mem_extractor, timestamp, date, status FROM memory_meta WHERE 1=1");
        java.util.List<String> args = new java.util.ArrayList<String>(3);
        if (kw.length() > 0) {
            sql.append(" AND (mem_value LIKE ? ESCAPE '\\' OR mem_key LIKE ? ESCAPE '\\')");
            String pattern = "%" + escapeLike(kw) + "%";
            args.add(pattern);
            args.add(pattern);
        }
        if (since > 0L) {
            sql.append(" AND timestamp >= ?");
            args.add(String.valueOf(since));
        }
        sql.append(" ORDER BY timestamp DESC, id DESC LIMIT ").append(Math.max(1, limit));
        return new Query(sql.toString(), args.isEmpty() ? null : args.toArray(new String[0]));
    }

    /** LIKE 里 {@code \} {@code %} {@code _} 都要转义（配合 {@code ESCAPE '\'}）。 */
    static String escapeLike(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' || c == '%' || c == '_') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }
}
