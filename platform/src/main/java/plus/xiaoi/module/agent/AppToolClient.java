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
                    AppToolProtocol.buildTodoRequest(t, c));
        } catch (Throwable e) {
            Logs.e("todo_create 调用失败: " + e);
            return AppToolProtocol.error("CALL_FAILED", "调用 Notes provider 失败", rootMessage(e));
        } finally {
            session.unbind();
        }
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
            final Context context, final IBinder binder, final String request) {
        Future<String> future;
        try {
            future = CALLS.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return handleRequest(context, binder, request);
                }
            });
        } catch (RejectedExecutionException e) {
            return AppToolProtocol.error("BUSY", "Notes 调用并发已满（前一次可能仍卡在 binder）");
        }
        try {
            String raw = future.get(AppToolProtocol.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return AppToolProtocol.parseResponse(raw);
        } catch (TimeoutException e) {
            future.cancel(true);
            return AppToolProtocol.error("CALL_TIMEOUT",
                    "handleRequest 超过 " + AppToolProtocol.CALL_TIMEOUT_MS + "ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return AppToolProtocol.error("INTERRUPTED", "等待 Notes 响应时被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            Logs.e("todo_create binder 异常: " + cause);
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
