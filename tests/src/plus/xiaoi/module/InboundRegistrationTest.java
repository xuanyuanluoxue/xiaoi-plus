package plus.xiaoi.module;

import java.util.List;
import java.util.Map;

import plus.xiaoi.module.core.mcp.InboundRegistration;

/** InboundRegistration：把 8771 写进小爱 mcp_servers.json 的纯合并逻辑。 */
public final class InboundRegistrationTest {

    private InboundRegistrationTest() {}

    public static int run() {
        empty();
        merge();
        Remove.run();
        return T.report("InboundRegistrationTest");
    }

    private static void empty() {
        String out = InboundRegistration.upsert("", "http://127.0.0.1:8771/mcp", "tok");
        Map<?, ?> root = obj(out);
        List<?> servers = (List<?>) root.get("servers");
        T.eq("空配置：生成 1 条", 1, servers.size());
        Map<?, ?> e = (Map<?, ?>) servers.get(0);
        T.eq("空配置：name", InboundRegistration.NAME, e.get("name"));
        T.eq("空配置：url", "http://127.0.0.1:8771/mcp", e.get("url"));
        T.eq("空配置：enabled", Boolean.TRUE, e.get("enabled"));
        T.eq("空配置：tool_prefix", Boolean.FALSE, e.get("tool_prefix"));
        T.eq("空配置：Bearer", "Bearer tok", ((Map<?, ?>) e.get("headers")).get("Authorization"));

        T.eq("坏 JSON 不写", null, InboundRegistration.upsert("{not json", "u", "t"));
        T.eq("根不是对象不写", null, InboundRegistration.upsert("[1,2]", "u", "t"));
        T.ok("空配置 contains=false", !InboundRegistration.contains(""));
    }

    private static void merge() {
        String base = "{\"servers\":[{\"name\":\"feishu\",\"url\":\"https://x\"}],"
                + "\"keepMe\":{\"a\":1}}";
        String once = InboundRegistration.upsert(base, "http://127.0.0.1:8771/mcp", "tok");
        Map<?, ?> root = obj(once);
        List<?> servers = (List<?>) root.get("servers");
        T.eq("合并不丢他人条目", 2, servers.size());
        T.eq("保留未知顶层键", Boolean.TRUE, ((Map<?, ?>) root.get("keepMe")).containsKey("a"));

        String twice = InboundRegistration.upsert(once, "http://127.0.0.1:8772/mcp", "tok2");
        List<?> after = (List<?>) obj(twice).get("servers");
        T.eq("重复 upsert 仍 2 条", 2, after.size());
        Map<?, ?> ours = null;
        for (Object s : after) {
            if (InboundRegistration.NAME.equals(((Map<?, ?>) s).get("name"))) ours = (Map<?, ?>) s;
        }
        T.ok("upsert 覆盖 url", ours != null
                && "http://127.0.0.1:8772/mcp".equals(ours.get("url")));
        T.ok("upsert 更新 token", ours != null
                && "Bearer tok2".equals(((Map<?, ?>) ours.get("headers")).get("Authorization")));
        T.ok("contains=true", InboundRegistration.contains(twice));
    }

    private static final class Remove {
        static void run() {
            String base = "{\"servers\":[{\"name\":\"" + InboundRegistration.NAME + "\"},"
                    + "{\"name\":\"feishu\"}]}";
            String removed = InboundRegistration.remove(base);
            List<?> servers = (List<?>) obj(removed).get("servers");
            T.eq("移除后只剩他人条目", 1, servers.size());
            T.eq("移除后他人条目保留", "feishu", ((Map<?, ?>) servers.get(0)).get("name"));
            T.ok("移除后 contains=false", !InboundRegistration.contains(removed));

            T.eq("重复移除幂等", removed, InboundRegistration.remove(removed));
            T.eq("坏 JSON 移除返回 null", null, InboundRegistration.remove("{oops"));
        }
    }

    private static Map<?, ?> obj(String json) {
        Object v = Json.read(json);
        if (!(v instanceof Map)) throw new IllegalStateException("not an object: " + json);
        return (Map<?, ?>) v;
    }
}
