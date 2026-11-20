package plus.xiaoi.module.core;

import java.util.regex.Pattern;

/**
 * 日志脱敏（纯 JVM）：键值形式的 apiKey/token/authorization 与 Bearer 头。
 * 所有入库/上报的日志行都应先经过这里；调用方仍必须避免主动写正文。
 */
public final class Redact {

    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(api[_-]?key|token|authorization)(\\s*[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"',}&;]+)");

    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer)\\s+[^\\s\"',}&;]+");

    private Redact() {}

    public static String redact(String line) {
        if (line == null) return "";
        // 先处理 Bearer：否则 "Authorization: Bearer xxx" 会先被键值规则吃掉 Bearer 这个词，
        // 把真正的 token 留在后面。
        String out = BEARER.matcher(line).replaceAll("$1 ***");
        return KEY_VALUE.matcher(out).replaceAll("$1$2***");
    }

    /** 单行摘要：脱敏、折叠空白、截断；null/空返回空串。 */
    public static String summarize(String value, int max) {
        if (value == null) return "";
        String s = redact(value).replace('\n', ' ').replace('\r', ' ').trim();
        while (s.contains("  ")) s = s.replace("  ", " ");
        if (max > 0 && s.length() > max) s = s.substring(0, max) + "…";
        return s;
    }

    /**
     * 只保留 URL 的 host（含端口），用于日志。
     *
     * <p>端点路径里可能带租户 key，日志不能整条打出来；userinfo 与路径一律丢弃。
     * 解析不出来时返回 {@code "?"}，绝不回显原串。</p>
     */
    public static String host(String url) {
        if (url == null) return "?";
        String rest = url.trim();
        if (rest.length() == 0) return "?";
        int scheme = rest.indexOf("://");
        if (scheme >= 0) rest = rest.substring(scheme + 3);
        int at = rest.indexOf('@');
        if (at >= 0) rest = rest.substring(at + 1);
        int end = 0;
        while (end < rest.length()) {
            char ch = rest.charAt(end);
            if (ch == '/' || ch == '?' || ch == '#') break;
            end++;
        }
        return end == 0 ? "?" : rest.substring(0, end);
    }
}
