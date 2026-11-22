package plus.xiaoi.module;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import plus.xiaoi.module.PromptInjector;

/**
 * kotlinx JSON 元素的「结构反射」工具：不查类名，按样本对象自己的构造器重建。
 *
 * <p>小爱把 {@code JsonPrimitive} 混淆成短名（8.2.70 是 {@code t92.r}），构造器是
 * {@code (Object, boolean, <kind>)}；{@code JsonObject} 名字保留但构造器可能是
 * {@code (Map)} 或 {@code (Companion, Map)}。所以这里统一用「样本对象的
 * {@code getDeclaredConstructors()}」来造新值，避免硬编码签名。
 *
 * <p>放在 :platform 而不是 :app，一是 app 壳有行数上限，二是这层纯反射无业务逻辑。
 */
public final class JsonNodes {

    private JsonNodes() {
    }

    /** 既不是 Map 也不是 List 的 JsonElement（字符串/数字/布尔）。 */
    public static boolean isScalar(Object o) {
        return o != null && !(o instanceof Map) && !(o instanceof List);
    }

    /** 取 JsonPrimitive 的正文；getContent 被混淆时退回用我们自己的 JSON 解析。 */
    public static String contentOf(Object element) {
        if (element == null) return "";
        if (element instanceof String) return (String) element;
        try {
            Object v = element.getClass().getMethod("getContent").invoke(element);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {
        }
        try {
            Object v = Json.read(String.valueOf(element));
            if (v instanceof String) return (String) v;
            if (v != null) return String.valueOf(v);
        } catch (Throwable ignored) {
        }
        return String.valueOf(element);
    }

    /** 用同类元素的构造函数造一个新的字符串元素；造不出来返回 null。 */
    public static Object newStringLike(Object sample, String text) {
        String value = text == null ? "" : text;
        if (sample == null) return null;
        try {
            for (java.lang.reflect.Constructor<?> ctor : sample.getClass().getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 1 && p[0] == String.class) {
                    ctor.setAccessible(true);
                    return ctor.newInstance(value);
                }
            }
            for (java.lang.reflect.Constructor<?> ctor : sample.getClass().getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 2 && p[0] == String.class && p[1] == boolean.class) {
                    ctor.setAccessible(true);
                    return ctor.newInstance(value, Boolean.TRUE);
                }
            }
            // (Object, boolean, ...)：kotlinx JsonLiteral 的混淆形态
            for (java.lang.reflect.Constructor<?> ctor : sample.getClass().getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length < 2 || p[0] != Object.class || p[1] != boolean.class) continue;
                Object[] args = new Object[p.length];
                args[0] = value;
                args[1] = Boolean.TRUE;
                boolean ok = true;
                for (int i = 2; i < p.length; i++) {
                    Object d = defaultFor(p[i], sample);
                    if (d == null && p[i].isPrimitive()) { ok = false; break; }
                    args[i] = d;
                }
                if (!ok) continue;
                try {
                    ctor.setAccessible(true);
                    return ctor.newInstance(args);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        if (!sCtorLogged) {
            sCtorLogged = true;
            try {
                StringBuilder sb = new StringBuilder(sample.getClass().getName());
                for (java.lang.reflect.Constructor<?> c : sample.getClass().getDeclaredConstructors()) {
                    sb.append(" (").append(c.getParameterCount()).append(':');
                    for (Class<?> t : c.getParameterTypes()) sb.append(t.getSimpleName()).append(',');
                    sb.append(')');
                }
                Logs.i("字符串元素构造器: " + sb);
            } catch (Throwable ignored2) {
            }
        }
        return null;
    }

    /** 给缺失的构造参数取一个安全默认值：优先复用样本里同类型的字段。 */
    private static Object defaultFor(Class<?> type, Object sample) {
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return Integer.valueOf(0);
        if (type == long.class) return Long.valueOf(0L);
        if (sample != null) {
            for (java.lang.reflect.Field f : sample.getClass().getDeclaredFields()) {
                if (!type.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(sample);
                    if (v != null) return v;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 用同类型的构造函数把「复制后的 Map/List」变回 JsonObject/JsonArray。 */
    public static Object constructLike(Object sample, Object arg) {
        if (sample == null || arg == null) return null;
        try {
            // 1) 单参数构造（常规 Kotlin 类）
            for (java.lang.reflect.Constructor<?> ctor : sample.getClass().getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 1 && p[0].isInstance(arg)) {
                    ctor.setAccessible(true);
                    return ctor.newInstance(arg);
                }
            }
            // 2) (Companion, Map/List)：kotlinx 的 JsonObject/JsonArray 是这种形状
            Object companion = companionOf(sample);
            if (companion != null) {
                for (java.lang.reflect.Constructor<?> ctor : sample.getClass().getDeclaredConstructors()) {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length == 2 && p[0].isInstance(companion) && p[1].isInstance(arg)) {
                        ctor.setAccessible(true);
                        return ctor.newInstance(companion, arg);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 取类的 Kotlin 伴生对象实例（静态字段 {@code Companion}）；没有返回 null。 */
    private static Object companionOf(Object sample) {
        if (sample == null) return null;
        try {
            java.lang.reflect.Field f = sample.getClass().getDeclaredField("Companion");
            f.setAccessible(true);
            try {
                return f.get(null);
            } catch (Throwable ignored) {
                return f.get(sample);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 浅拷贝一个 Map，键统一转成 String。 */
    public static Map<String, Object> copyMap(Map<?, ?> src) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (src == null) return out;
        for (Map.Entry<?, ?> e : src.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /**
     * 取 JsonObject 的**可写** backing Map。
     *
     * <p>kotlinx 的 JsonObject 覆写了 {@code put} 并直接抛
     * {@code UnsupportedOperationException}，但它内部字段就是一个普通 Map。
     * 想要「原地改一个别人已经持有的请求对象」，只能改这层 backing 结构。</p>
     */
    public static Map<String, Object> backingMap(Object node) {
        if (node == null) return null;
        for (java.lang.reflect.Field f : node.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (!Map.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(node);
                if (v instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) v;
                    return m;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 取 JsonArray 的**可写** backing List；拿不到返回 null。 */
    public static List<Object> backingList(Object node) {
        if (node == null) return null;
        for (java.lang.reflect.Field f : node.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (!List.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(node);
                if (v instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> l = (List<Object>) v;
                    return l;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * content 可能是字符串、text part 数组，或带 {@code text} 的对象；
     * 返回注入后的新元素，识别不了返回 null（调用方保留原值）。
     */
    public static Object mergeContent(Object content, String block, boolean replace) {
        if (!sContentSeen) {
            sContentSeen = true;
            Logs.i("content 诊断: null=" + (content == null)
                    + " class=" + (content == null ? "-" : content.getClass().getName())
                    + " scalar=" + isScalar(content)
                    + " map=" + (content instanceof Map)
                    + " list=" + (content instanceof List));
        }
        if (isScalar(content)) {
            return newStringLike(content, PromptInjector.merge(contentOf(content), block, replace));
        }
        if (content instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) content;
            Object text = map.get("text");
            if (isScalar(text)) {
                Map<String, Object> copy = copyMap(map);
                copy.put("text", newStringLike(text,
                        PromptInjector.merge(contentOf(text), block, replace)));
                Object rebuilt = constructLike(content, copy);
                if (rebuilt != null) return rebuilt;
            }
            logContentShapeOnce(content);
            return null;
        }
        if (!(content instanceof List)) {
            logContentShapeOnce(content);
            return null;
        }
        List<?> parts = (List<?>) content;
        List<Object> out = new ArrayList<Object>();
        boolean done = false;
        for (Object raw : parts) {
            if (!done && raw instanceof Map && isScalar(((Map<?, ?>) raw).get("text"))) {
                Map<String, Object> part = copyMap((Map<?, ?>) raw);
                Object text = part.get("text");
                part.put("text", newStringLike(text,
                        PromptInjector.merge(contentOf(text), block, replace)));
                Object rebuilt = constructLike(raw, part);
                out.add(rebuilt == null ? raw : rebuilt);
                done = true;
            } else {
                out.add(raw);
            }
        }
        if (!done) return null;
        return constructLike(content, out);
    }

    /** 集合/数组参数的条数；非集合返回 0。 */
    public static int sizeOf(Object[] args, int idx) {
        if (args == null || idx >= args.length) return 0;
        Object v = args[idx];
        if (v instanceof List) return ((List<?>) v).size();
        if (v instanceof java.util.Collection) return ((java.util.Collection<?>) v).size();
        return 0;
    }

    /**
     * 工具参数的形状描述：类型、数量、前 24 个元素的名字。只对 name/id/type 这类
     * 元数据键取值且截断 40 字，绝不打印参数说明或正文。
     */
    public static String describeTools(Object[] args) {
        if (args == null || args.length < 2) return "tools=<无形参>";
        Object tools = args[1];
        if (tools == null) return "tools=null";
        StringBuilder sb = new StringBuilder("tools=").append(tools.getClass().getName());
        if (tools instanceof java.util.Collection) {
            java.util.Collection<?> col = (java.util.Collection<?>) tools;
            sb.append(" size=").append(col.size());
            int i = 0;
            for (Object t : col) {
                if (i >= 24) { sb.append(" ..."); break; }
                sb.append(" <").append(i++).append(':').append(describeTool(t)).append('>');
            }
        } else if (tools instanceof Map) {
            sb.append(" keys=").append(((Map<?, ?>) tools).keySet());
        }
        return sb.toString();
    }

    private static String describeTool(Object tool) {
        if (tool == null) return "null";
        StringBuilder sb = new StringBuilder(tool.getClass().getSimpleName());
        if (tool instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) tool;
            sb.append('{').append(map.keySet()).append('}');
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                String lower = k.toLowerCase(Locale.ROOT);
                if (!lower.contains("name") && !lower.equals("id") && !lower.contains("type")) continue;
                String v = isScalar(e.getValue())
                        ? contentOf(e.getValue())
                        : "<" + e.getValue().getClass().getSimpleName() + ">";
                if (v.length() > 40) v = v.substring(0, 40) + "…";
                sb.append(' ').append(k).append('=').append(v);
            }
            // 常见的嵌套形状：{type=function, function={name=...}}
            Object fn = map.get("function");
            if (fn instanceof Map) {
                Object n = ((Map<?, ?>) fn).get("name");
                if (isScalar(n)) sb.append(" fn=").append(contentOf(n));
            }
            return sb.toString();
        }
        // 领域对象（ToolDefinition）：直接读 getName()，这是最准的工具名。
        String name = "";
        try {
            Object v = tool.getClass().getMethod("getName").invoke(tool);
            if (v != null) name = String.valueOf(v);
        } catch (Throwable ignored) {
        }
        if (name.isEmpty()) {
            try {
                java.lang.reflect.Field f = tool.getClass().getDeclaredField("name");
                f.setAccessible(true);
                Object v = f.get(tool);
                if (v != null) name = String.valueOf(v);
            } catch (Throwable ignored) {
            }
        }
        if (!name.isEmpty()) sb.append('(').append(name).append(')');
        return sb.toString();
    }

    private static void logContentShapeOnce(Object content) {
        if (sContentLogged || content == null) return;
        sContentLogged = true;
        try {
            StringBuilder sb = new StringBuilder(content.getClass().getName());
            for (java.lang.reflect.Field f : content.getClass().getDeclaredFields()) {
                sb.append(' ').append(f.getName()).append(':').append(f.getType().getSimpleName());
            }
            Logs.i("content 结构: " + sb);
        } catch (Throwable ignored) {
        }
    }

    /** 每进程只打一次的诊断开关。 */
    private static volatile boolean sCtorLogged;
    private static volatile boolean sContentSeen;
    private static volatile boolean sContentLogged;
}
