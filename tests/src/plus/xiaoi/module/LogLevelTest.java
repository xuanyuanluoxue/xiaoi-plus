package plus.xiaoi.module;

import plus.xiaoi.module.core.LogLevel;
import plus.xiaoi.module.core.LogSettings;

/** 日志档位：解析、回落、粒度判断。 */
public final class LogLevelTest {

    private LogLevelTest() {}

    public static int run() {
        T.eq("默认 standard", LogLevel.STANDARD, LogLevel.parse(""));
        T.eq("parse off", LogLevel.OFF, LogLevel.parse("off"));
        T.eq("parse brief", LogLevel.BRIEF, LogLevel.parse("BRIEF"));
        T.eq("非法回落 standard", LogLevel.STANDARD, LogLevel.parse("verbose"));

        T.ok("off 不记录 brief", !LogLevel.OFF.allows(LogLevel.BRIEF));
        T.ok("brief 允许 brief", LogLevel.BRIEF.allows(LogLevel.BRIEF));
        T.ok("brief 不允许 standard", !LogLevel.BRIEF.allows(LogLevel.STANDARD));
        T.ok("standard 允许 detailed? no", !LogLevel.STANDARD.allows(LogLevel.DETAILED));
        T.ok("detailed 允许 detailed", LogLevel.DETAILED.allows(LogLevel.DETAILED));
        T.ok("debug 允许全部", LogLevel.DEBUG.allows(LogLevel.DEBUG));

        LogSettings.reset();
        T.eq("settings 默认", LogLevel.STANDARD, LogSettings.get());
        LogSettings.setWire("debug");
        T.eq("settings setWire", LogLevel.DEBUG, LogSettings.get());
        LogSettings.setWire("garbage");
        T.eq("settings 非法回落", LogLevel.STANDARD, LogSettings.get());
        LogSettings.reset();

        return T.report("LogLevelTest");
    }
}
