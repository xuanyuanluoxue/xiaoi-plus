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

    /** 清空内存镜像（不删磁盘日志）。 */
    void clear();
}
