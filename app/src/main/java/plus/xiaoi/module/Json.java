package plus.xiaoi.module;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 极小 JSON 实现（纯 JVM，无 android 依赖）。
 *
 * 为什么不用 org.json：它是 Android 平台类，桌面 JVM 上没有；
 * 为了让 Mcp / Http / Logs 这些纯逻辑层能脱离设备做单元测试，这里手写一份
 * 解析器 + 写出器。写出格式与 Android org.json 的紧凑输出逐字节一致
 * （Map 保插入序、无空白、小于 0x20 的字符转六位 u 转义、非 ASCII 原样输出），
 * 由 tests/ 的黄金串断言与真机契约基线共同保证。
 *
 * 值模型：null / Boolean / String / Long / Double / Map&lt;String,Object&gt; / List&lt;Object&gt;。
 * 解析规则：标准 JSON；重复 key 后者覆盖；嵌套深度上限 64（防恶意深嵌套栈溢出）；
 * 结尾多余内容视为解析错误；字符串里未转义的控制字符按 org.json 的宽松行为接受。
 */
public final class Json {

    private Json() {}

    /** 解析失败。名字避开 java.lang.NullPointerException 一类的常见误捕。 */
    public static final class Exception extends RuntimeException {
        public Exception(String msg) { super(msg); }
    }

    public static Map<String, Object> obj() { return new LinkedHashMap<String, Object>(); }

    public static List<Object> arr() { return new ArrayList<Object>(); }

    // ------------------------------------------------------------- 读取

    /** 解析一个 JSON 值；输入不合法时抛 {@link Exception}。 */
    public static Object read(String s) {
        Parser p = new Parser(s);
        Object v = p.parseValue(0);
        p.skipWs();
        if (!p.eof()) throw new Exception("trailing garbage at " + p.pos);
        return v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s == null ? "" : s; }

        boolean eof() { return pos >= s.length(); }

        char peek() {
            if (eof()) throw new Exception("unexpected end at " + pos);
            return s.charAt(pos);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object parseValue(int depth) {
            if (depth > 64) throw new Exception("depth limit at " + pos);
            skipWs();
            char c = peek();
            switch (c) {
                case '{': return parseObject(depth);
                case '[': return parseArray(depth);
                case '"': return parseString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return parseNumber();
            }
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new Exception("bad literal at " + pos);
            pos += lit.length();
        }

        private Map<String, Object> parseObject(int depth) {
            pos++; // '{'
            Map<String, Object> m = obj();
            skipWs();
            if (!eof() && peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                if (peek() != '"') throw new Exception("expected key at " + pos);
                String k = parseString();
                skipWs();
                if (peek() != ':') throw new Exception("expected ':' at " + pos);
                pos++;
                m.put(k, parseValue(depth + 1));
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return m; }
                throw new Exception("expected ',' or '}' at " + pos);
            }
        }

        private List<Object> parseArray(int depth) {
            pos++; // '['
            List<Object> l = arr();
            skipWs();
            if (!eof() && peek() == ']') { pos++; return l; }
            while (true) {
                l.add(parseValue(depth + 1));
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return l; }
                throw new Exception("expected ',' or ']' at " + pos);
            }
        }

        private String parseString() {
            pos++; // '"'
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek(); // EOF 会抛，等价于「字符串未闭合」
                if (c == '"') { pos++; return sb.toString(); }
                if (c == '\\') {
                    pos++;
                    char e = peek();
                    pos++;
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > s.length()) throw new Exception("bad \\u at " + pos);
                            int hex = 0;
                            for (int i = 0; i < 4; i++) {
                                char h = s.charAt(pos + i);
                                hex <<= 4;
                                if (h >= '0' && h <= '9') hex |= (h - '0');
                                else if (h >= 'a' && h <= 'f') hex |= (h - 'a' + 10);
                                else if (h >= 'A' && h <= 'F') hex |= (h - 'A' + 10);
                                else throw new Exception("bad \\u digit at " + (pos + i));
                            }
                            pos += 4;
                            sb.append((char) hex);
                            break;
                        default:
                            throw new Exception("bad escape at " + (pos - 1));
                    }
                } else {
                    sb.append(c);
                    pos++;
                }
            }
        }

        private Object parseNumber() {
            int start = pos;
            while (!eof()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') pos++;
                else break;
            }
            if (pos == start) throw new Exception("bad value at " + start);
            String n = s.substring(start, pos);
            try {
                if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
                    return Long.valueOf(Long.parseLong(n));
                }
                return Double.valueOf(Double.parseDouble(n));
            } catch (NumberFormatException t) {
                throw new Exception("bad number at " + start);
            }
        }
    }

    // ------------------------------------------------------------- 写出

    /** 序列化为紧凑 JSON（无空白、插入序、非 ASCII 原样）。 */
    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String) { writeString(sb, (String) v); return; }
        if (v instanceof Boolean) { sb.append(v.toString()); return; }
        if (v instanceof Number) { sb.append(v.toString()); return; }
        if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, o);
            }
            sb.append(']');
            return;
        }
        writeString(sb, String.valueOf(v));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------- 取值（语义对齐 org.json 的 opt*）

    public static Object opt(Map<?, ?> o, String k) {
        return o == null ? null : o.get(k);
    }

    public static boolean has(Map<?, ?> o, String k) {
        return o != null && o.containsKey(k);
    }

    /** String 直取；Boolean/Number 按 org.json 的行为强转；其余（对象/数组/null）返回默认值。 */
    public static String optString(Map<?, ?> o, String k, String def) {
        Object v = opt(o, k);
        if (v == null) return def;
        if (v instanceof String) return (String) v;
        if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
        return def;
    }

    public static boolean optBoolean(Map<?, ?> o, String k, boolean def) {
        Object v = opt(o, k);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) {
            String s = (String) v;
            if ("true".equals(s)) return true;
            if ("false".equals(s)) return false;
        }
        return def;
    }

    public static long optLong(Map<?, ?> o, String k, long def) {
        Object v = opt(o, k);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong(((String) v).trim()); } catch (Throwable ignored) {}
        }
        return def;
    }
}
