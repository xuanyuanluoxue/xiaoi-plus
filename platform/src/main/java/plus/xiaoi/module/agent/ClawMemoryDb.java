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
import plus.xiaoi.module.core.Contract;
import plus.xiaoi.module.core.memory.ClawMemorySql;

/**
 * 小爱加密记忆库 {@code claw_memory.db} 的只读访问。
 *
 * <p>库是 SQLCipher 加密的，口令由小爱自己的 AndroidKeyStore 条目
 * （{@code claw_memory_db_master_key}）解开（见 {@link #passphrase}），只有 voiceassist
 * 进程能拿到。SQLCipher 类走反射引用小爱自带的实现；拿不到就返回结构化错误，不假装成功。</p>
 *
 * <p>只读、不开事务、每次调用开关一次库，避免和 App 自己的连接池抢锁。</p>
 */
public final class ClawMemoryDb {

    /** 口令记录（SharedPreferences 名 + 两个键）。 */
    private static final String PREFS = "claw_memory_db_security";
    private static final String PREF_PASS = "db_enc_passphrase_v1";
    private static final String PREF_IV = "db_enc_iv_v1";
    /** AndroidKeyStore 里包口令的 AES-256-GCM 主密钥。 */
    private static final String KEY_ALIAS = "claw_memory_db_master_key";
    /** 小爱自带的 SQLCipher 实现。 */
    private static final String SQLCIPHER_DB = "net.zetetic.database.sqlcipher.SQLiteDatabase";
    private static final String CURSOR_FACTORY =
            "net.zetetic.database.sqlcipher.SQLiteDatabase$CursorFactory";
    private static final String SQLCIPHER_HOOK =
            "net.zetetic.database.sqlcipher.SQLiteDatabaseHook";
    /** {@code SQLiteDatabase.OPEN_READONLY}。 */
    private static final int OPEN_READONLY = 1;

    private ClawMemoryDb() {}

    /** 通道是否可用（只查类与口令，不打开库）。 */
    public static String probe(Context context) {
        try {
            passphrase(context);
            return "clawdb=ok";
        } catch (Throwable t) {
            return "clawdb=none";
        }
    }

    /** 各表行数 + {@code mem_type} 分布；不含任何正文。 */
    public static String stats(Context context) {
        Map<String, Object> out = Json.obj();
        try {
            List<Map<String, Object>> tables = rows(context, ClawMemorySql.stats(), null,
                    new String[]{"t", "n", "lo", "hi"});
            List<Map<String, Object>> types = rows(context, ClawMemorySql.typeDistribution(), null,
                    new String[]{"t", "n"});
            out.put("ok", Boolean.TRUE);
            out.put("db", ClawMemorySql.DB_NAME);
            out.put("tables", tables);
            out.put("types", types);
            return Json.write(out);
        } catch (ClawDbException e) {
            return error(e.code, e.getMessage());
        } catch (Throwable t) {
            return error("CLAWDB_QUERY_FAILED", String.valueOf(t));
        }
    }

