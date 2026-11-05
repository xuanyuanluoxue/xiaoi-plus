package plus.xiaoi.module;

import java.util.ArrayList;
import java.util.List;

/** v2 配置：服务商 → 模型 两级；密钥跟服务商走。 */
public final class AppConfig {

    public static final int VERSION = 2;

    /** 文件里的 schema 版本（读取后原样保留，避免高版本文件被静默降级）。 */
    public int version = VERSION;

    /** 未知顶层字段（前向兼容：解析不到的一律原样写回）。 */
    public final java.util.Map<String, Object> extra = new java.util.LinkedHashMap<String, Object>();

    public static final class ModelItem {
        public String id = "";
        public String name = "";

        public ModelItem() {}

        public ModelItem(String id, String name) {
            this.id = id;
            this.name = name == null || name.length() == 0 ? id : name;
        }
    }

    public static final class Provider {
        public String id = "";
        public String name = "";
        public String baseUrl = "";
        public String apiKey = "";
        public List<ModelItem> models = new ArrayList<ModelItem>();

        public Provider() {}

        public Provider(String id, String name, String baseUrl, String apiKey) {
            this.id = id;
            this.name = name;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
        }
    }

    public boolean enabled;
    public boolean marker = true;
    /** 输出前缀模板；空 = 不注入提示词前缀。支持 {model} 占位符。 */
    public String markerTemplate = "";
    public boolean mcpEnabled;
    /** true = 能力桥监听 0.0.0.0，同一局域网可用 Bearer token 直连；默认 false 仅本机。 */
    public boolean mcpLan;
    public String mcpToken = "";
    public String activeProviderId = "";
    public String activeModelId = "";
    public List<Provider> providers = new ArrayList<Provider>();

    public Provider activeProvider() {
        for (Provider p : providers) {
            if (p.id.equals(activeProviderId)) return p;
        }
        return providers.isEmpty() ? null : providers.get(0);
    }

    public ModelItem activeModel() {
        Provider p = activeProvider();
        if (p == null) return null;
        for (ModelItem m : p.models) {
            if (m.id.equals(activeModelId)) return m;
        }
        return p.models.isEmpty() ? null : p.models.get(0);
    }

    /** 当前激活服务商的密钥（判空用）。 */
    public String apiKeyOfActive() {
        Provider p = activeProvider();
        return p == null || p.apiKey == null ? "" : p.apiKey.trim();
    }

    /** 供桥/小爱使用的快照：只取当前生效的那一套。 */
    public String baseUrl() { Provider p = activeProvider(); return p == null ? "" : p.baseUrl; }
    public String apiKey()  { Provider p = activeProvider(); return p == null ? "" : p.apiKey; }
    public String model()   { ModelItem m = activeModel(); return m == null ? "" : m.id; }
}
