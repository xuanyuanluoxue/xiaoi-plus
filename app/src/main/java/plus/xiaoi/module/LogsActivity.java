package plus.xiaoi.module;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import plus.xiaoi.module.control.BridgeControlService;

/**
 * 日志页：从控制面的内存镜像读取，不再依赖 MCP 开关或 Bearer token。
 *
 * <p>数据链路：小爱进程 {@code Logs} → 控制面 {@code C2S_APPEND_LOGS} →
 * App 内存 {@code ControlLogRing} → {@code BridgeControlService.logDump()} → 本页。
 * 控制面还没收到任何上报时明确显示「等待小爱上报日志」，不报错、不引导去开 MCP。</p>
 */
public class LogsActivity extends BaseActivity {

    private LinearLayout container;
    private TextView tvCount, tvUnavailable, btnFilter;
    private View tvEmpty;
    private Switch swFollow;
    private ScrollView scroll;

    private final List<JSONObject> all = new ArrayList<JSONObject>();
    private int filter = 0; // 0 全部 / 1 I / 2 E
    /** 本次读取时控制面完全没有日志（区别于「筛选后为空」）。 */
    private boolean waiting;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean loading;
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
        click(R.id.btn_clear, new Runnable() { public void run() { clear(); } });
        click(R.id.btn_share_logs, new Runnable() { public void run() { share(); } });
        if (tvUnavailable != null) tvUnavailable.setVisibility(View.GONE);
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
                final List<JSONObject> parsed = new ArrayList<JSONObject>();
                final String dump = BridgeControlService.logDump();
                boolean empty = dump == null || dump.trim().length() == 0;
                if (!empty) {
                    String[] lines = dump.split("\n", -1);
                    for (String line : lines) {
                        if (line == null || line.length() == 0) continue;
                        parsed.add(parseLine(line));
                    }
                    if (parsed.isEmpty()) empty = true;
                }
                final boolean fEmpty = empty;
                AppExecutors.ui(new Runnable() {
                    public void run() {
                        loading = false;
                        if (isFinishing()) return;
                        all.clear();
                        all.addAll(parsed);
                        waiting = fEmpty;
                        render();
                    }
                });
            }
        });
    }

    /**
     * 解析 {@code HH:mm:ss.SSS I msg}。控制面内部调试行没有时间前缀时按普通信息展示，
     * 不丢弃内容；时间列留空即可。
     */
    private static JSONObject parseLine(String line) {
        String time = "";
        String level = "I";
        String msg = line;
        try {
            if (line.length() >= 16 && line.charAt(12) == ' ' && line.charAt(14) == ' ') {
                String t = line.substring(0, 12);
                if (t.matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}")) {
                    time = t;
                    level = line.substring(13, 14);
                    msg = line.substring(15);
                }
            }
            JSONObject o = new JSONObject();
            o.put("t", time);
            o.put("level", level);
            o.put("msg", msg);
            return o;
        } catch (Throwable t) {
            JSONObject o = new JSONObject();
            try {
                o.put("t", "");
                o.put("level", "I");
                o.put("msg", line);
            } catch (Throwable ignored) {
            }
            return o;
        }
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

        if (shown == 0) {
            if (all.isEmpty()) {
                if (waiting) {
                    setEmptyText("等待小爱上报日志", "小爱进程连接后这里会自动出现");
                } else {
                    setEmptyText("还没有日志", "小爱还没有上报运行日志");
                }
            } else {
                setEmptyText("筛选无结果", "切换筛选条件查看其他日志");
            }
            if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
        } else if (tvEmpty != null) {
            tvEmpty.setVisibility(View.GONE);
        }
        if (swFollow != null && swFollow.isChecked() && scroll != null) {
            scroll.post(new Runnable() { public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
        }
    }

    /**
     * 空状态的两行文案在共享布局里没有 ID；按固定子 View 顺序更新，
     * 避免为一句文案改布局（WP6 约束）。
     */
    private void setEmptyText(String title, String sub) {
        if (!(tvEmpty instanceof ViewGroup)) return;
        ViewGroup box = (ViewGroup) tvEmpty;
        // 布局顺序固定：0=图标，1=标题，2=副文案。
        if (box.getChildCount() > 1 && box.getChildAt(1) instanceof TextView) {
            ((TextView) box.getChildAt(1)).setText(title);
        }
        if (box.getChildCount() > 2 && box.getChildAt(2) instanceof TextView) {
            ((TextView) box.getChildAt(2)).setText(sub);
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

    private void clear() {
        // ControlLogRing 只是 App 内存镜像；清它不会删小爱落盘的日志，也不假装删了历史。
        BridgeControlService.clearLogs();
        all.clear();
        waiting = true;
        render();
        showMessage("已清空本机日志缓冲（不删除小爱落盘日志）");
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
