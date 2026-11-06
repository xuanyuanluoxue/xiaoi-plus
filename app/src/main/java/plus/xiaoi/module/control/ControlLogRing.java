package plus.xiaoi.module.control;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 控制面日志环形缓冲（纯 JVM，无 android 依赖）。
 *
 * WP1 只要求「最小实现」：桥通过 Binder 送来的日志先放 App 内存，不落盘。
 * 不落盘是有意的：日志内容由小爱进程决定，落盘可能把密钥/对话正文写进 App 数据目录。
 * 另外对常见秘密格式做一次脱敏，避免 UI（WP6）误展示。
 */
public final class ControlLogRing {

    public static final int MAX_LINES = 200;
    public static final int MAX_LINE_CHARS = 512;

    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(api[_-]?key|token|authorization)(\\s*[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"',}&;]+)");

    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer)\\s+[^\\s\"',}&;]+");

    private final ArrayDeque<String> lines = new ArrayDeque<String>();

    /** 追加多行文本；逐行脱敏、裁剪，超出容量丢弃最旧。 */
    public synchronized void append(String text) {
        if (text == null || text.length() == 0) return;
        String[] parts = text.split("\n", -1);
        for (String part : parts) {
            String line = redact(part);
            if (line.length() == 0) continue;
            if (line.length() > MAX_LINE_CHARS) line = line.substring(0, MAX_LINE_CHARS);
            lines.addLast(line);
            while (lines.size() > MAX_LINES) lines.removeFirst();
        }
    }

    public synchronized List<String> lines() {
        return new ArrayList<String>(lines);
    }

    public synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    public synchronized int size() {
        return lines.size();
    }

    public synchronized void clear() {
        lines.clear();
    }

    /** 秘密脱敏：apiKey/token/authorization 的键值对与 Bearer 头。 */
    public static String redact(String line) {
        if (line == null) return "";
        // 先处理 Bearer：否则 "Authorization: Bearer xxx" 会先被键值规则吃掉 Bearer 这个词，
        // 把真正的 token 留在后面。
        String out = BEARER.matcher(line).replaceAll("$1 ***");
        out = KEY_VALUE.matcher(out).replaceAll("$1$2***");
        return out;
    }
}
