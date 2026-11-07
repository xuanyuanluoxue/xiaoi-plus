package plus.xiaoi.module.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 单栈导航状态（纯 JVM，无 android / Kotlin 依赖）。
 *
 * <p>规则与 SCREENS.md §0 / PLAN §4.1 一致：</p>
 * <ul>
 *   <li>切 tab 清空栈，只保留该 tab 的根路由；</li>
 *   <li>一级根页返回非首页 → 首页；首页 → 不能返回（退出）；</li>
 *   <li>二级/三级页 {@link #isRoot()} 为 false，UI 据此隐藏底栏。</li>
 * </ul>
 *
 * <p>路由用 String，只放页面名；不携带 token/key。未知路由按二级页处理，
 * 深链白名单由调用方在 push 前裁决。</p>
 */
public final class NavStack {

    public enum MainTab {
        HOME("Home"),
        XIAOAI("Xiaoai"),
        ACCESS("Access"),
        ME("Me");

        public final String root;

        MainTab(String root) {
            this.root = root;
        }
    }

    public static final String HOME = "Home";
    public static final String XIAOAI = "Xiaoai";
    public static final String ACCESS = "Access";
    public static final String ME = "Me";

    private final ArrayList<String> stack = new ArrayList<>();
    private MainTab tab;

    public NavStack() {
        this(MainTab.HOME);
    }

    public NavStack(MainTab initial) {
        tab = initial == null ? MainTab.HOME : initial;
        stack.add(tab.root);
    }

    /**
     * 压入页面。根路由等同切 tab；null/空串忽略；与栈顶相同忽略（防双击）。
     */
    public void push(String route) {
        if (route == null) return;
        String r = route.trim();
        if (r.isEmpty()) return;
        MainTab rootTab = tabOfRoot(r);
        if (rootTab != null) {
            switchTab(rootTab);
            return;
        }
        if (r.equals(current())) return;
        stack.add(r);
    }

    /** 返回上一页并返回新的栈顶；已在根页时保持不动。 */
    public String pop() {
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
        }
        return current();
    }

    /** 切 tab：清空栈，只留该 tab 的根路由。 */
    public void switchTab(MainTab target) {
        if (target == null) return;
        tab = target;
        stack.clear();
        stack.add(tab.root);
    }

    /**
     * 系统返回：二级页 pop；非首页根页回首页；首页根页返回 false（交给 Activity 退出）。
     */
    public boolean back() {
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            return true;
        }
        if (tab != MainTab.HOME) {
            switchTab(MainTab.HOME);
            return true;
        }
        return false;
    }

    public String current() {
        return stack.get(stack.size() - 1);
    }

    /** 当前是否一级根页（决定是否显示底栏）。 */
    public boolean isRoot() {
        return stack.size() == 1 && tab.root.equals(current());
    }

    /** 系统返回是否还有效果。 */
    public boolean canGoBack() {
        return stack.size() > 1 || tab != MainTab.HOME;
    }

    public int size() {
        return stack.size();
    }

    public MainTab tab() {
        return tab;
    }

    /** 回到首页根页。 */
    public void reset() {
        switchTab(MainTab.HOME);
    }

    /** 根路由判断；未知/空返回 false。 */
    public static boolean isRootRoute(String route) {
        return tabOfRoot(route) != null;
    }

    /** 栈的快照（旧→新），用于调试或状态恢复；只读。 */
    public List<String> snapshot() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(stack));
    }

    private static MainTab tabOfRoot(String route) {
        if (route == null) return null;
        String r = route.trim();
        for (MainTab t : MainTab.values()) {
            if (t.root.equals(r)) return t;
        }
        return null;
    }
}
