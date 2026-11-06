package plus.xiaoi.module;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import plus.xiaoi.module.agent.AgentService;
import plus.xiaoi.module.core.BridgeEndpoint;
import plus.xiaoi.module.core.BridgePolicy;
import plus.xiaoi.module.core.BridgeStatus;
import plus.xiaoi.module.core.ErrorCatalog;
import plus.xiaoi.module.core.StateModel;
import plus.xiaoi.module.ui.config.AppearanceSection;
import plus.xiaoi.module.ui.config.ConfigDialogs;
import plus.xiaoi.module.ui.config.EditSection;
import plus.xiaoi.module.ui.config.McpSection;
import plus.xiaoi.module.ui.config.ModelEditor;
import plus.xiaoi.module.ui.config.StatusSection;

/**
 * 配置页（P3 重排）：状态条 + 模型 chips + 管理列表 + 保存/生效提示 + 冲突条。
 *
 * 状态条的单一事实来源（§4.2 的 P3 口径）：
 *   未配置 / 已暂停 / 待校验 / 校验中 / 可用 / 密钥无效 / 模型不存在 / 端点不可达
 * 浏览器外只有这里能说「配置是否有效」；模型卡里的「模型状态」只显示上一次校验的原始结果。
 *
 * WP4 起保存只写文件/prefs 并调用 {@link BridgeEndpoint#requestReload()}：
 * 保存成功显示「已保存，等待小爱应用」，不再有假推送结果或「令牌不一致」。
 * 真正的「已应用」状态留给 WP6 的四维状态模型。
 *
 * 约定：
 * - render() 期间的 setText/setChecked 不触发监听器（rendering 守卫）
 * - 暂停只停重定向/热应用，不锁编辑：服务商、模型、密钥始终可改可保存
 *
 * WP5：页面视图已拆到 {@code ui.config} 的 ConfigDialogs / McpSection / StatusSection /
 * EditSection / ModelEditor / AppearanceSection；本类只保留状态所有权与流程编排。
 */
public class ConfigActivity extends BaseActivity {

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

    /** 本次会话是否已保存；D3 的真实状态由控制面快照决定。 */
    private boolean savedWaiting;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable autoCheckTask = new Runnable() {
        public void run() { checkModelNow(false); }
    };

    private Switch swEnabled, swMcp, swPrefix, swMcpLan, swAgentLan;
    private View rowPrefixTpl, tvPrefixHelp, tvDirty;
    private TextView fab;
    private LinearLayout boxProviderChips, boxModelChips;
    private TextView btnEye, tvModelCheck;
    private EditText etApiKey, etPrefixTpl;
    private final List<View> chipViews = new ArrayList<View>();

    private ConfigDialogs dialogs;
    private EditSection editSection;
    private McpSection mcpSection;
    private ModelEditor modelEditor;
    private StatusSection statusSection;
    private AppearanceSection appearanceSection;

    /** 重建（切主题/旋转）时从 Bundle 恢复的未保存草稿；恢复后必须保持脏标记。 */
    private boolean restoreDraft;
    private static final String STATE_DRAFT = "draft_json";

    /** 损坏恢复对话框只弹一次；取消后保持只读，由保存入口继续阻止写入。 */
    private boolean corruptionDialogShown;

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
        swAgentLan = (Switch) find(R.id.sw_agent_lan);
        swPrefix = (Switch) find(R.id.sw_prefix);
        rowPrefixTpl = find(R.id.row_prefix_tpl);
        tvPrefixHelp = find(R.id.tv_prefix_help);
        tvModelCheck = (TextView) find(R.id.tv_model_check);
        etApiKey = (EditText) find(R.id.et_api_key);
        etPrefixTpl = (EditText) find(R.id.et_prefix_tpl);
        btnEye = (TextView) find(R.id.btn_key_eye);
        fab = (TextView) find(R.id.fab_save);
        tvDirty = find(R.id.tv_dirty);
        boxProviderChips = (LinearLayout) find(R.id.box_provider_chips);
        boxModelChips = (LinearLayout) find(R.id.box_model_chips);

