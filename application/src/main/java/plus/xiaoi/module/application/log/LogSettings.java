package plus.xiaoi.module.application.log;

import plus.xiaoi.module.core.LogLevel;

/**
 * 进程内日志档位（纯 JVM，WP13 从领域层移到应用层）。
 *
 * <p>它描述的是「当前进程的日志噪音级别」，不是领域规则，因此不该放在
 * {@code :domain}。两个进程各自持有一份：App 进程由 {@code ConfigRepository}/
 * {@code ConfigSync} 写入，小爱进程由 {@code BridgeControlClient} 写入。</p>
 */
public final class LogSettings {

    private static volatile LogLevel level = LogLevel.STANDARD;

    private LogSettings() {}

    public static LogLevel get() {
        return level;
    }

    public static void set(LogLevel next) {
        level = next == null ? LogLevel.STANDARD : next;
    }

    public static void setWire(String wire) {
        level = LogLevel.parse(wire);
    }

    public static boolean allows(LogLevel needed) {
        return level.allows(needed);
    }

    /** 单测用：恢复默认档位。 */
    public static void reset() {
        level = LogLevel.STANDARD;
    }
}
