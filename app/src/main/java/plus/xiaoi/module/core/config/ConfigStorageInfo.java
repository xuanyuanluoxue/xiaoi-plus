package plus.xiaoi.module.core.config;

/** 配置事实源文件的只读投影（纯 JVM），供“兼容模式/诊断”展示。 */
public final class ConfigStorageInfo {

    public final String path;
    public final boolean exists;
    public final long size;
    public final long lastModified;

    public ConfigStorageInfo(String path, boolean exists, long size, long lastModified) {
        this.path = path == null ? "" : path;
        this.exists = exists;
        this.size = size > 0 ? size : 0L;
        this.lastModified = lastModified > 0 ? lastModified : 0L;
    }
}