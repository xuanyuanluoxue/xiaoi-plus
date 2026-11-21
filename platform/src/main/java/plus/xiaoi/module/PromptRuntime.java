package plus.xiaoi.module;

import java.util.Map;

/**
 * 提示词注入的运行时统计（P2-C）。
 *
 * <p>只记<b>次数与位置</b>，绝不记提示词或记忆正文：日志里出现的内容长度是可接受的，
 * 正文本身不行（规划 §7 隐私边界）。Hook 在每次请求后调用这里的静态方法，
 * {@code xiaoai_prompt} 再把快照返回给调用方。</p>
 */
public final class PromptRuntime {

    private static volatile long sInjections;
    private static volatile long sSkips;
    private static volatile long sFailures;
    private static volatile long sLastAt;
    /** 最近一次的命中位置或失败原因（英文短码）。 */
    private static volatile String sLastNote = "";
    /** 首次成功注入只记一条日志，避免每轮对话刷屏。 */
    private static volatile boolean sLoggedHit;
    /** 失败原因每进程只记一次（按原因去重）。 */
    private static final java.util.Set<String> sLoggedFailures =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

    private PromptRuntime() {}

    /** 成功改写一条请求；{@code note} 是命中位置（instructions/system-message/system-inserted）。 */
    public static void recordInjected(String note) {
        sInjections++;
        sLastAt = System.currentTimeMillis();
        sLastNote = note == null ? "" : note;
        if (!sLoggedHit) {
            sLoggedHit = true;
            Logs.i("提示词注入已生效（" + sLastNote + "）");
        }
    }

    /** 配置开着但这次没有可改写的内容（缺字段 / 已经是最新）。 */
    public static void recordSkipped(String note) {
        sSkips++;
        sLastNote = note == null ? "" : note;
    }

    /** 注入失败（已保持原请求）；只记原因，不记正文。 */
    public static void recordFailed(String reason) {
        sFailures++;
        sLastNote = reason == null ? "" : reason;
        if (sLoggedFailures.add(sLastNote)) {
            Logs.w("提示词注入失败（已保持原请求）: " + sLastNote);
        }
    }

    public static long injections() {
        return sInjections;
    }

    public static long skips() {
        return sSkips;
    }

    public static long failures() {
        return sFailures;
    }

    public static long lastAt() {
        return sLastAt;
    }

    public static String lastNote() {
        return sLastNote;
    }

    /** 统计快照（JSON 对象），供 {@code xiaoai_prompt} 拼接。 */
    public static Map<String, Object> stats() {
        Map<String, Object> o = Json.obj();
        o.put("injections", sInjections);
        o.put("skips", sSkips);
        o.put("failures", sFailures);
        o.put("lastAt", sLastAt);
        o.put("lastNote", sLastNote);
        return o;
    }
}
