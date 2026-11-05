package plus.xiaoi.module;

import android.content.Context;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 配置快照。
 * 设置页写本模块 SharedPreferences；小爱进程用 XSharedPreferences 跨进程读，
 * 读到时立刻拷成不可变快照，避免后续重复 IO。
 */
public final class Config {
    public static final String PREFS = Prefs.NAME;
    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_API_KEY = "api" + "_key";
    public static final String KEY_MODEL = "model";
    public static final String KEY_MARKER = "marker";
    public static final String KEY_MARKER_TPL = "marker_tpl";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_BRIDGE = "bridge";
    public static final String KEY_MCP_LAN = "mcp_lan";
    public static final String KEY_TOKEN = "tok" + "en";

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final boolean marker;
    /** 输出前缀模板；空 = 不注入提示词前缀。{model} 占位符替换为当前模型名。 */
    private final String markerTemplate;
    /** 显式启用位：false = 暂停重定向（保留配置），区别于「未配置」。 */
    private final boolean enabled;
    private final boolean bridge;
    /** true = 能力桥监听 0.0.0.0（局域网可访问）；仍需非空 token。 */
    private final boolean lan;
    private final String token;

    /** 供能力桥热更新使用；所有字段自动归一化为非 null。 */
    public static Config create(String baseUrl, String apiKey, String model, boolean marker,
                                boolean bridge, String token) {
        return create(baseUrl, apiKey, model, marker, bridge, token, null);
    }

    public static Config create(String baseUrl, String apiKey, String model, boolean marker,
                                boolean bridge, String token, String markerTemplate) {
        return new Config(baseUrl, apiKey, model, marker, bridge, token, markerTemplate, true, false);
    }

    public static Config create(String baseUrl, String apiKey, String model, boolean marker,
                                boolean bridge, String token, String markerTemplate,
                                boolean enabled) {
        return new Config(baseUrl, apiKey, model, marker, bridge, token, markerTemplate, enabled, false);
    }

    public static Config create(String baseUrl, String apiKey, String model, boolean marker,
                                boolean bridge, String token, String markerTemplate,
                                boolean enabled, boolean lan) {
        return new Config(baseUrl, apiKey, model, marker, bridge, token, markerTemplate, enabled, lan);
    }

