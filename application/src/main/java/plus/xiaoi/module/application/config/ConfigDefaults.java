package plus.xiaoi.module.application.config;

/** 模块 prefs 中的配置默认值/降级缓存（纯 JVM）。 */
public final class ConfigDefaults {

    public final String baseUrl;
    public final String model;
    public final String apiKey;
    public final boolean marker;
    public final boolean bridge;
    public final String token;
    /** 上次写入的 cfg_rev；用于损坏重置时保持 rev 单调。 */
    public final long rev;

    public ConfigDefaults(String baseUrl, String model, String apiKey,
                          boolean marker, boolean bridge, String token, long rev) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.model = model == null ? "" : model;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.marker = marker;
        this.bridge = bridge;
        this.token = token == null ? "" : token;
        this.rev = rev > 0 ? rev : 0L;
    }
}