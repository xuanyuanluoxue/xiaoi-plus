package plus.xiaoi.module;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * config.json 的编解码与语义（纯 JVM，无 android 依赖，可桌面单测）。
 *
 * 把原先散在 ConfigRepository 里的 parse / toJson / validate 收拢到这里，
 * 并补上三件此前缺失的事：
 *   normalize  悬空 activeProviderId / activeModelId 修正、字段去空白、模型去重
 *   migrate    读取并保留 schema 版本；未知顶层字段原样写回（前向兼容）
 *   validate   暂停（enabled=false）视为合法；检测「密钥误填成 MCP token」
 *
 * 约束：序列化顺序固定为 version/enabled/marker/markerTemplate/
 * activeProviderId/activeModelId/mcp/providers，紧凑输出，保证 round-trip 稳定。
 *
 * 主题（themeMode）不在这里：它是 App 本地 UI 偏好，只存 prefs（见 ThemeManager）。
 * 旧文件的 themeMode 字段会被忽略且不再写回，避免与 prefs 形成双源。
 */
public final class ConfigCodec {

    private ConfigCodec() {}

    private static final String DEFAULT_BASE = "https://api.deepseek.com/v1";
    private static final String DEFAULT_MODEL = "deepseek-chat";

    // ------------------------------------------------------------- 解码

    /** 解析 + 迁移 + 归一化；JSON 非法时抛异常（调用方回退默认值）。 */
    public static AppConfig fromJson(String text) throws Exception {
        Object v = Json.read(text);
        if (!(v instanceof Map)) throw new Json.Exception("not a json object");
        Map<?, ?> o = (Map<?, ?>) v;

        AppConfig c = new AppConfig();
        long ver = Json.optLong(o, "version", (long) AppConfig.VERSION);
        c.version = ver <= 0 ? AppConfig.VERSION : (int) ver;

        c.enabled = Json.optBoolean(o, "enabled", false);
        c.marker = Json.optBoolean(o, "marker", true);
        c.markerTemplate = Json.optString(o, "markerTemplate", "");
        c.activeProviderId = Json.optString(o, "activeProviderId", "");
        c.activeModelId = Json.optString(o, "activeModelId", "");

        Object mcpV = o.get("mcp");
        if (mcpV instanceof Map) {
            Map<?, ?> mcp = (Map<?, ?>) mcpV;
            c.mcpEnabled = Json.optBoolean(mcp, "enabled", false);
            c.mcpLan = Json.optBoolean(mcp, "lan", false);
            c.mcpToken = Json.optString(mcp, "token", "");
        }

        Object arrV = o.get("providers");
        if (arrV instanceof List) {
            for (Object pv : (List<?>) arrV) {
                if (!(pv instanceof Map)) continue;
                Map<?, ?> p = (Map<?, ?>) pv;
                AppConfig.Provider pr = new AppConfig.Provider();
                pr.id = Json.optString(p, "id", "");
                pr.name = Json.optString(p, "name", pr.id);
                pr.baseUrl = Json.optString(p, "baseUrl", "");
                pr.apiKey = Json.optString(p, "apiKey", "");
                Object msV = p.get("models");
                if (msV instanceof List) {
                    for (Object mv : (List<?>) msV) {
                        if (!(mv instanceof Map)) continue;
                        Map<?, ?> m = (Map<?, ?>) mv;
                        pr.models.add(new AppConfig.ModelItem(
                                Json.optString(m, "id", ""), Json.optString(m, "name", "")));
                    }
                }
                c.providers.add(pr);
            }
        }

        // 未知顶层字段原样保留
        for (Map.Entry<?, ?> e : o.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (!isKnownKey(k) && !isDeprecatedKey(k)) c.extra.put(k, e.getValue());
        }

        return normalize(c);
    }

    private static boolean isKnownKey(String k) {
        return "version".equals(k) || "enabled".equals(k) || "marker".equals(k)
                || "markerTemplate".equals(k)
                || "activeProviderId".equals(k) || "activeModelId".equals(k)
                || "mcp".equals(k) || "providers".equals(k);
    }

