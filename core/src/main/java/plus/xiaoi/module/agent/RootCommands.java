package plus.xiaoi.module.agent;

import java.util.Locale;

/**
 * Root 兜底使用的固定命令与结果判定（纯 JVM）。
 *
 * <p>这里只允许两条命令族：
 * <ul>
 *   <li>{@code monkey -p <package> -c android.intent.category.LAUNCHER 1}</li>
 *   <li>{@code cmd media_session dispatch play|pause|next|previous}</li>
 * </ul>
 * 不接受调用方传入的命令文本；包名必须整体匹配 {@code [A-Za-z0-9._]+}，
 * 因此不会把 {@code ;}、空格、引号、换行等 shell 元字符带进 {@code su -c}。
 * 命令文本与错误映射都是纯函数，方便在 JVM 上直接测试。</p>
 */
public final class RootCommands {

    /** root 命令超时：超过后强杀进程并返回 TIMEOUT，不假装成功。 */
    public static final long TIMEOUT_MS = 3000;

    /** 命令输出保留上限（字符）；超出部分截断，避免 stderr 洪水占内存。 */
    public static final int MAX_OUTPUT_CHARS = 1024;

    /** 包名长度上限，与 Actions 的 schema maxLength 保持一致。 */
    public static final int MAX_PACKAGE_LENGTH = 200;

    private RootCommands() {}

    /** 包名白名单：只允许字母、数字、点和下划线，且非空、不超长。 */
    public static boolean validPackage(String pkg) {
        if (pkg == null) return false;
        String s = pkg.trim();
        if (s.length() == 0 || s.length() > MAX_PACKAGE_LENGTH) return false;
        // 手工逐字符判断，避免 Pattern 在热路径上的额外开销，也避免正则元字符歧义。
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /** 构造 monkey 启动命令；包名不合法返回 null（调用方映射 INVALID_ARGUMENT）。 */
    public static String appOpenCommand(String pkg) {
        if (!validPackage(pkg)) return null;
        return "monkey -p " + pkg.trim()
                + " -c android.intent.category.LAUNCHER 1";
    }

    /**
     * 构造媒体键命令；只支持 play/pause/next/prev。
     *
     * @return 固定命令文本；不支持的命令（含 volume）返回 null
     */
    public static String mediaCommand(String command) {
        if (command == null) return null;
        String c = command.trim();
        String key;
        if ("play".equals(c)) key = "play";
        else if ("pause".equals(c)) key = "pause";
        else if ("next".equals(c)) key = "next";
        else if ("prev".equals(c)) key = "previous";
        else return null;
        return "cmd media_session dispatch " + key;
    }

    /** 截断命令输出；null 视为空串。 */
    public static String clip(String output) {
        if (output == null) return "";
        String s = output.trim();
        if (s.length() <= MAX_OUTPUT_CHARS) return s;
        return s.substring(0, MAX_OUTPUT_CHARS) + "…";
    }

    /**
     * 把一次 root 命令的结果映射为稳定错误码。
     *
     * @return null 表示命令成功；否则为应返回给协议面的错误
     */
    public static AgentError evaluate(boolean timedOut, int exitCode, String output) {
        if (timedOut) {
            return AgentError.of(AgentError.Code.TIMEOUT, "root 命令超时（"
                    + TIMEOUT_MS + "ms）");
        }
        String out = output == null ? "" : output.toLowerCase(Locale.US);
        if (out.contains("permission denied") || out.contains("not allowed")) {
            return AgentError.of(AgentError.Code.PERMISSION_DENIED,
                    "root 授权被拒绝或权限不足");
        }
        if (exitCode != 0) {
            return AgentError.of(AgentError.Code.NOT_AVAILABLE,
                    "root 命令执行失败（exit=" + exitCode + "）");
        }
        // 退出码 0 但命令明确报告“没找到可启动的 Activity / 没有媒体会话”：
        // 这是“设备当前不可用”，不能算成功。
        if (out.contains("no activities found")
                || out.contains("monkey aborted")
                || out.contains("no active sessions")
                || out.contains("no active media session")) {
            return AgentError.of(AgentError.Code.NOT_AVAILABLE,
                    "root 命令没有可执行目标（应用/媒体会话不存在）");
        }
        return null;
    }
}
