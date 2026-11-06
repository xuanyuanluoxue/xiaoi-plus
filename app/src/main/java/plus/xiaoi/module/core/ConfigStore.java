package plus.xiaoi.module.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * config.json 的纯 JVM 文件层（无 android 依赖，可桌面单测）。
 *
 * 只做字节/文件操作，不解析 JSON、不关心 schema：
 *  - {@link #read()}：不存在返回 null
 *  - {@link #writeAtomic(String)}：tmp + fsync + rename；不先删除目标，失败保留旧文件
 *  - {@link #backupCorrupt(String)}：把无法解析的原文另存为 {@code config.json.corrupt.<ts>}
 *  - {@link #migrateFrom(File)}：一次性把旧外部文件搬到当前目录，成功后删除外部文件
 *
 * 所有受管文件名都经过 {@link #resolve(File, String)}：
 * 拒绝 {@code ..}、路径分隔符和绝对路径，确保写入永远落在传入目录内。
 * 目录由调用方决定（App 传私有 getFilesDir()；单测传临时目录）。
 */
public final class ConfigStore {

    public static final String FILE_NAME = "config.json";
    private static final String TMP_NAME = FILE_NAME + ".tmp";
    private static final String CORRUPT_PREFIX = FILE_NAME + ".corrupt.";
    public static final String LEGACY_PREFIX = FILE_NAME + ".legacy.";

    private final File dir;

    public ConfigStore(File dir) {
        if (dir == null) throw new IllegalArgumentException("dir == null");
        this.dir = dir;
    }

    /** 受管目录（私有目录或单测临时目录）。 */
    public File dir() {
        return dir;
    }

    /** 事实源文件：{@code <dir>/config.json}。 */
    public File file() {
        return resolve(dir, FILE_NAME);
    }

    /** 读取 UTF-8 全文；文件不存在返回 null。 */
    public String read() throws IOException {
        File f = file();
        if (!f.isFile()) return null;
        return new String(readBytes(f), "UTF-8");
    }

    /** 读取指定备份文件（只读；不解析、不校验内容）。 */
    public String read(File f) throws IOException {
        if (f == null || !f.isFile()) return null;
        return new String(readBytes(f), "UTF-8");
    }

    /**
     * 列出受管目录中指定前缀的文件，按修改时间倒序（相同时按文件名倒序）。
     * 只返回普通文件，不递归；前缀为空时返回目录内全部文件。
     */
    public List<File> list(String prefix) {
        List<File> out = new ArrayList<File>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        String p = prefix == null ? "" : prefix;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            if (p.length() == 0 || f.getName().startsWith(p)) out.add(f);
        }
        Collections.sort(out, new Comparator<File>() {
            public int compare(File a, File b) {
                long ta = a.lastModified();
                long tb = b.lastModified();
                if (ta != tb) return ta > tb ? -1 : 1;
                return b.getName().compareTo(a.getName());
            }
        });
        return out;
    }

    /**
     * 把损坏的 {@code config.json} 移到 {@code config.json.corrupt.<ts>}。
     *
     * <p>相同内容已备份过时复用旧备份，避免为同一份坏文件反复复制密钥。
     * 只有备份落盘成功后才删除原文件；删除失败会抛 IOException，调用方不得继续
     * 覆盖原文件。文件不存在时返回 null。</p>
     *
     * @return 损坏原文备份；没有原文件时为 null
     */
    public File moveCorruptAside() throws IOException {
        File f = file();
        if (!f.isFile()) return null;
        byte[] raw = readBytes(f);
        File backup = findIdentical(CORRUPT_PREFIX, raw);
        if (backup == null) {
            ensureDir();
            backup = unique(CORRUPT_PREFIX + System.currentTimeMillis());
            writeBytes(backup, raw);
        }
        if (!f.delete() && f.exists()) {
            throw new IOException("cannot move corrupt config aside");
        }
        return backup;
    }

    /**
     * 把同目录的备份文件原子复制为 {@code config.json}。
     * 只接受受管目录内的普通文件；失败时目标文件保持不变。
     */
    public void restoreFrom(File backup) throws IOException {
        if (backup == null || !backup.isFile()) {
            throw new IOException("backup not found");
        }
        File parent = backup.getParentFile();
        if (parent == null || !parent.getCanonicalFile().equals(dir.getCanonicalFile())) {
            throw new IOException("backup outside config dir");
        }
        writeAtomic(new String(readBytes(backup), "UTF-8"));
    }

    /**
     * 原子写入：先写 {@code config.json.tmp} 并 fsync，再 rename 覆盖目标。
     *
     * 关键约束：**不先删除目标**。rename 在 Linux/Android 上是原子的，
     * 任何一步失败都只留下旧文件（或一个可清理的 .tmp），绝不会得到半份配置。
     */
    public void writeAtomic(String json) throws IOException {
        if (json == null) throw new IllegalArgumentException("json == null");
        ensureDir();
        File target = file();
        File tmp = resolve(dir, TMP_NAME);
        if (tmp.exists() && !tmp.isFile()) {
            // 例如被误建成目录：直接失败，绝不碰旧文件。
            throw new IOException("temporary path is not a file: " + tmp.getName());
        }

        boolean replaced = false;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(json.getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
            replace(tmp, target);
            replaced = true;
        } finally {
            closeQuietly(out);
            if (!replaced) {
                // 失败时清理自己的临时文件；目标文件从头到尾没被动过。
                try { tmp.delete(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 把损坏原文另存为私有目录下的 {@code config.json.corrupt.<ts>}。
     * 原文可能包含 apiKey/token，所以只允许写进传入目录（App 传私有目录）。
     *
     * @return 备份文件
     */
    public File backupCorrupt(String raw) throws IOException {
        ensureDir();
        byte[] data = raw == null ? new byte[0] : raw.getBytes("UTF-8");
        // 同一份损坏内容只保留一份备份：避免每次启动都复制一份含密钥的文件。
        File existing = findIdentical(CORRUPT_PREFIX, data);
        if (existing != null) return existing;
        File f = unique(CORRUPT_PREFIX + System.currentTimeMillis());
        writeBytes(f, data);
        return f;
    }

    /**
     * 一次性迁移旧外部文件到当前目录。
     *
     * - 当前目录还没有 config.json：复制内容并原子写入，成功后删除旧文件。
     * - 当前目录已有 config.json：**不覆盖**，把旧文件另存为
     *   {@code config.json.legacy.<ts>}，再删除旧文件（保留证据供人工核对）。
     *
     * @return 迁移后的私有文件（新 config.json 或 legacy 归档）
     */
    public File migrateFrom(File legacy) throws IOException {
        if (legacy == null) throw new IllegalArgumentException("legacy == null");
        if (!legacy.isFile()) throw new IOException("legacy config not found");
        ensureDir();

        File target = file();
        byte[] raw = readBytes(legacy);
        File out;
        if (target.exists()) {
            out = unique(LEGACY_PREFIX + System.currentTimeMillis());
            writeBytes(out, raw);
        } else {
            writeAtomic(new String(raw, "UTF-8"));
            out = target;
        }
        // 复制已经成功落盘，删除失败也不回滚（否则会丢掉已经迁移的私有文件）。
        // 下次 load 看到私有文件存在，会把残留外部文件归档为 .legacy，不会覆盖。
        try { legacy.delete(); } catch (Throwable ignored) {}
        return out;
    }

    // ------------------------------------------------------------- 路径与 IO

    /**
     * 解析受管文件名并保证不越界。
     * 拒绝：null/空名、{@code .}/{@code ..}、包含 {@code /} 或 {@code \}、绝对路径、解析后跑出 dir 的名字。
     */
    public static File resolve(File dir, String name) {
        if (dir == null) throw new IllegalArgumentException("dir == null");
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("file name is empty");
        }
        if (".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("relative traversal not allowed: " + name);
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("path separators not allowed: " + name);
        }
        if (new File(name).isAbsolute()) {
            throw new IllegalArgumentException("absolute path not allowed: " + name);
        }
        File out = new File(dir, name);
        try {
            String base = dir.getCanonicalPath();
            String path = out.getCanonicalPath();
            if (!path.startsWith(base + File.separator)) {
                throw new IllegalArgumentException("path escapes config dir: " + name);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("bad path: " + name, e);
        }
        return out;
    }

    private void ensureDir() throws IOException {
        if (dir.isDirectory()) return;
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create config dir: " + dir.getAbsolutePath());
        }
    }

    /** 原子替换：优先 rename（POSIX 覆盖语义），必要时用 nio 兜底。 */
    private static void replace(File tmp, File target) throws IOException {
        if (tmp.renameTo(target)) return;
        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            throw new IOException("atomic replace failed: " + t.getClass().getSimpleName());
        }
    }

    private File unique(String base) {
        File f = resolve(dir, base);
        if (!f.exists()) return f;
        for (int i = 1; i < 10000; i++) {
            File n = resolve(dir, base + "." + i);
            if (!n.exists()) return n;
        }
        throw new IllegalStateException("too many backup files for " + base);
    }

    /** 目录里是否已有同前缀、同内容的备份；有则复用，避免重复复制密钥文件。 */
    private File findIdentical(String prefix, byte[] data) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f == null || !f.isFile() || !f.getName().startsWith(prefix)) continue;
            try {
                if (java.util.Arrays.equals(readBytes(f), data)) return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static byte[] readBytes(File f) throws IOException {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    private static void writeBytes(File f, byte[] raw) throws IOException {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(raw);
            out.flush();
            out.getFD().sync();
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (Throwable ignored) {}
    }
}
