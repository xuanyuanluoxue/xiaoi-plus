package plus.xiaoi.module;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import java.net.URL;

import plus.xiaoi.module.agent.AgentService;
import plus.xiaoi.module.control.BridgeControlService;
import plus.xiaoi.module.core.BridgeStatus;
import plus.xiaoi.module.core.ErrorCatalog;
import plus.xiaoi.module.core.StateModel;

/** 主页：Hero 状态 + 数字砖 + 快捷动作 + 信息流。 */
public class HomeActivity extends BaseActivity {

    private TextView tvState, tvStateSub, tvVersion, tvThird, tvOfficial, tvError;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 调用量探针节流：控制面晚于 onResume 就绪时，靠 ticker 补一次。 */
    private long lastUsageProbeAt;

    /** 每 5s 重绘一次：控制面超过 30s 没上报时要自动退回「未确认」，不能停在已确认。 */
    private final Runnable statusTick = new Runnable() {
        public void run() {
            refresh();
            loadLastError();
            // 控制面可能晚于 onResume 才连上；此时补一次调用量探针，避免「今日三方」一直为 —。
            if (BridgeStatus.get().connected
                    && System.currentTimeMillis() - lastUsageProbeAt > 15_000L) {
                probe();
            }
            ui.postDelayed(this, 5000L);
        }
    };

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
        // 只读信息行不再显示右箭头：箭头意味着可点击/可跳转，但这几行没有目标页。
        hideChevron(R.id.row_base);
        hideChevron(R.id.row_kernel);
        hideChevron(R.id.row_android);
        hideChevron(R.id.row_root);
        hideChevron(R.id.row_version);

