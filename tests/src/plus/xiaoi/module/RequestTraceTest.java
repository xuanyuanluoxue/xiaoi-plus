package plus.xiaoi.module;

import plus.xiaoi.module.application.log.RequestTrace;

/** 请求关联标识：生成、读取、清理、线程隔离。 */
public final class RequestTraceTest {

    private RequestTraceTest() {}

    public static int run() {
        RequestTrace.exit();
        T.eq("初始无 trace", null, RequestTrace.current());

        String a = RequestTrace.enter();
        T.ok("id 是 6 位 hex", a != null && a.matches("[0-9a-f]{6}"));
        T.eq("enter 后 current 一致", a, RequestTrace.current());
        RequestTrace.exit();
        T.eq("exit 后清空", null, RequestTrace.current());

        String b = RequestTrace.enter();
        RequestTrace.exit();
        String c = RequestTrace.enter();
        RequestTrace.exit();
        T.ok("连续请求 id 不同", !b.equals(c));

        final String[] seen = new String[1];
        RequestTrace.enter();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                seen[0] = RequestTrace.current();
            }
        });
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        T.eq("其他线程不继承 trace", null, seen[0]);
        T.ok("主线程 trace 不受影响", RequestTrace.current() != null);
        RequestTrace.exit();

        return T.report("RequestTraceTest");
    }
}
