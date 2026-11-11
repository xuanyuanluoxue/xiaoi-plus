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
}
