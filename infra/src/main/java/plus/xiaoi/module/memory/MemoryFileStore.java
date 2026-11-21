package plus.xiaoi.module.memory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import plus.xiaoi.module.application.memory.MemoryStore;
import plus.xiaoi.module.core.Contract;

/**
 * 记忆文件的文件系统实现（P2-D，{@code :infra}）。
 *
 * <p>只做三件事：整文件读、备份、原子写。写入顺序固定为</p>
 * <pre>
 *   备份 → 写临时文件（fsync）→ rename 覆盖
 * </pre>
 * <p>任何一步失败都返回 null，<b>原文件保持不变</b>。备份最多保留
 * {@value #KEEP_BACKUPS} 份（按文件名里的时间戳淘汰最旧的）。</p>
 *
 * <p>目录就是小爱进程的 {@code context.getFilesDir()/memory}：模块跑在小爱进程内，
 * 同 uid 可直接读写，不需要 root，也不碰加密数据库。</p>
 */
public final class MemoryFileStore implements MemoryStore {

    /** 备份保留份数。 */
    public static final int KEEP_BACKUPS = 5;

    /** 单次读取上限：文件超过上限时只读前面这一截（避免把巨型文件读进内存）。 */
    private static final int MAX_READ_BYTES = Contract.MEMORY_FILE_MAX_BYTES + 4096;

    private final File dir;
    private final int keepBackups;

    public MemoryFileStore(File dir) {
        this(dir, KEEP_BACKUPS);
    }

    /** 测试用：可指定保留份数。 */
    public MemoryFileStore(File dir, int keepBackups) {
        this.dir = dir;
        this.keepBackups = keepBackups < 1 ? 1 : keepBackups;
    }

    /** 记忆目录（不存在时也会返回路径，不创建）。 */
    public File dir() {
        return dir;
    }

    @Override
    public String read(String name) {
        if (dir == null || name == null || name.length() == 0) return null;
        File f = new File(dir, name);
        if (!f.isFile() || !f.canRead()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            int size = (int) Math.min(f.length(), (long) MAX_READ_BYTES);
            byte[] buf = new byte[size];
            int off = 0;
            while (off < size) {
                int n = in.read(buf, off, size - off);
                if (n <= 0) break;
                off += n;
            }
            return new String(buf, 0, off, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    @Override
    public String write(String name, String content) {
        if (dir == null || name == null || name.length() == 0 || content == null) return null;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File target = new File(dir, name);

            String backupName = "";
            if (target.isFile()) {
                backupName = name + ".bak-" + stamp();
                if (!copy(target, new File(dir, backupName))) return null;
                prune(name);
            }

            File tmp = new File(dir, name + ".tmp-" + System.nanoTime());
            if (!writeFile(tmp, content)) {
                deleteQuietly(tmp);
                return null;
            }
            if (!tmp.renameTo(target)) {
                deleteQuietly(tmp);
                return null;
            }
            return backupName;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 现有备份（按名字升序 = 时间升序）。 */
    public List<File> backups(String name) {
        List<File> out = new ArrayList<File>();
        if (dir == null || name == null) return out;
        File[] all = dir.listFiles();
        if (all == null) return out;
        String prefix = name + ".bak-";
        for (File f : all) {
            if (f.isFile() && f.getName().startsWith(prefix)) out.add(f);
        }
        sortByName(out);
        return out;
    }

    // ------------------------------------------------------------- 内部

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).format(new Date());
    }

    /** 只保留最近 {@link #keepBackups} 份；删除失败不影响写入结果。 */
    private void prune(String name) {
        List<File> backups = backups(name);
        int excess = backups.size() - keepBackups;
        for (int i = 0; i < excess; i++) deleteQuietly(backups.get(i));
    }

    private static void sortByName(List<File> files) {
        File[] arr = files.toArray(new File[0]);
        Arrays.sort(arr, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        files.clear();
        files.addAll(Arrays.asList(arr));
    }

    private static boolean copy(File from, File to) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            syncQuietly(out);
            return true;
        } catch (Throwable t) {
            deleteQuietly(to);
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static boolean writeFile(File file, String content) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(content.getBytes("UTF-8"));
            out.flush();
            syncQuietly(out);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuietly(out);
        }
    }

    private static void syncQuietly(FileOutputStream out) {
        try {
            out.getFD().sync();
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private static void deleteQuietly(File f) {
        if (f == null) return;
        try {
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }
}
