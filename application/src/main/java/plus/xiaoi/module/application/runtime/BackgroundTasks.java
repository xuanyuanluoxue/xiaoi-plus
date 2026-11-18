package plus.xiaoi.module.application.runtime;

/** 后台线程端口（WP11）：UI 不直接引用 AppExecutors。 */
public interface BackgroundTasks {

    /** 阻塞任务（读配置 / 网络探测）。 */
    void io(Runnable task);

    /** 回到主线程。 */
    void main(Runnable task);
}
