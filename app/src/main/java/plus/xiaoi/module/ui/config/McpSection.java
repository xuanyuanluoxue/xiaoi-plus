package plus.xiaoi.module.ui.config;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import plus.xiaoi.module.AppConfig;
import plus.xiaoi.module.AppExecutors;
import plus.xiaoi.module.Bridge;
import plus.xiaoi.module.BridgeClient;
import plus.xiaoi.module.Lan;
import plus.xiaoi.module.Mcp;
import plus.xiaoi.module.R;
import plus.xiaoi.module.core.BridgePolicy;

/**
 * MCP 板块：开关联动后的视图、令牌输入、URL/JSON 速览、复制连接信息与 Skill、端口探测。
 *
 * 不持有 Activity，也不复制配置状态：{@link Host#config()} 始终返回宿主当前的 cfg，
 * 异步完成后用 {@link Host#alive()} 判断是否还需要更新视图。
 */
public final class McpSection {

    public interface Host {
        AppConfig config();
        boolean alive();
        void toast(String msg);
    }

    private final Context ctx;
    private final Host host;
    private final View rowToken, rowMcpLan, boxMcpJson;
    private final TextView tvMcpUrl, tvMcpJson;
    private final EditText etToken;

    /** 实际桥端口（控制面未上报时保留 8771 提示值）；用于复制连接信息与 Skill。 */
    private volatile int bridgePort = Bridge.PORT_START;
    private long lastPortProbeAt;

    public McpSection(Context ctx, View root, Host host) {
        this.ctx = ctx;
        this.host = host;
        rowToken = root.findViewById(R.id.row_token);
        rowMcpLan = root.findViewById(R.id.row_mcp_lan);
        boxMcpJson = root.findViewById(R.id.box_mcp_json);
        tvMcpUrl = (TextView) root.findViewById(R.id.tv_mcp_url);
        tvMcpJson = (TextView) root.findViewById(R.id.tv_mcp_json);
        etToken = (EditText) root.findViewById(R.id.et_token);
    }

    public EditText tokenView() {
        return etToken;
    }

    public String token() {
        return etToken.getText().toString().trim();
    }

    public void setToken(String token) {
        etToken.setText(token);
    }

    public void render() {
        AppConfig cfg = host.config();
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

    /** 后台探一次真实桥端口；10 秒节流，失败保留默认 8771。 */
    public void refreshPort() {
        AppConfig cfg = host.config();
        if (!cfg.mcpEnabled) return;
        final String token = cfg.mcpToken == null ? "" : cfg.mcpToken.trim();
        if (token.length() == 0) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastPortProbeAt < 10_000L) return;
        lastPortProbeAt = now;
        AppExecutors.io(new Runnable() {
            public void run() {
                int p = BridgeClient.discover(token);
                if (p <= 0) return;
                bridgePort = p;
                AppExecutors.ui(new Runnable() {
                    public void run() {
                        if (host.alive() && tvMcpUrl != null) tvMcpUrl.setText(mcpUrlHint());
                    }
                });
            }
        });
    }

    public void resetPortProbeThrottle() {
        lastPortProbeAt = 0;
    }

    private String localMcpUrl() {
        return "http://127.0.0.1:" + bridgePort + "/mcp";
    }

    /** 配置里开了局域网，但 token 不足 32 hex 时实际回落 loopback（不改 BridgePolicy）。 */
    private static boolean lanEffective(AppConfig cfg) {
        return cfg.mcpLan && BridgePolicy.tokenStrongEnoughForLan(cfg.mcpToken);
    }

    /** 展示用：局域网模式同时给出局域网与本机地址；没连 Wi-Fi 时明确提示。 */
    private String mcpUrlHint() {
        AppConfig cfg = host.config();
        if (!cfg.mcpLan) return localMcpUrl();
        if (!BridgePolicy.tokenStrongEnoughForLan(cfg.mcpToken)) {
            return "令牌不足 32 位，当前实际仅本机\n" + localMcpUrl();
        }
        String lan = Lan.mcpUrl(bridgePort, true);
        if (lan.contains("127.0.0.1")) {
            return "未检测到局域网 IPv4（连上 Wi-Fi 后重进本页）\n" + localMcpUrl();
        }
        return lan + "\n" + localMcpUrl() + "（本机）";
    }

    /**
     * 终端风格的 MCP 字段速览：让用户一眼看懂每个字段是什么、现在是什么值。
     * 明确标注这是「config.json 里的期望配置」；token 只显示是否设置，不回显明文。
     */
    private String mcpJsonSummary() {
        AppConfig cfg = host.config();
        int port = bridgePort;
        boolean tokenSet = cfg.mcpToken != null && cfg.mcpToken.trim().length() > 0;
        boolean lanEffective = lanEffective(cfg);
        String enabled = cfg.enabled ? "true" : "false";
        String model = cfg.model().length() == 0 ? "未配置" : cfg.model();
        String endpoint = lanEffective ? Lan.mcpUrl(port, true) : localMcpUrl();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"source\":    \"config.json（期望配置）\",\n");
        sb.append("  \"endpoint\":  \"").append(endpoint).append("\",\n");
        sb.append("  \"auth\":      \"Bearer ").append(tokenSet ? "<已设置>" : "<未设置，接口将拒绝请求>").append("\",\n");
        sb.append("  \"protocol\":  \"JSON-RPC 2.0 (MCP 2025-03-26)\",\n");
        sb.append("  \"model\":     \"").append(model).append("\",\n");
        sb.append("  \"enabled\":   ").append(enabled).append(",\n");
        sb.append("  \"lan\":       ").append(cfg.mcpLan ? "true" : "false").append(",\n");
        sb.append("  \"tools\":     \"").append(Mcp.toolNames(" · ")).append("\",\n");
        sb.append("  \"bind\":      \"").append(lanEffective
                ? "0.0.0.0（同一局域网可访问，必须带 Bearer token）"
                : (cfg.mcpLan
                    ? "127.0.0.1（令牌不足 32 位，当前实际仅本机）"
                    : "127.0.0.1（仅本机可访问）")).append("\"\n");
        sb.append("}");
        return sb.toString();
    }

    public void copyMcp() {
        AppConfig cfg = host.config();
        String token = cfg.mcpToken == null ? "" : cfg.mcpToken;
        String local = localMcpUrl();
        boolean lanOn = lanEffective(cfg);
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
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("xiaoi mcp", txt.toString()));
        host.toast("已复制 MCP 连接信息");
    }

    public void copySkill() {
        AppConfig cfg = host.config();
        String local = localMcpUrl();
        boolean lanOn = lanEffective(cfg);
        String url = Lan.mcpUrl(bridgePort, true);
        if (!lanOn || url.contains("127.0.0.1")) url = local;
        String pre = lanOn && !url.equals(local)
                ? "前置：手机与电脑在同一 Wi-Fi，无需 adb forward\n"
                : "前置：adb forward tcp:" + bridgePort + " tcp:" + bridgePort + "\n";
        String skill = "# 小爱同学plus MCP\n\n"
                + pre
                + "端点：" + url + "（streamable-http，Bearer token）\n\n"
                + "工具：\n" + Mcp.toolListText();
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("xiaoi skill", skill));
        host.toast("已复制 Skill 文本");
    }
}
