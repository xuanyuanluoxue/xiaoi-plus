package plus.xiaoi.module;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 配置页（P3 重排）：状态条 + 模型 chips + 管理列表 + 真实推送结果 + 冲突条。
 *
 * 状态条的单一事实来源（§4.2 的 P3 口径）：
 *   未配置 / 已暂停 / 待校验 / 校验中 / 可用 / 密钥无效 / 模型不存在 / 端点不可达
 * 浏览器外只有这里能说「配置是否有效」；模型卡里的「模型状态」只显示上一次校验的原始结果。
 *
 * 「是否已推到小爱」由 {@link BridgePusher.Result} 决定（保存/重试都走
 * {@link ConfigRepository#pushSync}），不再无条件 toast「已保存并推送」。
 *
 * 约定：
 * - render() 期间的 setText/setChecked 不触发监听器（rendering 守卫）
 * - enabled=false 时模型卡用「子 View 不可点 + 父容器吞掉触摸」屏蔽，
 *   只有「暂停且密钥为空」时保留密钥输入（首次配置必须能填 key）
 */
public class ConfigActivity extends BaseActivity {

    private static final int REQ_EXPORT = 1001;
    private static final int REQ_IMPORT = 1002;

    // ------------------------------------------------------------- 状态机


    private AppConfig cfg;
    private boolean dirty;

    /** 外部文件变更监听：提升为字段，onDestroy 时注销，避免 Activity 泄漏。 */
    private final Runnable fileListener = new Runnable() {
        public void run() { onExternalChange(); }
    };

    /** 程序化更新守卫：render() 期间抑制监听器副作用（脏标记误报）。 */
    private boolean rendering;

    /** 校验结果（状态条与「模型状态」行共用）。 */
    private AppStatus statusState = AppStatus.ENABLED;
    private String statusDetail = "";

    /** 本次会话里最后一次推送结果（null = 还没推过）。 */
    private BridgePusher.Result lastPush;
    private String lastPushError;

    /** 暂停（enabled=false）时是否允许继续编辑密钥：仅首次配置（key 为空）时放行。 */
    private boolean keyEditableWhenPaused;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable autoCheckTask = new Runnable() {
        public void run() { checkModelNow(false); }
    };

    private Switch swEnabled, swMcp, swPrefix, swMcpLan;
    private View cardModel, rowToken, rowMcpLan, rowPrefixTpl, tvPrefixHelp, boxMcpJson, tvDirty;
    private TextView fab;
    private View rowModelCheck, rowManage, rowPush, bannerConflict, bannerRuntime;
    private LinearLayout boxProviderChips, boxModelChips;
    private TextView tvStatusDot, tvStatusTitle, tvStatusDesc, tvPushState, tvPushAction;
    private TextView tvRuntimeTitle, tvRuntimeDesc;
    private TextView tvMcpUrl, tvMcpJson, btnEye, tvModelCheck;
    private EditText etApiKey, etToken, etPrefixTpl;
    private final TextView[] themeChips = new TextView[3];
    /** 运行时生成的 chips（服务商/模型），用于统一处理禁用态。 */
    private final List<View> chipViews = new ArrayList<View>();

    private AlertDialog manageDlg;
    /** 实际桥端口（默认 8771，探测到后更新）；用于复制连接信息与 Skill。 */
    private volatile int bridgePort = BridgeClient.PORT_START;
    private long lastPortProbeAt;
    /** 重建（切主题/旋转）时从 Bundle 恢复的未保存草稿；恢复后必须保持脏标记。 */
    private boolean restoreDraft;
    private static final String STATE_DRAFT = "draft_json";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_config);

        ConfigSync.start(this);
        cfg = ConfigRepository.get(this).load();
        // 切主题/旋转会重建页面：未保存的编辑随 Bundle 带过来，不能静默丢回文件版本。
        if (b != null && b.getString(STATE_DRAFT) != null) {
            try {
                cfg = ConfigCodec.fromJson(b.getString(STATE_DRAFT));
                restoreDraft = true;
            } catch (Throwable ignored) {
            }
        }
        ConfigSync.addListener(fileListener);

        swEnabled = (Switch) find(R.id.sw_enabled);
        swMcp = (Switch) find(R.id.sw_mcp);
        swMcpLan = (Switch) find(R.id.sw_mcp_lan);
        rowMcpLan = find(R.id.row_mcp_lan);
        swPrefix = (Switch) find(R.id.sw_prefix);
        cardModel = find(R.id.card_model_fields);
        rowToken = find(R.id.row_token);
        rowPrefixTpl = find(R.id.row_prefix_tpl);
        tvPrefixHelp = find(R.id.tv_prefix_help);
        tvMcpUrl = (TextView) find(R.id.tv_mcp_url);
        tvMcpJson = (TextView) find(R.id.tv_mcp_json);
        boxMcpJson = find(R.id.box_mcp_json);
        tvModelCheck = (TextView) find(R.id.tv_model_check);
        etApiKey = (EditText) find(R.id.et_api_key);
        etToken = (EditText) find(R.id.et_token);
        etPrefixTpl = (EditText) find(R.id.et_prefix_tpl);
        btnEye = (TextView) find(R.id.btn_key_eye);
        fab = (TextView) find(R.id.fab_save);
        tvDirty = find(R.id.tv_dirty);
        bannerConflict = find(R.id.banner_conflict);
        bannerRuntime = find(R.id.banner_runtime);
        tvRuntimeTitle = (TextView) find(R.id.tv_runtime_title);
        tvRuntimeDesc = (TextView) find(R.id.tv_runtime_desc);
        boxProviderChips = (LinearLayout) find(R.id.box_provider_chips);
        boxModelChips = (LinearLayout) find(R.id.box_model_chips);
        rowModelCheck = find(R.id.row_model_check);
        rowManage = find(R.id.row_manage);
        rowPush = find(R.id.row_push);
        tvStatusDot = (TextView) find(R.id.tv_status_dot);
        tvStatusTitle = (TextView) find(R.id.tv_status_title);
        tvStatusDesc = (TextView) find(R.id.tv_status_desc);
        tvPushState = (TextView) find(R.id.tv_push_state);
        tvPushAction = (TextView) find(R.id.tv_push_action);
        themeChips[0] = (TextView) find(R.id.chip_theme_0);
        themeChips[1] = (TextView) find(R.id.chip_theme_1);
        themeChips[2] = (TextView) find(R.id.chip_theme_2);

        bindListeners();
        render();
        if (restoreDraft) markDirty();
        bindThemeChips();
        setupNav();
    }

    @Override protected void onResume() {
        super.onResume();
        // FileObserver 在部分 ROM 的 /sdcard（FUSE）上会静默失效：
        // 回到前台时补一次哈希比对 + 热应用（§5.2 #6）。
        if (ConfigSync.fileChanged(this)) {
            ConfigSync.syncNow(this);
            // 应用成功时监听器会异步走 onExternalChange；这里只兜底「应用失败/未回调」，
            // 避免同一次文件变更把页面重载两次。
            if (ConfigSync.fileChanged(this)) onExternalChange();
        }
        // 回到前台时顺带核对「小爱运行时是否与文件一致」（MCP 未开启时静默跳过）。
        ui.postDelayed(new Runnable() { public void run() { checkRuntimeDrift(); } }, 600);
        refreshBridgePort();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        ConfigSync.removeListener(fileListener);
        if (manageDlg != null) {
            try { manageDlg.dismiss(); } catch (Throwable ignored) {}
            manageDlg = null;
        }
        super.onDestroy();
    }

    /**
     * recreate()（切主题）会恢复输入框/开关的旧视图状态，与从文件加载的 cfg 不一致：
     * 恢复后再按 cfg 渲染一次，避免把控件回填误判成「未保存」并清掉脏标记。
     */
    @Override protected void onRestoreInstanceState(Bundle b) {
        super.onRestoreInstanceState(b);
        render();
        if (restoreDraft) markDirty();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (dirty) {
            try {
                out.putString(STATE_DRAFT, ConfigCodec.toJson(cfg));
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------- 绑定

    private void bindListeners() {
        swEnabled.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean on) {
                if (rendering) return;
                cfg.enabled = on;
                keyEditableWhenPaused = !on && cfg.apiKeyOfActive().length() == 0;
                invalidateCheck();
                applyEnabledState();
                applyMcpState(); // JSON 速览里的 enabled 字段要跟着变
                markDirty();
                // 打开开关后自动跑一次校验，让状态条不用等用户点按；关闭时不发请求。
                if (on) scheduleAutoCheck();
            }
        });
        swMcp.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean on) {
                if (rendering) return;
                cfg.mcpEnabled = on;
                applyMcpState();
                markDirty();
            }
        });
        swMcpLan.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean on) {
                if (rendering) return;
                cfg.mcpLan = on;
                // 局域网必须带 token；打开开关时自动补一个随机令牌，避免保存被校验拦下。
                if (on && cfg.mcpToken.trim().length() == 0) {
                    cfg.mcpToken = newToken();
                    rendering = true;
                    etToken.setText(cfg.mcpToken);
                    rendering = false;
                    toast("已自动生成令牌（局域网访问必须）");
                }
                applyMcpState();
                markDirty();
            }
        });
        swPrefix.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean on) {
                if (rendering) return;
                // 开关 = 模板的存在性：开且模板为空时给默认模板
                if (on && cfg.markerTemplate.trim().length() == 0) {
                    cfg.markerTemplate = "[模型: {model}]";
                    rendering = true;
                    etPrefixTpl.setText(cfg.markerTemplate);
                    rendering = false;
                }
                applyPrefixState();
                markDirty();
            }
        });

        click(R.id.btn_key_eye, new Runnable() { public void run() { toggleKey(); } });
        click(R.id.btn_gen_token, new Runnable() { public void run() { etToken.setText(newToken()); markDirty(); } });
        click(R.id.btn_copy_mcp, new Runnable() { public void run() { copyMcp(); } });
        click(R.id.btn_copy_skill, new Runnable() { public void run() { copySkill(); } });
        click(R.id.btn_export, new Runnable() { public void run() { startMaskedExport(); } });
        click(R.id.btn_backup, new Runnable() { public void run() { confirmFullExport(); } });
        click(R.id.btn_restore, new Runnable() { public void run() { startImport(); } });
        click(R.id.row_model_check, new Runnable() { public void run() { checkModelNow(true); } });
        click(R.id.row_manage, new Runnable() { public void run() { openManage(); } });
        click(R.id.row_import, new Runnable() { public void run() { importFromRuntime(); } });
        click(R.id.btn_share, new Runnable() { public void run() { share(); } });
        click(R.id.fab_save, new Runnable() { public void run() { save(); } });

        click(R.id.card_status, new Runnable() { public void run() { checkModelNow(true); } });
        click(R.id.row_push, new Runnable() { public void run() { retryPush(); } });
        click(R.id.banner_conflict, new Runnable() {
            public void run() {
                hideConflict();
                reloadFromFile(true);
            }
        });
        click(R.id.banner_runtime, new Runnable() { public void run() { retryPush(); } });

        TextWatcher collectWatcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                if (rendering) return;
                collect();
                applyMcpState(); // 令牌是否设置要实时反映到 JSON 速览
                markDirty();
            }
        };
        etToken.addTextChangedListener(collectWatcher);
        etApiKey.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                if (rendering) return;
                collect();
                markDirty();
                invalidateCheck();
                scheduleAutoCheck();
            }
        });
        etPrefixTpl.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                if (rendering) return;
                cfg.markerTemplate = s.toString().trim();
                markDirty();
            }
        });

        // 暂停时模型卡整体不可点：非交互子 View 不消费触摸，父容器这里兜底吞掉。
        if (cardModel != null) {
            cardModel.setOnTouchListener(new View.OnTouchListener() {
                public boolean onTouch(View v, MotionEvent e) {
                    return !cfg.enabled;
                }
            });
        }
    }

    private void bindThemeChips() {
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            if (themeChips[i] == null) continue;
            themeChips[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    ThemeManager.set(ConfigActivity.this, mode);
                    recreate();
                }
            });
        }
        renderThemeChips();
    }

    private void renderThemeChips() {
        int cur = ThemeManager.get(this);
        for (int i = 0; i < 3; i++) {
            TextView chip = themeChips[i];
            if (chip == null) continue;
            boolean on = i == cur;
            chip.setBackgroundResource(on ? R.drawable.bg_chip_ok : R.drawable.bg_chip);
            chip.setTextColor(color(on ? R.color.primary : R.color.text_secondary));
        }
    }

    private void setModelCheckText(String text, int colorRes) {
        if (tvModelCheck == null) return;
        tvModelCheck.setText(text);
        tvModelCheck.setTextColor(color(colorRes));
    }

    // ------------------------------------------------------------- 渲染

    private AppConfig.Provider provider() { return cfg.activeProvider(); }

    private void render() {
        rendering = true;
        try {
            swEnabled.setChecked(cfg.enabled);
            swMcp.setChecked(cfg.mcpEnabled);
            swMcpLan.setChecked(cfg.mcpLan);
            swPrefix.setChecked(cfg.markerTemplate.trim().length() > 0);
            AppConfig.Provider p = provider();
            AppConfig.ModelItem m = cfg.activeModel();
            etApiKey.setText(p != null && p.apiKey != null ? p.apiKey : "");
            etToken.setText(cfg.mcpToken);
            etPrefixTpl.setText(cfg.markerTemplate);
            setModelCheckText(m != null ? "未校验 · 点按校验" : "未选择模型", R.color.text_hint);
        } finally {
            rendering = false;
        }
        keyEditableWhenPaused = !cfg.enabled && cfg.apiKeyOfActive().length() == 0;
        // 删掉一个「非当前」模型等情况会重绘页面，但可用性结论并没有失效：
        // 只有参与校验的 baseUrl/key/model 变了，才退回「待校验」。
        if (!currentCheckKey().equals(lastCheckKey)) {
            statusState = AppStatus.ENABLED;
            statusDetail = "";
        }
        renderChips();
        applyEnabledState();
        applyMcpState();
        applyPrefixState();
        syncStatusFromConfig();
        markClean();
        autoCheckModel();
    }

    /** 页面渲染后自动校验（有节流），让状态条反映真实可用性；暂停/无密钥时跳过。 */
    private void autoCheckModel() {
        checkModelNow(false);
    }

    private void applyPrefixState() {
        boolean on = swPrefix != null && swPrefix.isChecked();
        if (rowPrefixTpl != null) rowPrefixTpl.setVisibility(on ? View.VISIBLE : View.GONE);
        if (tvPrefixHelp != null) tvPrefixHelp.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /**
     * enabled=false 时的屏蔽：不再只 setEnabled(false)（那拦不住子 View），
     * 而是把交互子 View 的 clickable/focusable 全关掉，父容器再吞掉剩余触摸。
     * 例外：首次配置（暂停 + 无密钥）保留密钥输入，否则用户无法完成配置。
     */
    private void applyEnabledState() {
        if (cardModel == null) return;
        boolean on = cfg.enabled;
        cardModel.setAlpha(on ? 1f : 0.55f);
        for (View chip : chipViews) {
            chip.setClickable(on);
            chip.setFocusable(on);
            chip.setAlpha(on ? 1f : 0.6f);
        }
        setRowEnabled(rowModelCheck, on);
        setRowEnabled(rowManage, on);
        setRowEnabled(btnEye, on);
        boolean allowKey = on || keyEditableWhenPaused;
        if (etApiKey != null) {
            etApiKey.setEnabled(allowKey);
            etApiKey.setFocusable(allowKey);
            etApiKey.setFocusableInTouchMode(allowKey);
            etApiKey.setAlpha(allowKey ? 1f : 0.6f);
        }
    }

    private void setRowEnabled(View v, boolean enabled) {
        if (v == null) return;
        v.setClickable(enabled);
        v.setFocusable(enabled);
        v.setAlpha(enabled ? 1f : 0.6f);
    }

    private String localMcpUrl() {
        return "http://127.0.0.1:" + bridgePort + "/mcp";
    }

    private String mcpUrl() {
        return Lan.mcpUrl(bridgePort, cfg.mcpLan);
    }

    /** 展示用：局域网模式同时给出局域网与本机地址；没连 Wi-Fi 时明确提示。 */
    private String mcpUrlHint() {
        if (!cfg.mcpLan) return localMcpUrl();
        String lan = Lan.mcpUrl(bridgePort, true);
        if (lan.contains("127.0.0.1")) {
            return "未检测到局域网 IPv4（连上 Wi-Fi 后重进本页）\n" + localMcpUrl();
        }
        return lan + "\n" + localMcpUrl() + "（本机）";
    }

    /** 后台探一次真实桥端口；10 秒节流，失败保留默认 8771。 */
    private void refreshBridgePort() {
        if (!cfg.mcpEnabled) return;
        final String token = cfg.mcpToken == null ? "" : cfg.mcpToken.trim();
        if (token.length() == 0) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastPortProbeAt < 10_000L) return;
        lastPortProbeAt = now;
        AppExecutors.io(new Runnable() {
            public void run() {
                int p = BridgeClient.discover(token);
                if (p <= 0) return;
                bridgePort = p;
                AppExecutors.ui(new Runnable() {
                    public void run() {
                        if (!isFinishing() && tvMcpUrl != null) tvMcpUrl.setText(mcpUrlHint());
                    }
                });
            }
        });
    }

    private void applyMcpState() {
        boolean on = cfg.mcpEnabled;
        if (rowToken != null) rowToken.setVisibility(on ? View.VISIBLE : View.GONE);
        if (rowMcpLan != null) rowMcpLan.setVisibility(on ? View.VISIBLE : View.GONE);
        if (boxMcpJson != null) boxMcpJson.setVisibility(on ? View.VISIBLE : View.GONE);
        if (tvMcpUrl != null) {
            tvMcpUrl.setVisibility(on ? View.VISIBLE : View.GONE);
            tvMcpUrl.setText(mcpUrlHint());
        }
        if (on && tvMcpJson != null) tvMcpJson.setText(mcpJsonSummary());
    }

    /**
     * 终端风格的 MCP 字段速览：让用户一眼看懂每个字段是什么、现在是什么值。
     * 明确标注这是「config.json 里的期望配置」；token 只显示是否设置，不回显明文。
     */
    private String mcpJsonSummary() {
        int port = bridgePort;
        boolean tokenSet = cfg.mcpToken != null && cfg.mcpToken.trim().length() > 0;
        String enabled = cfg.enabled ? "true" : "false";
        String model = cfg.model().length() == 0 ? "未配置" : cfg.model();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"source\":    \"config.json（期望配置）\",\n");
        sb.append("  \"endpoint\":  \"").append(mcpUrl()).append("\",\n");
        sb.append("  \"auth\":      \"Bearer ").append(tokenSet ? "<已设置>" : "<未设置，接口将拒绝请求>").append("\",\n");
        sb.append("  \"protocol\":  \"JSON-RPC 2.0 (MCP 2025-03-26)\",\n");
        sb.append("  \"model\":     \"").append(model).append("\",\n");
        sb.append("  \"enabled\":   ").append(enabled).append(",\n");
        sb.append("  \"lan\":       ").append(cfg.mcpLan ? "true" : "false").append(",\n");
        sb.append("  \"tools\":     \"").append(Mcp.toolNames(" · ")).append("\",\n");
        sb.append("  \"bind\":      \"").append(cfg.mcpLan
                ? "0.0.0.0（同一局域网可访问，必须带 Bearer token）"
                : "127.0.0.1（仅本机可访问）").append("\"\n");
        sb.append("}");
        return sb.toString();
    }

    private void collect() {
        AppConfig.Provider p = provider();
        if (p != null) p.apiKey = etApiKey.getText().toString().trim();
        cfg.mcpToken = etToken.getText().toString().trim();
        cfg.markerTemplate = etPrefixTpl.getText().toString().trim();
    }

    // ------------------------------------------------------------- chips

    /** 服务商/模型 chips：点击即选，选中态用 bg_chip_ok（与主题 chip 同一套令牌）。 */
    private void renderChips() {
        if (boxProviderChips == null || boxModelChips == null) return;
        boxProviderChips.removeAllViews();
        boxModelChips.removeAllViews();
        chipViews.clear();

        AppConfig.Provider active = provider();
        if (cfg.providers.isEmpty()) {
            addHint(boxProviderChips, "还没有服务商 · 点＋添加第一个");
        } else {
            for (final AppConfig.Provider p : cfg.providers) {
                boolean on = active != null && active.id.equals(p.id);
                boxProviderChips.addView(chip(p.name, on, new Runnable() {
                    public void run() { selectProvider(p); }
                }));
            }
        }
        boxProviderChips.addView(chip("＋", false, new Runnable() {
            public void run() { promptAddProvider(); }
        }));

        if (active == null) {
            addHint(boxModelChips, "先添加服务商");
        } else if (active.models.isEmpty()) {
            addHint(boxModelChips, "这个服务商还没有模型 · 点＋添加");
        } else {
            AppConfig.ModelItem current = cfg.activeModel();
            for (final AppConfig.ModelItem m : active.models) {
                boolean on = current != null && current.id.equals(m.id);
                boxModelChips.addView(chip(m.id, on, new Runnable() {
                    public void run() { selectModel(m); }
                }));
            }
        }
        boxModelChips.addView(chip("＋", false, new Runnable() {
            public void run() {
                AppConfig.Provider p = provider();
                if (p == null) promptAddProvider();
                else promptAddModel(p);
            }
        }));

        applyChipEnabled();
    }

    private TextView chip(String text, boolean selected, final Runnable action) {
        TextView v = (TextView) getLayoutInflater().inflate(R.layout.item_chip, null, false);
        // inflate(root=null) 不会生成 LayoutParams，XML 里的 28dp 高与 8dp 间距会丢：
        // 这里显式补上，保证运行时 chips 与外观卡的静态 chips 完全同高。
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(28));
        lp.rightMargin = dp(8);
        v.setLayoutParams(lp);
        v.setText(text);
        v.setBackgroundResource(selected ? R.drawable.bg_chip_ok : R.drawable.bg_chip);
        v.setTextColor(color(selected ? R.color.primary : R.color.text_secondary));
        v.setTag(Boolean.valueOf(selected));
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { action.run(); }
        });
        chipViews.add(v);
        return v;
    }

    private void addHint(LinearLayout box, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(color(R.color.text_hint));
        t.setPadding(0, dp(6), dp(8), dp(6));
        box.addView(t);
    }

    private void applyChipEnabled() {
        boolean on = cfg.enabled;
        for (View chip : chipViews) {
            chip.setClickable(on);
            chip.setFocusable(on);
            chip.setAlpha(on ? 1f : 0.6f);
        }
    }

    private void selectProvider(AppConfig.Provider p) {
        if (p == null) return;
        // 点已选中的服务商不应重置当前模型，也不该产生脏标记
        if (p.id.equals(cfg.activeProviderId) && cfg.activeModelId.length() > 0) return;
        collect(); // 先把当前服务商的密钥写回
        cfg.activeProviderId = p.id;
        cfg.activeModelId = p.models.isEmpty() ? "" : p.models.get(0).id;
        rendering = true;
        etApiKey.setText(p.apiKey == null ? "" : p.apiKey);
        rendering = false;
        keyEditableWhenPaused = !cfg.enabled && cfg.apiKeyOfActive().length() == 0;
        statusState = AppStatus.ENABLED;
        statusDetail = "配置已改，点按重新校验";
        setModelCheckText("配置已改，点按重新校验", R.color.text_hint);
        renderChips();
        applyEnabledState();
        applyMcpState();
        syncStatusFromConfig();
        markDirty();
        scheduleAutoCheck();
    }

    private void selectModel(AppConfig.ModelItem m) {
        if (m == null) return;
        if (m.id.equals(cfg.activeModelId)) return;
        cfg.activeModelId = m.id;
        statusState = AppStatus.ENABLED;
        statusDetail = "配置已改，点按重新校验";
        setModelCheckText("配置已改，点按重新校验", R.color.text_hint);
        renderChips();
        applyMcpState();
        syncStatusFromConfig();
        markDirty();
        scheduleAutoCheck();
    }

    // ------------------------------------------------------------- 管理列表（选择/重命名/删除）

    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(this, R.style.Theme_XiaoiPlus_Dialog);
    }

    private boolean isActiveProvider(AppConfig.Provider p) {
        AppConfig.Provider active = provider();
        return p != null && active != null && active.id.equals(p.id);
    }

    private boolean isActiveModel(AppConfig.Provider p, AppConfig.ModelItem m) {
        if (p == null || m == null) return false;
        AppConfig.ModelItem active = cfg.activeModel();
        return isActiveProvider(p) && active != null && active.id.equals(m.id);
    }

    /** 一个对话框完成：选择 / 重命名 / 删除模型，以及删除服务商（行尾按钮）。 */
    private void openManage() {
        dismissManage();
        LinearLayout box = dialogBox();

        addSection(box, "服务商");
        final boolean canDeleteProvider = cfg.providers.size() > 1;
        for (final AppConfig.Provider p : new ArrayList<AppConfig.Provider>(cfg.providers)) {
            boolean active = isActiveProvider(p);
            String desc = (p.baseUrl.length() == 0 ? "端点未填写" : p.baseUrl)
                    + " · " + p.models.size() + " 个模型" + (active ? " · 当前使用" : "");
            addManageRow(box, p.name, desc,
                    new Runnable() { public void run() { dismissManage(); selectProvider(p); } },
                    new Runnable() { public void run() { dismissManage(); promptRenameProvider(p); } },
                    canDeleteProvider
                            ? new Runnable() { public void run() { dismissManage(); confirmDeleteProvider(p); } }
                            : null);
        }
        if (!canDeleteProvider) {
            addHint(box, "至少保留一个服务商；添加第二个后这里会出现删除入口");
        }
        addActionRow(box, "＋ 添加服务商", new Runnable() {
            public void run() { dismissManage(); promptAddProvider(); }
        });

        AppConfig.Provider cur = provider();
        addSection(box, "模型" + (cur == null ? "" : " · " + cur.name));
        if (cur == null) {
            addHint(box, "先添加服务商");
        } else if (cur.models.isEmpty()) {
            addHint(box, "这个服务商还没有模型 · 点＋添加");
        } else {
            for (final AppConfig.ModelItem m : new ArrayList<AppConfig.ModelItem>(cur.models)) {
                boolean active = isActiveModel(cur, m);
                String desc = (m.id.equals(m.name) ? "" : m.id) + (active ? (m.id.equals(m.name) ? "当前使用" : " · 当前使用") : "");
                addManageRow(box, m.name, desc,
                        new Runnable() { public void run() { dismissManage(); selectModel(m); } },
                        new Runnable() { public void run() { dismissManage(); promptRenameModel(m); } },
                        new Runnable() { public void run() { dismissManage(); confirmDeleteModel(cur, m); } });
            }
        }
        final AppConfig.Provider addTo = cur;
        addActionRow(box, "＋ 添加模型", new Runnable() {
            public void run() {
                dismissManage();
                if (addTo == null) promptAddProvider();
                else promptAddModel(addTo);
            }
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        manageDlg = dialogBuilder().setTitle("管理服务商与模型").setView(sv)
                .setNegativeButton("关闭", null).create();
        manageDlg.show();
    }

    private void dismissManage() {
        if (manageDlg == null) return;
        try { manageDlg.dismiss(); } catch (Throwable ignored) {}
        manageDlg = null;
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(8));
        return box;
    }

    private void addSection(LinearLayout box, String title) {
        TextView t = styled(title, 12, R.color.text_secondary, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(2);
        box.addView(t, lp);
    }

    private void addManageRow(LinearLayout box, String title, String desc,
                              final Runnable pick, final Runnable rename, final Runnable delete) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        tapBackground(row);

        LinearLayout textBox = new LinearLayout(this);
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

    private void addActionRow(LinearLayout box, String title, final Runnable action) {
        TextView t = styled(title, 14, R.color.primary, true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(0, dp(12), 0, dp(12));
        tapBackground(t);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        box.addView(t, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private TextView actionText(String label, int colorRes, final Runnable action) {
        TextView t = styled(label, 13, colorRes, true);
        t.setPadding(dp(10), dp(8), dp(4), dp(8));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return t;
    }

    private TextView styled(String text, int sizeSp, int colorRes, boolean medium) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color(colorRes));
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
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

    private TextView errorText() {
        TextView t = styled("", 12, R.color.error, false);
        t.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private void showError(TextView t, String msg) {
        if (t == null) { toast(msg); return; }
        t.setText(msg);
        t.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------- 服务商 / 模型编辑

    private void promptAddProvider() {
        final EditText name = input("名称，如 GLM");
        final EditText url = input("https://api.example.com/v1");
        final TextView err = errorText();
        LinearLayout box = dialogBox();
        box.addView(name);
        box.addView(url);
        box.addView(err);

        final AlertDialog d = dialogBuilder().setTitle("添加服务商").setView(box)
                .setPositiveButton("添加", null).setNegativeButton("取消", null).create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        String n = name.getText().toString().trim();
                        String u = url.getText().toString().trim();
                        if (n.length() == 0) { showError(err, "服务商名称不能为空"); return; }
                        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                            showError(err, "端点必须是 http(s):// 开头");
                            return;
                        }
                        // 已经有可用模型时不要把当前选择切走（否则只是加一家服务商，
                        // 正在生效的模型就被清了）；只有在「还没配置好」时才自动选中新的。
                        boolean hadActive = cfg.activeProvider() != null && cfg.activeModel() != null;
                        AppConfig.Provider p = new AppConfig.Provider(slug(n), n, u, "");
                        cfg.providers.add(p);
                        if (!hadActive) {
                            cfg.activeProviderId = p.id;
                            cfg.activeModelId = "";
                        }
                        d.dismiss();
                        render();
                        markDirty();
                        toast(hadActive
                                ? "已添加服务商 " + n + "，点它的 chip 再添加模型"
                                : "已添加服务商 " + n + "，记得添加模型");
                    }
                });
            }
        });
        d.show();
    }

    private void promptAddModel(final AppConfig.Provider p) {
        if (p == null) { promptAddProvider(); return; }
        final EditText id = input("模型 ID，如 your-model");
        final EditText name = input("显示名（可选，如 GLM 5.3 Flash）");
        final TextView err = errorText();
        LinearLayout box = dialogBox();
        box.addView(id);
        box.addView(name);
        box.addView(err);

        final AlertDialog d = dialogBuilder().setTitle("添加模型到 " + p.name).setView(box)
                .setPositiveButton("添加", null).setNegativeButton("取消", null).create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        String mid = id.getText().toString().trim();
                        String mname = name.getText().toString().trim();
                        if (mid.length() == 0) { showError(err, "模型 ID 不能为空"); return; }
                        for (AppConfig.ModelItem m : p.models) {
                            if (m.id.equals(mid)) { showError(err, "已存在同名模型"); return; }
                        }
                        p.models.add(new AppConfig.ModelItem(mid, mname));
                        if (cfg.activeModelId.length() == 0
                                || !cfg.activeProviderId.equals(p.id)) {
                            cfg.activeProviderId = p.id;
                            cfg.activeModelId = mid;
                        }
                        d.dismiss();
                        render();
                        markDirty();
                    }
                });
            }
        });
        d.show();
    }

    private void promptRenameProvider(final AppConfig.Provider p) {
        final EditText name = input("服务商显示名");
        name.setText(p.name);
        final TextView err = errorText();
        LinearLayout box = dialogBox();
        box.addView(name);
        box.addView(err);

        final AlertDialog d = dialogBuilder().setTitle("重命名服务商").setView(box)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        String n = name.getText().toString().trim();
                        if (n.length() == 0) { showError(err, "名称不能为空"); return; }
                        p.name = n; // id 永不变（§3.5）
                        d.dismiss();
                        render();
                        markDirty();
                    }
                });
            }
        });
        d.show();
    }

    private void promptRenameModel(final AppConfig.ModelItem m) {
        final EditText name = input("模型显示名（ID 不变）");
        name.setText(m.name == null ? m.id : m.name);
        final TextView err = errorText();
        LinearLayout box = dialogBox();
        box.addView(name);
        box.addView(styled("请求时使用的 ID 仍是 " + m.id, 11, R.color.text_hint, false));
        box.addView(err);

        final AlertDialog d = dialogBuilder().setTitle("重命名模型").setView(box)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        String n = name.getText().toString().trim();
                        if (n.length() == 0) { showError(err, "名称不能为空"); return; }
                        m.name = n;
                        d.dismiss();
                        render();
                        markDirty();
                    }
                });
            }
        });
        d.show();
    }

    private void confirmDeleteModel(final AppConfig.Provider p, final AppConfig.ModelItem m) {
        final boolean active = isActiveModel(p, m);
        dialogBuilder().setTitle("删除模型")
                .setMessage("将被删除：" + m.id
                        + (active ? "\n这是当前使用的模型，删除后自动切到同服务商的下一个" : ""))
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int i) {
                        collect();
                        int idx = p.models.indexOf(m);
                        p.models.remove(m);
                        if (p.models.isEmpty()) {
                            if (isActiveProvider(p)) cfg.activeModelId = "";
                        } else if (active || cfg.activeModelId.length() == 0) {
                            cfg.activeModelId = p.models.get(Math.min(idx, p.models.size() - 1)).id;
                        }
                        render();
                        markDirty();
                        toast("已删除 " + m.id);
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void confirmDeleteProvider(final AppConfig.Provider p) {
        if (cfg.providers.size() <= 1) {
            toast("至少保留一个服务商，无法删除");
            return;
        }
        final boolean activeRef = isActiveProvider(p);
        dialogBuilder().setTitle("删除服务商")
                .setMessage("将被删除：" + p.name + "\n该服务商的模型与密钥会一并移除。"
                        + (activeRef ? "\n这是当前使用的服务商，删除后自动切到剩余第一个" : ""))
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int i) {
                        collect();
                        boolean wasActive = isActiveProvider(p);
                        cfg.providers.remove(p);
                        if (wasActive || cfg.activeProviderId.equals(p.id)) {
                            AppConfig.Provider first = cfg.providers.get(0);
                            cfg.activeProviderId = first.id;
                            cfg.activeModelId = first.models.isEmpty()
                                    ? "" : first.models.get(0).id;
                        }
                        render();
                        markDirty();
                        toast("已删除服务商 " + p.name);
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    /** §3.5：provider id = slug(name)，小写、非字母数字转 -、截断 24、冲突追加 -2/-3。 */
    private String slug(String name) {
        StringBuilder sb = new StringBuilder();
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) sb.append(ch);
            else sb.append('-');
        }
        String s = sb.toString().replaceAll("-+", "-");
        while (s.startsWith("-")) s = s.substring(1);
        while (s.endsWith("-")) s = s.substring(0, s.length() - 1);
        if (s.length() == 0) s = "provider";
        if (s.length() > 24) s = s.substring(0, 24);
        String base = s;
        int n = 2;
        while (hasProviderId(s)) {
            String suffix = "-" + n++;
            s = (base.length() > 24 - suffix.length()
                    ? base.substring(0, 24 - suffix.length()) : base) + suffix;
        }
        return s;
    }

    private boolean hasProviderId(String id) {
        for (AppConfig.Provider p : cfg.providers) {
            if (p.id.equals(id)) return true;
        }
        return false;
    }

    private void toggleKey() {
        boolean visible = btnEye.getText().toString().equals("隐藏");
        etApiKey.setInputType(visible
                ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        btnEye.setText(visible ? "显示" : "隐藏");
    }

    // ------------------------------------------------------------- 状态条

    /** 依据配置与最近一次校验结果更新状态条（规则统一在 StatusResolver）。 */
    private void syncStatusFromConfig() {
        AppConfig.Provider p = provider();
        AppConfig.ModelItem m = cfg.activeModel();
        StatusResolver.Input in = new StatusResolver.Input();
        in.hasProvider = p != null;
        in.hasModel = m != null && m.id != null && m.id.length() > 0;
        in.keySet = cfg.apiKeyOfActive().length() > 0;
        in.enabled = cfg.enabled;
        in.checking = statusState == AppStatus.CHECKING;
        in.probe = StatusResolver.probeOf(statusState);

        StatusResolver.Result r = StatusResolver.resolve(in);
        // 已校验过的状态保留页面上的真实副文案（延迟、错误原因），不退回通用文案。
        String detail = in.probe != StatusResolver.Probe.UNKNOWN && statusDetail != null
                && statusDetail.length() > 0 ? statusDetail : r.detail;
        paintStatus(r.status, detail);
        renderPush();
    }

    private void paintStatus(AppStatus state, String detail) {
        statusState = state;
        statusDetail = detail;
        if (tvStatusTitle == null) return;
        tvStatusTitle.setText(state.title());
        tvStatusTitle.setTextColor(statusColor(state));
        tvStatusDot.setTextColor(statusColor(state));
        tvStatusDesc.setText(detail);
    }

    /** 本次会话/上次启动的推送状态：能确定才给出结论，否则显示「未知 · 点按检测」。 */
    private void renderPush() {
        if (tvPushState == null) return;
        if (lastPush == null) {
            if (lastPushError != null) {
                setPush("未同步 · " + shortText(lastPushError) + "（点按重试）", R.color.error, true);
            } else if (ConfigSync.isPending(this)) {
                setPush("未同步 · 小爱未收到（点按重试）", R.color.warning, true);
            } else {
                setPush("推送状态未知 · 点按推送", R.color.text_hint, true);
            }
            return;
        }
        if (lastPush.total == 0) {
            setPush("未同步 · " + failureReason(lastPush) + "（点按重试）", R.color.warning, true);
        } else if (lastPush.pushed == lastPush.total) {
            setPush("已推送 · " + lastPush.pushed + " 个进程", R.color.success, false);
        } else if (lastPush.pushed == 0) {
            setPush("未同步 · " + failureReason(lastPush) + "（点按重试）", R.color.error, true);
        } else {
            setPush("部分推送 " + lastPush.pushed + "/" + lastPush.total
                    + "（点按重试）", R.color.warning, true);
        }
    }

    private void setPush(String text, int colorRes, boolean tappable) {
        tvPushState.setText(text);
        tvPushState.setTextColor(color(colorRes));
        if (rowPush != null) {
            rowPush.setClickable(tappable);
            rowPush.setFocusable(tappable);
        }
        if (tvPushAction != null) {
            tvPushAction.setText(tappable ? "↻" : "✓");
            tvPushAction.setTextColor(color(colorRes));
        }
    }

    private String firstError(BridgePusher.Result r) {
        if (r == null || r.errors.isEmpty()) return "原因未知";
        return r.errors.get(0);
    }

    /**
     * 把 {@link BridgePusher.Result#errors} 翻成人话：优先识别两种最常见的失败，
     * 其它情况保留原始行（含端口），避免只显示「失败」而没有下一句。
     */
    private String failureReason(BridgePusher.Result r) {
        if (r == null || r.errors.isEmpty()) return "原因未知";
        for (String e : r.errors) {
            if (e.contains("health 401") || e.contains("health 403")) {
                return "MCP 令牌与桥不一致（检查令牌是否被改过）";
            }
        }
        for (String e : r.errors) {
            if (e.contains("连接失败") || e.contains("timed out")) {
                return "小爱未运行或 MCP 未开启";
            }
        }
        return shortText(firstError(r));
    }

    private String shortText(String s) {
        if (s == null || s.length() == 0) return "原因未知";
        String t = s.replace('\n', ' ');
        return t.length() > 48 ? t.substring(0, 48) + "…" : t;
    }

    // ------------------------------------------------------------- 运行时冲突

    /**
     * 只读核对「小爱运行时」与「已保存的文件」是否一致（§5.5 / DRIFT）。
     *
     * 不一致时显示第二条黄色提示条：它不是文件冲突，所以不复用 banner_conflict，
     * 避免「点按载入文件」把用户正在编辑的内容丢掉。这里只提供「以文件为准重新推送」，
     * 「以运行时为准」的入口仍在「配置编辑 → 从运行中小爱导入」。
     */
    private void checkRuntimeDrift() {
        final AppConfig saved;
        try {
            saved = ConfigRepository.get(this).load();
        } catch (Throwable t) {
            return;
        }
        String token = saved.mcpToken == null ? "" : saved.mcpToken.trim();
        if (!saved.mcpEnabled || token.length() == 0 || saved.model().length() == 0) {
            hideRuntimeConflict();
            return;
        }
        AppExecutors.io(new Runnable() {
            public void run() {
                final JSONObject rt = ConfigSync.readRuntimeConfig(ConfigActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        if (rt == null) { hideRuntimeConflict(); return; }
                        String model = rt.optString("model", "");
                        boolean enabled = rt.optBoolean("enabled", false);
                        boolean same = enabled == saved.enabled
                                && (!saved.enabled || model.equals(saved.model()));
                        if (same) {
                            hideRuntimeConflict();
                            return;
                        }
                        showRuntimeConflict(saved, model, enabled);
                    }
                });
            }
        });
    }

    private void showRuntimeConflict(AppConfig saved, String runtimeModel, boolean runtimeEnabled) {
        if (bannerRuntime == null) return;
        String title;
        if (runtimeEnabled != saved.enabled) {
            title = runtimeEnabled
                    ? "小爱侧已启用，文件里是暂停"
                    : "小爱侧未启用，文件里是启用";
        } else {
            title = "小爱在用 " + runtimeModel + "，与文件里的 " + saved.model() + " 不一致";
        }
        if (tvRuntimeTitle != null) tvRuntimeTitle.setText(title);
        if (tvRuntimeDesc != null) {
            tvRuntimeDesc.setText("点按以文件为准重新推送；或以「配置编辑 → 从运行中小爱导入」采用运行时");
        }
        bannerRuntime.setVisibility(View.VISIBLE);
    }

    private void hideRuntimeConflict() {
        if (bannerRuntime != null) bannerRuntime.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------- 保存与推送

    private void save() {
        collect();
        ConfigRepository repo = ConfigRepository.get(this);
        // WP2：文件损坏时已保留原文件并进入只读态；此处必须阻止写入，等 UI 恢复/重置。
        if (repo.isReadOnly()) {
            toast("配置文件损坏，已保留原文件；请先恢复/重置");
            return;
        }
        String err = repo.validate(cfg);
        if (err != null) {
            statusState = AppStatus.ENABLED;
            statusDetail = err + " · 修正后再保存";
            syncStatusFromConfig();
            toast("未保存：" + err);
            return;
        }
        try {
            // saveQuiet = 写文件 + 写 prefs（不丢弃结果）；推送由本页拿真实 Result 展示。
            repo.saveQuiet(cfg);
            ConfigSync.markApplied(this); // 自己写的文件不再触发「外部变更」
            cfg = repo.load();            // 读回归一化后的文件（trim / 悬空 active 修正）
            render();
            markClean();
            hideConflict();
            hideRuntimeConflict();
            pushNow(cfg, true);
            checkModelNow(true);
        } catch (Throwable t) {
            toast("保存失败：" + t.getMessage());
        }
    }

    private void retryPush() {
        hideRuntimeConflict();
        AppConfig c;
        try {
            c = ConfigRepository.get(this).load(); // 以文件为准（不推未保存的输入）
        } catch (Throwable t) {
            c = cfg;
        }
        pushNow(c, false);
    }

    /** 后台推送一次，把 {@link BridgePusher.Result} 原样转成状态条 + toast。 */
    private void pushNow(final AppConfig c, final boolean fromSave) {
        setPush("正在推送到小爱…", R.color.text_hint, false);
        AppExecutors.io(new Runnable() {
            public void run() {
                BridgePusher.Result res = null;
                String ex = null;
                try {
                    res = ConfigRepository.get(ConfigActivity.this).pushSync(c);
                } catch (Throwable t) {
                    ex = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final BridgePusher.Result fr = res;
                final String fex = ex;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        lastPush = fr;
                        lastPushError = fex;
                        renderPush();
                        toast(pushToast(fr, fex, fromSave));
                        // 局域网开关可能导致桥重绑到相邻端口：强制重新探一次，URL 立即指向真实端口。
                        lastPortProbeAt = 0;
                        refreshBridgePort();
                        // 推送后核对运行时模型/开关：不一致时出黄色冲突条（只读，不写文件）。
                        ui.postDelayed(new Runnable() {
                            public void run() { checkRuntimeDrift(); }
                        }, 1200);
                    }
                });
            }
        });
    }

    private String pushToast(BridgePusher.Result r, String ex, boolean fromSave) {
        String prefix = fromSave ? "已保存，" : "";
        if (r == null) return prefix + "推送失败（" + shortText(ex) + "）";
        if (r.total == 0) return prefix + "推送失败（" + failureReason(r) + "）";
        if (r.pushed == r.total) return "已推送 · " + r.pushed + " 个进程";
        if (r.pushed == 0) return prefix + "推送失败（" + failureReason(r) + "）";
        return prefix + "已推送 " + r.pushed + "/" + r.total + " · 其余进程稍后从 prefs 生效";
    }

    // ------------------------------------------------------------- 模型校验

    /** 校验结果序号：只采纳最新一次的结果，避免旧请求覆盖新状态。 */
    private int checkSeq;

    /** 自动校验节流：同一份配置 30 秒内只自动跑一次。 */
    private String lastAutoCheckKey;
    private long lastAutoCheckAt;

    /** 上一次真正发起过校验的配置指纹（行/密钥/模型），用于重绘时保留已有结论。 */
    private String lastCheckKey;

    private String currentCheckKey() {
        AppConfig.Provider p = provider();
        AppConfig.ModelItem m = cfg.activeModel();
        if (p == null || m == null) return "";
        return p.baseUrl + "|" + (p.apiKey == null ? "" : p.apiKey.trim()) + "|" + m.id;
    }

    private void invalidateCheck() {
        statusState = AppStatus.ENABLED;
        statusDetail = "配置已改，点按重新校验";
        setModelCheckText("配置已改，点按重新校验", R.color.text_hint);
        syncStatusFromConfig();
    }

    private void scheduleAutoCheck() {
        ui.removeCallbacks(autoCheckTask);
        ui.postDelayed(autoCheckTask, 700);
    }

    /** 文件被外部修改后的统一入口：有未保存输入就只提示，没有才自动载入。 */
    private void onExternalChange() {
        if (dirty) {
            showConflict();
        } else {
            hideConflict();
            reloadFromFile(false);
        }
    }

    private void showConflict() {
        if (bannerConflict != null) bannerConflict.setVisibility(View.VISIBLE);
    }

    private void hideConflict() {
        if (bannerConflict != null) bannerConflict.setVisibility(View.GONE);
    }

    /**
     * 点冲突条 / 无脏数据时：重载文件并重绘。
     * @param announce true=用户点按（toast 反馈）；false=自动载入（静默，避免每次开页都弹）
     */
    private void reloadFromFile(boolean announce) {
        try {
            cfg = ConfigRepository.get(this).load();
            render();
            if (announce) toast("已从文件重新载入");
            checkModelNow(false);
        } catch (Throwable ignored) {}
    }

    /**
     * 校验模型连通性。
     * @param manual true=用户点按（总是执行）；false=自动（同配置 30s 节流）
     */
    private void checkModelNow(boolean manual) {
        final AppConfig.Provider p = provider();
        final AppConfig.ModelItem m = cfg.activeModel();
        if (p == null) {
            if (manual) toast("先选择服务商");
            syncStatusFromConfig();
            return;
        }
        if (m == null || m.id.length() == 0) {
            if (manual) toast("先选择或添加模型");
            syncStatusFromConfig();
            return;
        }
        if (!cfg.enabled) {
            if (manual) toast("已暂停，打开上方开关后再校验");
            syncStatusFromConfig();
            return;
        }
        if (p.apiKey == null || p.apiKey.trim().length() == 0) {
            if (manual) toast("先填写密钥");
            paintStatus(AppStatus.ENABLED, "密钥为空 · 填写后才能校验可用性");
            renderPush();
            return;
        }
        String key = currentCheckKey();
        long now = android.os.SystemClock.elapsedRealtime();
        if (!manual && key.equals(lastAutoCheckKey) && now - lastAutoCheckAt < 30_000L) return;
        lastCheckKey = key;
        lastAutoCheckKey = key;
        lastAutoCheckAt = now;

        final int seq = ++checkSeq;
        paintStatus(AppStatus.CHECKING, "正在请求 " + p.baseUrl + " …");
        renderPush();
        if (tvModelCheck != null) {
            tvModelCheck.setText("校验中…");
            tvModelCheck.setTextColor(color(R.color.text_hint));
        }
        AppExecutors.io(new Runnable() {
            public void run() {
                final ModelValidator.Result r =
                        ModelValidator.check(p.baseUrl, p.apiKey, m.id);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || seq != checkSeq) return;
                        applyCheckResult(r);
                    }
                });
            }
        });
    }

    /** ModelValidator.code → 状态条文案（§4.3 D 的映射）。 */
    private void applyCheckResult(ModelValidator.Result r) {
        AppStatus state;
        String detail;
        if (r.ok) {
            state = AppStatus.OK;
            detail = "响应 " + r.latencyMs + "ms · 点按重新校验";
        } else if ("auth".equals(r.code)) {
            state = AppStatus.AUTH;
            detail = r.message + " · 检查密钥是否属于该服务商，或是否误填了 MCP 令牌";
        } else if ("model".equals(r.code)) {
            state = AppStatus.MODEL;
            detail = r.message + " · 点「＋」添加正确的模型 ID，或换服务商";
        } else if ("endpoint".equals(r.code)) {
            state = AppStatus.ENDPOINT;
            detail = r.message + " · 检查地址拼写、是否需要 /v1、手机能否访问该域名";
        } else if ("timeout".equals(r.code)) {
            state = AppStatus.ENDPOINT;
            detail = "8 秒无响应 · 网络不稳或端点限流，稍后重试";
        } else {
            // http / empty：端点有响应但不能用，仍归入「端点不可达」这一档，
            // 副文案保留真实原因，避免用户只看到「失败」两个字。
            state = AppStatus.ENDPOINT;
            detail = r.message + " · 多为账号配额、参数不被支持或不是 OpenAI 兼容端点";
        }
        paintStatus(state, detail);
        AppConfig.Provider p = provider();
        AppConfig.ModelItem m = cfg.activeModel();
        if (p != null && m != null) {
            StatusCache.saveProbe(this, StatusCache.fingerprint(p.baseUrl, p.apiKey, m.id),
                    StatusResolver.probeOf(state));
        }
        syncStatusFromConfig();
        setModelCheckText(r.message, r.ok ? R.color.success : R.color.error);
    }

    // ------------------------------------------------------------- 导入导出

    /** 从运行中的小爱导入真实生效配置到 config.json（文件优先，手动触发）。 */
    private void importFromRuntime() {
        toast("正在读取运行中配置…");
        AppExecutors.io(new Runnable() {
            public void run() {
                final boolean ok = ConfigSync.importFromRuntime(ConfigActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        if (ok) {
                            cfg = ConfigRepository.get(ConfigActivity.this).load();
                            render();
                            hideRuntimeConflict(); // 已按运行时对齐，冲突条不再成立
                            toast("已导入到 config.json（密钥需确认）");
                            checkModelNow(true);
                        } else {
                            toast("导入失败：小爱未运行或 MCP 未开启");
                        }
                    }
                });
            }
        });
    }

    /** 导出（脱敏）：只用于分享/排障，不能用于恢复。 */
    private boolean exportMasked = true;

    private void startMaskedExport() {
        exportMasked = true;
        launchExport();
    }

    private void confirmFullExport() {
        dialogBuilder()
                .setTitle("备份（含密钥）")
                .setMessage("文件将包含明文 API Key 与 MCP Token，可用于换机/回滚恢复。\n任何拿到文件的人都能使用，请只在可信位置保存。")
                .setPositiveButton("仍然导出", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        exportMasked = false;
                        launchExport();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void launchExport() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "xiaoi-plus-config.json");
            startActivityForResult(i, REQ_EXPORT);
        } catch (Throwable t) { toast("无法打开文件选择器"); }
    }

    private void startImport() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) { toast("无法打开文件选择器"); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (req == REQ_EXPORT) {
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(exportJson(exportMasked).getBytes("UTF-8"));
                os.close();
                toast(exportMasked ? "已导出（已脱敏）" : "已导出（含密钥，注意保管）");
            } else if (req == REQ_IMPORT) {
                InputStream in = getContentResolver().openInputStream(uri);
                AppConfig c = ConfigRepository.parse(in);
                in.close();
                // 脱敏文件（密钥被写成 ***）不能用于恢复：直接阻断，避免把 *** 写成有效 key
                if (ConfigCodec.containsMaskedSecrets(c)) {
                    toast("这是脱敏文件（密钥为 ***），不能用于恢复；请使用「备份（含密钥）」的文件");
                    return;
                }
                // 导入同样走 codec 校验：非法配置不覆盖当前编辑状态
                String err = ConfigCodec.validate(c);
                if (err != null && c.enabled) {
                    toast("导入失败：" + err);
                    return;
                }
                cfg = c;
                render();
                markDirty(); // 只载入未写入文件，必须让用户确认保存
                toast("已载入，记得保存");
            }
        } catch (Throwable t) {
            toast("操作失败：" + t.getMessage());
        }
    }

    private String exportJson(boolean mask) throws Exception {
        JSONObject o = ConfigRepository.toJson(cfg);
        if (mask) {
            JSONArray arr = o.getJSONArray("providers");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                if (p.optString("apiKey").length() > 0) p.put("apiKey", "***");
            }
            o.getJSONObject("mcp").put("token", "***");
        }
        return o.toString(2);
    }

    private void share() {
        try {
            File dir = new File(getExternalFilesDir(null), "exports");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "xiaoi-plus-config.json");
            FileOutputStream out = new FileOutputStream(f);
            // 分享默认脱敏：分享出去的文件可能被转发/留存
            out.write(exportJson(true).getBytes("UTF-8"));
            out.close();
            Uri u = Uri.parse("content://" + ShareProvider.AUTHORITY + "/" + f.getName());
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "分享配置（已脱敏）"));
        } catch (Throwable t) { toast("分享失败：" + t.getMessage()); }
    }

    private void copyMcp() {
        String token = cfg.mcpToken == null ? "" : cfg.mcpToken;
        String local = localMcpUrl();
        boolean lanOn = cfg.mcpLan;
        String lanUrl = Lan.mcpUrl(bridgePort, true);
        boolean hasLanIp = !lanUrl.contains("127.0.0.1");
        String url = lanOn && hasLanIp ? lanUrl : local;
        StringBuilder txt = new StringBuilder();
        txt.append("URL : ").append(url).append('\n');
        if (lanOn && hasLanIp) {
            txt.append("本机: ").append(local).append('\n');
            txt.append("前置: 手机与电脑在同一 Wi-Fi，无需 adb forward\n");
        } else {
            txt.append("前置: adb forward tcp:").append(bridgePort)
                    .append(" tcp:").append(bridgePort).append('\n');
        }
        txt.append("Auth: Bearer ").append(token)
                .append("\n\n客户端片段：\n{\"mcpServers\":{\"xiaoi-plus\":{\"url\":\"")
                .append(url).append("\",\"headers\":{\"Authorization\":\"Bearer ")
                .append(token).append("\"}}}}");
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("xiaoi mcp", txt.toString()));
        toast("已复制 MCP 连接信息");
    }

    private void copySkill() {
        String local = localMcpUrl();
        boolean lanOn = cfg.mcpLan;
        String url = Lan.mcpUrl(bridgePort, true);
        if (!lanOn || url.contains("127.0.0.1")) url = local;
        String pre = lanOn && !url.equals(local)
                ? "前置：手机与电脑在同一 Wi-Fi，无需 adb forward\n"
                : "前置：adb forward tcp:" + bridgePort + " tcp:" + bridgePort + "\n";
        String skill = "# 小爱同学plus MCP\n\n"
                + pre
                + "端点：" + url + "（streamable-http，Bearer token）\n\n"
                + "工具：\n" + Mcp.toolListText();
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("xiaoi skill", skill));
        toast("已复制 Skill 文本");
    }

    // ------------------------------------------------------------- 基础设施

    private void markDirty() {
        dirty = true;
        if (tvDirty != null) tvDirty.setVisibility(View.VISIBLE);
        if (fab != null) {
            fab.setBackgroundResource(R.drawable.bg_button_primary);
            fab.setTextColor(color(R.color.on_primary));
            fab.setAlpha(1f);
        }
    }

    private void markClean() {
        dirty = false;
        if (tvDirty != null) tvDirty.setVisibility(View.GONE);
        if (fab != null) {
            // 干净态用次按钮（浅底主色字），避免 35% 透明的白字在浅色下不可读
            fab.setBackgroundResource(R.drawable.bg_button_secondary);
            fab.setTextColor(color(R.color.primary));
            fab.setAlpha(1f);
        }
    }

    private void tapBackground(View v) {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                && tv.resourceId != 0) {
            v.setBackgroundResource(tv.resourceId);
        }
    }

    private String newToken() {
        byte[] buf = new byte[16];
        new java.security.SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder();
        for (byte b : buf) { int v = b & 0xFF; if (v < 16) sb.append('0'); sb.append(Integer.toHexString(v)); }
        return sb.toString();
    }

    private View find(int id) { return findViewById(id); }

    private void click(int id, final Runnable r) {
        View v = find(id);
        if (v != null) v.setOnClickListener(new View.OnClickListener() { public void onClick(View x) { r.run(); } });
    }

    @Override protected String currentTab() { return "config"; }

    private void toast(String s) { showMessage(s); }
}
