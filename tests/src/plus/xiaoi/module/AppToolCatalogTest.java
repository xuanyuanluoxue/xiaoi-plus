package plus.xiaoi.module;

import plus.xiaoi.module.core.apptool.AppToolCatalog;

/** AppTool 白名单目录（纯 JVM）。 */
public final class AppToolCatalogTest {

    private AppToolCatalogTest() {}

    public static int run() {
        AppToolCatalog.Entry read = AppToolCatalog.find("alarm", "deskclock_query_alarms");
        ok("只读条目存在", read != null && !read.writable());
        AppToolCatalog.Entry write = AppToolCatalog.find("alarm", "deskclock_add_alarm");
        ok("写条目存在", write != null && write.writable());
        ok("未知组合返回 null",
                AppToolCatalog.find("alarm", "phone_make_call") == null
                        && AppToolCatalog.find("telecom", "phone_make_call") == null);

        ok("危险 provider 未收录",
                !AppToolCatalog.hasApp("telecom") && !AppToolCatalog.hasApp("bugreport")
                        && !AppToolCatalog.hasApp("scanner"));
        ok("已收录 provider",
                AppToolCatalog.hasApp("alarm") && AppToolCatalog.hasApp("calendar")
                        && AppToolCatalog.hasApp("notes") && AppToolCatalog.hasApp("recorder")
                        && AppToolCatalog.hasApp("gallery") && AppToolCatalog.hasApp("weather"));

        eq("alarm 包名", "com.android.deskclock", AppToolCatalog.packageOf("alarm"));
        eq("alarm Service", "com.android.deskclock.tool.AlarmToolService",
                AppToolCatalog.serviceOf("alarm"));
        eq("notes 包名", "com.miui.notes", AppToolCatalog.packageOf("notes"));
        ok("未知别名无包名", AppToolCatalog.packageOf("nope") == null);

        int readable = AppToolCatalog.readable().size();
        int all = AppToolCatalog.entries().size();
        ok("只读条目为正且少于总数", readable > 0 && readable < all);
        ok("app 下工具非空", !AppToolCatalog.byApp("alarm").isEmpty());
        ok("未知 app 无工具", AppToolCatalog.byApp("nope").isEmpty());
        ok("别名表含 12 个 provider", AppToolCatalog.apps().size() == 12);

        // 风险归类回归：这三个曾经归错
        ok("phone_settings 需确认",
                AppToolCatalog.find("phone", "phone_settings").writable());
        ok("card_template 只读",
                !AppToolCatalog.find("notes", "card_template").writable());
        ok("gallery_assess_trash 只读",
                !AppToolCatalog.find("gallery", "gallery_assess_trash").writable());
        return T.report("AppToolCatalogTest");
    }

    private static void ok(String n, boolean c) { T.ok(n, c); }

    private static void eq(String n, Object e, Object a) { T.eq(n, e, a); }
}
