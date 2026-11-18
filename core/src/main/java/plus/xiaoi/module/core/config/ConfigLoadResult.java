package plus.xiaoi.module.core.config;

import plus.xiaoi.module.AppConfig;

/**
 * 一次配置读取的完整结果（纯 JVM）。
 *
 * <p>把 {@code load()} 与 {@code isReadOnly()/loadError()/isSkeleton()} 的隐式时序
 * 耦合收成一次原子返回值：调用方不需要“先 load 再 get”，也不会读到上一次
 * 读取遗留的标志。</p>
 */
public final class ConfigLoadResult {

    /** 永不为 null；文件不存在/损坏时是内存骨架（不可写盘）。 */
    public final AppConfig config;
    /** true = 损坏或读取失败后进入只读态，保存必须被阻止。 */
    public final boolean readOnly;
    /** 用户可读原因；空串 = 正常。不含配置内容/密钥。 */
    public final String error;
    /** true = 默认骨架（文件不存在或损坏后），不是用户的真实配置。 */
    public final boolean skeleton;

    public ConfigLoadResult(AppConfig config, boolean readOnly, String error, boolean skeleton) {
        this.config = config;
        this.readOnly = readOnly;
        this.error = error == null ? "" : error;
        this.skeleton = skeleton;
    }

    public boolean ok() {
        return !readOnly;
    }
}