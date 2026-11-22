package plus.xiaoi.module.core.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import plus.xiaoi.module.Json;

/**
 * 把本模块的 8771 端点写进小爱的「个人 MCP 服务」配置（纯 JVM 合并逻辑）。
 *
 * <p>小爱侧文件是 {@code files/mcp/mcp_servers.json}，结构为
 * {@code {"servers":[{"name","url","enabled","headers":{"Authorization"},"tool_prefix"}]}}
 * （见 {@code reverse/docs/08-internal-api.md} §4）。</p>
 *
 * <p>合并规则：只动 {@code servers} 里 {@code name == "xiaoi-plus"} 的那一条，
 * 其余条目与顶层未知键原样保留；JSON 坏了就返回 null，调用方不得写盘。</p>
 */
public final class InboundRegistration {

    /** 我们这条服务在小爱配置里的名字。 */
    public static final String NAME = "xiaoi-plus";

    private InboundRegistration() {}

    /** 写入/更新我们的条目；{@code json} 非法或根不是对象时返回 null。 */
    public static String upsert(String json, String url, String token) {
        Map<String, Object> root = parse(json);
        if (root == null) return null;
        List<Object> servers = serversOf(root);

        List<Object> out = new ArrayList<Object>();
        boolean replaced = false;
        for (Object item : servers) {
            if (isOurs(item)) {
                if (!replaced) {
                    out.add(entry(url, token));
                    replaced = true;
                }
                // 重复的同名条目只保留一条
                continue;
            }
            out.add(item);
        }
        if (!replaced) out.add(entry(url, token));
        root.put("servers", out);
        return Json.write(root);
    }

    /** 精确移除我们的条目；没有该条目时原样返回（幂等）。 */
    public static String remove(String json) {
        Map<String, Object> root = parse(json);
        if (root == null) return null;
        List<Object> servers = serversOf(root);
        List<Object> out = new ArrayList<Object>();
        boolean changed = false;
        for (Object item : servers) {
            if (isOurs(item)) {
                changed = true;
                continue;
            }
            out.add(item);
        }
        if (!changed) return Json.write(root);
        root.put("servers", out);
        return Json.write(root);
    }

    /** 当前配置里是否已经有我们的条目。 */
    public static boolean contains(String json) {
        Map<String, Object> root = parse(json);
        if (root == null) return false;
        for (Object item : serversOf(root)) {
            if (isOurs(item)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------- 内部

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        if (json == null || json.trim().length() == 0) {
            return Json.obj();
        }
        try {
            Object v = Json.read(json);
            return v instanceof Map ? (Map<String, Object>) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<Object> serversOf(Map<String, Object> root) {
        Object v = root.get("servers");
        List<Object> out = new ArrayList<Object>();
        if (v instanceof List) {
            for (Object item : (List<?>) v) out.add(item);
        }
        return out;
    }

    private static boolean isOurs(Object item) {
        if (!(item instanceof Map)) return false;
        Object name = ((Map<?, ?>) item).get("name");
        return NAME.equals(name);
    }

    private static Map<String, Object> entry(String url, String token) {
        Map<String, Object> headers = Json.obj();
        headers.put("Authorization", "Bearer " + (token == null ? "" : token));

        Map<String, Object> e = Json.obj();
        e.put("name", NAME);
        e.put("url", url == null ? "" : url);
        e.put("enabled", Boolean.TRUE);
        e.put("headers", headers);
        // 工具名已带 xiaoai_ 前缀，避免再套一层服务名前缀
        e.put("tool_prefix", Boolean.FALSE);
        return e;
    }
}
