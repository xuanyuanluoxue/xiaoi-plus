package plus.xiaoi.module;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import plus.xiaoi.module.core.ConfigStore;

/**
 * ConfigStore 纯 JVM 测试：原子写、损坏备份、旧文件迁移、路径穿越、失败保留旧文件。
 * 全部在临时目录里跑，不依赖 android。
 */
public final class ConfigStoreTest {

    private ConfigStoreTest() {}

    public static int run() throws Exception {
        File base = java.nio.file.Files.createTempDirectory("xiaoi-configstore-").toFile();
        try {
            File dir = new File(base, "private");
            ConfigStore store = new ConfigStore(dir);

            // ------------------------------------------------------ read
            T.eq("read 不存在返回 null", null, store.read());
            T.ok("file 位于传入目录", store.file().getParentFile().equals(dir));

            // ------------------------------------------------------ 原子写
            store.writeAtomic("{\"rev\":1}");
            T.eq("writeAtomic 后可读", "{\"rev\":1}", store.read());
            T.ok("tmp 已清理", !new File(dir, "config.json.tmp").exists());
            T.ok("事实源文件名", "config.json".equals(store.file().getName()));

            // 覆盖已有文件：不先删除目标，写后读到新内容
            store.writeAtomic("{\"rev\":2}");
            T.eq("覆盖写成功", "{\"rev\":2}", store.read());

            // ------------------------------------------------------ 写失败保留旧文件
            File tmpAsDir = new File(dir, "config.json.tmp");
            T.ok("制造写失败：tmp 是目录", tmpAsDir.mkdir());
            boolean threw = false;
            try {
                store.writeAtomic("{\"rev\":99}");
            } catch (IOException e) {
                threw = true;
            }
            T.ok("写失败抛 IOException", threw);
            T.eq("写失败保留旧文件", "{\"rev\":2}", store.read());
            T.ok("清理测试目录", tmpAsDir.delete());

            // ------------------------------------------------------ backupCorrupt
            File b1 = store.backupCorrupt("{oops}");
            T.ok("corrupt 备份位于私有目录", b1.getParentFile().equals(dir));
            T.ok("corrupt 备份命名", b1.getName().startsWith("config.json.corrupt."));
            T.eq("corrupt 备份内容", "{oops}", readText(b1));
            File b2 = store.backupCorrupt("{oops2}");
            T.ok("corrupt 备份不覆盖", !b1.getName().equals(b2.getName()));
            T.eq("第二份 corrupt 内容", "{oops2}", readText(b2));
            T.ok("相同损坏内容复用同一份备份", store.backupCorrupt("{oops}").equals(b1));

            // ------------------------------------------------------ list（只列指定前缀、时间倒序）
            File listDir = new File(base, "list");
            ConfigStore ls = new ConfigStore(listDir);
            ls.writeAtomic("{\"rev\":1}");
            File legacyOld = new File(listDir, "config.json.legacy.100");
            writeText(legacyOld, "{\"old\":1}");
            File legacyNew = new File(listDir, "config.json.legacy.200");
            writeText(legacyNew, "{\"new\":2}");
            legacyOld.setLastModified(1_000L);
            legacyNew.setLastModified(2_000L);
            writeText(new File(listDir, "config.json.corrupt.300"), "{bad}");
            java.util.List<File> legs = ls.list(ConfigStore.LEGACY_PREFIX);
            T.eq("list 只返回 legacy 前缀", 2, legs.size());
            T.ok("list 最新备份在前", legs.get(0).getName().endsWith(".200"));
            T.ok("list 不列 .corrupt", !legs.get(0).getName().contains(".corrupt."));
            T.eq("list(\"\") 返回全部普通文件", 4, ls.list("").size());

            // ------------------------------------------------------ moveCorruptAside
            File moveDir = new File(base, "move");
            ConfigStore ms = new ConfigStore(moveDir);
            ms.writeAtomic("{bad}");
            File moved = ms.moveCorruptAside();
            T.ok("move 后事实源不存在", !ms.file().exists());
            T.ok("move 生成 .corrupt 备份", moved.getName().startsWith("config.json.corrupt."));
            T.eq("move 保留损坏原文", "{bad}", readText(moved));
            T.eq("无原文件时 move 返回 null", null, ms.moveCorruptAside());
            // 同一份损坏内容再次出现：复用旧备份，只删原文件
            ms.writeAtomic("{bad}");
            File moved2 = ms.moveCorruptAside();
            T.ok("相同损坏内容复用备份", moved2.equals(moved));
            T.eq("重复 move 不产生第二份备份", 1,
                    ms.list("config.json.corrupt.").size());

            // ------------------------------------------------------ restoreFrom
            File restoreDir = new File(base, "restore");
            ConfigStore rs = new ConfigStore(restoreDir);
            rs.writeAtomic("{\"rev\":1}");
            File backup = new File(restoreDir, "config.json.legacy.42");
            writeText(backup, "{\"rev\":4}");
            rs.restoreFrom(backup);
            T.eq("restore 写回事实源", "{\"rev\":4}", rs.read());
            boolean outsideRejected = false;
            File outside = new File(base, "outside.json");
            writeText(outside, "{\"evil\":1}");
            try {
                rs.restoreFrom(outside);
            } catch (IOException e) {
                outsideRejected = true;
            }
            T.ok("restore 拒绝目录外文件", outsideRejected);
            T.eq("拒绝后事实源不变", "{\"rev\":4}", rs.read());

            // ------------------------------------------------------ migrateFrom（私有不存在）
            File external = new File(base, "external-config.json");
            writeText(external, "{\"version\":2,\"rev\":9}");
            File privateDir = new File(base, "migrate-into");
            ConfigStore fresh = new ConfigStore(privateDir);
            File migrated = fresh.migrateFrom(external);
            T.ok("迁移写入私有文件", migrated.equals(fresh.file()));
            T.eq("迁移内容一致", "{\"version\":2,\"rev\":9}", fresh.read());
            T.ok("迁移后删除外部文件", !external.exists());

            // ------------------------------------------------------ migrateFrom（私有已存在：归档不覆盖）
            File external2 = new File(base, "external-config-2.json");
            writeText(external2, "{\"legacy\":true}");
            File archived = fresh.migrateFrom(external2);
            T.eq("已存在时私有文件不被覆盖", "{\"version\":2,\"rev\":9}", fresh.read());
            T.ok("旧外部文件归档到私有目录", archived.getParentFile().equals(privateDir));
            T.ok("归档命名", archived.getName().startsWith("config.json.legacy."));
            T.eq("归档保留旧内容", "{\"legacy\":true}", readText(archived));
            T.ok("第二次迁移也删除外部文件", !external2.exists());

            // ------------------------------------------------------ 路径穿越
            throwsIae("拒绝 ..", dir, "..");
            throwsIae("拒绝相对越界", dir, "../evil.json");
            throwsIae("拒绝子目录", dir, "sub/config.json");
            throwsIae("拒绝反斜杠", dir, "..\\evil.json");
            throwsIae("拒绝绝对路径", dir, "/tmp/evil.json");
            throwsIae("拒绝空名", dir, "");

            // 正常名字仍可解析
            T.ok("正常名字解析", ConfigStore.resolve(dir, "config.json.bak")
                    .getParentFile().equals(dir));
        } finally {
            deleteRecursively(base);
        }
        return T.report("ConfigStoreTest");
    }

    // ------------------------------------------------------------- helpers

    private static void throwsIae(String name, File dir, String child) {
        boolean threw = false;
        try {
            ConfigStore.resolve(dir, child);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        T.ok(name, threw);
    }

    private static String readText(File f) throws IOException {
        byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
        return new String(raw, "UTF-8");
    }

    private static void writeText(File f, String text) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursively(k);
        }
        f.delete();
    }
}
