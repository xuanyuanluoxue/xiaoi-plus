package plus.xiaoi.module;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import java.net.URL;

/** 主页：Hero 状态 + 数字砖 + 快捷动作 + 信息流。 */
public class HomeActivity extends BaseActivity {

    private TextView tvState, tvStateSub, tvVersion, tvThird, tvOfficial, tvError;
    /** 上次桥探测结论；onResume 重绘时先沿用，异步 probe 再刷新。 */
    private volatile Boolean runtimeActive;
    private volatile String runtimeModel;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentViewPinnedNav(R.layout.activity_home, R.id.nav_home);

        tvState = (TextView) find(R.id.tv_state);
        tvStateSub = (TextView) find(R.id.tv_state_sub);
        tvVersion = (TextView) find(R.id.tv_version_hero);
        tvThird = (TextView) find(R.id.tv_third);
        tvOfficial = (TextView) find(R.id.tv_official);
        tvError = (TextView) find(R.id.tv_error);

        click(R.id.btn_test, new Runnable() { public void run() { testModel(); } });
        click(R.id.tv_error, new Runnable() { public void run() { Nav.go(HomeActivity.this, "logs"); } });
        click(R.id.btn_switch_model, new Runnable() { public void run() { Nav.go(HomeActivity.this, "config"); } });
        click(R.id.btn_island, new Runnable() { public void run() { postIsland(); } });

        row(R.id.row_model, new Runnable() { public void run() { Nav.go(HomeActivity.this, "config"); } });
        row(R.id.row_base, null);
        row(R.id.row_kernel, null);
        row(R.id.row_android, null);
        row(R.id.row_root, null);
        row(R.id.row_version, null);

