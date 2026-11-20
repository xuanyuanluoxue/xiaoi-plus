package plus.xiaoi.module;

import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import plus.xiaoi.module.application.log.LogEntry;
import plus.xiaoi.module.application.log.LogRing;
import plus.xiaoi.module.application.log.LogSettings;
import plus.xiaoi.module.core.Contract;
import plus.xiaoi.module.core.LogLevel;

/**
 * 模块运行日志：内存环形缓冲（最近 300 条）+ logcat + 滚动落盘。
 * 只记录 hook 成败、配置变更、端口、调用摘要；**绝不记录 apiKey / token / 对话正文**。
 *
 * <p>环形缓冲的纯逻辑在 {@link LogRing}（:application）；本类只做档位过滤、logcat、
 * 文件镜像与 JSON 装配，保持 {@code i/w/e/d/event/add/dump} 这些调用点不变。</p>
 */
public final class Logs {

    private static final long FILE_MAX = 256 * 1024;
    private static final int FLUSH_LINES = 16;
    private static final int FLUSH_BYTES = 8 * 1024;
    private static final String TAG = "[xiaoi+] ";

    private static final LogRing RING = new LogRing(Contract.LOGS_CAPACITY);
    private static volatile LogFileSink sSink;
    private static File sFile;

    private Logs() {}

    public static synchronized int count() { return RING.count(); }

    /** 设置当前进程日志档位（由控制面配置/App 配置写入）。 */
    public static void setLevel(LogLevel level) {
        LogSettings.set(level);
    }

    public static void i(String msg) {
        event(LogLevel.BRIEF, "I", null, msg);
    }

    public static void w(String msg) {
        event(LogLevel.BRIEF, "W", null, msg);
    }

    public static void e(String msg) {
        event(LogLevel.OFF, "E", null, msg);
    }

    public static void d(String msg) {
        event(LogLevel.DEBUG, "D", null, msg);
    }

    /**
     * 按档位记录一条日志。
     *
     * @param needed   记录该事件所需的档位（E 级不受限制）
     * @param severity I/W/E/D，供 UI 筛选
     * @param tag      mcp/agent/http/ctl 等来源，写进 msg 前缀以便穿过 Binder 字符串协议
     */
    public static void event(LogLevel needed, String severity, String tag, String msg) {
        String level = severity == null || severity.length() == 0 ? "I" : severity;
        if (!"E".equals(level) && !LogSettings.allows(needed)) return;
        String body = msg == null ? "" : msg;
        if (tag != null && tag.length() > 0) body = "[" + tag + "] " + body;
        record(level, tag == null ? "" : tag, body);
    }

    /** 兼容旧调用点与测试：无 tag、无档位过滤。 */
    public static void add(String level, String msg) {
        record(level, "", msg);
    }

    private static synchronized void record(String level, String tag, String msg) {
        try {
            LogEntry e = RING.add(level, tag, msg, System.currentTimeMillis(), pid());
            XposedBridge.log(TAG + e.msg);
            mirror(e);
        } catch (Throwable ignored) {
        }
    }

    /**
     * JSON 数组；since>0 时只返回该序号之后的，limit 取尾部；limit=0 返回空数组。
     *
     * <p>每行固定前四个字段 {@code seq/t/level/msg}（兼容旧消费者），随后追加
     * {@code tag/ts/pid}；{@code t} 是本地时间，{@code ts} 是 epoch 毫秒。</p>
     */
    public static synchronized String dump(int limit, long since) {
        List<LogEntry> list = RING.snapshot(limit, since);
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        List<Object> arr = Json.arr();
        for (LogEntry e : list) {
            Map<String, Object> o = Json.obj();
            o.put("seq", e.seq);
            o.put("t", f.format(new Date(e.tsMillis)));
            o.put("level", e.level);
            o.put("msg", e.msg);
            o.put("tag", e.tag);
            o.put("ts", e.tsMillis);
            o.put("pid", (long) e.pid);
            arr.add(o);
        }
        Map<String, Object> root = Json.obj();
        root.put("count", arr.size());
        root.put("lastSeq", RING.lastSeq());
        root.put("lines", arr);
        return Json.write(root);
    }

    private static void mirror(LogEntry e) {
        try {
            LogFileSink sink = sink();
            if (sink == null) return;
            String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date(e.tsMillis)) + " " + e.level + " " + e.msg;
            sink.append(line);
            // 错误行立即落盘：批量写不能把唯一的失败证据留在内存里。
            if ("E".equals(e.level)) sink.flush();
        } catch (Throwable ignored) {
        }
    }

    /** 把批量缓冲立即写入磁盘；控制面 flushLogs 与退出前使用。 */
    public static void flush() {
        LogFileSink sink = sSink;
        if (sink != null) sink.flush();
    }

    private static LogFileSink sink() {
        LogFileSink sink = sSink;
        if (sink != null) return sink;
        File f = file();
        if (f == null) return null;
        synchronized (Logs.class) {
            if (sSink == null) {
                sSink = new LogFileSink(f, FILE_MAX, FLUSH_LINES, FLUSH_BYTES);
            }
            return sSink;
        }
    }

    /** 当前进程 pid；桌面单测/反射失败返回 0。 */
    private static int pid() {
        try {
            Class<?> p = Class.forName("android.os.Process");
            Object v = p.getMethod("myPid").invoke(null);
            return v instanceof Integer ? ((Integer) v).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static File file() {
        if (sFile != null) return sFile;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            // 反射判定 Context：避免编译期硬引用 android 类，桌面 JVM 才能跑单测
            Class<?> ctxCls = Class.forName("android.content.Context");
            if (ctxCls.isInstance(app)) {
                Object dir = ctxCls.getMethod("getFilesDir").invoke(app);
                if (dir instanceof File) {
                    sFile = new File((File) dir, "xiaoi_plus.log");
                }
            }
        } catch (Throwable ignored) {
        }
        return sFile;
    }
}
