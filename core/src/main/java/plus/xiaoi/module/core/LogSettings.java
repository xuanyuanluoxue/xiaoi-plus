package plus.xiaoi.module.core;

/**
 * 进程内日志档位（纯 JVM）。两个进程各自持有一份：
 * App 进程由 ConfigRepository/ConfigSync 写入，小爱进程由 BridgeControlClient 写入。
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
