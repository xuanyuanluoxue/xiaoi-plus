package plus.xiaoi.module;

import android.content.Context;
import android.content.Intent;

/**
 * 「打开本 App」的唯一入口（WP10）。
 *
 * <p>{@code :platform} 是下层适配器，不能引用 {@code :app} 里的 {@code MainActivity}
 * （否则依赖方向倒置）。这里按「包名 + 类名」拼出显式 ComponentName：
 * 应用进程与 {@code MainActivity} 同包（{@code applicationId = plus.xiaoi.module}），
 * 所以 {@code getPackageName() + ".MainActivity"} 就是启动页。</p>
 *
 * <p>改名保护：{@code scripts/check-consistency.sh} 已经断言清单的 launcher
 * 仍指向 {@code .MainActivity}；改启动页时必须同步本类的常量。</p>
 */
public final class AppLaunch {

    static final String ENTRY_CLASS = "MainActivity";

    private AppLaunch() {}

    /** 指向设置页的显式 Intent（调用方自己加 FLAG_ACTIVITY_*）。 */
    public static Intent intent(Context ctx) {
        if (ctx == null) throw new IllegalArgumentException("ctx == null");
        String pkg = ctx.getPackageName();
        return new Intent().setClassName(pkg, pkg + "." + ENTRY_CLASS);
    }
}
