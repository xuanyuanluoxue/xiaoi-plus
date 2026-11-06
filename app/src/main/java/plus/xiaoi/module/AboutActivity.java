package plus.xiaoi.module;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/** 关于：版本 / 作者 / GitHub / 许可证 / 致谢 / 免责。 */
public class AboutActivity extends BaseActivity {

    private static final String REPO = "https://github.com/xuanyuanluoxue/xiaoi-plus";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentViewPinnedNav(R.layout.activity_about, R.id.nav_home);

        set(R.id.tv_name, "小爱同学plus");
        set(R.id.tv_version, version());
        set(R.id.tv_value_author, "轩辕落雪");
        set(R.id.tv_value_github, "xuanyuanluoxue/xiaoi-plus");
        set(R.id.tv_value_license, "GPL-3.0");
        set(R.id.tv_value_thanks, "OpenClaw · KernelSU Next");
        set(R.id.tv_value_update, "查看 Releases");

        set(R.id.tv_build, "构建于 2026-11-03 · GPL-3.0");

        // 许可证/致谢只是展示信息，没有可跳转目标：隐藏行尾箭头，避免死交互暗示。
        hideChevron(R.id.row_license);
        hideChevron(R.id.row_thanks);

        click(R.id.row_github, new Runnable() { public void run() { open(REPO); } });
        click(R.id.row_update, new Runnable() { public void run() { open(REPO + "/releases"); } });
        click(R.id.row_author, new Runnable() {
            public void run() {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("author", "xuanyuanluoxue"));
                showMessage("已复制用户名");
            }
        });
    }

    @Override protected String currentTab() { return "about"; }

    private String version() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) { return "v?"; }
    }

    private void set(int id, String text) {
        TextView v = (TextView) find(id);
        if (v != null) v.setText(text);
    }

    private void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Throwable t) {
            showMessage("没有可用浏览器");
        }
    }

    private View find(int id) { return findViewById(id); }
    private void click(int id, final Runnable r) {
        View v = find(id);
        if (v != null) v.setOnClickListener(new View.OnClickListener() { public void onClick(View x) { r.run(); } });
    }

    /** 隐藏行尾箭头（最后一个 ImageView）：没有点击目标的展示行不再暗示可点击。 */
    private void hideChevron(int rowId) {
        View v = find(rowId);
        if (!(v instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View child = g.getChildAt(i);
            if (child instanceof android.widget.ImageView) {
                child.setVisibility(View.GONE);
                return;
            }
        }
    }
}
