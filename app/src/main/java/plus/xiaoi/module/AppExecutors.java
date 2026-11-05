package plus.xiaoi.module;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * App 进程唯一的后台执行器 + 主线程回调。
 *
 * 页面不再各自 {@code new Thread}：统一 3 个 IO 线程（网络/文件都是短任务，
 * 3 个足够且不会把线程数打爆），所有 UI 更新经 {@link #ui(Runnable)} 回主线程。
 *
 * 注意：这是 App 进程使用的；小爱进程里的 Bridge/Hook 不能依赖它。
 */
public final class AppExecutors {

    private static final int IO_THREADS = 3;

    private static final ExecutorService IO = Executors.newFixedThreadPool(
            IO_THREADS, new ThreadFactory() {
                private int n;

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "xiaoi-app-io-" + (n++));
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppExecutors() {}

    public static void io(Runnable r) {
        if (r == null) return;
        try {
            IO.execute(r);
        } catch (Throwable t) {
            // 执行器被关闭等极端情况：退化为同步执行，保证调用方逻辑不丢。
            try { r.run(); } catch (Throwable ignored) {}
        }
    }

    public static void ui(Runnable r) {
        if (r == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return;
        }
        MAIN.post(r);
    }
}
