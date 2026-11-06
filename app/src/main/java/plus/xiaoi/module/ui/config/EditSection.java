package plus.xiaoi.module.ui.config;

import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import plus.xiaoi.module.AppConfig;
import plus.xiaoi.module.AppExecutors;
import plus.xiaoi.module.ConfigCodec;
import plus.xiaoi.module.ConfigRepository;
import plus.xiaoi.module.ConfigSync;
import plus.xiaoi.module.R;
import plus.xiaoi.module.ShareProvider;

/**
 * 配置编辑板块：从运行中小爱导入、脱敏导出、完整备份、从文件还原、分享。
 *
 * 文件选择器仍由宿主 Activity 触发（{@link Host#startForResult(Intent, int)}），
 * 结果回传给 {@link #onActivityResult(int, int, Intent)}；本类不持有 Activity。
 */
public final class EditSection {

    public static final int REQ_EXPORT = 1001;
    public static final int REQ_IMPORT = 1002;

    public interface Host {
        AppConfig config();
        void setConfig(AppConfig cfg);
        void render();
        void markDirty();
        void toast(String msg);
        void startForResult(Intent intent, int request);
        void startActivity(Intent intent);
        ContentResolver resolver();
        File externalFilesDir();
        boolean alive();
        void hideRuntimeConflict();
        void checkModelNow();
        ConfigDialogs dialogs();
    }

    private final Context ctx;
    private final Host host;
    /** 导出（脱敏）：只用于分享/排障，不能用于恢复。 */
    private boolean exportMasked = true;

    public EditSection(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
    }

    /** 从运行中的小爱导入真实生效配置到 config.json（文件优先，手动触发）。 */
    public void importFromRuntime() {
        host.toast("正在读取运行中配置…");
        AppExecutors.io(new Runnable() {
            public void run() {
                final boolean ok = ConfigSync.importFromRuntime(ctx);
                AppExecutors.ui(new Runnable() {
                    public void run() {
                        if (!host.alive()) return;
                        if (ok) {
                            host.setConfig(ConfigRepository.get(ctx).load());
                            host.render();
                            host.hideRuntimeConflict();
                            host.toast("已导入到 config.json（密钥需确认）");
                            host.checkModelNow();
                        } else {
                            host.toast("导入失败：小爱未运行或 MCP 未开启");
                        }
                    }
                });
            }
        });
    }

    /** 导出（脱敏）：只用于分享/排障，不能用于恢复。 */
    public void startMaskedExport() {
        exportMasked = true;
        launchExport();
    }

    public void confirmFullExport() {
        host.dialogs().dialogBuilder()
                .setTitle("备份（含密钥）")
                .setMessage("文件将包含明文 API Key 与 MCP Token，可用于换机/回滚恢复。\n任何拿到文件的人都能使用，请只在可信位置保存。")
                .setPositiveButton("仍然导出", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
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
            host.startForResult(i, REQ_EXPORT);
        } catch (Throwable t) { host.toast("无法打开文件选择器"); }
    }

    public void startImport() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            host.startForResult(i, REQ_IMPORT);
        } catch (Throwable t) { host.toast("无法打开文件选择器"); }
    }

    public void onActivityResult(int req, int res, Intent data) {
        if (res != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (req == REQ_EXPORT) {
                OutputStream os = host.resolver().openOutputStream(uri);
                os.write(exportJson(exportMasked).getBytes("UTF-8"));
                os.close();
                host.toast(exportMasked ? "已导出（已脱敏）" : "已导出（含密钥，注意保管）");
            } else if (req == REQ_IMPORT) {
                InputStream in = host.resolver().openInputStream(uri);
                AppConfig c = ConfigRepository.parse(in);
                in.close();
                // 脱敏文件（密钥被写成 ***）不能用于恢复：直接阻断，避免把 *** 写成有效 key
                if (ConfigCodec.containsMaskedSecrets(c)) {
                    host.toast("这是脱敏文件（密钥为 ***），不能用于恢复；请使用「备份（含密钥）」的文件");
                    return;
                }
                // 导入同样走 codec 校验：非法配置不覆盖当前编辑状态
                String err = ConfigCodec.validate(c);
                if (err != null && c.enabled) {
                    host.toast("导入失败：" + err);
                    return;
                }
                host.setConfig(c);
                host.render();
                host.markDirty(); // 只载入未写入文件，必须让用户确认保存
                host.toast("已载入，记得保存");
            }
        } catch (Throwable t) {
            host.toast("操作失败：" + t.getMessage());
        }
    }

    private String exportJson(boolean mask) throws Exception {
        JSONObject o = ConfigRepository.toJson(host.config());
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

    public void share() {
        try {
            File dir = new File(host.externalFilesDir(), "exports");
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
            host.startActivity(Intent.createChooser(i, "分享配置（已脱敏）"));
        } catch (Throwable t) { host.toast("分享失败：" + t.getMessage()); }
    }
}
