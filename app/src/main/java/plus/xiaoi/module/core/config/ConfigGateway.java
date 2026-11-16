package plus.xiaoi.module.core.config;

import java.util.List;

import plus.xiaoi.module.AppConfig;

/**
 * 配置读写端口（WP1a）。
 *
 * <p>UI/应用层只依赖本接口与结果对象，不直接依赖 {@code ConfigRepository}，
 * 也不再使用 {@code isReadOnly()/loadError()/isSkeleton()} 这类与 load 有隐式
 * 时序耦合的 getter。</p>
 *
 * <p>本阶段只抽端口，不改变文件格式、prefs 镜像顺序、rev 公式与损坏语义；
 * 这些规则仍在适配器实现里，WP1b 再上移到 application 层。</p>
 */
public interface ConfigGateway {

    /** 读取事实源；一次返回 config + 只读/损坏/骨架标志。 */
    ConfigLoadResult loadState();

    /**
     * 带 rev 校验的保存：文件 rev 与 {@code expectedRev} 不一致时返回冲突，
     * 不写入任何内容。{@code expectedRev < 0} 表示不检查（旧路径/显式覆盖）。
     */
    ConfigSaveResult saveIfRev(AppConfig next, long expectedRev);

    /** 同 {@link #saveIfRev}，但不回填“已应用”哈希（导入/静默场景）。 */
    ConfigSaveResult saveIfRevQuiet(AppConfig next, long expectedRev);

    /** 可恢复的 legacy 备份（时间倒序）。 */
    List<ConfigBackupInfo> backupInfos();

    /** 损坏原文备份（仅供取证，不可恢复）。 */
    List<ConfigBackupInfo> corruptInfos();

    /** 事实源文件信息。 */
    ConfigStorageInfo storage();

    /** 从 legacy 备份恢复；失败抛异常且不改动原文件。 */
    AppConfig restore(ConfigBackupInfo backup) throws Exception;

    /** 损坏后重建默认配置；损坏原文保留。 */
    AppConfig resetAfterCorruption() throws Exception;

    /** 与 {@code ConfigCodec.validate} 一致；null = 通过。 */
    String validate(AppConfig config);
}