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

        // ---- 诊断结论：导出包自己回答「成没成功」----
        LogExport.Diagnosis none = LogExport.diagnose(DUMP);
        T.ok("无 hook 记录判为未装载", none.verdict.contains("未装载"));
        T.eq("诊断进程数", 3, none.procs);
        T.eq("诊断 hook 进程数", 0, none.hookPids);

        String installed = line(1, "I", 11, "配置 hook 完成");
        String okCase = wrap(installed + "," + line(2, "I", 11, "改写命中: model=m")
                + "," + line(3, "I", 11, "模型调用: 三方 qm.i model=m"));
        LogExport.Diagnosis ok = LogExport.diagnose(okCase);
        T.ok("改写+三方判为正常", ok.verdict.contains("正常"));
        T.eq("改写命中计数", 1, ok.rewriteHits);
        T.eq("三方调用计数", 1, ok.thirdCalls);

        String badCase = wrap(installed + "," + line(2, "I", 11, "模型调用: 官方 um.h model=m"));
        LogExport.Diagnosis bad = LogExport.diagnose(badCase);
        T.ok("官方调用无改写判为不兼容", bad.verdict.contains("不兼容"));
        T.eq("官方调用计数", 1, bad.officialCalls);

        String noCall = wrap(installed);
        T.ok("装了但没调用要提示去测", LogExport.diagnose(noCall).verdict.contains("尚未测到"));

        String ignoredCase = wrap(installed + ","
                + line(2, "I", 11, "第三方模型已启用，但配置 getter 从未被读取（um.h）"));
        T.ok("识别 getter 告警", LogExport.diagnose(ignoredCase).getterIgnored);
        T.ok("告警进结论", LogExport.diagnose(ignoredCase).verdict.contains("getter"));

        // 结论必须进 meta 与 json，收日志的人不用再问
        LogExport.Result withDiag = LogExport.build(okCase, new LogExport.Filter(), null);
        T.ok("meta 含结论", withDiag.meta.contains("# 结论: 正常"));
        T.ok("json 含 diagnosis", withDiag.json.contains("\"diagnosis\""));
        T.eq("Result 带结论", ok.verdict, withDiag.verdict);

        return T.report("LogExportTest");
    }

    private static LogExport.Filter warn2(int severity) {
        LogExport.Filter f = new LogExport.Filter();
        f.minSeverity = severity;
        return f;
    }

    /** 构造一条结构化日志行（ts 随 seq 递增，便于排序）。 */
    private static String line(int seq, String level, int pid, String msg) {
        return "{\"seq\":" + seq + ",\"level\":\"" + level + "\",\"msg\":\"" + msg
                + "\",\"tag\":\"\",\"ts\":" + (1000 + seq) + ",\"pid\":" + pid
                + ",\"proc\":\"com.miui.voiceassist\"}";
    }

    private static String wrap(String csvLines) {
        int n = csvLines.split(",").length;
        return "{\"count\":" + n + ",\"lastSeq\":" + n
                + ",\"scope\":\"merged\",\"lines\":[" + csvLines + "]}";
    }
}
