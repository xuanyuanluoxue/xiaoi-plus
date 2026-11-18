package plus.xiaoi.module.agent;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import plus.xiaoi.module.core.LogLevel;
import plus.xiaoi.module.core.LogSettings;

/**
 * App 进程内的 8770 调用日志（纯 JVM 环形缓冲，不落盘）。
 *
 * <p>只记录工具名/成败/耗时/参数个数与 HTTP 错误，不记录参数值、对话正文、
 * token 或 apiKey。日志页通过 {@code BridgeControlService.logDump()} 与 8771
 * 上报的日志合并展示。</p>
 */
public final class AgentCallLog {

    private static final int CAP = 300;

    private static final ArrayDeque<String> LINES = new ArrayDeque<String>();

    private AgentCallLog() {}

    public static void add(String level, String tag, String msg) {
        boolean err = "E".equals(level);
        LogLevel needed = err ? LogLevel.OFF
                : ("W".equals(level) ? LogLevel.BRIEF : LogLevel.STANDARD);
        if (!LogSettings.allows(needed)) return;
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String tagPart = tag == null || tag.length() == 0 ? "" : " [" + tag + "]";
        String line = time + " " + (level == null ? "I" : level) + tagPart + " "
                + (msg == null ? "" : msg);
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > CAP) LINES.removeFirst();
        }
    }

    public static synchronized List<String> lines() {
        return new ArrayList<String>(LINES);
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : LINES) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}
