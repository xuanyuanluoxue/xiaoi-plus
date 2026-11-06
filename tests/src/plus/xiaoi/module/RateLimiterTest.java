package plus.xiaoi.module;

import plus.xiaoi.module.core.RateLimiter;

/** RateLimiter：固定窗口、按 key 隔离、超限拒绝、窗口滚动与容量上限。 */
public final class RateLimiterTest {

    private RateLimiterTest() {}

    public static int run() {
        System.out.println("-- RateLimiter");

        RateLimiter rl = new RateLimiter(3, 10_000L);
        T.ok("第 1 次放行", rl.allow("ip-a", 1000L));
        T.ok("第 2 次放行", rl.allow("ip-a", 1001L));
        T.ok("第 3 次放行", rl.allow("ip-a", 1002L));
        T.ok("第 4 次拒绝", !rl.allow("ip-a", 1003L));
        T.ok("窗口未到仍拒绝", !rl.allow("ip-a", 10_999L));
        T.ok("窗口到期放行", rl.allow("ip-a", 11_000L));

        T.ok("其他 key 不受影响", rl.allow("ip-b", 11_001L));
        T.ok("null key 可限流", rl.allow(null, 11_002L));
        T.ok("null key 计数独立", rl.allow("", 11_003L));

        // 时钟回拨：重置窗口，不永久锁死
        T.ok("时钟回拨重置", rl.allow("ip-a", 500L));

        // 容量上限：超过 maxKeys 后仍有界
        RateLimiter bounded = new RateLimiter(10, 10_000L, 4);
        for (int i = 0; i < 10; i++) {
            bounded.allow("k" + i, 20_000L + i);
        }
        T.ok("key 表有上限", bounded.size() <= 4);

        // 默认参数：30 次/10s
        RateLimiter def = new RateLimiter();
        for (int i = 0; i < 30; i++) {
            T.ok("默认第 " + (i + 1) + " 次放行", def.allow("x", 1_000L + i));
        }
        T.ok("默认第 31 次拒绝", !def.allow("x", 1_099L));

        return T.report("RateLimiterTest");
    }
}
