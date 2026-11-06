package plus.xiaoi.module.ui.config;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

import plus.xiaoi.module.AppConfig;
import plus.xiaoi.module.R;

/**
 * 服务商/模型的管理列表与编辑对话框：选择、添加、重命名、删除。
 *
 * 选择与渲染仍由宿主 {@code ConfigActivity} 执行（chips 与状态条在页面里），
 * 本类只负责对话框内容，通过 {@link Host} 读取配置、回写脏标记并请求重绘。
 */
public final class ModelEditor {

    public interface Host {
        AppConfig config();
        void render();
        void markDirty();
        void toast(String msg);
        void collect();
        void selectProvider(AppConfig.Provider p);
        void selectModel(AppConfig.ModelItem m);
        ConfigDialogs dialogs();
    }

    private final Context ctx;
    private final Host host;
    private AlertDialog manageDlg;

    public ModelEditor(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
    }

    private AppConfig.Provider provider() {
        return host.config().activeProvider();
    }

    private boolean isActiveProvider(AppConfig.Provider p) {
        AppConfig.Provider active = provider();
        return p != null && active != null && active.id.equals(p.id);
    }

    private boolean isActiveModel(AppConfig.Provider p, AppConfig.ModelItem m) {
        if (p == null || m == null) return false;
        AppConfig.ModelItem active = host.config().activeModel();
        return isActiveProvider(p) && active != null && active.id.equals(m.id);
    }

    /** 一个对话框完成：选择 / 重命名 / 删除模型，以及删除服务商（行尾按钮）。 */
    public void openManage() {
        dismissManage();
        AppConfig cfg = host.config();
        ConfigDialogs dialogs = host.dialogs();
        LinearLayout box = dialogs.dialogBox();

        dialogs.addSection(box, "服务商");
        final boolean canDeleteProvider = cfg.providers.size() > 1;
        for (final AppConfig.Provider p : new ArrayList<AppConfig.Provider>(cfg.providers)) {
            boolean active = isActiveProvider(p);
            String desc = (p.baseUrl.length() == 0 ? "端点未填写" : p.baseUrl)
                    + " · " + p.models.size() + " 个模型" + (active ? " · 当前使用" : "");
            dialogs.addManageRow(box, p.name, desc,
                    new Runnable() { public void run() { dismissManage(); host.selectProvider(p); } },
                    new Runnable() { public void run() { dismissManage(); promptRenameProvider(p); } },
                    canDeleteProvider
                            ? new Runnable() { public void run() { dismissManage(); confirmDeleteProvider(p); } }
                            : null);
        }
        if (!canDeleteProvider) {
            dialogs.addHint(box, "至少保留一个服务商；添加第二个后这里会出现删除入口");
        }
        dialogs.addActionRow(box, "＋ 添加服务商", new Runnable() {
            public void run() { dismissManage(); promptAddProvider(); }
        });

        AppConfig.Provider cur = provider();
        dialogs.addSection(box, "模型" + (cur == null ? "" : " · " + cur.name));
        if (cur == null) {
            dialogs.addHint(box, "先添加服务商");
        } else if (cur.models.isEmpty()) {
            dialogs.addHint(box, "这个服务商还没有模型 · 点＋添加");
        } else {
            for (final AppConfig.ModelItem m : new ArrayList<AppConfig.ModelItem>(cur.models)) {
                boolean active = isActiveModel(cur, m);
                String desc = (m.id.equals(m.name) ? "" : m.id) + (active ? (m.id.equals(m.name) ? "当前使用" : " · 当前使用") : "");
                dialogs.addManageRow(box, m.name, desc,
                        new Runnable() { public void run() { dismissManage(); host.selectModel(m); } },
                        new Runnable() { public void run() { dismissManage(); promptRenameModel(m); } },
                        new Runnable() { public void run() { dismissManage(); confirmDeleteModel(cur, m); } });
            }
        }
        final AppConfig.Provider addTo = cur;
        dialogs.addActionRow(box, "＋ 添加模型", new Runnable() {
            public void run() {
                dismissManage();
                if (addTo == null) promptAddProvider();
                else promptAddModel(addTo);
            }
        });

        ScrollView sv = new ScrollView(ctx);
        sv.addView(box);
        manageDlg = dialogs.dialogBuilder().setTitle("管理服务商与模型").setView(sv)
                .setNegativeButton("关闭", null).create();
        manageDlg.show();
    }

    public void dismissManage() {
        if (manageDlg == null) return;
        try { manageDlg.dismiss(); } catch (Throwable ignored) {}
        manageDlg = null;
    }