    /** 实时查 {@code memory_meta}（记忆条目），正文按 {@link Contract#MEMORY_LIVE_VALUE_CHARS} 截断。 */
    public static String live(Context context, String q, long since, int limit) {
        Map<String, Object> out = Json.obj();
        try {
            ClawMemorySql.Query query = ClawMemorySql.live(q, since, limit);
            List<Map<String, Object>> items = rows(context, query.sql, query.args,
                    new String[]{"id", "mem_key", "mem_value", "mem_type", "mem_filename",
                            "mem_source", "mem_extractor", "timestamp", "date", "status"});
            for (Map<String, Object> item : items) {
                Object v = item.get("mem_value");
                if (v instanceof String && ((String) v).length() > Contract.MEMORY_LIVE_VALUE_CHARS) {
                    item.put("mem_value", ((String) v).substring(0, Contract.MEMORY_LIVE_VALUE_CHARS));
                    item.put("truncated", Boolean.TRUE);
                }
            }
            out.put("ok", Boolean.TRUE);
            out.put("count", (long) items.size());
            out.put("items", items);
            return Json.write(out);
        } catch (ClawDbException e) {
            return error(e.code, e.getMessage());
        } catch (Throwable t) {
            return error("CLAWDB_QUERY_FAILED", String.valueOf(t));
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 执行一条查询，按给定列名逐行映射成 JSON 对象（值统一取字符串）。 */
    private static List<Map<String, Object>> rows(Context context, String sql, String[] args,
                                                  String[] columns) throws Exception {
        if (context == null) throw new ClawDbException("CONTEXT_UNAVAILABLE", "拿不到小爱 Context");
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new ClawDbException("MAIN_THREAD_BLOCKED", "拒绝在主线程读记忆库");
        }
        byte[] pass = passphrase(context);
        ClassLoader cl = context.getClassLoader();
        String path = context.getDatabasePath(ClawMemorySql.DB_NAME).getAbsolutePath();

        Class<?> factory = Class.forName(CURSOR_FACTORY, false, cl);
        Class<?> hook = Class.forName(SQLCIPHER_HOOK, false, cl);
        Class<?> dbClass = Class.forName(SQLCIPHER_DB, false, cl);
        Method open = dbClass.getMethod("openDatabase",
                String.class, byte[].class, factory, int.class, hook);
        Object db;
        try {
            db = open.invoke(null, path, pass, null, OPEN_READONLY, null);
        } catch (Throwable t) {
            throw new ClawDbException("CLAWDB_OPEN_FAILED", rootMessage(t));
        }
        if (db == null) throw new ClawDbException("CLAWDB_OPEN_FAILED", "openDatabase 返回 null");

        Cursor cursor = null;
        try {
            Method rawQuery = dbClass.getMethod("rawQuery", String.class, String[].class);
            Object c = rawQuery.invoke(db, sql, args);
            if (!(c instanceof Cursor)) {
                throw new ClawDbException("CLAWDB_QUERY_FAILED", "rawQuery 未返回 Cursor");
            }
            cursor = (Cursor) c;
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
            if (cursor != null) {
                try { cursor.close(); } catch (Throwable ignored) { /* 关闭失败不影响结果 */ }
            }
            try {
                Method close = dbClass.getMethod("close");
                close.invoke(db);
            } catch (Throwable ignored) {
                // 关闭失败只记一行，不覆盖已拿到的数据
            }
        }
    }

    /**
     * 解出 SQLCipher 口令：SharedPreferences 里的密文由 AndroidKeyStore 的
     * {@code claw_memory_db_master_key}（AES-256-GCM）包着，解出 32 字节后按小爱自己的
     * 约定转成小写 hex 文本（64 字符）作为口令。
     *
     * <p>只依赖系统 API（KeyStore / Cipher）与稳定的 prefs 键名，不碰小爱的混淆类名。
     * 口令不缓存、不落日志。</p>
     */
    private static byte[] passphrase(Context context) throws ClawDbException {
        try {
            android.content.SharedPreferences sp =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String pass = sp.getString(PREF_PASS, null);
            String iv = sp.getString(PREF_IV, null);
            if (pass == null || iv == null || pass.length() == 0 || iv.length() == 0) {
                throw new IllegalStateException("prefs 缺少口令记录（可能是全新安装，还未建库）");
            }
            byte[] cipher = android.util.Base64.decode(pass, android.util.Base64.NO_WRAP);
            byte[] vec = android.util.Base64.decode(iv, android.util.Base64.NO_WRAP);

            java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            java.security.KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
            if (!(entry instanceof java.security.KeyStore.SecretKeyEntry)) {
                throw new IllegalStateException("Keystore 缺少 " + KEY_ALIAS);
            }
            javax.crypto.SecretKey master =
                    ((java.security.KeyStore.SecretKeyEntry) entry).getSecretKey();

            javax.crypto.Cipher cipherer = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipherer.init(javax.crypto.Cipher.DECRYPT_MODE, master,
                    new javax.crypto.spec.GCMParameterSpec(128, vec));
            byte[] raw = cipherer.doFinal(cipher);

            if (raw.length != 32) {
                throw new IllegalStateException("解出的口令长度异常: " + raw.length);
            }
            return hex(raw).getBytes(java.nio.charset.Charset.forName("UTF-8"));
        } catch (Throwable t) {
            Logs.e("clawdb 口令不可用: " + rootMessage(t));
            throw new ClawDbException("CLAWDB_UNAVAILABLE",
                    "拿不到记忆库口令（AndroidKeyStore 或 prefs 变化）：" + rootMessage(t));
        }
    }

    /** 小写 hex（与小爱自己的 hex 编码一致）。 */
    private static String hex(byte[] raw) {
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(digits[(b >> 4) & 0x0f]).append(digits[b & 0x0f]);
        }
        return sb.toString();
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
    private static final class ClawDbException extends Exception {
        final String code;

        ClawDbException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
