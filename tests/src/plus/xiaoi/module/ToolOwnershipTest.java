package plus.xiaoi.module;

import java.util.List;
import java.util.Map;

import plus.xiaoi.module.agent.AgentMcp;
import plus.xiaoi.module.core.mcp.ToolOwnership;

/**
 * 单端口统一的「归属表」不变量。
 *
 * <p>8771 与 8772 各自按 {@link ToolOwnership} 决定是否转发；只要两边表和实际工具表
 * 一致，就不会出现 A→B→A 的转发环。这里在纯 JVM 层把这条不变量钉住。
 */
public final class ToolOwnershipTest {

    private ToolOwnershipTest() {}

    public static int run() {
        List<?> bridge = toolsOf(Mcp.toolsList());
        T.eq("8771 工具数", 11, bridge.size());
        for (Object o : bridge) {
            String name = nameOf(o);
            T.ok("8771 工具归属 bridge: " + name, ToolOwnership.isBridge(name));
            T.ok("8771 工具不是 app: " + name, !ToolOwnership.isApp(name));
        }

        List<?> app = toolsOf(new AgentMcp("test").toolsList());
        T.eq("8772 工具数", 6, app.size());
        for (Object o : app) {
            String name = nameOf(o);
            T.ok("8772 工具归属 app: " + name, ToolOwnership.isApp(name));
            T.ok("8772 工具不是 bridge: " + name, !ToolOwnership.isBridge(name));
        }

        for (String s : ToolOwnership.bridgeTools()) {
            T.ok("表不重叠: " + s, !ToolOwnership.isApp(s));
        }
        T.eq("未知工具无归属", null, ToolOwnership.ownerOf("xiaoai_nope"));
        T.eq("空名无归属", null, ToolOwnership.ownerOf(""));
        T.eq("null 无归属", null, ToolOwnership.ownerOf(null));

        return T.report("ToolOwnershipTest");
    }

    private static List<?> toolsOf(Map<String, Object> list) {
        Object v = list == null ? null : list.get("tools");
        return v instanceof List ? (List<?>) v : java.util.Collections.emptyList();
    }

    private static String nameOf(Object tool) {
        return tool instanceof Map ? Json.optString((Map<?, ?>) tool, "name", "") : "";
    }
}