        ConfigSync.start(this);
        // WP1.2a：打开 App 时按 mcpEnabled + token 恢复 8770（不阻塞主线程）。
        AgentService.startIfEnabled(this);
        refresh();
        loadLastError();
    }

    @Override protected void onResume() {
        super.onResume();
        // FileObserver 失效兜底：文件变了就先热应用一次，再刷新界面（§5.2 #6）
        if (ConfigSync.fileChanged(this)) ConfigSync.syncNow(this);
        refresh();
        // 回到前台必须重新探一次调用量；运行态(D4)只从控制面快照读，不再 probe HTTP。
        probe();
        loadLastError();
        ui.removeCallbacks(statusTick);
        ui.postDelayed(statusTick, 5000L);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(statusTick);
        super.onPause();
    }

    private void refresh() {
        AppConfig c = ConfigRepository.get(this).load();
        AppConfig.Provider p = c.activeProvider();
        AppConfig.ModelItem m = c.activeModel();
        renderStatus(c);
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
     * 用四维状态模型渲染 Hero。
     *
     * <p>D1 只表示文件是否完整；D2 只来自校验缓存；D3/D4 只来自控制面快照。
     * 端点校验成功最多显示「端点可用」，只有控制面上报 IN_USE 才显示「已确认」。</p>
     */
    private void renderStatus(AppConfig c) {
        if (tvState == null || c == null) return;
        AppConfig.Provider p = c.activeProvider();
        AppConfig.ModelItem m = c.activeModel();

        StatusResolver.Probe cached = p == null || m == null
                ? StatusResolver.Probe.UNKNOWN
                : StatusCache.probe(this, StatusCache.fingerprint(p.baseUrl, p.apiKey, m.id));
        BridgeStatus.Snapshot s = BridgeStatus.get();
        long now = System.currentTimeMillis();

        StateModel.Input in = new StateModel.Input();
        in.hasProvider = p != null;
        in.hasModel = m != null && m.id != null && m.id.length() > 0;
        in.hasBase = p != null && p.baseUrl != null && p.baseUrl.trim().length() > 0;
        in.hasKey = c.apiKeyOfActive().length() > 0;
        in.enabled = c.enabled;
        in.configInvalid = ConfigRepository.get(this).isReadOnly();
        in.probe = probeOf(cached);
        in.bridgeConnected = s.connected;
        in.fileRev = c.rev;
        in.appliedRev = s.appliedRev;
        in.lastReportAt = s.updatedAt;
        in.now = now;
        in.runtimeKnown = s.runtimeKnown;
        in.runtimeEnabled = s.enabled;
        in.runtimeHasKey = s.hasKey;
        in.runtimeModel = s.model;
        in.fileModel = m != null ? m.id : "";

        StateModel.Result r = StateModel.resolve(in);
        tvState.setText(r.heroTitle());
        tvState.setTextColor(statusColor(r.status()));
        if (tvStateSub == null) return;
        tvStateSub.setText(r.heroSub());
        if (r.status().isError()) {
            tvStateSub.setTextColor(color(R.color.error));
        } else if (r.verified() || r.d2 == StateModel.EndpointState.OK) {
            tvStateSub.setTextColor(color(R.color.success));
        } else if (r.status().isPaused() || r.status().isNotConfigured()
                || r.d1 == StateModel.ConfigState.INVALID) {
            tvStateSub.setTextColor(color(R.color.text_secondary));
        } else {
            tvStateSub.setTextColor(color(R.color.primary));
        }
    }

    private static StateModel.Probe probeOf(StatusResolver.Probe probe) {
        if (probe == null) return StateModel.Probe.UNKNOWN;
        switch (probe) {
            case OK: return StateModel.Probe.OK;
            case AUTH: return StateModel.Probe.AUTH;
            case MODEL: return StateModel.Probe.MODEL;
            case ENDPOINT: return StateModel.Probe.ENDPOINT;
            case UNKNOWN:
            default: return StateModel.Probe.UNKNOWN;
        }
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

    /**
     * 只更新调用量：运行态(D4)来自控制面 {@link BridgeStatus}，不再请求 /config 或 MCP。
     * 桥端口来自控制面；没有 token/端口时保持「—」。
     */
    private void probe() {
        lastUsageProbeAt = System.currentTimeMillis();
        // 事实源是私有 config.json；module prefs 在外部编辑/迁移后可能滞后。
        final String token = ConfigRepository.get(this).load().mcpToken;
        AppExecutors.io(new Runnable() {
            public void run() {
                final int port = BridgeClient.discover(token);
                int[] usage = port > 0 ? parseUsage(BridgeClient.stats(port, token, 1)) : null;
                final int[] fUsage = usage;
                AppExecutors.ui(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        renderStatus(ConfigRepository.get(HomeActivity.this).load());
                        if (fUsage != null) {
                            if (tvThird != null) tvThird.setText(String.valueOf(fUsage[0]));
                            if (tvOfficial != null) tvOfficial.setText(String.valueOf(fUsage[1]));
                        }
                    }
                });
            }
        });
    }

    /** 真正校验模型（不是探桥）：ModelValidator 只更新 D2/StatusCache，绝不伪造 D4。 */
    private void testModel() {
        final AppConfig c = ConfigRepository.get(this).load();
        final AppConfig.Provider p = c.activeProvider();
        final AppConfig.ModelItem m = c.activeModel();
        if (p == null || m == null) {
            showMessage("先配置服务商与模型");
            return;
        }
        final String key = p.apiKey == null ? "" : p.apiKey.trim();
        if (key.length() == 0) {
            showMessage("先填写 API Key");
            return;
        }
        showMessage("正在校验端点…");
        AppExecutors.io(new Runnable() {
            public void run() {
                final ModelValidator.Result r = ModelValidator.check(p.baseUrl, key, m.id);
                AppExecutors.ui(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        StatusResolver.Probe probe = probeOfCode(r);
                        StatusCache.saveProbe(HomeActivity.this,
                                StatusCache.fingerprint(p.baseUrl, key, m.id), probe);
                        // 只重绘 D2；运行态仍由控制面快照决定，校验成功不能改 D4。
                        renderStatus(ConfigRepository.get(HomeActivity.this).load());
                        showMessage(r.ok ? "端点可用 · " + r.latencyMs + "ms"
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

    /** 最后一条错误来自控制面内存日志；MCP 关闭/无 token 也能读。 */
    private void loadLastError() {
        AppExecutors.io(new Runnable() {
            public void run() {
                final String text = BridgeControlService.lastError();
                final String fText = text == null || text.length() == 0 ? null : text;
                AppExecutors.ui(new Runnable() {
                    public void run() {
                        if (tvError == null) return;
                        tvError.setText(fText == null ? "" : fText);
                        tvError.setVisibility(fText == null ? View.GONE : View.VISIBLE);
                    }
                });
            }
        });
    }

    private void postIsland() {
        final AppConfig c = ConfigRepository.get(this).load();
        final String token = c.mcpToken == null ? "" : c.mcpToken.trim();
        BridgeStatus.Snapshot s = BridgeStatus.get();
        final int port = BridgeClient.discover(token);
        ErrorCatalog.Code blocked = ErrorCatalog.islandBlocked(
                token.length() > 0, c.mcpEnabled, s.connected,
                s.fresh(System.currentTimeMillis()), port);
        if (blocked != null) {
            toast(ErrorCatalog.message(blocked));
            return;
        }
        AppExecutors.io(new Runnable() {
            public void run() {
                final boolean ok = port > 0 && BridgeClient.island(port);
                AppExecutors.ui(new Runnable() {
                    public void run() {
                        toast(ok ? "已触发超级岛"
                                : ErrorCatalog.message(ErrorCatalog.Code.PORT_UNREACHABLE));
                    }
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

    /** 隐藏行尾箭头（最后一个 ImageView）：只读行不再暗示可点击。 */
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

    @Override protected String currentTab() { return "home"; }

    private void toast(String s) { showMessage(s); }
}
