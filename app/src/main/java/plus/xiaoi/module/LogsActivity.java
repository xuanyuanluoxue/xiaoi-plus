package plus.xiaoi.module;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 日志页：从桥的 /logs 拉取，动态渲染。 */
public class LogsActivity extends BaseActivity {

    private LinearLayout container;
    private TextView tvCount, tvUnavailable, btnFilter;
    private View tvEmpty;
    private Switch swFollow;
    private ScrollView scroll;

    private final List<JSONObject> all = new ArrayList<JSONObject>();
    private int filter = 0; // 0 全部 / 1 I / 2 E

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean loading;
    private boolean errorIsConfig;
    private final Runnable poll = new Runnable() {
        public void run() {
            if (isFinishing() || swFollow == null || !swFollow.isChecked()) return;
            load();
            ui.postDelayed(this, 2000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_logs);

        container = (LinearLayout) find(R.id.ll_logs);
        tvCount = (TextView) find(R.id.tv_count);
        tvEmpty = find(R.id.tv_empty);
        tvUnavailable = (TextView) find(R.id.tv_unavailable);
        btnFilter = (TextView) find(R.id.btn_filter);
        swFollow = (Switch) find(R.id.sw_follow);
        scroll = (ScrollView) find(R.id.scroll_logs);

        click(R.id.btn_filter, new Runnable() { public void run() { cycleFilter(); } });
        click(R.id.btn_clear, new Runnable() { public void run() { all.clear(); render(); } });
        click(R.id.btn_share_logs, new Runnable() { public void run() { share(); } });
        tvUnavailable.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (errorIsConfig) Nav.go(LogsActivity.this, "config");
                else load();
            }
        });
        swFollow.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                if (on) { load(); startPolling(); }
                else ui.removeCallbacks(poll);
            }
        });

        setupNav();

        load();
    }

    @Override protected void onResume() {
        super.onResume();
        load();
        if (swFollow != null && swFollow.isChecked()) startPolling();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(poll);
        super.onPause();
    }

    private void startPolling() {
        ui.removeCallbacks(poll);
        ui.postDelayed(poll, 2000);
    }

    private void cycleFilter() {
        filter = (filter + 1) % 3;
        btnFilter.setText(filter == 0 ? "全部" : filter == 1 ? "信息" : "错误");
        render();
    }

    private void load() {
        if (loading) return;
        loading = true;
        AppExecutors.io(new Runnable() {
            public void run() {
                final Fetch r = fetch();
                AppExecutors.ui(new Runnable() {
                    public void run() {
                        loading = false;
                        if (isFinishing()) return;
                        if (r.error != null) {
                            errorIsConfig = r.config;
                            all.clear();
                            if (tvUnavailable != null) {
                                tvUnavailable.setText(r.error);
                                tvUnavailable.setVisibility(View.VISIBLE);
                            }
                            render();
                            return;
                        }
                        if (tvUnavailable != null) tvUnavailable.setVisibility(View.GONE);
                        try {
                            JSONArray arr = new JSONObject(r.body).optJSONArray("lines");
                            all.clear();
                            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                                JSONObject l = arr.optJSONObject(i);
                                if (l != null) all.add(l);
                            }
                        } catch (Throwable ignored) {
                        }
                        render();
                    }
                });
            }
        });
    }

    /** 拉取结果：区分配置问题 / 小爱未运行 / 读取失败，避免一律误诊。 */
    private static final class Fetch {
        final String body;
        final String error;
        final boolean config;

        Fetch(String body, String error, boolean config) {
            this.body = body;
            this.error = error;
            this.config = config;
        }
    }

    private Fetch fetch() {
        Config c = Config.forApp(this);
        if (!c.bridge()) {
            return new Fetch(null, "MCP 接口未开启 · 点按去配置", true);
        }
        String token = c.token();
        if (token == null || token.length() == 0) {
            return new Fetch(null, "未配置 MCP 令牌 · 点按去配置", true);
        }
        int port = BridgeClient.discover(token);
        if (port <= 0) {
            return new Fetch(null, "小爱未运行或模块未加载 · 点按重试", false);
        }
        String body = BridgeClient.logs(port, token, 300);
        if (body == null) {
            return new Fetch(null, "读取日志失败 · 点按重试", false);
        }
        return new Fetch(body, null, false);
    }

    private void render() {
        if (container == null) return;
        container.removeAllViews();
        int shown = 0;
        for (JSONObject o : all) {
            String lv = o.optString("level", "I");
            if (filter == 1 && !"I".equals(lv)) continue;
            if (filter == 2 && !"E".equals(lv)) continue;
            container.addView(row(o.optString("t", ""), lv, o.optString("msg", "")));
            shown++;
        }
        if (tvCount != null) tvCount.setText(shown + " 条");
        if (tvEmpty != null) tvEmpty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
        if (swFollow != null && swFollow.isChecked() && scroll != null) {
            scroll.post(new Runnable() { public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
        }
    }

    private View row(String time, String level, String msg) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(16, 8, 16, 8);
        TextView t = new TextView(this);
        t.setText(time);
        t.setTextSize(11);
        t.setTextColor(color(R.color.text_hint));
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setWidth(dp(84));
        TextView l = new TextView(this);
        l.setText(level);
        l.setTextSize(11);
        l.setTextColor("E".equals(level) ? color(R.color.error) : color(R.color.success));
        l.setPadding(dp(6), 0, dp(8), 0);
        TextView m = new TextView(this);
        m.setText(msg);
        m.setTextSize(12);
        m.setTextColor(color(R.color.text));
        m.setTypeface(android.graphics.Typeface.MONOSPACE);
        r.addView(t); r.addView(l); r.addView(m);
        if ("E".equals(level)) r.setBackgroundColor(color(R.color.error_bg));
        return r;
    }

    private void share() {
        StringBuilder sb = new StringBuilder();
        for (JSONObject o : all) {
            sb.append(o.optString("t")).append(' ').append(o.optString("level"))
              .append(' ').append(o.optString("msg")).append('\n');
        }
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(android.content.Intent.EXTRA_TEXT, sb.toString());
        startActivity(android.content.Intent.createChooser(i, "分享日志"));
    }

    private View find(int id) { return findViewById(id); }
    private void click(int id, final Runnable r) {
        View v = find(id);
        if (v != null) v.setOnClickListener(new View.OnClickListener() { public void onClick(View x) { r.run(); } });
    }
    @Override protected String currentTab() { return "logs"; }
}
