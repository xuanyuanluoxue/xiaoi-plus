package plus.xiaoi.module;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import plus.xiaoi.module.core.mcp.InboundRegistration;

/**
 * 把 8771 写进小爱的「个人 MCP 服务」配置（双向枢纽的落地端）。
 *
 * <p>文件：{@code <filesDir>/mcp/mcp_servers.json}（小爱私有目录，同 uid 可写）。
 * 合并规则在 domain 的 {@link InboundRegistration}；这里只做读、备份、原子写。
 * 坏 JSON / 目录不可用时一律不动原文件。</p>
 */
public final class McpServerConfig {

    private static final String DIR = "mcp";
    private static final String FILE = "mcp_servers.json";
    private static final String BACKUP = FILE + ".bak-xiaoi";

    private McpServerConfig() {}

    /** 配置文件路径；Application 未就绪返回 null。 */
    public static File file() {
        android.content.Context ctx = MemoryFiles.appContext();
        if (ctx == null) return null;
        try {
            return new File(new File(ctx.getFilesDir(), DIR), FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 同步我们的条目：{@code enabled=true} 写入/更新，false 精确移除。
     *
     * @return 是否已处于期望状态（false = 读不到/写失败，调用方只记日志）
     */
    public static boolean sync(boolean enabled, int port, String token) {
        File target = file();
        if (target == null) return false;
        try {
            String current = read(target);
            String next = enabled
                    ? InboundRegistration.upsert(current,
                            "http://127.0.0.1:" + port + "/mcp", token)
                    : InboundRegistration.remove(current);
            if (next == null) {
                Logs.i("mcp_servers.json 解析失败，未改动（" + current.length() + " 字）");
                return false;
            }
            if (next.equals(current)) return true;

            File dir = target.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
            if (target.isFile() && dir != null && !new File(dir, BACKUP).isFile()) {
                copy(target, new File(dir, BACKUP));
            }
            return writeAtomically(dir, target, next);
        } catch (Throwable t) {
            Logs.i("mcp_servers.json 同步失败: " + t);
            return false;
        }
    }

    /** 便于诊断：文件是否存在、是否已包含我们的条目。 */
    public static String describe() {
        File f = file();
        if (f == null) return "file=null";
        if (!f.isFile()) return "file=missing";
        return "file=" + f.length() + "B inbound=" + InboundRegistration.contains(read(f));
    }

    // ------------------------------------------------------------- 内部

    private static String read(File f) {
        if (f == null || !f.isFile()) return "";
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 256 * 1024L)];
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

    private static boolean writeAtomically(File dir, File target, String content) {
        File tmp = new File(dir, FILE + ".tmp-" + System.nanoTime());
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
        } catch (Throwable ignored) {
        }
    }
}
