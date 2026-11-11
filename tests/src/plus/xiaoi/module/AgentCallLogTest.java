package plus.xiaoi.module;

import java.util.List;

import plus.xiaoi.module.agent.AgentCallLog;
import plus.xiaoi.module.core.LogLevel;
import plus.xiaoi.module.core.LogSettings;

/** 8770 调用日志：格式、档位门控、清空。 */
public final class AgentCallLogTest {

    private AgentCallLogTest() {}

    public static int run() {
        LogSettings.reset();
        AgentCallLog.clear();
        AgentCallLog.add("I", "agent", "tool=xiaoai_capabilities ok=true ms=1 args=0");
        List<String> lines = AgentCallLog.lines();
        T.eq("一条记录", 1, lines.size());
        T.ok("含来源前缀", lines.get(0).contains("[agent] tool=xiaoai_capabilities"));
        T.ok("含时间", lines.get(0).matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} I .*"));

        LogSettings.set(LogLevel.OFF);
        AgentCallLog.add("I", "agent", "dropped");
        T.eq("关闭档位丢弃 I", 1, AgentCallLog.lines().size());
        AgentCallLog.add("E", "agent", "kept");
        T.eq("关闭档位保留 E", 2, AgentCallLog.lines().size());

        AgentCallLog.clear();
        T.eq("清空", 0, AgentCallLog.lines().size());
        LogSettings.reset();
        return T.report("AgentCallLogTest");
    }
}
