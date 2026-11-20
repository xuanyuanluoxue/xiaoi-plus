package plus.xiaoi.module;

import java.util.Arrays;
import java.util.List;

import plus.xiaoi.module.application.log.LogExport;

/** 日志导出：级别/进程/时间/关键字筛选、上限、meta 与文本渲染。 */
public final class LogExportTest {

    private LogExportTest() {}

    private static final String DUMP =
            "{\"count\":5,\"lastSeq\":9,\"scope\":\"merged\",\"lines\":["
            + "{\"seq\":1,\"t\":\"10:00:00.000\",\"level\":\"D\",\"msg\":\"debug-line\","
            + "\"tag\":\"http\",\"ts\":1000,\"pid\":11,\"proc\":\"com.miui.voiceassist:core\"},"
            + "{\"seq\":2,\"t\":\"10:00:01.000\",\"level\":\"I\",\"msg\":\"model call\","
            + "\"tag\":\"mcp\",\"ts\":2000,\"pid\":11,\"proc\":\"com.miui.voiceassist:core\"},"
            + "{\"seq\":3,\"t\":\"10:00:02.000\",\"level\":\"W\",\"msg\":\"getter missing\","
            + "\"tag\":\"\",\"ts\":3000,\"pid\":22,\"proc\":\"com.miui.voiceassist\"},"
            + "{\"seq\":4,\"t\":\"10:00:03.000\",\"level\":\"E\",\"msg\":\"bind failed\","
            + "\"tag\":\"ctl\",\"ts\":4000,\"pid\":22,\"proc\":\"com.miui.voiceassist\"},"
            + "{\"seq\":5,\"t\":\"10:00:04.000\",\"level\":\"I\",\"msg\":\"ok\","
            + "\"tag\":\"mcp\",\"ts\":5000,\"pid\":33,\"proc\":\"com.miui.voiceassist:voice_trigger\"}"
            + "]}";

    public static int run() {
        T.eq("rank E", 3, LogExport.rank("E"));
        T.eq("rank W", 2, LogExport.rank("W"));
        T.eq("rank I", 1, LogExport.rank("I"));
        T.eq("rank D", 0, LogExport.rank("D"));
        T.eq("rank 未知按 I", 1, LogExport.rank("?"));

        // 全部
        LogExport.Filter all = new LogExport.Filter();
        all.limit = 100;
        LogExport.Result r = LogExport.build(DUMP, all, Arrays.asList("模块: 0.16.4"));
        T.eq("全部条数", 5, r.count);
        T.eq("总数", 5, r.total);
        T.eq("scope 透传", "merged", r.scope);
        T.ok("meta 含条数", r.meta.contains("# 条数: 5 / 5"));
        T.ok("meta 含环境信息", r.meta.contains("模块: 0.16.4"));
        T.ok("meta 含脱敏声明", r.meta.contains("脱敏"));
        T.ok("文本带来源列", r.text.contains("core pid=11"));
        T.ok("文本带毫秒时间", r.text.matches("(?s).*\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} D core pid=11 debug-line.*"));
        T.ok("json 含 filters", r.json.contains("\"filters\""));
        T.ok("json 保留原字段", r.json.contains("\"proc\":\"com.miui.voiceassist:core\""));

        // 级别（阈值与 rank 同尺度：2=警告以上，3=仅错误）
        T.eq("警告及以上", 2, LogExport.build(DUMP, warn2(2), null).count);
        T.eq("仅错误", 1, LogExport.build(DUMP, warn2(3), null).count);

        // 进程
        LogExport.Filter byProc = new LogExport.Filter();
        byProc.proc = "com.miui.voiceassist";
        T.eq("按进程筛选", 2, LogExport.build(DUMP, byProc, null).count);
        byProc.proc = "com.miui.voiceassist:core";
        T.eq("按进程精确匹配（不串前缀）", 2, LogExport.build(DUMP, byProc, null).count);

        // 时间窗
        LogExport.Filter since = new LogExport.Filter();
        since.sinceTs = 3000;
        T.eq("时间窗", 3, LogExport.build(DUMP, since, null).count);

        // 关键字：命中正文，无视大小写
        LogExport.Filter kw = new LogExport.Filter();
        kw.keyword = "MODEL";
        T.eq("关键字大小写不敏感", 1, LogExport.build(DUMP, kw, null).count);
        // 关键字也能命中 tag
        LogExport.Filter kwTag = new LogExport.Filter();
        kwTag.keyword = "ctl";
        T.eq("关键字命中 tag", 1, LogExport.build(DUMP, kwTag, null).count);
        // 无命中
        LogExport.Filter kwMiss = new LogExport.Filter();
        kwMiss.keyword = "definitely-absent";
        LogExport.Result miss = LogExport.build(DUMP, kwMiss, null);
        T.eq("无命中为 0 条", 0, miss.count);
        T.eq("无命中仍报总数", 5, miss.total);

        // 上限取尾部
        LogExport.Filter cap = new LogExport.Filter();
        cap.limit = 2;
        LogExport.Result tail = LogExport.build(DUMP, cap, null);
        T.eq("limit 取尾部条数", 2, tail.count);
        T.ok("尾部是最后两条", tail.text.contains("bind failed") && tail.text.contains("ok"));

        // limit<=0 走 MAX_LINES，不至于导出空
        LogExport.Filter zero = new LogExport.Filter();
        zero.limit = 0;
        T.eq("limit=0 仍导出", 5, LogExport.build(DUMP, zero, null).count);

        // 组合条件
        LogExport.Filter combo = new LogExport.Filter();
        combo.proc = "com.miui.voiceassist";
        combo.minSeverity = 2;
        combo.sinceTs = 3500;
        T.eq("组合筛选", 1, LogExport.build(DUMP, combo, null).count);

        // 空/坏输入不许抛
        LogExport.Result empty = LogExport.build("", null, null);
        T.eq("空输入 0 条", 0, empty.count);
        T.eq("空输入 scope 回落 local", "local", empty.scope);
        T.eq("坏 JSON 不抛", 0, LogExport.build("{not json", null, null).count);

        // 描述
        LogExport.Filter d = new LogExport.Filter();
        d.minSeverity = 2;
        d.proc = "a:core";
        d.rangeLabel = "最近 30 分钟";
        d.keyword = "abc";
        String desc = LogExport.describe(d);
        T.ok("描述含级别", desc.contains("警告及以上"));
        T.ok("描述含进程", desc.contains("a:core"));
        T.ok("描述含时间窗", desc.contains("最近 30 分钟"));
        T.ok("描述含关键字", desc.contains("abc"));

        // 进程列表：去重 + 排序，供界面生成 chips
        java.util.List<String> procs = LogExport.procs(DUMP);
        T.eq("进程数", 3, procs.size());
        T.eq("进程按名排序", "com.miui.voiceassist", procs.get(0));
        T.eq("进程含 voice_trigger", "com.miui.voiceassist:voice_trigger", procs.get(2));
        T.eq("坏 JSON 无进程", 0, LogExport.procs("{oops").size());

        return T.report("LogExportTest");
    }

    private static LogExport.Filter warn2(int severity) {
        LogExport.Filter f = new LogExport.Filter();
        f.minSeverity = severity;
        return f;
    }
}
