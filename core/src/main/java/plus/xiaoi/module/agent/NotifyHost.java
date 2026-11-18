package plus.xiaoi.module.agent;

/**
 * 提醒通路宿主（纯 JVM 接口）。
 *
 * <p>{@code xiaoai_notify} 只负责“提醒”，不承担批准/拒绝：危险动作仍走
 * confirm token。真实实现 {@code AndroidNotifyHost} 先走小爱超级岛，
 * 失败再降级 Android 普通通知；本接口不依赖 android，便于单测协议面。</p>
 */
public interface NotifyHost {

    /**
     * 发送一条提醒。
     *
     * @param channel {@code island} 或 {@code notification}
     * @param text    已按通道截断后的文案
     * @param force   超级岛是否忽略“每进程一次”限制
     */
    NotifyResult notify(String channel, String text, boolean force);

    /** 实际送达的通道与降级标记；失败时 {@code error} 是稳定短语，不含异常正文。 */
    final class NotifyResult {
        public final boolean ok;
        public final String channel;
        public final String error;
        public final boolean fallback;

        public NotifyResult(boolean ok, String channel, String error, boolean fallback) {
            this.ok = ok;
            this.channel = channel == null ? "" : channel;
            this.error = error == null ? "" : error;
            this.fallback = fallback;
        }

        public static NotifyResult success(String channel, boolean fallback) {
            return new NotifyResult(true, channel, "", fallback);
        }

        public static NotifyResult failure(String channel, String error, boolean fallback) {
            return new NotifyResult(false, channel, error, fallback);
        }
    }
}
