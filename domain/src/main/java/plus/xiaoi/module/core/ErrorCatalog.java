package plus.xiaoi.module.core;

/**
 * 可行动错误目录（纯 JVM，无 android 依赖）。
 *
 * <p>WP7 起所有「失败」提示都必须回答两件事：出了什么问题（title）和下一步做什么
 * （action）。这样主页、配置页、导入、日志空态就不会再共用一句「小爱未运行」，
 * 也不会把「没有令牌」「控制面没连上」「端口不可达」混成同一个原因。</p>
 *
 * <p>本类只保存文案，不读取配置/网络；调用方负责判断当前事实（token、MCP 开关、
 * 控制面快照、端口），再用 {@link #islandBlocked} 之类的纯函数选择错误码。</p>
 */
public final class ErrorCatalog {

    /** 稳定错误码：新增只能追加，不能复用旧码表达新含义。 */
    public enum Code {
        /** 小爱进程未运行，或 LSPosed 未加载模块。 */
        NO_XIAOI,
        /** 用户关闭了 MCP（能力桥）。 */
        MCP_OFF,
        /** MCP 已开启但没有令牌，数据面不会启动。 */
        NO_TOKEN,
        /** 控制面曾经连上，但超过 30 秒没有新上报。 */
        CONTROL_OFFLINE,
        /** 控制面登记了 leader，但 HTTP 数据面连不上。 */
        PORT_UNREACHABLE,
        /** 私有 config.json 解析失败，当前只读。 */
        CONFIG_CORRUPT,
        /** 导入的是脱敏文件（密钥为 ***），不能用于恢复。 */
        IMPORT_MASKED,
        /** 生成新令牌后，旧 MCP 客户端会立即 401。 */
        TOKEN_ROTATED
    }

    /** 一条错误文案：title 说明事实，action 说明可执行的下一步。 */
    public static final class Entry {
        public final Code code;
        public final String title;
        public final String action;

        Entry(Code code, String title, String action) {
            this.code = code;
            this.title = title == null ? "" : title;
            this.action = action == null ? "" : action;
        }

        /** 单行提示（应用内提示条/Toast 替代品使用）。 */
        public String message() {
            if (title.length() == 0) return action;
            if (action.length() == 0) return title;
            return title + "；" + action;
        }

        /** 多行正文（对话框使用）。 */
        public String detail() {
            if (title.length() == 0) return action;
            if (action.length() == 0) return title;
            return title + "\n" + action;
        }
    }

    private static final Entry[] ENTRIES = {
            new Entry(Code.NO_XIAOI,
                    "小爱未运行或模块未加载",
                    "打开小爱同学，并确认 LSPosed 已启用本模块后重试"),
            new Entry(Code.MCP_OFF,
                    "MCP 未开启",
                    "到配置页打开「能力桥」并保存，再重试"),
            new Entry(Code.NO_TOKEN,
                    "未设置 MCP 令牌",
                    "到配置页生成令牌并保存，否则数据面不会启动"),
            new Entry(Code.CONTROL_OFFLINE,
                    "控制面未连接",
                    "小爱已超过 30 秒未上报状态；重启小爱或点按重新通知"),
            new Entry(Code.PORT_UNREACHABLE,
                    "数据面端口不可达",
                    "确认 MCP 已开启、令牌有效，并查看日志中的端口/鉴权错误"),
            new Entry(Code.CONFIG_CORRUPT,
                    "配置文件损坏",
                    "在配置页恢复旧配置或重建默认配置；保存已被阻止"),
            new Entry(Code.IMPORT_MASKED,
                    "脱敏文件不能恢复",
                    "请改用「备份（含密钥）」导出的文件"),
            new Entry(Code.TOKEN_ROTATED,
                    "旧客户端令牌已失效",
                    "生成新令牌后，把新令牌更新到 MCP 客户端")
    };

    private ErrorCatalog() {}

    /** 按错误码取文案；未知/null 码返回通用兜底，绝不返回 null。 */
    public static Entry of(Code code) {
        if (code != null) {
            for (Entry e : ENTRIES) {
                if (e.code == code) return e;
            }
        }
        return new Entry(null, "操作失败", "查看日志后重试");
    }

    /** 单行提示。 */
    public static String message(Code code) {
        return of(code).message();
    }

    /**
     * 超级岛请求的前置判断：返回 null 表示可以继续请求。
     *
     * <p>顺序即用户可行动顺序：没有令牌 → MCP 没开 → 小爱没连 → 端口为 0 →
     * 控制面过旧。只有全部通过才发 HTTP，失败原因不会都被说成「小爱未运行」。</p>
     */
    public static Code islandBlocked(boolean tokenSet, boolean mcpEnabled,
                                     boolean controlConnected, boolean controlFresh,
                                     int port) {
        if (!tokenSet) return Code.NO_TOKEN;
        if (!mcpEnabled) return Code.MCP_OFF;
        if (!controlConnected) return Code.NO_XIAOI;
        if (port <= 0) return Code.NO_XIAOI;
        if (!controlFresh) return Code.CONTROL_OFFLINE;
        return null;
    }
}
