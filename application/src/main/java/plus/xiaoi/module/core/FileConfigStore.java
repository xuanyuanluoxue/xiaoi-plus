package plus.xiaoi.module.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import plus.xiaoi.module.application.config.ConfigStorePort;

/**
 * {@link ConfigStorePort} 的文件实现（WP7）。
 *
 * <p>只是把 {@link ConfigStore} 的受检 {@link IOException} 收敛成
 * {@link ConfigStorePort.Failure}，让用例层不必 import {@code java.io}。
 * WP8 会连同 {@link ConfigStore} 一起搬到 {@code :infra}。</p>
 *
 * <p>过渡期仍然保留 {@link #store()}，供 {@code ConfigRepository} /
 * {@code ConfigSync} 这类还需要 {@link File} 的 Android 适配器使用；
 * WP9 删掉旧页面后这些入口会逐步改走端口。</p>
 */
public final class FileConfigStore implements ConfigStorePort {

    private final ConfigStore store;
    /** beta.4 的外部配置位置；null = 外部目录不可用。 */
    private final File legacyExternal;

    public FileConfigStore(ConfigStore store) {
        this(store, null);
    }

    public FileConfigStore(ConfigStore store, File legacyExternal) {
        if (store == null) throw new IllegalArgumentException("store == null");
        this.store = store;
        this.legacyExternal = legacyExternal;
    }

    /** 过渡用：底层文件存储。 */
    public ConfigStore store() {
        return store;
    }

    @Override
    public String path() {
        return store.file().getAbsolutePath();
    }

    @Override
    public boolean exists() {
        return store.file().isFile();
    }

    @Override
    public long size() {
        File f = store.file();
        return f.isFile() ? f.length() : 0L;
    }

    @Override
    public long lastModified() {
        File f = store.file();
        return f.isFile() ? f.lastModified() : 0L;
    }

    @Override
    public long size(String name) {
        File f = resolve(name);
        return f != null && f.isFile() ? f.length() : 0L;
    }

    @Override
    public long lastModified(String name) {
        File f = resolve(name);
        return f != null && f.isFile() ? f.lastModified() : 0L;
    }

    @Override
    public String read() {
        try {
            return store.read();
        } catch (IOException e) {
            throw new Failure(message(e), e);
        }
    }

    @Override
    public String readNamed(String name) {
        File f = resolve(name);
        if (f == null || !f.isFile()) return null;
        try {
            return store.read(f);
        } catch (IOException e) {
            throw new Failure(message(e), e);
        }
    }

    @Override
    public void writeAtomic(String json) {
        try {
            store.writeAtomic(json);
        } catch (IOException e) {
            throw new Failure(message(e), e);
        }
    }

    @Override
    public List<String> listNames(String prefix) {
        List<String> out = new ArrayList<String>();
        for (File f : store.list(prefix)) {
            if (f != null) out.add(f.getName());
        }
        return out;
    }

    @Override
    public void backupCorrupt(String raw) {
        try {
            store.backupCorrupt(raw);
        } catch (IOException e) {
            throw new Failure(message(e), e);
        }
    }

    @Override
    public void moveCorruptAside() {
        try {
            store.moveCorruptAside();
        } catch (IOException e) {
            throw new Failure(message(e), e);
        }
    }

    @Override
    public void restoreNamed(String name) {
        File f = resolve(name);
        if (f == null || !f.isFile()) throw new Failure("备份文件不存在");
        try {
            store.restoreFrom(f);
        } catch (IOException e) {
            throw new Failure(message(e), e);
        }
    }

    @Override
    public boolean migrateLegacy() {
        File legacy = legacyExternal;
        if (legacy == null || !legacy.isFile()) return false;
        try {
            store.migrateFrom(legacy);
            return true;
        } catch (IOException e) {
            throw new Failure(message(e), e);
        }
    }

    /** 解析受管文件名；非法名字返回 null（不抛，避免查询类方法炸掉 UI）。 */
    private File resolve(String name) {
        try {
            return ConfigStore.resolve(store.dir(), name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.length() == 0 ? e.getClass().getSimpleName() : m;
    }
}
