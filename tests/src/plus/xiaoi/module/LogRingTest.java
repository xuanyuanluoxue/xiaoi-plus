package plus.xiaoi.module;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import plus.xiaoi.module.application.log.LogEntry;
import plus.xiaoi.module.application.log.LogRing;
import plus.xiaoi.module.core.Contract;

/** 纯 JVM 日志环形缓冲：容量、since/limit、空值、并发。 */
public final class LogRingTest {

    private LogRingTest() {}

    public static int run() {
        LogRing ring = new LogRing();
        T.eq("默认容量来自 Contract", Contract.LOGS_CAPACITY, ring.capacity());

        for (int i = 1; i <= Contract.LOGS_CAPACITY + 50; i++) {
            ring.add(i % 10 == 0 ? "E" : "I", "t", "m" + i, 1000L + i, 7);
        }
        T.eq("容量上限", Contract.LOGS_CAPACITY, ring.count());
        T.eq("lastSeq 不因淘汰回退", (long) (Contract.LOGS_CAPACITY + 50), ring.lastSeq());

        List<LogEntry> tail = ring.snapshot(80, 0);
        T.eq("limit 取尾部", 80, tail.size());
        T.eq("尾部起点", 271L, tail.get(0).seq);
        T.eq("字段保留", "m271", tail.get(0).msg);
        T.eq("tag 保留", "t", tail.get(0).tag);
        T.eq("ts 保留", 1271L, tail.get(0).tsMillis);
        T.eq("pid 保留", 7, tail.get(0).pid);

        T.eq("limit=0 返回空", 0, ring.snapshot(0, 0).size());
        T.eq("limit<0 返回空", 0, ring.snapshot(-3, 0).size());
        T.eq("since 过滤", 2, ring.snapshot(300, 348).size());
        T.eq("since 首条", 349L, ring.snapshot(300, 348).get(0).seq);
        T.eq("limit 超过总量返回全部", Contract.LOGS_CAPACITY,
                ring.snapshot(9999, 0).size());

        LogRing tiny = new LogRing(1);
        tiny.add(null, null, null, 5L, -1);
        LogEntry e = tiny.snapshot(1, 0).get(0);
        T.eq("空 level 回落 I", "I", e.level);
        T.eq("空 tag 归一", "", e.tag);
        T.eq("空 msg 归一", "", e.msg);
        T.eq("负 pid 归一", 0, e.pid);
        tiny.add("E", "x", "second", 6L, 1);
        T.eq("容量 1 淘汰最旧", 1, tiny.count());
        T.eq("容量 1 保留最新", "second", tiny.snapshot(1, 0).get(0).msg);

        long beforeClear = tiny.lastSeq();
        tiny.clear();
        T.eq("clear 清空缓冲", 0, tiny.count());
        T.eq("clear 不回退 seq", beforeClear, tiny.lastSeq());
        tiny.add("I", "", "after", 7L, 1);
        T.eq("clear 后 seq 继续递增", beforeClear + 1, tiny.lastSeq());

        // 并发：4 线程各写 250 条，seq 必须唯一且连续；容量足够时不丢。
        final LogRing conc = new LogRing(2000);
        final Throwable[] error = new Throwable[1];
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int i = 0; i < 250; i++) {
                            conc.add("I", "c", "x", System.currentTimeMillis(), 1);
                        }
                    } catch (Throwable ex) {
                        error[0] = ex;
                    }
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        T.ok("并发写入无异常", error[0] == null);
        T.eq("并发写入条数", 1000, conc.count());
        T.eq("并发写入 lastSeq", 1000L, conc.lastSeq());
        List<LogEntry> all = conc.snapshot(2000, 0);
        Set<Long> seqs = new HashSet<Long>();
        boolean ordered = true;
        long prev = 0;
        for (LogEntry x : all) {
            if (x.seq <= prev) ordered = false;
            prev = x.seq;
            seqs.add(x.seq);
        }
        T.ok("并发后 seq 严格递增", ordered);
        T.eq("并发后 seq 无重复", 1000, seqs.size());

        return T.report("LogRingTest");
    }
}
