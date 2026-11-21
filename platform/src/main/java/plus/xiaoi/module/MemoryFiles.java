package plus.xiaoi.module;

import plus.xiaoi.module.application.memory.MemoryService;
import plus.xiaoi.module.memory.MemoryFileStore;

/**
 * 小爱记忆文件的位置与读取（P2-C / P2-D 接线）。
 *
 * <p>路径固定为<b>小爱进程</b>的 {@code getFilesDir()/memory}；模块跑在小爱进程内
 * （同 uid），直接读写即可，不需要 root，也不碰加密数据库。</p>
 *
 * <p>Context 通过 {@code ActivityThread.currentApplication()} 取：Hook 在早期加载时
 * 可能还拿不到，这时返回 null，调用方按「没有记忆」处理，绝不抛异常。</p>
 */
public final class MemoryFiles {

    /** 记忆目录名（真机：{@code files/memory}）。 */
    public static final String DIR_NAME = "memory";

    private MemoryFiles() {}

    /** 记忆目录；Application 未就绪时返回 null。 */
    public static java.io.File dir() {
        android.content.Context ctx = appContext();
        if (ctx == null) return null;
        try {
            return new java.io.File(ctx.getFilesDir(), DIR_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 记忆用例；目录不可用时返回 null。 */
    public static MemoryService service() {
        java.io.File d = dir();
        if (d == null) return null;
        return new MemoryService(new MemoryFileStore(d));
    }

    /** 注入用摘要：优先 outline.md，退回 MEMORY.md；失败返回空串。 */
    public static String outline(int maxLines) {
        MemoryService svc = service();
        if (svc == null) return "";
        try {
            return svc.injectText(maxLines);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 当前进程的 Application（小爱）；未就绪返回 null。 */
    public static android.content.Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof android.content.Context ? (android.content.Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
