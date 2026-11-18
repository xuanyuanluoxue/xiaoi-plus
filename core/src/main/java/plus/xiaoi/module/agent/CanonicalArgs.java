package plus.xiaoi.module.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import plus.xiaoi.module.Hash;
import plus.xiaoi.module.Json;

/**
 * 动作参数的规范化哈希（纯 JVM）。
 *
 * <p>确认 token 与幂等键都绑定“参数哈希”，因此同一组参数不能因为 Map 的迭代顺序
 * 不同而算出不同结果。规则：对象键按字典序递归排序，数组保持顺序，标量用
 * {@link Json#write(Object)} 的稳定文本；{@code token} / {@code confirmToken} /
 * {@code apiKey} 等敏感键不参与哈希。</p>
 *
 * <p>只输出哈希，不输出参数正文；审计里建议用 {@link #shortHash(Map)}。</p>
 */
public final class CanonicalArgs {

    /** 不参与哈希的敏感键（大小写不敏感）。 */
    private static final String[] SENSITIVE = {
            "token", "confirmtoken", "confirm_token", "apikey", "api_key",
            "authorization", "password", "secret"
    };

    private CanonicalArgs() {}

    /** 是否为不参与哈希的敏感参数名。 */
    public static boolean isSensitive(String key) {
        if (key == null) return false;
        String k = key.toLowerCase(java.util.Locale.US);
        for (String s : SENSITIVE) {
            if (s.equals(k)) return true;
        }
        return false;
    }

    /** 规范化文本：键排序、递归、排除敏感键；null 视作空参数。 */
    public static String canonical(Map<String, Object> args) {
        StringBuilder sb = new StringBuilder();
        append(sb, args == null ? new java.util.LinkedHashMap<String, Object>() : args, 0);
        return sb.toString();
    }

    /** 完整 SHA-256（64 个十六进制字符）；参数为空时也是稳定值。 */
    public static String hash(Map<String, Object> args) {
        return Hash.sha256Hex(canonical(args));
    }

    /** 审计用短哈希（前 16 个十六进制字符），足够关联同一组参数。 */
    public static String shortHash(Map<String, Object> args) {
        String h = hash(args);
        return h.length() > 16 ? h.substring(0, 16) : h;
    }

    private static void append(StringBuilder sb, Object value, int depth) {
        if (depth > 64) {
            sb.append("\"<depth-limit>\"");
            return;
        }
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            List<String> keys = new ArrayList<String>();
            for (Object k : map.keySet()) {
                if (k == null) continue;
                String key = String.valueOf(k);
                if (!isSensitive(key)) keys.add(key);
            }
            Collections.sort(keys);
            sb.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                String key = keys.get(i);
                sb.append(Json.write(key)).append(':');
                append(sb, map.get(key), depth + 1);
            }
            sb.append('}');
            return;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                append(sb, list.get(i), depth + 1);
            }
            sb.append(']');
            return;
        }
        sb.append(Json.write(value));
    }
}
