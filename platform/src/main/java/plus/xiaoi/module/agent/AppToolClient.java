package plus.xiaoi.module.agent;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import plus.xiaoi.module.Logs;

/**
 * 在小爱（voiceassist）进程里确定性调用 Notes 的 AppToolProvider。
 *
 * 线程模型：
 * - 本方法绝不在主线程等待；主线程调用直接返回结构化错误（ServiceConnection
 *   回调依赖主线程，主线程等待会死锁）。
 * - 连接在调用方后台线程完成，wait 5s；handleRequest 再交给专用后台池，wait 5s。
 * - 每次调用都 unbind，不缓存跨请求连接，避免 Notes 升级/被杀后的脏 binder。
 *
 * 授权前提（手机侧已确认，不在本类重复验证）：voiceassist 是系统 UID，
 * Notes 的 verifyCallerOrDebug() 才会放行。
 */
public final class AppToolClient {

    private AppToolClient() {}

    /**
     * 专用后台池：SynchronousQueue + 上限 2。binder 调用不可中断，若两次都卡死，
     * 后续请求立即返回 BUSY，而不是无限堆积线程。
     */
    private static final ThreadPoolExecutor CALLS = new ThreadPoolExecutor(
            0, 2, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());

    /**
     * {@code nlp_query} 独占的单线程池。
     *
     * <p>它对本机语句毫秒级返回，但音乐/天气这类要联网的语句在小爱内部可能**永不返回**
     * （实测 30s 仍无响应，线程卡在 binder）。binder 不可中断，所以只能隔离：
     * 卡住的是这一个池，记忆/待办共用 {@link #CALLS} 不受影响；同一时刻最多留一个
     * 僵尸线程，后续调用直接 {@code NLP_BUSY}，不会越积越多。</p>
     */
    private static final ThreadPoolExecutor NLP_CALL = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<Runnable>());

    /** 上一个 nlp_query 是否仍在飞（含卡死后被放弃等待的那种）。 */
    private static final AtomicBoolean NLP_INFLIGHT = new AtomicBoolean(false);

    /** 独占线程池的工具名。 */
    private static final String NLP_TOOL = "nlp_query";

    public static AppToolProtocol.Result createTodo(Context context, String title, String body) {
        if (context == null) {
            return AppToolProtocol.error("CONTEXT_UNAVAILABLE", "拿不到 voiceassist Context");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return AppToolProtocol.error("MAIN_THREAD_BLOCKED",
                    "拒绝在主线程等待 Notes binder（会造成 ServiceConnection 回调死锁）");
        }
        String t = title == null ? "" : title.trim();
        if (t.length() == 0) {
            return AppToolProtocol.error("INVALID_ARGUMENT", "title 不能为空");
        }
        String c = body == null ? "" : body;
        if (c.trim().length() == 0) c = AppToolProtocol.defaultContent(t);

        Context app = context.getApplicationContext();
        if (app == null) app = context;

        ComponentName component = resolveProvider(app);
        Session session = new Session(app, component);
        try {
            if (!session.bind()) {
                return AppToolProtocol.error("BIND_REJECTED",
                        "bindService 被拒绝：" + component.flattenToShortString());
            }
            IBinder binder = session.await(AppToolProtocol.BIND_TIMEOUT_MS);
            if (binder == null) {
                String reason = session.failure;
                if (reason == null || reason.length() == 0) reason = "BIND_TIMEOUT";
                return AppToolProtocol.error(reason, "等待 Notes provider 连接失败："
                        + component.flattenToShortString());
            }
            return callWithTimeout(app, binder,
                    AppToolProtocol.buildTodoRequest(t, c),
                    AppToolProtocol.CALL_TIMEOUT_MS, CALLS, false);
        } catch (Throwable e) {
            Logs.e("todo_create 调用失败: " + e);
            return AppToolProtocol.error("CALL_FAILED", "调用 Notes provider 失败", rootMessage(e));
        } finally {
            session.unbind();
        }
    }

    /**
     * 通用入口：显式组件（不走 queryIntentServices），其余纪律与 {@link #createTodo} 相同。
     *
     * <p>用于小爱自己的 {@code UIAgentServiceForOSBot}（同一 action、同一 AIDL），
     * 例如 {@code read_memory} / {@code write_memory}。</p>
     */
    public static AppToolProtocol.Result callTool(Context context, String pkg, String serviceClass,
                                                 String tool, java.util.Map<String, Object> args) {
        return callTool(context, pkg, serviceClass, tool, args,
                AppToolProtocol.CALL_TIMEOUT_MS);
    }

    /**
     * 同上，但可指定等待上限。
     *
     * <p>{@code nlp_query} 会走网络，调用方按需放宽；它同时独占 {@link #NLP_CALL}
     * 单线程池，卡死也不会拖垮记忆/待办。binder 本身不可中断，超时后只放弃结果
     * （线程仍停在原调用内）。</p>
     */
    public static AppToolProtocol.Result callTool(Context context, String pkg, String serviceClass,
                                                 String tool, java.util.Map<String, Object> args,
                                                 long timeoutMs) {
        if (context == null) {
            return AppToolProtocol.error("CONTEXT_UNAVAILABLE", "拿不到 voiceassist Context");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return AppToolProtocol.error("MAIN_THREAD_BLOCKED",
                    "拒绝在主线程等待 AppTool binder（会造成 ServiceConnection 回调死锁）");
        }
        boolean dedicated = NLP_TOOL.equals(tool);
        if (dedicated && !NLP_INFLIGHT.compareAndSet(false, true)) {
            return AppToolProtocol.error("NLP_BUSY",
                    "上一次 nlp_query 还没返回（联网类语句会在小爱内部卡住），"
                            + "稍后重试或改用 xiaoai_submit");
        }
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        ComponentName component = new ComponentName(pkg, serviceClass);
        Session session = new Session(app, component);
        boolean submitted = false;
        try {
            if (!session.bind()) {
                return AppToolProtocol.error("BIND_REJECTED",
                        "bindService 被拒绝：" + component.flattenToShortString());
            }
            IBinder binder = session.await(AppToolProtocol.BIND_TIMEOUT_MS);
            if (binder == null) {
                String reason = session.failure;
                if (reason == null || reason.length() == 0) reason = "BIND_TIMEOUT";
                return AppToolProtocol.error(reason, "等待 provider 连接失败："
                        + component.flattenToShortString());
            }
            submitted = true;
            return callWithTimeout(app, binder, AppToolProtocol.buildCall(tool, args), timeoutMs,
                    dedicated ? NLP_CALL : CALLS, dedicated);
        } catch (Throwable e) {
            Logs.e("apptool 调用失败: " + e);
            return AppToolProtocol.error("CALL_FAILED", "调用 provider 失败", rootMessage(e));
        } finally {
            session.unbind();
            // 独占池的占用标记由任务本身释放；这里只兜住「根本没提交」的早退。
            if (dedicated && !submitted) NLP_INFLIGHT.set(false);
        }
    }

    /**
     * 就绪度探针：只看类在不在、有多少方法，不实例化、不调用业务方法。
     *
     * @return 形如 {@code apptools=ok methods=64} / {@code apptools=none}（方法数含 binder 样板）
     */
    public static String probe(Context context) {
        try {
            ClassLoader cl = context != null ? context.getClassLoader()
                    : AppToolClient.class.getClassLoader();
            Class<?> c = Class.forName(AppToolProtocol.VOICEASSIST_SERVICE, false, cl);
            return "apptools=ok methods=" + c.getDeclaredMethods().length;
        } catch (Throwable t) {
            return "apptools=none";
        }
    }

    /** 官方记忆检索（{@code read_memory}）。 */
    public static AppToolProtocol.Result readMemory(Context context, String keywords,
                                                    int page, int pageSize) {
        if (context == null) {
            return AppToolProtocol.error("CONTEXT_UNAVAILABLE", "拿不到 voiceassist Context");
        }
        java.util.Map<String, Object> args = plus.xiaoi.module.Json.obj();
        if (keywords != null && keywords.length() > 0) args.put("keywords", keywords);
        args.put("page", (long) Math.max(1, page));
        args.put("page_size", (long) Math.max(1, pageSize));
        return readCall(context, "read_memory", args);
    }

    /** 官方记忆写入（{@code write_memory}，自然语言）。 */
    public static AppToolProtocol.Result writeMemory(Context context, String query) {
        if (context == null) {
            return AppToolProtocol.error("CONTEXT_UNAVAILABLE", "拿不到 voiceassist Context");
        }
        java.util.Map<String, Object> args = plus.xiaoi.module.Json.obj();
        args.put("query", query == null ? "" : query);
        return readCall(context, "write_memory", args);
    }

    /** 内存/写入共用的实参构造，避免两处拼 JSON 方式不一致。 */
    private static AppToolProtocol.Result readCall(Context context, String tool,
                                                   java.util.Map<String, Object> args) {
        return callTool(context, AppToolProtocol.VOICEASSIST_PACKAGE,
                AppToolProtocol.VOICEASSIST_SERVICE, tool, args);
    }

    /**
     * 先按 action 查；查不到再回退到已确认的组件名。这样即使 package visibility
     * 过滤了 queryIntentServices，显式组件绑定仍可再试一次。
     */
    private static ComponentName resolveProvider(Context context) {
        ComponentName candidate = null;
        try {
            Intent query = new Intent(AppToolProtocol.ACTION_PROVIDER);
            List<ResolveInfo> list = context.getPackageManager()
                    .queryIntentServices(query, 128);
            if (list != null) {
                for (ResolveInfo ri : list) {
                    ServiceInfo si = ri == null ? null : ri.serviceInfo;
                    if (si == null || !AppToolProtocol.NOTES_PACKAGE.equals(si.packageName)) {
                        continue;
                    }
                    String cls = si.name;
                    if (cls != null && cls.startsWith(".")) {
                        cls = AppToolProtocol.NOTES_PACKAGE + cls;
                    }
                    if (cls == null || cls.length() == 0) continue;
                    ComponentName found = new ComponentName(AppToolProtocol.NOTES_PACKAGE, cls);
                    if (AppToolProtocol.NOTES_SERVICE.equals(cls)) return found;
                    if (candidate == null) candidate = found;
                }
            }
        } catch (Throwable e) {
            Logs.e("todo_create 查询 provider 失败（回退已知组件）: " + e);
        }
        if (candidate != null) return candidate;
        return new ComponentName(AppToolProtocol.NOTES_PACKAGE,
                AppToolProtocol.NOTES_SERVICE);
    }

    private static AppToolProtocol.Result callWithTimeout(
            final Context context, final IBinder binder, final String request,
            final long timeoutMs, ThreadPoolExecutor pool, final boolean dedicated) {
        Future<String> future;
        try {
            future = pool.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    try {
                        return handleRequest(context, binder, request);
                    } finally {
                        // 真的返回了才释放独占标记；卡死的线程永远占着，后续调用走 NLP_BUSY。
                        if (dedicated) NLP_INFLIGHT.set(false);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            if (dedicated) NLP_INFLIGHT.set(false);
            return AppToolProtocol.error("BUSY",
                    "AppTool 并发已满（前一次调用仍卡在 binder）");
        }
        try {
            String raw = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return AppToolProtocol.parseResponse(raw);
        } catch (TimeoutException e) {
            future.cancel(true);
            return AppToolProtocol.error("CALL_TIMEOUT",
                    "handleRequest 超过 " + timeoutMs + "ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return AppToolProtocol.error("INTERRUPTED", "等待 AppTool 响应时被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            Logs.e("apptool binder 异常: " + cause);
            return AppToolProtocol.error("CALL_FAILED", "handleRequest 抛异常",
                    rootMessage(cause));
        }
    }

    /** 反射调用 IAppToolProvider.Stub.asInterface(binder).handleRequest(json)。 */
    private static String handleRequest(Context context, IBinder binder, String request)
            throws Exception {
        ClassLoader cl = context.getClassLoader();
        Class<?> stub = Class.forName(
                "com.aios.apptoolsdk.aidl.IAppToolProvider$Stub", false, cl);
        Method asInterface = stub.getMethod("asInterface", IBinder.class);
        Object provider = asInterface.invoke(null, binder);
        if (provider == null) throw new IllegalStateException("asInterface returned null");

        Class<?> iface = Class.forName(
                "com.aios.apptoolsdk.aidl.IAppToolProvider", false, cl);
        Method handle = iface.getMethod("handleRequest", String.class);
        Object out = handle.invoke(provider, request);
        if (!(out instanceof String)) {
            throw new IllegalStateException("handleRequest returned "
                    + (out == null ? "null" : out.getClass().getName()));
        }
        return (String) out;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur instanceof InvocationTargetException
                && ((InvocationTargetException) cur).getCause() != null) {
            cur = ((InvocationTargetException) cur).getCause();
        }
        if (cur == null) return "";
        String msg = cur.getMessage();
        return cur.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private static final class Session implements ServiceConnection {
        private final Context context;
        private final ComponentName component;
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile IBinder binder;
        private volatile String failure;
        private volatile boolean bound;

        Session(Context context, ComponentName component) {
            this.context = context;
            this.component = component;
        }

        boolean bind() {
            Intent intent = new Intent();
            intent.setComponent(component);
            // 与系统 StandardAppToolProvider 一致，允许拉起处于 stopped 状态的 Notes。
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            try {
                bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE);
                return bound;
            } catch (Throwable e) {
                failure = "BIND_REJECTED";
                Logs.e("todo_create bindService 异常: " + rootMessage(e));
                return false;
            }
        }

        IBinder await(long timeoutMs) {
            try {
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = "INTERRUPTED";
                return null;
            }
            return binder;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service == null) {
                failure = "EMPTY_BINDER";
            } else {
                binder = service;
            }
            latch.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
        }

        @Override
        public void onNullBinding(ComponentName name) {
            failure = "NULL_BINDING";
            latch.countDown();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            failure = "BINDING_DIED";
            latch.countDown();
        }

        void unbind() {
            if (!bound) return;
            bound = false;
            try {
                context.unbindService(this);
            } catch (Throwable e) {
                Logs.e("todo_create unbindService 失败（已忽略）: " + rootMessage(e));
            }
        }
    }
}
