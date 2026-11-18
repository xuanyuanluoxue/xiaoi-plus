package plus.xiaoi.module.application.config;

import java.util.List;

/**
 * 配置存储端口（WP7）。
 *
 * <p>把「字节怎么落盘」从用例里彻底拿掉：{@link ConfigService} 只通过本接口
 * 读写，不再 import {@code java.io}。纯 JVM 实现见
 * {@code plus.xiaoi.module.core.FileConfigStore}（WP8 起迁到 {@code :infra}），
 * 单测可以给内存实现，不需要临时目录。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>所有方法都不抛受检异常；失败抛 {@link Failure}（运行时异常）。</li>
 *   <li>查询类方法（{@link #exists()}、{@link #size()} 等）不抛异常，失败按「空/0」处理。</li>
 *   <li>文件名必须是受管目录内的简单名字，实现负责拒绝 {@code ..}、分隔符与绝对路径。</li>
 * </ul>
 */
public interface ConfigStorePort {

    String FILE_NAME = "config.json";
    String CORRUPT_PREFIX = "config.json.corrupt.";
    String LEGACY_PREFIX = "config.json.legacy.";

    /** 存储失败（IO 被封装成运行时异常，避免用例层依赖 java.io）。 */
    final class Failure extends RuntimeException {
        public Failure(String message) {
            super(message);
        }

        public Failure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 事实源绝对路径，仅用于诊断展示。 */
    String path();

    /** 事实源是否存在。 */
    boolean exists();

    /** 事实源字节数；不存在时为 0。 */
    long size();

    /** 事实源最后修改时间；不存在时为 0。 */
    long lastModified();

    /** 指定受管文件的字节数；不存在时为 0。 */
    long size(String name);

    /** 指定受管文件的最后修改时间；不存在时为 0。 */
    long lastModified(String name);

    /** 读取事实源全文；不存在返回 null。 */
    String read();

    /** 读取指定受管文件全文；不存在返回 null。 */
    String readNamed(String name);

    /** 原子写事实源（tmp + fsync + rename，失败保留旧文件）。 */
    void writeAtomic(String json);

    /** 列出指定前缀的受管文件名，按修改时间倒序。 */
    List<String> listNames(String prefix);

    /** 把损坏原文另存为 {@code config.json.corrupt.<ts>}。 */
    void backupCorrupt(String raw);

    /** 把当前事实源移到 {@code config.json.corrupt.<ts>}。 */
    void moveCorruptAside();

    /** 把同目录的备份原子复制为事实源。 */
    void restoreNamed(String name);

    /**
     * 一次性迁移旧外部配置（beta.4 的 {@code Android/data/.../config.json}）。
     *
     * @return true = 发现并处理了旧文件；false = 没有旧文件
     */
    boolean migrateLegacy();
}
