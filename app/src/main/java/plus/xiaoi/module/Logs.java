package plus.xiaoi.module;

import de.robv.android.xposed.XposedBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模块运行日志：内存环形缓冲（最近 300 条）+ logcat + 滚动落盘。
 * 只记录 hook 成败、配置变更、端口、调用摘要；**绝不记录 apiKey / token / 对话正文**。
 */
public final class Logs {

    private static final int CAP = 300;
    private static final long FILE_MAX = 256 * 1024;
    private static final String TAG = "[xiaoi+] ";

    private static final ArrayDeque<Entry> BUF = new ArrayDeque<Entry>();
    private static long sSeq;
    private static long sLastWrite;
    private static File sFile;

    private Logs() {}

    private static final class Entry {
        long seq;
        long ts;
        String level;
        String msg;
    }

    public static synchronized int count() { return BUF.size(); }

    public static void i(String msg) {
        add("I", msg);
    }

    public static void e(String msg) {
        add("E", msg);
    }

    public static synchronized void add(String level, String msg) {
        try {
            Entry e = new Entry();
            e.seq = ++sSeq;
            e.ts = System.currentTimeMillis();
            e.level = level;
            e.msg = msg == null ? "" : msg;
            BUF.addLast(e);
            while (BUF.size() > CAP) BUF.removeFirst();
            XposedBridge.log(TAG + e.msg);
            mirror(e);
        } catch (Throwable ignored) {
        }
    }

    /** JSON 数组；since>0 时只返回该序号之后的，limit 取尾部。 */
    public static synchronized String dump(int limit, long since) {
        ArrayList<Entry> list = new ArrayList<Entry>();
        for (Entry e : BUF) {
            if (since > 0 && e.seq <= since) continue;
            list.add(e);
        }
        if (limit > 0 && list.size() > limit) {
            list = new ArrayList<Entry>(list.subList(list.size() - limit, list.size()));
        }
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        List<Object> arr = Json.arr();
        for (Entry e : list) {
            Map<String, Object> o = Json.obj();
            o.put("seq", e.seq);
            o.put("t", f.format(new Date(e.ts)));
            o.put("level", e.level);
            o.put("msg", e.msg);
            arr.add(o);
        }
        Map<String, Object> root = Json.obj();
        root.put("count", arr.size());
        root.put("lastSeq", sSeq);
        root.put("lines", arr);
        return Json.write(root);
    }

    private static void mirror(Entry e) {
        try {
            long now = System.currentTimeMillis();
            if ("I".equals(e.level) && now - sLastWrite < 500) return;
            sLastWrite = now;
            File f = file();
            if (f == null) return;
            if (f.length() > FILE_MAX) {
                File old = new File(f.getPath() + ".1");
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
            String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date(e.ts)) + " " + e.level + " " + e.msg + "\n";
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(line.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
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
