package plus.xiaoi.module.core.config;

import plus.xiaoi.module.AppConfig;

/**
 * 一次配置写入的结果（纯 JVM）。
 *
 * <p>{@link #saved} 与 {@link #conflictRev} 互斥：成功时 {@code saved != null}，
 * 冲突时 {@code conflictRev > 0}，校验/IO 失败时 {@code error} 非空。</p>
 */
public final class ConfigSaveResult {

    public final AppConfig saved;
    /** >0 = 文件 rev 与调用方期望不一致；调用方应让用户选择“载入最新/覆盖”。 */
    public final long conflictRev;
    /** 失败原因；空串 = 成功/冲突。 */
    public final String error;

    private ConfigSaveResult(AppConfig saved, long conflictRev, String error) {
        this.saved = saved;
        this.conflictRev = conflictRev > 0 ? conflictRev : 0L;
        this.error = error == null ? "" : error;
    }

    public static ConfigSaveResult success(AppConfig saved) {
        return new ConfigSaveResult(saved, 0L, "");
    }

    public static ConfigSaveResult conflict(long rev) {
        return new ConfigSaveResult(null, rev, "");
    }

    public static ConfigSaveResult failure(String error) {
        return new ConfigSaveResult(null, 0L, error == null ? "保存失败" : error);
    }

    public boolean ok() {
        return saved != null;
    }

    public boolean conflict() {
        return conflictRev > 0;
    }
}