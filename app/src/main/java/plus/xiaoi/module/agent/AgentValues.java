package plus.xiaoi.module.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 参数/schema 的防御性拷贝（纯 JVM）。
 *
 * <p>ActionDescriptor / ActionRequest 对外只暴露不可变集合：构造时深拷贝并包一层
 * unmodifiable，避免调用方拿到私有字段后原地修改 schema 或 args。</p>
 */
final class AgentValues {

    private AgentValues() {}

    static Map<String, Object> map(Object v) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (v instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (e.getKey() == null) continue;
                out.put(String.valueOf(e.getKey()), deep(e.getValue()));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    static List<Object> list(Object v) {
        List<Object> out = new ArrayList<Object>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) out.add(deep(o));
        }
        return Collections.unmodifiableList(out);
    }

    static List<String> strings(Object v) {
        List<String> out = new ArrayList<String>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o != null) out.add(String.valueOf(o));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** 深拷贝：Map / List 递归，其余标量原样返回。 */
    static Object deep(Object v) {
        if (v instanceof Map) return map(v);
        if (v instanceof List) return list(v);
        return v;
    }

    static String text(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
