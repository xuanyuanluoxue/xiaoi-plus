package plus.xiaoi.module.ui.config;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import plus.xiaoi.module.R;

/**
 * 配置页的纯 UI 工具：对话框容器、管理行、输入框与错误行。
 *
 * 只负责创建 View / Dialog，不读写配置，也不持有 Activity：
 * 需要提示时通过构造传入的 {@link Runnable} 回调宿主的 {@code showMessage}。
 */
public final class ConfigDialogs {

    public interface Toast {
        void show(String msg);
    }

    private final Context ctx;
    private final Toast toast;

    public ConfigDialogs(Context ctx, Toast toast) {
        this.ctx = ctx;
        this.toast = toast;
    }

    public AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(ctx, R.style.Theme_XiaoiPlus_Dialog);
    }

    public LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(8));
        return box;
    }

    public void addSection(LinearLayout box, String title) {
        TextView t = styled(title, 12, R.color.text_secondary, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(2);
        box.addView(t, lp);
    }

    public void addManageRow(LinearLayout box, String title, String desc,
                             final Runnable pick, final Runnable rename, final Runnable delete) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        tapBackground(row);

        LinearLayout textBox = new LinearLayout(ctx);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        textBox.addView(styled(title, 14, R.color.text, true));
        if (desc != null && desc.length() > 0) textBox.addView(styled(desc, 11, R.color.text_hint, false));
        row.addView(textBox);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pick.run(); }
        });

        if (rename != null) row.addView(actionText("重命名", R.color.primary, rename));
        if (delete != null) row.addView(actionText("删除", R.color.error, delete));
        box.addView(row);
    }

    public void addHint(LinearLayout box, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(color(R.color.text_hint));
        t.setPadding(0, dp(6), dp(8), dp(6));
        box.addView(t);
    }

    public void addActionRow(LinearLayout box, String title, final Runnable action) {        TextView t = styled(title, 14, R.color.primary, true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(0, dp(12), 0, dp(12));
        tapBackground(t);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        box.addView(t, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    public TextView actionText(String label, int colorRes, final Runnable action) {
        TextView t = styled(label, 13, colorRes, true);
        t.setPadding(dp(10), dp(8), dp(4), dp(8));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return t;
    }

    public TextView styled(String text, int sizeSp, int colorRes, boolean medium) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color(colorRes));
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    public EditText input(String hint) {
        EditText e = new EditText(ctx);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setHint(hint);
        e.setHintTextColor(color(R.color.text_hint));
        e.setTextColor(color(R.color.text));
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(8);
        e.setLayoutParams(lp);
        return e;
    }

    public TextView errorText() {
        TextView t = styled("", 12, R.color.error, false);
        t.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    public void showError(TextView t, String msg) {
        if (t == null) { toast.show(msg); return; }
        t.setText(msg);
        t.setVisibility(View.VISIBLE);
    }

    public void tapBackground(View v) {
        TypedValue tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                && tv.resourceId != 0) {
            v.setBackgroundResource(tv.resourceId);
        }
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    private int color(int resId) {
        return ctx.getColor(resId);
    }
}
