package plus.xiaoi.module;

import java.util.List;

import plus.xiaoi.module.core.ActivityFeed;
import plus.xiaoi.module.core.ActivityFeed.Domain;
import plus.xiaoi.module.core.ActivityFeed.Event;
import plus.xiaoi.module.core.ActivityFeed.Level;
import plus.xiaoi.module.core.ActivityHub;

/** ActivityFeed：环形覆盖、last 顺序、clear、容量下限、简单并发安全。 */
public final class ActivityFeedTest {

    private ActivityFeedTest() {}

    public static int run() {
        ActivityFeed def = new ActivityFeed();
        T.eq("默认容量 20", 20, def.capacity());
        T.eq("初始为空", 0, def.count());

        ActivityFeed feed = new ActivityFeed(3);
        T.eq("自定义容量", 3, feed.capacity());
        feed.add(1L, Level.INFO, Domain.XIAOAI, "e1");
        feed.add(2L, Level.WARN, Domain.AGENT, "e2");
        Event third = feed.add(3L, Level.ERROR, Domain.ACCESS, "e3");
        T.eq("写入三条", 3, feed.count());
        T.eq("add 返回事件", "e3", third.text);
        T.eq("事件保留级别", Level.ERROR, third.level);
        T.eq("事件保留域", Domain.ACCESS, third.domain);
        T.eq("事件保留时间", 3L, third.timestamp);

        List<Event> all = feed.last(10);
        T.eq("last 超出返回全部", 3, all.size());
        T.eq("last 从旧到新", "e1,e2,e3", texts(all));
        T.eq("last(2) 取最近两条", "e2,e3", texts(feed.last(2)));
        T.eq("last(0) 为空", 0, feed.last(0).size());
        T.eq("last(-1) 为空", 0, feed.last(-1).size());

        // 环形覆盖：4/5 覆盖 1/2
        feed.add(4L, Level.INFO, Domain.XIAOAI, "e4");
        feed.add(5L, Level.INFO, Domain.XIAOAI, "e5");
        T.eq("覆盖后仍是容量条数", 3, feed.count());
        T.eq("覆盖后保留最新三条", "e3,e4,e5", texts(feed.last(10)));
        T.eq("覆盖后 last(1)", "e5", texts(feed.last(1)));

        feed.clear();
        T.eq("clear 后为 0", 0, feed.count());
        T.eq("clear 后 last 为空", 0, feed.last(5).size());

        ActivityFeed tiny = new ActivityFeed(0);
        T.eq("容量小于 1 按 1", 1, tiny.capacity());
        tiny.add(Level.INFO, Domain.ACCESS, "a");
        tiny.add(Level.INFO, Domain.ACCESS, "b");
        T.eq("容量 1 只保留最后一条", "b", texts(tiny.last(10)));

        ActivityFeed nullable = new ActivityFeed(2);
        Event e = nullable.add(7L, null, null, null);
        T.eq("null level 默认 INFO", Level.INFO, e.level);
        T.eq("null domain 默认 ACCESS", Domain.ACCESS, e.domain);
        T.eq("null text 转空串", "", e.text);

        // 简单并发：4 线程 × 100 条，容量 500，不丢不重
        final ActivityFeed concurrent = new ActivityFeed(500);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            final int base = t * 100;
            threads[t] = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 100; i++) {
                        concurrent.add(base + i, Level.INFO, Domain.AGENT, "t" + (base + i));
                    }
                }
            });
            threads[t].start();
        }
        boolean joined = true;
        for (Thread th : threads) {
            try {
                th.join(5000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                joined = false;
            }
            if (th.isAlive()) joined = false;
        }
        T.ok("并发线程正常结束", joined);
        T.eq("并发写入 400 条", 400, concurrent.count());
        T.eq("并发 last 400 条", 400, concurrent.last(400).size());
        T.eq("并发 last 只取最近 10", 10, concurrent.last(10).size());

        // App 级共享入口：容量 50，写入/读取顺序与独立 feed 一致。
        ActivityHub.clear();
        T.eq("ActivityHub 容量 50", 50, ActivityHub.feed().capacity());
        T.eq("ActivityHub 初始为空", 0, ActivityHub.count());
        ActivityHub.info(Domain.XIAOAI, "h1");
        ActivityHub.warn(Domain.AGENT, "h2");
        ActivityHub.error(Domain.ACCESS, "h3");
        T.eq("ActivityHub 写入三条", 3, ActivityHub.count());
        T.eq("ActivityHub 最近两条", "h2,h3", texts(ActivityHub.recent(2)));
        T.eq("ActivityHub 级别保留", Level.WARN,
                ActivityHub.recent(2).get(0).level);
        T.eq("ActivityHub domain 保留", Domain.ACCESS,
                ActivityHub.recent(1).get(0).domain);
        T.eq("ActivityHub 文本去换行", "a b",
                ActivityHub.info(Domain.AGENT, "a\nb").text);
        ActivityHub.clear();
        T.eq("ActivityHub clear 后为空", 0, ActivityHub.count());

        return T.report("ActivityFeedTest");
    }

    private static String texts(List<Event> events) {
        StringBuilder sb = new StringBuilder();
        for (Event e : events) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.text);
        }
        return sb.toString();
    }
}
