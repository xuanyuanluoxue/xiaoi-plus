package plus.xiaoi.module;

import plus.xiaoi.module.core.NavStack;
import plus.xiaoi.module.core.NavStack.MainTab;

/** NavStack：push/pop、切 tab 清栈、一级返回规则、根页判断、未知路由。 */
public final class NavStackTest {

    private NavStackTest() {}

    public static int run() {
        NavStack nav = new NavStack();
        T.eq("初始首页", "Home", nav.current());
        T.eq("初始栈大小 1", 1, nav.size());
        T.eq("初始 tab HOME", MainTab.HOME, nav.tab());
        T.ok("首页是根页", nav.isRoot());
        T.ok("首页不能返回", !nav.canGoBack());

        // 一级页切换 + 二级页
        nav.switchTab(MainTab.XIAOAI);
        T.eq("切到小爱", "Xiaoai", nav.current());
        T.eq("切 tab 后栈清空", 1, nav.size());
        T.ok("小爱是根页", nav.isRoot());
        T.ok("非首页根页可返回首页", nav.canGoBack());

        nav.push("Model");
        T.eq("push 模型页", "Model", nav.current());
        T.eq("二级页栈大小 2", 2, nav.size());
        T.ok("二级页隐藏底栏", !nav.isRoot());
        T.eq("二级页不改变 tab", MainTab.XIAOAI, nav.tab());

        nav.push("CapabilityDetail");
        T.eq("三级页当前", "CapabilityDetail", nav.current());
        T.eq("三级页栈大小 3", 3, nav.size());

        T.eq("pop 到模型页", "Model", nav.pop());
        T.eq("pop 后大小 2", 2, nav.size());
        T.eq("pop 到小爱根页", "Xiaoai", nav.pop());
        T.ok("回到根页", nav.isRoot());
        T.eq("根页继续 pop 不越界", "Xiaoai", nav.pop());
        T.eq("根页 pop 后大小不变", 1, nav.size());

        T.ok("非首页返回有效", nav.back());
        T.eq("根页返回到首页", "Home", nav.current());
        T.eq("返回后 tab HOME", MainTab.HOME, nav.tab());
        T.ok("首页返回交给退出", !nav.back());
        T.eq("首页 back 后仍首页", "Home", nav.current());

        // 切 tab 清空栈
        nav.switchTab(MainTab.ACCESS);
        nav.push("Bridge");
        nav.push("Capabilities");
        nav.switchTab(MainTab.ME);
        T.eq("切 tab 清空二级页", 1, nav.size());
        T.eq("切 tab 到我的", "Me", nav.current());
        T.ok("我的根页可返回", nav.back());
        T.eq("我的返回首页", "Home", nav.current());

        // push 根路由等同切 tab
        nav.push("Access");
        T.eq("push 根路由切 tab", MainTab.ACCESS, nav.tab());
        T.eq("push 根路由清栈", 1, nav.size());
        T.eq("push 根路由当前页", "Access", nav.current());

        // 未知路由按二级页处理
        nav.switchTab(MainTab.HOME);
        nav.push("Bogus");
        T.eq("未知路由可进入", "Bogus", nav.current());
        T.eq("未知路由不是根页", false, nav.isRoot());
        T.ok("未知路由可返回", nav.back());
        T.eq("未知路由返回首页", "Home", nav.current());

        // null/空/重复忽略
        int before = nav.size();
        nav.push(null);
        nav.push("");
        nav.push("   ");
        T.eq("null/空路由被忽略", before, nav.size());
        nav.push("Model");
        nav.push("Model");
        T.eq("连续重复 push 被忽略", before + 1, nav.size());
        T.eq("重复 push 当前不变", "Model", nav.current());

        // 根路由判断 / reset / 快照
        nav.push("Xiaoai");
        T.eq("push 根路由重置为根页", 1, nav.size());
        nav.switchTab(MainTab.ME);
        nav.reset();
        T.eq("reset 回首页", "Home", nav.current());
        T.eq("reset 后 tab HOME", MainTab.HOME, nav.tab());

        T.ok("Home 是根路由", NavStack.isRootRoute("Home"));
        T.ok("Xiaoi 是根路由", NavStack.isRootRoute("Xiaoai"));
        T.ok("Model 不是根路由", !NavStack.isRootRoute("Model"));
        T.ok("null 不是根路由", !NavStack.isRootRoute(null));
        T.eq("snapshot 只读", true, throwsOnAdd(nav.snapshot()));

        NavStack nullInit = new NavStack(null);
        T.eq("null 初始 tab 回首页", "Home", nullInit.current());

        return T.report("NavStackTest");
    }

    private static boolean throwsOnAdd(java.util.List<String> list) {
        try {
            list.add("X");
            return false;
        } catch (UnsupportedOperationException expected) {
            return true;
        }
    }
}
