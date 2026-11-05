package plus.xiaoi.module;

import android.content.Context;

/**
 * 最近一次模型校验结果的缓存（App prefs）。
 *
 * 只是缓存，不是事实源：键是「端点 + 密钥 + 模型」的哈希指纹，
 * 配置一变指纹就失配，结论自动作废。主页借它显示与配置页一致的状态，
 * 避免出现「配置页说密钥无效、主页说已启用」。
 */
public final class StatusCache {

    private static final String KEY_FP = "probe_fp";
    private static final String KEY_RESULT = "probe_result";
    private static final String KEY_AT = "probe_at";
    /** 结论最多信 10 分钟；超过则回到「已启用/待校验」，避免拿旧结论当现状。 */
    private static final long TTL_MS = 10 * 60 * 1000L;

    private StatusCache() {}

    public static String fingerprint(String baseUrl, String apiKey, String model) {
        return Hash.sha256Hex((baseUrl == null ? "" : baseUrl.trim()) + "|"
                + (apiKey == null ? "" : apiKey.trim()) + "|"
                + (model == null ? "" : model.trim()));
    }

    public static void saveProbe(Context ctx, String fingerprint, StatusResolver.Probe probe) {
        if (ctx == null || fingerprint == null || fingerprint.length() == 0 || probe == null) return;
        try {
            ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_FP, fingerprint)
                    .putString(KEY_RESULT, probe.name())
                    .putLong(KEY_AT, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 指纹匹配才返回缓存结论；否则 UNKNOWN（配置已变，旧结论不可信）。 */
    public static StatusResolver.Probe probe(Context ctx, String fingerprint) {
        if (ctx == null || fingerprint == null || fingerprint.length() == 0) {
            return StatusResolver.Probe.UNKNOWN;
        }
        try {
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
            String fp = p.getString(KEY_FP, "");
            if (!fingerprint.equals(fp)) return StatusResolver.Probe.UNKNOWN;
            long at = p.getLong(KEY_AT, 0L);
            if (at <= 0 || System.currentTimeMillis() - at > TTL_MS) {
                return StatusResolver.Probe.UNKNOWN;
            }
            String v = p.getString(KEY_RESULT, "");
            for (StatusResolver.Probe probe : StatusResolver.Probe.values()) {
                if (probe.name().equals(v)) return probe;
            }
        } catch (Throwable ignored) {
        }
        return StatusResolver.Probe.UNKNOWN;
    }
}
