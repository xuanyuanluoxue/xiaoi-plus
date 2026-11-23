package plus.xiaoi.module.agent;

import android.content.Context;
import android.database.Cursor;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import plus.xiaoi.module.Json;
import plus.xiaoi.module.Logs;
import plus.xiaoi.module.core.memory.OfficialMemorySql;

/**
 * 官方记忆库 {@code memory_data_<uid>.db} 的只读访问（含同步状态）。
 *
 * <p>密钥由小爱自己的 JCEKS 保管（MMKV 里的口令 → MemoryKeyStore.jceks → MemoryKey），
 * 我们不去解密钥，而是直接调用小爱自己的 {@code MemoryDbHelper.createDb(Context)}
 * 拿它已经开好的 Room 实例，再走 {@code getOpenHelper().getReadableDatabase()} 查询。
 * 因此**只能在 voiceassist 进程内用**，且不关闭这个实例（它和小爱共用）。</p>
 *
 * <p>只读、不写、不开事务；只回条数、{@code syncStatus} 分布与待同步行的身份字段，
 * 不回 {@code meta} 正文。</p>
 */
public final class OfficialMemoryDb {

    /** 小爱自己的库工厂（非混淆类名）。 */
    private static final String HELPER = "com.xiaomi.voiceassistant.database.memory.MemoryDbHelper";

    private OfficialMemoryDb() {}

    /** 汇总同步状态：各表条数 + {@code syncStatus} 分布 + 待同步行。 */
    public static String sync(Context context, int limit) {
        Map<String, Object> out = Json.obj();
        try {
            Object db = open(context);
            List<String> present = tables(db);
            List<Map<String, Object>> tablesOut = new ArrayList<Map<String, Object>>();
            for (String table : present) {
                if (!OfficialMemorySql.allowed(table)) continue;
                Map<String, Object> row = Json.obj();
                row.put("table", table);
                row.put("rows", scalar(db, OfficialMemorySql.count(table)));
                if (OfficialMemorySql.SYNC_TABLES.contains(table)) {
                    row.put("sync", rows(db, OfficialMemorySql.syncBreakdown(table),
                            new String[]{"s", "n"}, null));
                }
                tablesOut.add(row);
            }

            List<Map<String, Object>> pending = new ArrayList<Map<String, Object>>();
            for (String table : new String[]{"memory_public_table", "memory_relation_table"}) {
                if (!present.contains(table)) continue;
                List<Map<String, Object>> items = rows(db,
                        OfficialMemorySql.pendingRows(table, limit),
                        new String[]{"id", "name", "nickName", "relationship", "category",
                                "syncStatus", "updateTime"}, null);
                for (Map<String, Object> item : items) {
                    item.put("table", table);
                    pending.add(item);
                }
            }

            out.put("ok", Boolean.TRUE);
            out.put("tables", tablesOut);
            out.put("pendingStatus", Long.valueOf(OfficialMemorySql.PENDING_STATUS));
            out.put("pendingCount", (long) pending.size());
            out.put("pending", pending);
            return Json.write(out);
        } catch (Cause c) {
            return error(c.code, c.getMessage());
        } catch (Throwable t) {
            return error("OFFICIALDB_FAILED", String.valueOf(t));
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 拿小爱的 Room 实例（首次调用会触发小爱自己的建库/迁移）。 */
    private static Object open(Context context) throws Cause {
        if (context == null) throw new Cause("CONTEXT_UNAVAILABLE", "拿不到小爱 Context");
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new Cause("MAIN_THREAD_BLOCKED", "拒绝在主线程读官方记忆库");
        }
        try {
            ClassLoader cl = context.getClassLoader();
            Class<?> helper = Class.forName(HELPER, false, cl);
            Method create = helper.getMethod("createDb", Context.class);
            Object room = create.invoke(null, context.getApplicationContext());
            if (room == null) throw new IllegalStateException("createDb 返回 null");
            Object openHelper = room.getClass().getMethod("getOpenHelper").invoke(room);
            if (openHelper == null) throw new IllegalStateException("getOpenHelper 返回 null");
            Object db = openHelper.getClass().getMethod("getReadableDatabase").invoke(openHelper);
            if (db == null) throw new IllegalStateException("getReadableDatabase 返回 null");
            return db;
        } catch (Throwable t) {
            Logs.e("officialdb 打开失败: " + rootMessage(t));
            throw new Cause("OFFICIALDB_UNAVAILABLE", "打不开官方记忆库：" + rootMessage(t));
        }
    }

    private static List<String> tables(Object db) throws Exception {
        List<Map<String, Object>> list = rows(db, OfficialMemorySql.listTables(),
                new String[]{"name"}, null);
        List<String> out = new ArrayList<String>();
        for (Map<String, Object> m : list) {
            Object v = m.get("name");
            if (v instanceof String) out.add((String) v);
        }
        return out;
    }

    /** 单值查询（COUNT(*) 这类）。 */
    private static Long scalar(Object db, String sql) throws Exception {
        List<Map<String, Object>> list = rows(db, sql, new String[]{"n"}, null);
        if (list.isEmpty()) return Long.valueOf(0L);
        Object v = list.get(0).get("n");
        if (v == null) return Long.valueOf(0L);
        try {
            return Long.valueOf(Long.parseLong(String.valueOf(v)));
        } catch (NumberFormatException e) {
            return Long.valueOf(0L);
        }
    }

    /** 执行查询并映射成 JSON 行；{@code columns} 里的列不存在就跳过。 */
    private static List<Map<String, Object>> rows(Object db, String sql, String[] columns,
                                                  String[] args) throws Exception {
        Method query = db.getClass().getMethod("query", String.class);
        Object c = args == null ? query.invoke(db, sql) : null;
        if (c == null && args != null) {
            Method q2 = db.getClass().getMethod("query", String.class, String[].class);
            c = q2.invoke(db, sql, args);
        }
        if (!(c instanceof Cursor)) throw new IllegalStateException("query 未返回 Cursor");
        Cursor cursor = (Cursor) c;
        try {
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            while (cursor.moveToNext()) {
                Map<String, Object> row = Json.obj();
                for (String col : columns) {
                    int idx = cursor.getColumnIndex(col);
                    if (idx < 0) continue;
                    row.put(col, cursor.isNull(idx) ? null : cursor.getString(idx));
                }
                out.add(row);
            }
            return out;
        } finally {
            try { cursor.close(); } catch (Throwable ignored) { /* 关闭失败不影响结果 */ }
        }
    }

    private static String error(String code, String message) {
        Map<String, Object> o = Json.obj();
        o.put("ok", Boolean.FALSE);
        o.put("code", code);
        o.put("message", message == null ? "" : message);
        return Json.write(o);
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return cur.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    /** 内部错误；{@code code} 直接进结构化 JSON。 */
    private static final class Cause extends Exception {
        final String code;

        Cause(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
