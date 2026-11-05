package plus.xiaoi.module;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 调用量统计的 Android 侧存储：一条调用一行，追加写到小爱自己的 files 目录。
 *
 * 路径是<b>小爱进程自己的</b> files（/data/data/com.miui.voiceassist/files/xiaoi_usage.log），
 * 因为模块运行在小爱进程里，只能写这个目录；主进程 / :core / :voice_trigger 同 uid，
 * 都读写同一个文件，天然就是全进程合并后的账本。
 *
 * 读取方是小爱进程内的能力桥（GET /stats 与 MCP xiaoai_stats），
 * 设置 App 的主页再通过桥取数——App 读不到小爱私有目录，也就不会出现两个事实源。
 *
 * 所有 IO 失败都降级为内存计数，绝不影响模型请求。
 */
public final class Usage {

    private static final String FILE = "xiaoi_usage.log";
    /** 文件超过该大小就在写入时顺手清理超期行。 */
    private static final long PRUNE_BYTES = 512 * 1024;
    private static final long PRUNE_INTERVAL = 60 * 60 * 1000L;

    private static final Object LOCK = new Object();
    /** 文件不可写时的兜底（本进程可见，至少 /stats 不会恒为 0）。 */
    private static final List<String> sMemory = new ArrayList<String>();
    private static volatile long sLastPrune;

    private Usage() {}

    /** 记录一次模型调用；任何失败都静默。 */
    public static void record(boolean third, boolean mainCall, String model) {
        try {
            String line = UsageCounter.encode(System.currentTimeMillis(), third, mainCall, model);
            synchronized (LOCK) {
                File f = file();
                if (f == null) {
                    addMemory(line);
                    return;
                }
                try {
                    FileOutputStream out = new FileOutputStream(f, true);
                    try {
                        out.write((line + "\n").getBytes("UTF-8"));
                    } finally {
                        out.close();
                    }
                } catch (Throwable t) {
                    addMemory(line);
                    return;
                }
                maybePrune(f);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 统计最近 days 天的调用量（JSON，结构见 {@link UsageCounter#stats}）。 */
    public static String stats(int days) {
        List<String> lines = new ArrayList<String>();
        try {
            File f = file();
            if (f != null) lines.addAll(read(f));
        } catch (Throwable ignored) {
        }
        synchronized (LOCK) {
            lines.addAll(sMemory);
        }
        return UsageCounter.stats(lines, System.currentTimeMillis(), days);
    }

    /** 当前累计写入的行数（诊断用）。 */
    public static int size() {
        List<String> lines = new ArrayList<String>();
        try {
            File f = file();
            if (f != null) lines.addAll(read(f));
        } catch (Throwable ignored) {
        }
        synchronized (LOCK) {
            lines.addAll(sMemory);
        }
        return lines.size();
    }

    private static void addMemory(String line) {
        synchronized (LOCK) {
            sMemory.add(line);
            if (sMemory.size() > 2000) sMemory.remove(0);
        }
    }

    /** 尾部超限时按天清理一次，失败保留原文件（数据比整洁重要）。 */
    private static void maybePrune(File f) {
        try {
            long now = System.currentTimeMillis();
            if (f.length() < PRUNE_BYTES || now - sLastPrune < PRUNE_INTERVAL) return;
            sLastPrune = now;
            List<String> kept = UsageCounter.prune(read(f), now, UsageCounter.KEEP_DAYS);
            File tmp = new File(f.getParentFile(), FILE + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp, false);
            try {
                for (String line : kept) out.write((line + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Throwable ignored) {
        }
    }

    private static List<String> read(File f) {
        List<String> out = new ArrayList<String>();
        if (f == null || !f.exists()) return out;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 1024 * 1024)];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r <= 0) break;
                n += r;
            }
            String text = new String(buf, 0, n, "UTF-8");
            int start = 0;
            for (int i = 0; i <= text.length(); i++) {
                if (i == text.length() || text.charAt(i) == '\n') {
                    if (i > start) out.add(text.substring(start, i));
                    start = i + 1;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    private static File file() {
        try {
            Context ctx = appContext();
            if (ctx == null) return null;
            return new File(ctx.getFilesDir(), FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
