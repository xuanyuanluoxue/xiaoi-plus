package plus.xiaoi.module;

import java.util.List;
import java.util.Map;

/** Json 解析器与写出器的黄金串测试（含恶意输入）。 */
public final class JsonTest {

    private JsonTest() {}

    public static int run() {
        // ------------------------------------------------------------ 解析：基本类型
        eq("read int", Long.valueOf(1L), Json.read("1"));
        eq("read negative int", Long.valueOf(-5L), Json.read("-5"));
        eq("read double", Double.valueOf(1.5), Json.read("1.5"));
        eq("read exponent", Double.valueOf(1000.0), Json.read("1e3"));
        eq("read string", "s", Json.read("\"s\""));
        eq("read true", Boolean.TRUE, Json.read("true"));
        eq("read false", Boolean.FALSE, Json.read("false"));
        eq("read null", null, Json.read("null"));
        eq("read empty array", Json.arr(), Json.read("[]"));
        eq("read empty object", Json.obj(), Json.read("{}"));
        eq("read whitespace around", Long.valueOf(1L), Json.read("  1 \t\n"));

        // ------------------------------------------------------------ 解析：嵌套
        Object v = Json.read("{\"a\":[1,{\"b\":null}],\"c\":\"中\"}");
        ok("nested is map", v instanceof Map);
        Map<?, ?> m = (Map<?, ?>) v;
        List<?> a = (List<?>) m.get("a");
        eq("nested list size", 2, a.size());
        eq("nested list[0]", Long.valueOf(1L), a.get(0));
        eq("nested inner null", null, ((Map<?, ?>) a.get(1)).get("b"));
        eq("nested unicode", "中", m.get("c"));

        // ------------------------------------------------------------ 解析：转义
        eq("escape quote", "a\"b", Json.read("\"a\\\"b\""));
        eq("escape backslash", "a\\b", Json.read("\"a\\\\b\""));
        eq("escape newline", "a\nb", Json.read("\"a\\nb\""));
        eq("escape u4e2d", "中", Json.read("\"\\u4e2d\""));
        eq("escape slash", "/", Json.read("\"\\/\""));
        eq("surrogate pair", "𝄞", Json.read("\"\\ud834\\udd1e\""));

        // ------------------------------------------------------------ 解析：宽松与覆盖
        eq("duplicate key last wins", Long.valueOf(2L),
                ((Map<?, ?>) Json.read("{\"a\":1,\"a\":2}")).get("a"));
        eq("leading zero lenient", Long.valueOf(1L), Json.read("01"));
        eq("plus sign lenient", Long.valueOf(1L), Json.read("+1"));

        // ------------------------------------------------------------ 解析：必须抛
        throwsIt("empty input", new Runnable() { public void run() { Json.read(""); }});
        throwsIt("unterminated string", new Runnable() { public void run() { Json.read("\"abc"); }});
        throwsIt("trailing garbage", new Runnable() { public void run() { Json.read("{\"a\":1} x"); }});
        throwsIt("trailing comma array", new Runnable() { public void run() { Json.read("[1,]"); }});
        throwsIt("missing colon", new Runnable() { public void run() { Json.read("{\"a\" 1}"); }});
        throwsIt("bad literal", new Runnable() { public void run() { Json.read("nul"); }});
        throwsIt("unterminated array", new Runnable() { public void run() { Json.read("[1,2"); }});
        throwsIt("bad escape", new Runnable() { public void run() { Json.read("\"\\x\""); }});
        throwsIt("bad u escape", new Runnable() { public void run() { Json.read("\"\\uZZZZ\""); }});
        throwsIt("depth limit", new Runnable() { public void run() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) sb.append('[');
            for (int i = 0; i < 100; i++) sb.append(']');
            Json.read(sb.toString());
        }});
        throwsIt("bare number in object", new Runnable() { public void run() { Json.read("{1:2}"); }});

        // ------------------------------------------------------------ 写出：黄金串
        Map<String, Object> o = Json.obj();
        o.put("a", Long.valueOf(1));
        o.put("b", Boolean.TRUE);
        o.put("c", null);
        o.put("d", "中");
        eq("write order+primitives", "{\"a\":1,\"b\":true,\"c\":null,\"d\":\"中\"}", Json.write(o));

        eq("write escape quote", "\"a\\\"b\"", Json.write("a\"b"));
        eq("write escape backslash", "\"a\\\\b\"", Json.write("a\\b"));
        eq("write escape newline", "\"a\\nb\"", Json.write("a\nb"));
        eq("write control char", "\"a\\u0001b\"", Json.write("a\u0001b"));
        eq("write del raw", "\"a\u007fb\"", Json.write("a\u007fb"));
        eq("write integer", "5", Json.write(Integer.valueOf(5)));
        eq("write double", "1.0", Json.write(Double.valueOf(1.0)));
        eq("write empty array", "[]", Json.write(Json.arr()));
        eq("write empty object", "{}", Json.write(Json.obj()));

        List<Object> arr = Json.arr();
        arr.add("x");
        arr.add(Long.valueOf(2));
        eq("write array", "[\"x\",2]", Json.write(arr));

        // ------------------------------------------------------------ 往返
        String doc = "{\"k\":[1,2.5,\"s\",true,null],\"中\":\"\\u4e2d\"}";
        // 写出器不转义非 ASCII（与 org.json 一致），输入里的 unicode 转义会被归一化成原字符
        eq("round trip", "{\"k\":[1,2.5,\"s\",true,null],\"中\":\"中\"}", Json.write(Json.read(doc)));

        // ------------------------------------------------------------ opt* 语义
        Map<String, Object> p = Json.obj();
        p.put("s", "v");
        p.put("n", Long.valueOf(42));
        p.put("b", Boolean.TRUE);
        p.put("t", Boolean.FALSE);
        eq("optString string", "v", Json.optString(p, "s", "d"));
        eq("optString number coerced", "42", Json.optString(p, "n", "d"));
        eq("optString missing", "d", Json.optString(p, "nope", "d"));
        eq("optString null map", "d", Json.optString(null, "s", "d"));
        eq("optBoolean true", true, Json.optBoolean(p, "b", false));
        eq("optBoolean string true", true, Json.optBoolean(map("x", "true"), "x", false));
        eq("optBoolean string false", false, Json.optBoolean(map("x", "false"), "x", true));
        eq("optBoolean default", true, Json.optBoolean(p, "nope", true));
        eq("optLong number", 42L, Json.optLong(p, "n", 0));
        eq("optLong string", 7L, Json.optLong(map("x", " 7 "), "x", 0));
        eq("optLong default", 5L, Json.optLong(p, "nope", 5));
        eq("has", true, Json.has(p, "s"));
        eq("has missing", false, Json.has(p, "nope"));
        eq("has null map", false, Json.has(null, "s"));

        return T.report("JsonTest");
    }

    private static Map<String, Object> map(String k, Object v) {
        Map<String, Object> m = Json.obj();
        m.put(k, v);
        return m;
    }

    private static void ok(String n, boolean c) { T.ok(n, c); }
    private static void eq(String n, Object e, Object a) { T.eq(n, e, a); }
    private static void throwsIt(String n, Runnable r) { T.throwsIt(n, r); }
}