    /** 已废弃字段：读时忽略、写时不再输出（不能进 extra，否则会被原样写回）。 */
    private static boolean isDeprecatedKey(String k) {
        return "themeMode".equals(k);
    }

    // ------------------------------------------------------------- 归一化

    /**
     * 修正可自动修复的脏数据（就地修改并返回）：
     * - 字符串去首尾空白（模板除外，用户可能故意留空格）
     * - 服务商缺 id 时按位置生成；模型 id 去重、空 id 丢弃
     * - activeProviderId 悬空 → 第一个服务商；activeModelId 不属于当前服务商 → 其第一个模型
     */
    public static AppConfig normalize(AppConfig c) {
        if (c == null) return new AppConfig();
        c.activeProviderId = trim(c.activeProviderId);
        c.activeModelId = trim(c.activeModelId);
        c.mcpToken = trim(c.mcpToken);

        for (int i = 0; i < c.providers.size(); i++) {
            AppConfig.Provider p = c.providers.get(i);
            p.id = trim(p.id);
            if (p.id.length() == 0) p.id = "p" + i;
            p.baseUrl = trim(p.baseUrl);
            p.apiKey = trim(p.apiKey);
            p.name = trim(p.name);
            if (p.name.length() == 0) p.name = p.id;
            // 模型去重 + 空 id 丢弃
            java.util.List<AppConfig.ModelItem> kept = new java.util.ArrayList<AppConfig.ModelItem>();
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (AppConfig.ModelItem m : p.models) {
                m.id = trim(m.id);
                if (m.id.length() == 0) continue;
                if (!seen.add(m.id)) continue;
                m.name = trim(m.name);
                if (m.name.length() == 0) m.name = m.id;
                kept.add(m);
            }
            p.models.clear();
            p.models.addAll(kept);
        }

        // 活跃服务商必须存在
        if (c.providers.isEmpty()) {
            c.activeProviderId = "";
            c.activeModelId = "";
            return c;
        }
        AppConfig.Provider active = null;
        for (AppConfig.Provider p : c.providers) {
            if (p.id.equals(c.activeProviderId)) { active = p; break; }
        }
        if (active == null) {
            active = c.providers.get(0);
            c.activeProviderId = active.id;
        }
        // 活跃模型必须属于活跃服务商
        boolean found = false;
        for (AppConfig.ModelItem m : active.models) {
            if (m.id.equals(c.activeModelId)) { found = true; break; }
        }
        if (!found) {
            c.activeModelId = active.models.isEmpty() ? "" : active.models.get(0).id;
        }
        return c;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    // ------------------------------------------------------------- 编码

    /** 紧凑序列化（固定字段顺序，等价旧 org.json 输出）。 */
    public static String toJson(AppConfig c) {
        Map<String, Object> o = Json.obj();
        o.put("version", (long) (c.version > 0 ? c.version : AppConfig.VERSION));
        o.put("enabled", c.enabled);
        o.put("marker", c.marker);
        o.put("markerTemplate", c.markerTemplate == null ? "" : c.markerTemplate);
        o.put("activeProviderId", c.activeProviderId == null ? "" : c.activeProviderId);
        o.put("activeModelId", c.activeModelId == null ? "" : c.activeModelId);

        Map<String, Object> mcp = Json.obj();
        mcp.put("enabled", c.mcpEnabled);
        mcp.put("lan", c.mcpLan);
        mcp.put("token", c.mcpToken == null ? "" : c.mcpToken);
        o.put("mcp", mcp);

        List<Object> arr = Json.arr();
        for (AppConfig.Provider p : c.providers) {
            Map<String, Object> po = Json.obj();
            po.put("id", p.id == null ? "" : p.id);
            po.put("name", p.name == null ? "" : p.name);
            po.put("baseUrl", p.baseUrl == null ? "" : p.baseUrl);
            po.put("apiKey", p.apiKey == null ? "" : p.apiKey);
            List<Object> ms = Json.arr();
            for (AppConfig.ModelItem m : p.models) {
                Map<String, Object> mo = Json.obj();
                mo.put("id", m.id == null ? "" : m.id);
                mo.put("name", m.name == null ? "" : m.name);
                ms.add(mo);
            }
            po.put("models", ms);
            arr.add(po);
        }
        o.put("providers", arr);

        // 未知字段原样写回（不覆盖已知键）
        for (Map.Entry<String, Object> e : c.extra.entrySet()) {
            if (!o.containsKey(e.getKey())) o.put(e.getKey(), e.getValue());
        }
        return Json.write(o);
    }

    // ------------------------------------------------------------- 校验

    /** @return 错误文案；null = 通过。暂停状态（enabled=false）视为合法。 */
    public static String validate(AppConfig c) {
        if (c == null) return "配置为空";
        // MCP 与模型重定向相互独立：暂停模型时桥仍可运行，因此这条校验放在暂停判断之前。
        String token = c.mcpToken == null ? "" : c.mcpToken.trim();
        if (c.mcpEnabled && c.mcpLan && token.length() == 0) {
            return "局域网访问必须设置 MCP 令牌（留空会让同一 Wi-Fi 下的设备直接控制小爱）";
        }
        if (!c.enabled) return null;
        AppConfig.Provider p = c.activeProvider();
        AppConfig.ModelItem m = c.activeModel();
        if (p == null || p.id == null || p.id.length() == 0) return "没有可用的服务商";
        if (m == null) return "没有可用的模型";
        String base = p.baseUrl == null ? "" : p.baseUrl.trim();
        if (!(base.startsWith("http://") || base.startsWith("https://"))) return "接口地址必须是 http(s)";
        if (m.id == null || m.id.trim().length() == 0) return "模型名不能为空";
        String key = p.apiKey == null ? "" : p.apiKey.trim();
        if (key.length() == 0) return "密钥不能为空";
        // 防呆：把 MCP token 误填进密钥是最常见的复制错误
        if (token.length() > 0 && key.equals(token)) return "密钥填成了 MCP Token（复制错了字段）";
        return null;
    }

    /** 是否是脱敏导出（密钥/token 为 ***）：这种文件只能看，不能用于恢复。 */
    public static boolean containsMaskedSecrets(AppConfig c) {
        if (c == null) return false;
        if ("***".equals(trim(c.mcpToken))) return true;
        for (AppConfig.Provider p : c.providers) {
            if (p != null && "***".equals(trim(p.apiKey))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------- 默认值 / 迁移

    /**
     * 生成默认配置；优先沿用 v1 prefs 里的端点/模型/密钥（升级不丢配置）。
     */
    public static AppConfig defaults(String oldBaseUrl, String oldModel, String oldApiKey,
                                     boolean marker, boolean bridge, String token) {
        AppConfig c = new AppConfig();
        c.enabled = false;
        c.marker = marker;
        c.mcpEnabled = bridge;
        c.mcpToken = token == null ? "" : token;

        String base = trim(oldBaseUrl);
        String model = trim(oldModel);
        String key = trim(oldApiKey);
        if (base.length() == 0) base = DEFAULT_BASE;
        if (model.length() == 0) model = DEFAULT_MODEL;

        AppConfig.Provider p = new AppConfig.Provider("deepseek", "DeepSeek", base, key);
        p.models.add(new AppConfig.ModelItem(model, model));
        if (!DEFAULT_MODEL.equals(model)) {
            p.models.add(new AppConfig.ModelItem(DEFAULT_MODEL, "DeepSeek Chat"));
        }
        c.providers.add(p);
        c.activeProviderId = "deepseek";
        c.activeModelId = model;
        return c;
    }

    /** 从输入流读取全文后解析（供 ConfigRepository 使用）。 */
    public static AppConfig fromStream(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return fromJson(new String(out.toByteArray(), "UTF-8"));
    }
}
