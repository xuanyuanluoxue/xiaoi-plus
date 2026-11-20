package plus.xiaoi.module.application.runtime;

/**
 * 控制面日志端口（WP11 / P2）。
 *
 * <p>诊断页与日志页只需要「读当前内存镜像 / 清空」，不应该认识控制面的实现
 * （{@code control.BridgeControlService}）。这样 {@code :ui} 可以完全脱离
 * {@code :platform}。</p>
 */
public interface ControlLogStore {

    /** 当前日志镜像（桥 + Agent 两段合并，按时间序）；没有内容时返回空串。 */
    String dump();

    /**
     * 结构化合并视图（跨进程）：与 {@code xiaoai_logs} 同一份数据。
     *
     * <p>导出需要 {@code pid/proc/ts/tag} 才能按条件和进程筛选，纯文本做不到。</p>
     *
     * @param limit 尾部条数；0 = 空数组
     * @param since 全局 seq 游标；0 = 从头
     */
    String dumpJson(int limit, long since);

    /** 清空内存镜像（不删磁盘日志）。 */
    void clear();
}
