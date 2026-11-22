package plus.xiaoi.module;

import java.util.ArrayList;
import java.util.List;

/** v3 配置：服务商 → 模型 两级；密钥跟服务商走。 */
public final class AppConfig {

    public static final int VERSION = 3;

    /** 文件里的 schema 版本（旧版本在读取时升级；更高版本原样保留，避免静默降级）。 */
    public int version = VERSION;

    /**
     * 配置版本号。App 每次保存文件前自增；0 = 未初始化/默认骨架（骨架不写文件）。
     * target/module prefs 里的 cfgRev 只是降级缓存，不作为事实源。
     */
    public long rev = 0;

    /**
     * 仅内存标记：true = 这份配置是默认骨架（文件不存在或迁移前），不是用户的真实配置。
     * 不参与序列化；桥/同步逻辑必须拒绝用骨架覆盖真实配置。
     */
    public boolean skeleton;

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
    /**
     * 日志档位：off/brief/standard/detailed/debug。默认 standard。
     * 只影响记录粒度，不影响接口/模型；见 {@link plus.xiaoi.module.core.LogLevel}。
     */
    public String logLevel = "standard";
    public boolean mcpEnabled;
    /** true = 能力桥监听 0.0.0.0，同一局域网可用 Bearer token 直连；默认 false 仅本机。 */
    public boolean mcpLan;
    /**
     * 0.16.0 起已无独立端口：设备操作与 MCP 共用 8771，局域网由 {@link #mcpLan} 控制。
     *
     * <p>字段保留只为兼容旧 config.json（读写都保留原值），不再影响监听。</p>
     */
    public boolean agentLan;
    /**
     * true = 8771 免鉴权（可选，默认 false）。
     *
     * <p>0.16.0 起本机与局域网都不再校验 Bearer；这是测试便利选项。
     * 打开后 8771 的 `/health`、`/mcp`、`/logs`、`/stats`、`/cli` 以及转发到
     * App 进程的设备动作全部可无 token 调用。</p>
     */
    public boolean mcpNoAuth;
    /**
     * true = 允许把任意非空「密码」当局域网令牌用（不要求 32 位十六进制）。
     *
     * <p>默认 false：LAN 仍要求 32 hex。打开后可用 `test1234` 这类弱密码做
     * Bearer 鉴权，便于临时联调；风险由用户自担（见 docs/security.md）。</p>
     */
    public boolean mcpWeakToken;
    public String mcpToken = "";
    public String activeProviderId = "";
    public String activeModelId = "";
    public List<Provider> providers = new ArrayList<Provider>();

    // ------------------------------------------------------------- 提示词与记忆（P2，全部默认关）

    /** true = 把用户提示词注入小爱请求（空内容时无动作）。默认 false。 */
    public boolean promptEnabled;
    /** {@code append}（默认）或 {@code replace}；replace 会覆盖小爱原生能力描述。 */
    public String promptMode = "append";
    /** 系统提示词正文；支持 date/time/model/device 四个双花括号占位符。上限 8 KB。 */
    public String promptText = "";
    /** true = 把记忆摘要注入提示词（围栏 + 免责句）。默认 false。 */
    public boolean memoryInject;
    /** 注入多少行 outline；默认 20，范围 1~100。 */
    public int memoryInjectLines = 20;
    /** true = 允许 MCP / UI 写 MEMORY.md（写前备份）。默认 false。 */
    public boolean memoryWriteEnabled;
    /** true = 把 8771 注册进小爱的 mcp_servers.json（双向枢纽）。默认 false。 */
    public boolean inboundEnabled;

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
