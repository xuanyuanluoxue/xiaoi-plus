package plus.xiaoi.module.core.config;

/**
 * 配置备份的只读投影（纯 JVM）。
 *
 * <p>只暴露展示所需字段，不把 {@code File} 交给 UI；恢复时按 {@link #name}
 * 交给网关，由适配器做路径校验。</p>
 */
public final class ConfigBackupInfo {

    public final String name;
    public final long lastModified;
    public final long size;
    /** true = 可恢复的 legacy 备份；false = 仅供取证的 corrupt 原文。 */
    public final boolean restorable;

    public ConfigBackupInfo(String name, long lastModified, long size, boolean restorable) {
        this.name = name == null ? "" : name;
        this.lastModified = lastModified > 0 ? lastModified : 0L;
        this.size = size > 0 ? size : 0L;
        this.restorable = restorable;
    }
}