        final View root = findViewById(android.R.id.content);
        dialogs = new ConfigDialogs(this, new ConfigDialogs.Toast() {
            public void show(String msg) { toast(msg); }
        });
        modelEditor = new ModelEditor(this, new ModelEditor.Host() {
            public AppConfig config() { return cfg; }
            public void render() { ConfigActivity.this.render(); }
            public void markDirty() { ConfigActivity.this.markDirty(); }
            public void toast(String msg) { ConfigActivity.this.toast(msg); }
            public void collect() { ConfigActivity.this.collect(); }
            public void selectProvider(AppConfig.Provider p) { ConfigActivity.this.selectProvider(p); }
            public void selectModel(AppConfig.ModelItem m) { ConfigActivity.this.selectModel(m); }
            public ConfigDialogs dialogs() { return dialogs; }
        });
        editSection = new EditSection(this, new EditSection.Host() {
            public AppConfig config() { return cfg; }
            public void setConfig(AppConfig next) { cfg = next; }
            public void render() { ConfigActivity.this.render(); }
            public void markDirty() { ConfigActivity.this.markDirty(); }
            public void toast(String msg) { ConfigActivity.this.toast(msg); }
            public void startForResult(Intent intent, int request) {
                startActivityForResult(intent, request);
            }
            public void startActivity(Intent intent) { ConfigActivity.this.startActivity(intent); }
            public android.content.ContentResolver resolver() { return getContentResolver(); }
            public File externalFilesDir() { return getExternalFilesDir(null); }
            public boolean alive() { return !isFinishing(); }
            public void hideRuntimeConflict() { ConfigActivity.this.hideRuntimeConflict(); }
            public void checkModelNow() { ConfigActivity.this.checkModelNow(true); }
            public ConfigDialogs dialogs() { return dialogs; }
            public boolean dirty() { return ConfigActivity.this.dirty; }
        });
        mcpSection = new McpSection(this, root, new McpSection.Host() {
            public AppConfig config() { return cfg; }
            public boolean alive() { return !isFinishing(); }
            public void toast(String msg) { ConfigActivity.this.toast(msg); }
        });
        statusSection = new StatusSection(this, root, new StatusSection.Painter() {
            public int colorOf(AppStatus state) { return statusColor(state); }
        });
        appearanceSection = new AppearanceSection(this, root, new Runnable() {
            public void run() { recreate(); }
        });

