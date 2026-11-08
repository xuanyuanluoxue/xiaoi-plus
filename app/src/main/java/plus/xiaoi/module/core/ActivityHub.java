package plus.xiaoi.module.core;

import java.util.List;

/**
 * App 进程级「最近活动」入口（纯 JVM，无 android 依赖）。
 *
 * <p>控制面/Agent 服务写入事实，首页与日志页只读；容量固定 50 条，
 * 内存态、不落盘。与 {@link ActivityFeed} 的关系只是持有一个共享实例，
 * 不把 feed 塞进 {@link BridgeStatus} 或 {@link StateModel}。</p>
 *
 * <p>写入者负责去掉密钥/令牌；本类仍会截断换行与超长文本，避免单条日志撑爆 UI。</p>
 */
public final class ActivityHub {

    public static final int CAPACITY = 50;
    private static final int MAX_TEXT_CHARS = 240;

    private static final ActivityFeed FEED = new ActivityFeed(CAPACITY);

    private ActivityHub() {}

    /** 共享 feed；页面只调用 {@link ActivityFeed#last(int)}。 */
    public static ActivityFeed feed() {
        return FEED;
    }

    public static ActivityFeed.Event info(ActivityFeed.Domain domain, String text) {
        return FEED.add(ActivityFeed.Level.INFO, domain, clean(text));
    }

    public static ActivityFeed.Event warn(ActivityFeed.Domain domain, String text) {
        return FEED.add(ActivityFeed.Level.WARN, domain, clean(text));
    }

    public static ActivityFeed.Event error(ActivityFeed.Domain domain, String text) {
        return FEED.add(ActivityFeed.Level.ERROR, domain, clean(text));
    }

    /** 最近 n 条，按时间从旧到新；n &lt;= 0 返回空。 */
    public static List<ActivityFeed.Event> recent(int n) {
        return FEED.last(n);
    }

    public static int count() {
        return FEED.count();
    }

    /** 清空共享缓冲；仅诊断/测试使用，不影响控制面日志。 */
    public static void clear() {
        FEED.clear();
    }

    private static String clean(String text) {
        if (text == null) return "";
        String v = text.replace('\n', ' ').replace('\r', ' ').trim();
        return v.length() > MAX_TEXT_CHARS ? v.substring(0, MAX_TEXT_CHARS) : v;
    }
}