        ConfigSync.start(this);
        refresh();
        loadLastError(currentToken());
    }

    @Override protected void onResume() {
        super.onResume();
        // FileObserver 失效兜底：文件变了就先热应用一次，再刷新界面（§5.2 #6）
        if (ConfigSync.fileChanged(this)) ConfigSync.syncNow(this);
        refresh();
        // 回到前台必须重新探一次运行时：暂停/恢复、MCP 开关、模型切换都可能发生在别的页面
        probe();
    }

    private void refresh() {
        AppConfig c = ConfigRepository.get(this).load();
        AppConfig.Provider p = c.activeProvider();
        AppConfig.ModelItem m = c.activeModel();
        // 状态规则与配置页同源（StatusResolver）；桥拿到运行时结论后再修正一次。
        renderStatus(c, runtimeActive, runtimeModel);
        if (tvVersion != null) tvVersion.setText(versionLabel());
        // 调用量由 probe() 经桥的 /stats 填充；桥不可达时保持「—」
        if (tvThird != null && tvThird.getText().length() == 0) tvThird.setText("—");
        if (tvOfficial != null && tvOfficial.getText().length() == 0) tvOfficial.setText("—");
        setRow(R.id.row_model, R.id.tv_value_model, m != null ? m.id : "—");
        setRow(R.id.row_base, R.id.tv_value_base, p != null ? host(p.baseUrl) : "—");
        setRow(R.id.row_kernel, R.id.tv_value_kernel, System.getProperty("os.version"));
        setRow(R.id.row_android, R.id.tv_value_android, android.os.Build.VERSION.RELEASE);
        setRow(R.id.row_root, R.id.tv_value_root, rootName());
        setRow(R.id.row_version, R.id.tv_value_version, versionLabel());
    }

    /**
     * 用统一状态模型渲染 Hero。
     *
     * @param runtimeActive 桥返回的运行时启用位；null = 桥不可达，按文件配置判断
     * @param runtimeModel  桥返回的运行时模型名，仅用于副行展示
     */
    private void renderStatus(AppConfig c, Boolean runtimeActive, String runtimeModel) {
        if (tvState == null || c == null) return;
        AppConfig.Provider p = c.activeProvider();
        AppConfig.ModelItem m = c.activeModel();

        StatusResolver.Input in = new StatusResolver.Input();
        in.hasProvider = p != null;
        in.hasModel = m != null && m.id != null && m.id.length() > 0;
        in.keySet = c.apiKeyOfActive().length() > 0;
        in.enabled = runtimeActive != null ? runtimeActive : c.enabled;
        in.probe = p == null || m == null ? StatusResolver.Probe.UNKNOWN
                : StatusCache.probe(this, StatusCache.fingerprint(p.baseUrl, p.apiKey, m.id));
        StatusResolver.Result r = StatusResolver.resolve(in);

        tvState.setText(r.status.title());
        tvState.setTextColor(statusColor(r.status));
        if (tvStateSub == null) return;

        String model = runtimeModel != null && runtimeModel.length() > 0
                ? runtimeModel : (m != null ? m.id : "");
        String sub;
        if (r.status.isOk() || r.status == AppStatus.ENABLED || r.status == AppStatus.CHECKING) {
            if (runtimeModel != null && runtimeModel.length() > 0 && m != null
                    && m.id != null && m.id.length() > 0 && !runtimeModel.equals(m.id)) {
                sub = "小爱在用 " + runtimeModel + "（文件 " + m.id + "）";
            } else {
                sub = "模型重定向 · " + (model.length() > 0 ? model : "已启用");
                sub += runtimeActive != null ? " · 已确认" : " · 未确认";
            }
        } else if (r.status.isPaused()) {
            sub = "已暂停 · 小爱使用原生模型";
        } else if (r.status.isNotConfigured()) {
            sub = "未配置模型重定向，小爱使用原生模型";
        } else {
            sub = r.detail;
        }
        tvStateSub.setText(sub);
        tvStateSub.setTextColor(r.status.isError() ? color(R.color.error)
                : (r.status.isPaused() || r.status.isNotConfigured()
                ? color(R.color.text_secondary) : color(R.color.success)));
    }

    private String currentToken() {
        try {
            return getSharedPreferences(Config.PREFS, MODE_PRIVATE).getString(Config.KEY_TOKEN, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private String versionLabel() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) { return "v?"; }
    }

    private String host(String url) {
        try { return new URL(url).getHost(); } catch (Throwable t) { return url; }
    }

    private String rootName() {
        String[] pkgs = {"com.rifsxd.ksunext", "me.weishu.kernelsu", "com.topjohnwu.magisk"};
        String[] names = {"KernelSU Next", "KernelSU", "Magisk"};
        for (int i = 0; i < pkgs.length; i++) {
            try { getPackageManager().getPackageInfo(pkgs[i], 0); return names[i]; }
            catch (Throwable ignored) {}
        }
        return "未检测到";
    }

    private void setRow(int rowId, int valueId, String value) {
        View row = findViewById(rowId);
        if (row == null) return;
        TextView sub = (TextView) row.findViewById(valueId);
        if (sub != null) sub.setText(value);
    }

    private void probe() {
        final String token = Config.forApp(this).token();
        AppExecutors.io(new Runnable() {
            public void run() {
                final int port = BridgeClient.discover(token);
                Boolean active = null;
                String model = null;
                int[] usage = null;
                if (port > 0) {
                    String cfgJson = BridgeClient.runtimeConfig(port, token);
                    if (cfgJson != null) {
                        try {
                            org.json.JSONObject cfg = new org.json.JSONObject(cfgJson);
                            model = cfg.optString("model", "");
                            String base = cfg.optString("baseUrl", "");
                            boolean enabled = cfg.optBoolean("enabled", true);
                            active = enabled && model.length() > 0 && base.length() > 0;
                        } catch (Throwable ignored) {
                        }
                    }
                    usage = parseUsage(BridgeClient.stats(port, token, 1));
                }
                runtimeActive = active;
                runtimeModel = model;
                final Boolean fActive = active;
                final String fModel = model;
                final int[] fUsage = usage;
                AppExecutors.ui(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        renderStatus(ConfigRepository.get(HomeActivity.this).load(), fActive, fModel);
                        if (fUsage != null) {
                            if (tvThird != null) tvThird.setText(String.valueOf(fUsage[0]));
                            if (tvOfficial != null) tvOfficial.setText(String.valueOf(fUsage[1]));
                        }
                    }
                });
            }
        });
    }

    /** 真正校验模型（不是探桥）：ModelValidator 探针 + 更新状态缓存 + 明确结果。 */
    private void testModel() {
        final AppConfig c = ConfigRepository.get(this).load();
        final AppConfig.Provider p = c.activeProvider();
        final AppConfig.ModelItem m = c.activeModel();
        if (p == null || m == null) {
            showMessage("先配置服务商与模型");
            return;
        }
        if (!c.enabled) {
            showMessage("已暂停，打开开关后再校验");
            return;
        }
        final String key = p.apiKey == null ? "" : p.apiKey.trim();
        if (key.length() == 0) {
            showMessage("先填写 API Key");
            return;
        }
        showMessage("正在校验模型…");
        AppExecutors.io(new Runnable() {
            public void run() {
                final ModelValidator.Result r = ModelValidator.check(p.baseUrl, key, m.id);
                AppExecutors.ui(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        StatusResolver.Probe probe = probeOfCode(r);
                        StatusCache.saveProbe(HomeActivity.this,
                                StatusCache.fingerprint(p.baseUrl, key, m.id), probe);
                        renderStatus(c, true, m.id);
                        showMessage(r.ok ? "模型可用 · " + r.latencyMs + "ms"
                                : "校验失败：" + r.message);
                    }
                });
            }
        });
    }

    private static StatusResolver.Probe probeOfCode(ModelValidator.Result r) {
        if (r == null) return StatusResolver.Probe.UNKNOWN;
        if (r.ok) return StatusResolver.Probe.OK;
        if ("auth".equals(r.code)) return StatusResolver.Probe.AUTH;
        if ("model".equals(r.code)) return StatusResolver.Probe.MODEL;
        return StatusResolver.Probe.ENDPOINT;
    }

    /** /stats 响应 → {三方, 官方}；解析失败返回 null。 */
    private static int[] parseUsage(String json) {
        if (json == null) return null;
        try {
            org.json.JSONObject today = new org.json.JSONObject(json).optJSONObject("today");
            if (today == null) return null;
            return new int[]{today.optInt("third", 0), today.optInt("official", 0)};
        } catch (Throwable t) {
            return null;
        }
    }

    private void loadLastError(final String token) {
        AppExecutors.io(new Runnable() {
            public void run() {
                int port = BridgeClient.discover(token);
                String body = port > 0 ? BridgeClient.logs(port, token, 100) : null;
                String text = null;
                if (body != null) {
                    try {
                        org.json.JSONArray arr = new org.json.JSONObject(body).optJSONArray("lines");
                        String msg = null, time = null;
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                org.json.JSONObject l = arr.optJSONObject(i);
                                if (l != null && "E".equals(l.optString("level"))) {
                                    msg = l.optString("msg");
                                    time = l.optString("t");
                                }
                            }
                        }
                        if (msg != null) text = "最后错误 · " + time + " · " + msg;
                    } catch (Throwable ignored) {
                    }
                }
                final String fText = text;
                AppExecutors.ui(new Runnable() {
                    public void run() {
                        if (tvError != null) {
                            tvError.setText(fText == null ? "" : fText);
                            tvError.setVisibility(fText == null ? View.GONE : View.VISIBLE);
                        }
                    }
                });
            }
        });
    }

    private void postIsland() {
        final String token = Config.forApp(this).token();
        AppExecutors.io(new Runnable() {
            public void run() {
                int port = BridgeClient.discover(token);
                final boolean ok = port > 0 && BridgeClient.island(port);
                AppExecutors.ui(new Runnable() {
                    public void run() { toast(ok ? "已触发超级岛" : "触发失败：小爱未运行"); }
                });
            }
        });
    }

    private View find(int id) {
        return findViewById(id);
    }

    private void click(int id, final Runnable r) {
        View v = find(id);
        if (v != null) v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { r.run(); }
        });
    }

    private void row(int id, final Runnable r) {
        View v = find(id);
        if (v == null || r == null) return;
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { r.run(); }
        });
    }

    @Override protected String currentTab() { return "home"; }

    private void toast(String s) { showMessage(s); }
}
