package plus.xiaoi.module;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 四个页面的公共壳：主题应用、底部导航、尺寸工具。
 *
 * 主题只有一条路径：attachBaseContext 时按 ThemeManager 覆写 uiMode；
 * onResume 发现偏好变了就重建自己。这样任何页面（包括被 REORDER_TO_FRONT
 * 复用的旧实例）都会在同一帧内切到新主题。
 */
public class BaseActivity extends Activity {

    /** 本实例 attach 时实际使用的主题；-1 = 尚未记录。 */
    private int appliedTheme = -1;

    @Override
    protected void attachBaseContext(Context base) {
        appliedTheme = ThemeManager.get(base);
        super.attachBaseContext(ThemeManager.wrap(base, appliedTheme));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 主题是全局偏好：别的页面改了主题，复用中的旧实例在这里对齐。
        if (appliedTheme >= 0 && ThemeManager.get(this) != appliedTheme) recreate();
    }

    protected int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView messageView;

    /**
     * 应用内提示条（替代 Toast）：底部导航上方浮层，2.6 秒自动消失。
     * 四页共用同一位置/样式/时长，避免系统 Toast 在不同 ROM 上位置漂移。
     */
    protected void showMessage(String msg) {
        if (msg == null || msg.length() == 0) return;
        final ViewGroup root = findViewById(android.R.id.content);
        if (root == null) return;
        if (messageView != null && messageView.getParent() instanceof ViewGroup) {
            ((ViewGroup) messageView.getParent()).removeView(messageView);
        }
        final TextView t = new TextView(this);
        t.setText(msg);
        t.setTextSize(13);
        t.setTextColor(color(R.color.text));
        t.setBackgroundResource(R.drawable.bg_message);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setMaxLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setElevation(dp(6));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(96);
        lp.leftMargin = dp(24);
        lp.rightMargin = dp(24);
        root.addView(t, lp);
        t.setAlpha(0f);
        t.animate().alpha(1f).setDuration(120).start();
        messageView = t;
        t.postDelayed(new Runnable() {
            public void run() {
                t.animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
                    public void run() {
                        if (t.getParent() instanceof ViewGroup) {
                            ((ViewGroup) t.getParent()).removeView(t);
                        }
                        if (messageView == t) messageView = null;
                    }
                }).start();
            }
        }, 2600);
    }

    // ------------------------------------------------------------- 共享底部导航

    /** 当前页对应的 tab id（子类覆写；默认主页）。 */
    protected String currentTab() { return "home"; }

    /**
     * 绑定共享导航条（nav_bar.xml 的 include）：
     * 统一设置图标 tint 与选中态颜色，并接管 tab 点击跳转。
     * 子类不再需要自己写 nav(...) 与手写选中色——选中项 = currentTab()。
     */
    protected void setupNav() {
        String active = currentTab();
        String[] tabs = {"home", "config", "logs", "about"};
        for (String tab : tabs) {
            View item = findNavView(tab);
            if (item == null) continue;
            boolean selected = tab.equals(active);
            int color = selected ? color(R.color.primary) : color(R.color.text_secondary);
            // 条目内部：ImageView 一个 + TextView 一个
            if (item instanceof LinearLayout) {
                LinearLayout box = (LinearLayout) item;
                for (int i = 0; i < box.getChildCount(); i++) {
                    View c = box.getChildAt(i);
                    if (c instanceof ImageView) {
                        // 导航图标是单色矢量：用 tint 上色，选中 @color/primary，未选中 @color/text_secondary。
                        ((ImageView) c).setImageTintList(ColorStateList.valueOf(color));
                    } else if (c instanceof TextView) {
                        TextView t = (TextView) c;
                        t.setTextColor(color);
                        t.setTypeface(selected
                                ? android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                                : android.graphics.Typeface.DEFAULT);
                    }
                }
            }
            final String target = tab;
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { Nav.go(BaseActivity.this, target); }
            });
        }
    }

    private View findNavView(String tab) {
        int id;
        if ("config".equals(tab)) id = R.id.nav_config;
        else if ("logs".equals(tab)) id = R.id.nav_logs;
        else if ("about".equals(tab)) id = R.id.nav_about;
        else id = R.id.nav_home;
        return findViewById(id);
    }

    /** 取颜色令牌；调用方传 R.color.*。 */
    protected int color(int resId) {
        return getColor(resId);
    }

    /** 状态色映射（全站唯一一份）：成功绿、错误红、暂停黄、进行中/已启用主色。 */
    protected int statusColor(AppStatus status) {
        if (status == AppStatus.OK) return color(R.color.success);
        if (status != null && status.isError()) return color(R.color.error);
        if (status == AppStatus.PAUSED) return color(R.color.warning);
        if (status == AppStatus.CHECKING || status == AppStatus.ENABLED) return color(R.color.primary);
        return color(R.color.text_hint);
    }

    /** 把布局里的导航条从 ScrollView 内容中提出来，固定在底部。 */
    protected void setContentViewPinnedNav(int layout, int navItemId) {
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        android.view.View content = getLayoutInflater().inflate(layout, root, false);
        root.addView(content, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        // 优先按 nav_bar 提取共享导航条；navItemId 兜底（条目父容器=整条导航）
        android.view.View bar = content.findViewById(R.id.nav_bar);
        if (bar == null) {
            android.view.View item = navItemId == 0 ? null : content.findViewById(navItemId);
            if (item != null && item.getParent() instanceof android.view.View) {
                bar = (android.view.View) item.getParent();
            }
        }
        if (bar != null && bar.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) bar.getParent()).removeView(bar);
            root.addView(bar, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(80),
                    android.view.Gravity.BOTTOM));
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                    content.getPaddingRight(), dp(80));
        }
        setContentView(root);
        setupNav();
    }

}
