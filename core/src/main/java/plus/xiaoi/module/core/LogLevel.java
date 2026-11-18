package plus.xiaoi.module.core;

import java.util.Locale;

/**
 * 日志档位（纯 JVM）：只控制「记录到什么粒度」，不改变接口行为。
 *
 * <ul>
 *   <li>OFF：不写环形/不落盘，只保留 E 到 logcat；</li>
 *   <li>BRIEF：生命周期、W/E、安全事件聚合；</li>
 *   <li>STANDARD（默认）：BRIEF + 每次工具调用的元数据；</li>
 *   <li>DETAILED：STANDARD + 参数摘要/来源/UA/确认事件；</li>
 *   <li>DEBUG：DETAILED + HTTP 细节与调试字段。</li>
 * </ul>
 */
public enum LogLevel {
    OFF(0, "off"),
    BRIEF(1, "brief"),
    STANDARD(2, "standard"),
    DETAILED(3, "detailed"),
    DEBUG(4, "debug");

    public final int rank;
    public final String wire;

    LogLevel(int rank, String wire) {
        this.rank = rank;
        this.wire = wire;
    }

    /** 非法/空值回落到 STANDARD，避免旧配置或手写 JSON 把日志彻底关掉。 */
    public static LogLevel parse(String raw) {
        if (raw != null) {
            String v = raw.trim().toLowerCase(Locale.US);
            for (LogLevel l : values()) {
                if (l.wire.equals(v)) return l;
            }
        }
        return STANDARD;
    }

    /** 当前档位是否允许记录需要 {@code needed} 粒度的事件。 */
    public boolean allows(LogLevel needed) {
        return needed == null || rank >= needed.rank;
    }

    public boolean off() {
        return this == OFF;
    }
}