    private Config(String baseUrl, String apiKey, String model, boolean marker, boolean bridge,
                   String token, String markerTemplate, boolean enabled, boolean lan) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null ? "" : model;
        this.marker = marker;
        this.bridge = bridge;
        this.token = token == null ? "" : token;
        this.markerTemplate = markerTemplate == null ? "" : markerTemplate;
        this.enabled = enabled;
        this.lan = lan;
    }

    /** 跨进程热读缓存：prefs 文件 mtime 变了就重解析，最多 800ms 跟上。 */
    private static volatile Config sCurrent;
    private static volatile long sStamp = Long.MIN_VALUE;
    private static volatile long sCheckedAt;

    public static Config current() {
        long now = System.currentTimeMillis();
        Config c = sCurrent;
        if (c != null && now - sCheckedAt < 800) return c;
        sCheckedAt = now;
        try {
            long st = stamp();
            if (c == null || st != sStamp) {
                sStamp = st;
                c = forTarget();
                sCurrent = c;
            }
        } catch (Throwable t) {
            if (c == null) {
                c = forTarget();
                sCurrent = c;
            }
        }
        return c;
    }

    public static void setCurrent(Config c) {
        if (c == null) return;
        sCurrent = c;
        try {
            sStamp = stamp();
        } catch (Throwable ignored) {
        }
    }

    private static long stamp() {
        try {
            java.io.File f = new java.io.File(
                    "/data/data/com.miui.voiceassist/shared_prefs/" + PREFS + ".xml");
            return (f.lastModified() << 20) ^ f.length();
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static Config forApp(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Config(p.getString(KEY_BASE_URL, ""), p.getString(KEY_API_KEY, ""),
                p.getString(KEY_MODEL, ""), p.getBoolean(KEY_MARKER, true),
                p.getBoolean(KEY_BRIDGE, false), p.getString(KEY_TOKEN, ""),
                p.getString(KEY_MARKER_TPL, ""), parseFlag(p.getString(KEY_ENABLED, "1")),
                p.getBoolean(KEY_MCP_LAN, false));
    }

    public static Config forTarget() {
        // 1) 首选：小爱自己的 prefs（root 写入 / 模块自写）
        String[] t = parsePrefs("/data/data/com.miui.voiceassist/shared_prefs/" + PREFS + ".xml");
        if (t != null) {
            android.util.Log.i("xiaoi+", "配置来源：小爱 prefs");
            return new Config(t[0], t[1], t[2], true, true, t[3], t[4], parseFlag(t[5]),
                    "1".equals(t[6]));
        }
        // 2) 次选：模块自己的 prefs（XSharedPreferences，可能被 SELinux 拦）
        try {
            XSharedPreferences p = new XSharedPreferences("plus.xiaoi.module", PREFS);
            p.reload();
            String b = p.getString(KEY_BASE_URL, "");
            String m = p.getString(KEY_MODEL, "");
            if (b != null && b.length() > 0 && m != null && m.length() > 0) {
                android.util.Log.i("xiaoi+", "配置来源：XSharedPreferences");
                return new Config(b, p.getString(KEY_API_KEY, ""), m,
                        p.getBoolean(KEY_MARKER, true), p.getBoolean(KEY_BRIDGE, false),
                        p.getString(KEY_TOKEN, ""), p.getString(KEY_MARKER_TPL, ""),
                        parseFlag(p.getString(KEY_ENABLED, "1")),
                        p.getBoolean(KEY_MCP_LAN, false));
            }
        } catch (Throwable ignored) {}
        android.util.Log.w("xiaoi+", "没有可用配置");
        return new Config("", "", "", true, false, "", "", false, false);
    }

    /** 解析 prefs XML，失败返回 null */
    private static String[] parsePrefs(String path) {
        java.io.FileInputStream in = null;
        try {
            java.io.File f = new java.io.File(path);
            if (!f.exists() || !f.canRead()) return null;
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            String xml = new String(buf, 0, Math.max(n, 0), "UTF-8");
            String b = pick(xml, KEY_BASE_URL);
            String m = pick(xml, KEY_MODEL);
            if (b.length() == 0 || m.length() == 0) return null;
            return new String[]{b, pick(xml, KEY_API_KEY), m, pick(xml, KEY_TOKEN),
                    pick(xml, KEY_MARKER_TPL), pick(xml, KEY_ENABLED),
                    pickBoolean(xml, KEY_MCP_LAN)};
        } catch (Throwable t) {
            android.util.Log.w("xiaoi+", "解析 prefs 失败: " + t);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    /** prefs XML 里的布尔值；缺失/无法识别按 false。 */
    private static String pickBoolean(String xml, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "<boolean name=\"" + key + "\" value=\"(true|false)\"").matcher(xml);
        return m.find() && "true".equals(m.group(1)) ? "1" : "0";
    }


    /** prefs 里的开关值：空=默认开；"0"/"false" 视为关。 */
    private static boolean parseFlag(String v) {
        if (v == null || v.length() == 0) return true;
        return !("0".equals(v) || "false".equalsIgnoreCase(v));
    }

    private static String pick(String xml, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<string name=\"" + key + "\">([^<]*)</string>").matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    public String baseUrl() { return baseUrl; }
    public String apiKey() { return apiKey; }
    public String model() { return model; }
    public boolean marker() { return marker; }

    /** 输出前缀模板；空 = 不注入。支持 {model} 占位符。 */
    public String markerTemplate() { return markerTemplate; }
    public boolean bridge() { return bridge; }

    /** true = 允许同一局域网访问能力桥（仍要求非空 Bearer token）。 */
    public boolean lan() { return lan; }

    /** 能力桥的 Bearer token；为空表示拒绝所有桥请求 */
    public String token() { return token; }

    /** 生效 = 显式开启 且 model/baseUrl 齐全；false 时保留配置但暂停重定向。 */
    public boolean enabled() {
        return enabled && model.length() > 0 && baseUrl.length() > 0;
    }

    /** 显式开关原值（不看字段是否齐全）。 */
    public boolean enabledFlag() { return enabled; }
}