    public void promptAddProvider() {
        ConfigDialogs dialogs = host.dialogs();
        final EditText name = dialogs.input("名称，如 GLM");
        final EditText url = dialogs.input("https://api.example.com/v1");
        final TextView err = dialogs.errorText();
        LinearLayout box = dialogs.dialogBox();
        box.addView(name);
        box.addView(url);
        box.addView(err);

        final AlertDialog d = dialogs.dialogBuilder().setTitle("添加服务商").setView(box)
                .setPositiveButton("添加", null).setNegativeButton("取消", null).create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        ConfigDialogs dlgs = host.dialogs();
                        AppConfig cfg = host.config();
                        String n = name.getText().toString().trim();
                        String u = url.getText().toString().trim();
                        if (n.length() == 0) { dlgs.showError(err, "服务商名称不能为空"); return; }
                        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                            dlgs.showError(err, "端点必须是 http(s):// 开头");
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
                        host.render();
                        host.markDirty();
                        host.toast(hadActive
                                ? "已添加服务商 " + n + "，点它的 chip 再添加模型"
                                : "已添加服务商 " + n + "，记得添加模型");
                    }
                });
            }
        });
        d.show();
    }

    public void promptAddModel(final AppConfig.Provider p) {
        if (p == null) { promptAddProvider(); return; }
        ConfigDialogs dialogs = host.dialogs();
        final EditText id = dialogs.input("模型 ID，如 your-model");
        final EditText name = dialogs.input("显示名（可选，如 GLM 5.3 Flash）");
        final TextView err = dialogs.errorText();
        LinearLayout box = dialogs.dialogBox();
        box.addView(id);
        box.addView(name);
        box.addView(err);

        final AlertDialog d = dialogs.dialogBuilder().setTitle("添加模型到 " + p.name).setView(box)
                .setPositiveButton("添加", null).setNegativeButton("取消", null).create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        ConfigDialogs dlgs = host.dialogs();
                        AppConfig cfg = host.config();
                        String mid = id.getText().toString().trim();
                        String mname = name.getText().toString().trim();
                        if (mid.length() == 0) { dlgs.showError(err, "模型 ID 不能为空"); return; }
                        for (AppConfig.ModelItem m : p.models) {
                            if (m.id.equals(mid)) { dlgs.showError(err, "已存在同名模型"); return; }
                        }
                        p.models.add(new AppConfig.ModelItem(mid, mname));
                        if (cfg.activeModelId.length() == 0
                                || !cfg.activeProviderId.equals(p.id)) {
                            cfg.activeProviderId = p.id;
                            cfg.activeModelId = mid;
                        }
                        d.dismiss();
                        host.render();
                        host.markDirty();
                    }
                });
            }
        });
        d.show();
    }

    private void promptRenameProvider(final AppConfig.Provider p) {
        ConfigDialogs dialogs = host.dialogs();
        final EditText name = dialogs.input("服务商显示名");
        name.setText(p.name);
        final TextView err = dialogs.errorText();
        LinearLayout box = dialogs.dialogBox();
        box.addView(name);
        box.addView(err);

        final AlertDialog d = dialogs.dialogBuilder().setTitle("重命名服务商").setView(box)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        String n = name.getText().toString().trim();
                        if (n.length() == 0) { host.dialogs().showError(err, "名称不能为空"); return; }
                        p.name = n; // id 永不变（§3.5）
                        d.dismiss();
                        host.render();
                        host.markDirty();
                    }
                });
            }
        });
        d.show();
    }

    private void promptRenameModel(final AppConfig.ModelItem m) {
        ConfigDialogs dialogs = host.dialogs();
        final EditText name = dialogs.input("模型显示名（ID 不变）");
        name.setText(m.name == null ? m.id : m.name);
        final TextView err = dialogs.errorText();
        LinearLayout box = dialogs.dialogBox();
        box.addView(name);
        box.addView(dialogs.styled("请求时使用的 ID 仍是 " + m.id, 11, R.color.text_hint, false));
        box.addView(err);

        final AlertDialog d = dialogs.dialogBuilder().setTitle("重命名模型").setView(box)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        String n = name.getText().toString().trim();
                        if (n.length() == 0) { host.dialogs().showError(err, "名称不能为空"); return; }
                        m.name = n;
                        d.dismiss();
                        host.render();
                        host.markDirty();
                    }
                });
            }
        });
        d.show();
    }

    private void confirmDeleteModel(final AppConfig.Provider p, final AppConfig.ModelItem m) {
        final boolean active = isActiveModel(p, m);
        host.dialogs().dialogBuilder().setTitle("删除模型")
                .setMessage("将被删除：" + m.id
                        + (active ? "\n这是当前使用的模型，删除后自动切到同服务商的下一个" : ""))
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int i) {
                        AppConfig cfg = host.config();
                        host.collect();
                        int idx = p.models.indexOf(m);
                        p.models.remove(m);
                        if (p.models.isEmpty()) {
                            if (isActiveProvider(p)) cfg.activeModelId = "";
                        } else if (active || cfg.activeModelId.length() == 0) {
                            cfg.activeModelId = p.models.get(Math.min(idx, p.models.size() - 1)).id;
                        }
                        host.render();
                        host.markDirty();
                        host.toast("已删除 " + m.id);
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    private void confirmDeleteProvider(final AppConfig.Provider p) {
        AppConfig cfg = host.config();
        if (cfg.providers.size() <= 1) {
            host.toast("至少保留一个服务商，无法删除");
            return;
        }
        final boolean activeRef = isActiveProvider(p);
        host.dialogs().dialogBuilder().setTitle("删除服务商")
                .setMessage("将被删除：" + p.name + "\n该服务商的模型与密钥会一并移除。"
                        + (activeRef ? "\n这是当前使用的服务商，删除后自动切到剩余第一个" : ""))
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int i) {
                        AppConfig cfg = host.config();
                        host.collect();
                        boolean wasActive = isActiveProvider(p);
                        cfg.providers.remove(p);
                        if (wasActive || cfg.activeProviderId.equals(p.id)) {
                            AppConfig.Provider first = cfg.providers.get(0);
                            cfg.activeProviderId = first.id;
                            cfg.activeModelId = first.models.isEmpty()
                                    ? "" : first.models.get(0).id;
                        }
                        host.render();
                        host.markDirty();
                        host.toast("已删除服务商 " + p.name);
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
        for (AppConfig.Provider p : host.config().providers) {
            if (p.id.equals(id)) return true;
        }
        return false;
    }
}