        bindListeners();
        render();
        if (restoreDraft) markDirty();
        appearanceSection.bind();
        setupNav();
        ui.post(new Runnable() {
            public void run() { maybeShowCorruptionDialog(); }
        });
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
        if (modelEditor != null) modelEditor.dismissManage();
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
                invalidateCheck();
                applyMcpState(); // JSON 速览里的 enabled 字段要跟着变
                markDirty();
                // 打开开关后自动跑一次校验，让状态条不用等用户点按；关闭时不发请求。
                if (on) scheduleAutoCheck();
                else toast("已暂停：只停重定向，配置仍可继续编辑");
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
                onLanToggled(on);
            }
        });
        swAgentLan.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton v, boolean on) {
                if (rendering) return;
                onAgentLanToggled(on);
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
        click(R.id.btn_gen_token, new Runnable() { public void run() { confirmRotateToken(); } });
        click(R.id.btn_copy_mcp, new Runnable() {
            public void run() { confirmCopyIfDirty(new Runnable() { public void run() { mcpSection.copyMcp(); } }); }
        });
        click(R.id.btn_copy_skill, new Runnable() {
            public void run() { confirmCopyIfDirty(new Runnable() { public void run() { mcpSection.copySkill(); } }); }
        });
        click(R.id.btn_export, new Runnable() { public void run() { editSection.startMaskedExport(); } });
        click(R.id.btn_backup, new Runnable() { public void run() { editSection.confirmFullExport(); } });
        click(R.id.btn_restore, new Runnable() { public void run() { editSection.startImport(); } });
        click(R.id.row_model_check, new Runnable() { public void run() { checkModelNow(true); } });
        click(R.id.row_manage, new Runnable() { public void run() { modelEditor.openManage(); } });
        click(R.id.row_import, new Runnable() { public void run() { editSection.importFromRuntime(); } });
        click(R.id.btn_share, new Runnable() { public void run() { editSection.share(); } });
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
        mcpSection.tokenView().addTextChangedListener(collectWatcher);
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
    }

    /** 生成新令牌前确认：旧客户端会立即 401；取消不改变当前令牌。 */
    private void confirmRotateToken() {
        ErrorCatalog.Entry e = ErrorCatalog.of(ErrorCatalog.Code.TOKEN_ROTATED);
        dialogs.dialogBuilder()
                .setTitle("生成新令牌？")
                .setMessage("生成新令牌会让旧 MCP 客户端立即 401。\n" + e.action)
                .setPositiveButton("生成", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        cfg.mcpToken = newToken();
                        rendering = true;
                        mcpSection.setToken(cfg.mcpToken);
                        rendering = false;
                        applyMcpState();
                        markDirty();
                        toast("已生成，保存后生效");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 复制前提醒：未保存时复制的是草稿；取消不复制。 */
    private void confirmCopyIfDirty(final Runnable copy) {
        if (!dirty) {
            copy.run();
            return;
        }
        dialogs.dialogBuilder()
                .setTitle("尚未保存")
                .setMessage("当前有未保存修改，复制的是草稿；建议先保存。")
                .setPositiveButton("仍然复制", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        copy.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 局域网开关：先说明风险再改状态。
     *
     * <p>弱令牌不会阻断保存，但 BridgePolicy 会把实际监听退回 loopback；
     * 因此开启时给用户两条明确选择：生成强令牌并开启，或取消保持仅本机。</p>
     */
    private void onLanToggled(boolean on) {
        if (!on) {
            cfg.mcpLan = false;
            applyMcpState();
            markDirty();
            toast("已修改，保存后关闭局域网");
            return;
        }
        // 先弹确认；用户确认前开关保持关闭，取消即不改变配置。
        setLanChecked(false);
        String token = cfg.mcpToken == null ? "" : cfg.mcpToken.trim();
        boolean empty = token.length() == 0;
        boolean weak = !empty && !BridgePolicy.tokenStrongEnoughForLan(token);
        final String risk = "同一 Wi-Fi 下的设备都能访问 MCP 接口。\n"
                + "公共 Wi-Fi / 公司网络建议保持关闭。\n"
                + "局域网必须使用 32 位十六进制令牌；令牌不足 32 位时会自动回退为仅本机。";
        if (weak) {
            dialogs.dialogBuilder()
                    .setTitle("令牌不足 32 位？")
                    .setMessage("当前令牌不足 32 位，局域网将回退仅本机；是否生成新令牌？\n"
                            + "生成后旧 MCP 客户端会立即 401。")
                    .setPositiveButton("生成并开启", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int which) {
                            enableLan(true);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        dialogs.dialogBuilder()
                .setTitle("允许局域网访问？")
                .setMessage(risk + (empty ? "\n\n当前没有令牌：确认后会自动生成。" : ""))
                .setPositiveButton(empty ? "生成并开启" : "开启",
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                enableLan(empty);
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 用户确认后真正打开局域网；generate=true 时同时生成 32 hex 令牌。 */
    private void enableLan(boolean generate) {
        if (generate) {
            cfg.mcpToken = newToken();
            rendering = true;
            mcpSection.setToken(cfg.mcpToken);
            rendering = false;
        }
        cfg.mcpLan = true;
        setLanChecked(true);
        applyMcpState();
        markDirty();
        toast(generate ? "已生成令牌并开启局域网，保存后生效" : "已修改，保存后生效");
    }

    private void setLanChecked(boolean checked) {
        rendering = true;
        swMcpLan.setChecked(checked);
        rendering = false;
    }

    /**
     * 8770 Agent 动作面局域网开关：与 8771 的分开确认，不复用开关状态。
     *
     * <p>8770 能执行设备动作，风险比只读/对话工具更高；因此打开时先说明风险。
     * token 为空或不足 32 hex 时明确给出「生成并开启」路径，取消则不改变配置。</p>
     */
    private void onAgentLanToggled(boolean on) {
        if (!on) {
            cfg.agentLan = false;
            applyMcpState();
            markDirty();
            toast("已修改，保存后关闭 Agent 局域网");
            return;
        }
        // 确认前开关保持关闭；取消即不改变配置。
        setAgentLanChecked(false);
        String token = cfg.mcpToken == null ? "" : cfg.mcpToken.trim();
        boolean weak = !BridgePolicy.tokenStrongEnoughForLan(token);
        String risk = "同一 Wi-Fi 下的设备凭令牌可执行打开应用、发通知、调音量等设备动作。\n"
                + "公共 Wi-Fi / 公司网络建议关闭。\n"
                + "必须使用 32 位十六进制令牌；弱令牌会自动回退仅本机。";
        if (weak) {
            String why = token.length() == 0 ? "当前没有令牌" : "当前令牌不足 32 位";
            dialogs.dialogBuilder()
                    .setTitle("令牌不足 32 位？")
                    .setMessage(risk + "\n\n" + why + "，局域网无法生效；是否生成新令牌并开启？\n"
                            + "生成后旧 MCP 客户端会立即 401。")
                    .setPositiveButton("生成并开启", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int which) {
                            enableAgentLan(true);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        dialogs.dialogBuilder()
                .setTitle("允许 Agent 局域网访问？")
                .setMessage(risk)
                .setPositiveButton("开启", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        enableAgentLan(false);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 用户确认后真正打开 8770 局域网；generate=true 时同时生成 32 hex 令牌。 */
    private void enableAgentLan(boolean generate) {
        if (generate) {
            cfg.mcpToken = newToken();
            rendering = true;
            mcpSection.setToken(cfg.mcpToken);
            rendering = false;
        }
        cfg.agentLan = true;
        setAgentLanChecked(true);
        applyMcpState();
        markDirty();
        toast(generate ? "已生成令牌并开启 Agent 局域网，保存后生效" : "已修改，保存后生效");
    }

    private void setAgentLanChecked(boolean checked) {
        rendering = true;
        swAgentLan.setChecked(checked);
        rendering = false;
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
            swAgentLan.setChecked(cfg.agentLan);
            swPrefix.setChecked(cfg.markerTemplate.trim().length() > 0);
            AppConfig.Provider p = provider();
            AppConfig.ModelItem m = cfg.activeModel();
            etApiKey.setText(p != null && p.apiKey != null ? p.apiKey : "");
            mcpSection.setToken(cfg.mcpToken);
            etPrefixTpl.setText(cfg.markerTemplate);
            setModelCheckText(m != null ? "未校验 · 点按校验" : "未选择模型", R.color.text_hint);
        } finally {
            rendering = false;
        }
        // 删掉一个「非当前」模型等情况会重绘页面，但可用性结论并没有失效：
        // 只有参与校验的 baseUrl/key/model 变了，才退回「待校验」。
        if (!currentCheckKey().equals(lastCheckKey)) {
            statusState = AppStatus.ENABLED;
            statusDetail = "";
        }
        renderChips();
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

    private void refreshBridgePort() {
        mcpSection.refreshPort();
    }

    private void applyMcpState() {
        mcpSection.render();
    }

    private void collect() {
        AppConfig.Provider p = provider();
        if (p != null) p.apiKey = etApiKey.getText().toString().trim();
        cfg.mcpToken = mcpSection.token();
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
            public void run() { modelEditor.promptAddProvider(); }
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
                if (p == null) modelEditor.promptAddProvider();
                else modelEditor.promptAddModel(p);
            }
        }));
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
        dialogs.addHint(box, text);
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
        statusState = AppStatus.ENABLED;
        statusDetail = "配置已改，点按重新校验";
        setModelCheckText("配置已改，点按重新校验", R.color.text_hint);
        renderChips();
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
        in.hasBase = p != null && p.baseUrl != null && p.baseUrl.trim().length() > 0;
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
        statusSection.paint(state, detail);
    }

    /**
     * D3：保存只依赖文件写入；是否生效只看控制面 leader 上报的 appliedRev。
     * 桥不在线 = 等待，不显示「推送失败/令牌不一致」。
     */
    private void renderPush() {
        BridgeStatus.Snapshot s = BridgeStatus.get();
        StateModel.Input in = new StateModel.Input();
        in.bridgeConnected = s.connected;
        in.fileRev = cfg == null ? 0L : cfg.rev;
        in.appliedRev = s.appliedRev;
        in.lastReportAt = s.updatedAt;
        in.now = System.currentTimeMillis();
        StateModel.AppliedState d3 = StateModel.resolveApplied(in, in.now);
        statusSection.renderPush(d3, savedWaiting);
    }

    // ------------------------------------------------------------- 运行时冲突

    /**
     * 只读核对「小爱运行时」与「已保存的文件」是否一致（§5.5 / DRIFT）。
     *
     * <p>WP6 起只读控制面 {@link BridgeStatus}：不再依赖 MCP/token/HTTP。
     * 控制面超过 30s 未上报、或还没加载到当前 rev 时，不生成冲突条（那只是等待，不是冲突）。</p>
     */
    private void checkRuntimeDrift() {
        final AppConfig saved;
        try {
            saved = ConfigRepository.get(this).load();
        } catch (Throwable t) {
            return;
        }
        BridgeStatus.Snapshot s = BridgeStatus.get();
        long now = System.currentTimeMillis();
        if (!s.fresh(now) || !s.runtimeKnown || s.appliedRev < saved.rev
                || !saved.enabled || saved.model().length() == 0) {
            hideRuntimeConflict();
            renderPush();
            return;
        }
        boolean same = s.enabled == saved.enabled
                && (!saved.enabled || s.model.equals(saved.model()));
        if (same) {
            hideRuntimeConflict();
            renderPush();
            return;
        }
        showRuntimeConflict(saved, s.model, s.enabled);
        renderPush();
    }

    private void showRuntimeConflict(AppConfig saved, String runtimeModel, boolean runtimeEnabled) {
        statusSection.showRuntimeConflict(saved, runtimeModel, runtimeEnabled);
    }

    private void hideRuntimeConflict() {
        statusSection.hideRuntimeConflict();
    }

    // ------------------------------------------------------------- 保存与生效

    private void save() {
        collect();
        ConfigRepository repo = ConfigRepository.get(this);
        // WP2：文件损坏时已保留原文件并进入只读态；此处必须阻止写入，等 UI 恢复/重置。
        if (repo.isReadOnly()) {
            toast(ErrorCatalog.message(ErrorCatalog.Code.CONFIG_CORRUPT));
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
            // saveQuiet = 写文件 + 写 prefs + 请求控制面 reload；本页只显示「等待应用」。
            repo.saveQuiet(cfg);
            ConfigSync.markApplied(this); // 自己写的文件不再触发「外部变更」
            cfg = repo.load();            // 读回归一化后的文件（trim / 悬空 active 修正）
            // WP1.2a：MCP 开关/token 变化后按新配置启动或停止 8770。
            AgentService.startIfEnabled(this);
            render();
            markClean();
            hideConflict();
            hideRuntimeConflict();
            savedWaiting = true;
            renderPush();
            toast("已保存，等待小爱应用");
            // 端口可能因局域网/token 变化而重绑：强制重新取一次控制面端口。
            mcpSection.resetPortProbeThrottle();
            refreshBridgePort();
            // 核对运行时模型/开关：不一致时出黄色冲突条（只读，不写文件）。
            ui.postDelayed(new Runnable() {
                public void run() { checkRuntimeDrift(); }
            }, 1200);
            checkModelNow(true);
        } catch (Throwable t) {
            toast("保存失败：" + t.getMessage());
        }
    }

    /** 「点按重新通知」：只走控制面 reload，不再向任何端口发 Bearer。 */
    private void retryPush() {
        hideRuntimeConflict();
        boolean sent = BridgeEndpoint.requestReload();
        savedWaiting = true;
        renderPush();
        toast(sent ? "已通知小爱应用，等待生效" : "小爱未连接，将在下一次轮询时应用");
        mcpSection.resetPortProbeThrottle();
        refreshBridgePort();
        ui.postDelayed(new Runnable() {
            public void run() { checkRuntimeDrift(); }
        }, 1200);
    }

    // ------------------------------------------------------------- 损坏恢复

    /**
     * 首次发现只读态时弹一次恢复入口；取消后保持只读（保存继续被阻止）。
     * 不自动删除 {@code .corrupt}/{@code .legacy}，所以必须在文案里说明它们含密钥。
     */
    private void maybeShowCorruptionDialog() {
        if (corruptionDialogShown) return;
        final ConfigRepository repo = ConfigRepository.get(this);
        if (!repo.isReadOnly()) return;
        corruptionDialogShown = true;

        final List<File> backups = repo.legacyBackups();
        String reason = repo.loadError();
        if (reason == null || reason.length() == 0) reason = "无法解析 config.json";
        StringBuilder msg = new StringBuilder();
        msg.append("原因：").append(reason).append('\n');
        msg.append("原文件已保留在应用私有目录（含明文密钥，请勿外传），保存已被阻止。\n");
        if (!backups.isEmpty()) {
            msg.append("可恢复最近一份旧配置：").append(backups.get(0).getName()).append('\n');
        }
        msg.append("取消后页面保持只读，保存不会写入任何内容。");

        android.app.AlertDialog.Builder b = dialogs.dialogBuilder()
                .setTitle("配置文件损坏")
                .setMessage(msg.toString())
                .setNegativeButton("取消", null);
        if (!backups.isEmpty()) {
            b.setPositiveButton("恢复旧配置", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    restoreLegacy(backups.get(0));
                }
            });
            b.setNeutralButton("重建默认配置", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    resetCorruptConfig();
                }
            });
        } else {
            b.setPositiveButton("重建默认配置", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    resetCorruptConfig();
                }
            });
        }
        b.show();
    }

    private void restoreLegacy(final File backup) {
        final ConfigRepository repo = ConfigRepository.get(this);
        AppExecutors.io(new Runnable() {
            public void run() {
                try {
                    repo.restoreLegacy(backup);
                    AppExecutors.ui(new Runnable() {
                        public void run() {
                            if (isFinishing()) return;
                            cfg = repo.load();
                            savedWaiting = true;
                            render();
                            toast("已恢复旧配置");
                        }
                    });
                } catch (final Throwable t) {
                    AppExecutors.ui(new Runnable() {
                        public void run() {
                            toast("恢复失败：" + describe(t) + "（原文件未改动）");
                        }
                    });
                }
            }
        });
    }

    private void resetCorruptConfig() {
        final ConfigRepository repo = ConfigRepository.get(this);
        AppExecutors.io(new Runnable() {
            public void run() {
                try {
                    repo.resetToDefaultsAfterCorruption();
                    AppExecutors.ui(new Runnable() {
                        public void run() {
                            if (isFinishing()) return;
                            cfg = repo.load();
                            savedWaiting = true;
                            render();
                            toast("已重建默认配置（原文件保留为 .corrupt）");
                        }
                    });
                } catch (final Throwable t) {
                    AppExecutors.ui(new Runnable() {
                        public void run() {
                            toast("重建失败：" + describe(t) + "（原文件未改动）");
                        }
                    });
                }
            }
        });
    }

    private static String describe(Throwable t) {
        String m = t == null ? "" : t.getMessage();
        return m == null || m.length() == 0 ? "未知错误" : m;
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
        statusSection.showConflict();
    }

    private void hideConflict() {
        statusSection.hideConflict();
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
            if (ConfigRepository.get(this).isReadOnly()) {
                ui.post(new Runnable() {
                    public void run() { maybeShowCorruptionDialog(); }
                });
            }
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
        // 暂停不阻止校验：校验只影响 D2，不代表小爱已在用（D4 仍由控制面决定）。
        if (p.apiKey == null || p.apiKey.trim().length() == 0) {
            if (manual) toast("先填写密钥");
            paintStatus(AppStatus.NOT_CONFIGURED, "缺少密钥 · 填写后才能校验端点");
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

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        editSection.onActivityResult(req, res, data);
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
