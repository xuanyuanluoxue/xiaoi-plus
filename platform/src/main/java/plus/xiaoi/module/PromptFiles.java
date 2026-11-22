package plus.xiaoi.module;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 把提示词写进小爱官方的「自定义提示词」文件，并触发它的缓存失效。
 *
 * <p>依据（8.2.70 反汇编 {@code com.aios.osbot.tools.prompt.UpdateCustomPromptTool}）：
 * 官方工具做的事就是两步——</p>
 * <ol>
 *   <li>写 {@code <agentDir>/custom_prompt.md}</li>
 *   <li>调用 {@code rj.k1.e.invalidateMemoryCache()} 让提示词缓存失效</li>
 * </ol>
 * <p>我们不做工具调用，只复刻这两步：文件走原子写 + 一次性备份，失效走反射，
 * 类名/字段名取不到时安静放弃（不影响对话）。</p>
 */
public final class PromptFiles {

    /** agents 目录名（{@code getFilesDir()/agents}）。 */
    public static final String DIR_NAME = "agents";
    /** 主 Agent 目录名。 */
    public static final String AGENT_ID = "osbot.main";
    /** 官方自定义提示词文件名。 */
    public static final String FILE_NAME = "custom_prompt.md";

    /** 我们的块标记，方便关闭时精确摘除。 */
    public static final String MARK_BEGIN = "<!-- xiaoi-plus:custom-prompt:begin -->";
    public static final String MARK_END = "<!-- xiaoi-plus:custom-prompt:end -->";

    /** 备份文件名（只保留一份）。 */
    private static final String BACKUP = FILE_NAME + ".bak-xiaoi";
    private static final int MAX_BYTES = 64 * 1024;

    private PromptFiles() {}

    /** 主 Agent 目录；Application 未就绪时返回 null。 */
    public static File agentDir() {
        android.content.Context ctx = MemoryFiles.appContext();
        if (ctx == null) return null;
        try {
            return new File(new File(ctx.getFilesDir(), DIR_NAME), AGENT_ID);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 同步自定义提示词。
     *
     * @param block   注入块；空串表示「移除我们写过的内容」
     * @param replace true=整份替换成 block；false=保留别人写的内容并在末尾追加
     * @return 是否成功落地（false 时调用方应回退到请求体注入）
     */
    public static boolean sync(String block, boolean replace) {
        File dir = agentDir();
        if (dir == null) return false;
        File target = new File(dir, FILE_NAME);
        try {
            String current = read(target);
            String rest = strip(current);
            String next;
            if (block == null || block.isEmpty()) {
                next = rest;
            } else if (replace) {
                next = block;
            } else {
                next = rest.trim().isEmpty() ? block : rest.trim() + "\n\n" + block;
            }
            if (current != null && next.equals(current)) {
                invalidateCache();
                return true;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            if (target.isFile() && !new File(dir, BACKUP).isFile()) {
                copy(target, new File(dir, BACKUP));
            }
            if (next.trim().isEmpty()) {
                // 没什么可写：删掉我们的文件即可
                if (target.isFile() && !target.delete()) return false;
            } else if (!writeAtomically(dir, target, next)) {
                return false;
            }
            invalidateCache();
            Logs.i("自定义提示词已同步: " + next.length() + " 字（替换=" + replace + "）");
            return true;
        } catch (Throwable t) {
            Logs.i("自定义提示词同步失败: " + t);
            return false;
        }
    }

    /**
     * 让提示词缓存失效：{@code rj.k1.e.invalidateMemoryCache()}。
     *
     * <p>该方法是英文名（未混淆），类名与字段名是短名，取不到就跳过。</p>
     */
    public static void invalidateCache() {
        try {
            Class<?> holder = Class.forName("rj.k1", false, PromptFiles.class.getClassLoader());
            java.lang.reflect.Field f = holder.getDeclaredField("e");
            f.setAccessible(true);
            Object singleton = f.get(null);
            if (singleton == null) return;
            java.lang.reflect.Method m = singleton.getClass().getMethod("invalidateMemoryCache");
            m.invoke(singleton);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------- 内部

    private static String read(File f) {
        if (f == null || !f.isFile()) return "";
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), (long) MAX_BYTES)];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            return new String(buf, 0, off, "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    /** 去掉我们写过的整段（含标记），保留别人写的内容。 */
    static String strip(String text) {
        if (text == null || text.isEmpty()) return "";
        int begin = text.indexOf(MARK_BEGIN);
        if (begin < 0) return text;
        int end = text.indexOf(MARK_END, begin);
        if (end < 0) return text.substring(0, begin);
        String out = text.substring(0, begin) + text.substring(end + MARK_END.length());
        return out.trim().isEmpty() ? "" : out.trim() + "\n";
    }

    private static boolean writeAtomically(File dir, File target, String content) {
        File tmp = new File(dir, FILE_NAME + ".tmp-" + System.nanoTime());
        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(content.getBytes("UTF-8"));
            out.flush();
            try {
                ((FileOutputStream) out).getFD().sync();
            } catch (Throwable ignored) {
            }
            closeQuietly(out);
            out = null;
            if (tmp.renameTo(target)) return true;
            try {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Throwable ignored) {
            }
            if (target.exists() && !target.delete()) return false;
            return tmp.renameTo(target);
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuietly(out);
            if (tmp.exists()) tmp.delete();
        }
    }

    private static boolean copy(File from, File to) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    /** 便于诊断：当前自定义提示词文件是否存在。 */
    public static List<String> describe() {
        List<String> out = new ArrayList<String>();
        File dir = agentDir();
        if (dir == null) {
            out.add("agentDir=null");
            return out;
        }
        File f = new File(dir, FILE_NAME);
        out.add("dir=" + dir.getAbsolutePath());
        out.add("file=" + (f.isFile() ? f.length() + "B" : "missing"));
        return out;
    }
}
