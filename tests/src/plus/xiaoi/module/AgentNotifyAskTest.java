package plus.xiaoi.module;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import plus.xiaoi.module.agent.ActionRegistry;
import plus.xiaoi.module.agent.AgentMcp;
import plus.xiaoi.module.agent.AskHost;
import plus.xiaoi.module.agent.BridgeAskHost;
import plus.xiaoi.module.agent.NotifyHost;
import plus.xiaoi.module.core.BridgeEndpoint;

/**
 * WP1.3b 纯 JVM 测试：xiaoai_notify（超级岛提醒 + 通知降级）与
 * xiaoai_ask（代理小爱并返回 sessionId）。
 *
 * <p>协议面用 fake host 验证；8771 通路用一个本地 HttpServer 验证
 * {@code /cli} 的编码、session.open / agent.submit 顺序与失败映射。
 * 不含 Android 类，因此可以放进 tests/run.sh。</p>
 */
public final class AgentNotifyAskTest {

    private static final String VERSION = "0.13.0-notify-ask-test";

    private AgentNotifyAskTest() {}

    public static int run() throws Exception {
        notifySuccess();
        notifyFallback();
        notifyErrors();
        notifyTruncation();
        askSuccess();
        askErrors();
        askLimits();
        toolTable();
        bridgeAskHost();
        return T.report("AgentNotifyAskTest");
    }

    // ------------------------------------------------------------- notify 成功

    private static void notifySuccess() {
        FakeNotify host = new FakeNotify();
        AgentMcp mcp = mcp(host, null);

        Map<String, Object> tool = callTool(mcp, "xiaoai_notify", args("text", "任务完成"));
        T.eq("notify island isError=false", Boolean.FALSE, tool.get("isError"));
        Map<String, Object> out = json(toolText(tool));
        T.eq("notify ok=true", Boolean.TRUE, out.get("ok"));
        T.eq("notify 默认 channel=island", "island", out.get("channel"));
        T.eq("notify fallback=false", Boolean.FALSE, out.get("fallback"));
        T.eq("notify truncated=false", Boolean.FALSE, out.get("truncated"));
        T.eq("notify 走到 island 一次", 1, host.channels.size());
        T.eq("notify island 收到原文", "任务完成", host.texts.get(0));
        T.eq("notify force 默认 true", Boolean.TRUE, host.forces.get(0));

        callTool(mcp, "xiaoai_notify", args("text", "x", "force", Boolean.FALSE));
        T.eq("notify force=false 透传", Boolean.FALSE, host.forces.get(host.forces.size() - 1));

        callTool(mcp, "xiaoai_notify", args("text", "x", "channel", "notification"));
        T.eq("notify 指定 notification", "notification",
                host.channels.get(host.channels.size() - 1));
        Map<String, Object> notifTool = callTool(mcp, "xiaoai_notify",
                args("text", "x", "channel", "notification"));
        Map<String, Object> notifOut = json(toolText(notifTool));
        T.eq("notification 结果 channel", "notification", notifOut.get("channel"));
        T.eq("notification 结果 fallback=false", Boolean.FALSE, notifOut.get("fallback"));

        // 宿主内部完成降级（island 调用返回 notification/fallback=true）时如实透传。
        FakeNotify fallbackHost = new FakeNotify();
        fallbackHost.islandFallbackSuccess = true;
        Map<String, Object> fb = json(toolText(callTool(mcp(fallbackHost, null),
                "xiaoai_notify", args("text", "降级"))));
        T.eq("宿主内部降级 channel", "notification", fb.get("channel"));
        T.eq("宿主内部降级 fallback", Boolean.TRUE, fb.get("fallback"));
        T.eq("宿主内部降级只调用一次", 1, fallbackHost.channels.size());
    }

    // ------------------------------------------------------------- notify 降级

    private static void notifyFallback() {
        FakeNotify host = new FakeNotify();
        host.islandOk = false;
        Map<String, Object> out = json(toolText(callTool(mcp(host, null),
                "xiaoai_notify", args("text", "小爱不在"))));
        T.eq("island 失败仍 ok=true", Boolean.TRUE, out.get("ok"));
        T.eq("island 失败降级 notification", "notification", out.get("channel"));
        T.eq("island 失败 fallback=true", Boolean.TRUE, out.get("fallback"));
        T.eq("island 失败后尝试两条通道", 2, host.channels.size());
        T.eq("降级通道是 notification", "notification", host.channels.get(1));

        FakeNotify both = new FakeNotify();
        both.islandOk = false;
        both.notificationOk = false;
        Map<String, Object> tool = callTool(mcp(both, null),
                "xiaoai_notify", args("text", "两条都失败"));
        T.eq("双失败 isError=true", Boolean.TRUE, tool.get("isError"));
        Map<String, Object> envelope = json(toolText(tool));
        T.eq("双失败错误码", "NOT_AVAILABLE", envelope.get("code"));
        T.eq("双失败 ok=false", Boolean.FALSE, envelope.get("ok"));

        FakeNotify throwsIsland = new FakeNotify();
        throwsIsland.throwIsland = true;
        Map<String, Object> thrown = json(toolText(callTool(mcp(throwsIsland, null),
                "xiaoai_notify", args("text", "异常"))));
        T.eq("island 抛异常仍尝试通知", "notification", thrown.get("channel"));
        T.eq("island 抛异常 fallback", Boolean.TRUE, thrown.get("fallback"));

        FakeNotify throwsAll = new FakeNotify();
        throwsAll.throwIsland = true;
        throwsAll.throwAll = true;
        Map<String, Object> allThrown = json(toolText(callTool(mcp(throwsAll, null),
                "xiaoai_notify", args("text", "全异常"))));
        T.eq("宿主全抛异常 -> NOT_AVAILABLE", "NOT_AVAILABLE", allThrown.get("code"));

        FakeNotify nullHostResult = new FakeNotify();
        nullHostResult.nullIsland = true;
        nullHostResult.islandOk = false;
        Map<String, Object> nullOut = json(toolText(callTool(mcp(nullHostResult, null),
                "xiaoai_notify", args("text", "null"))));
        T.eq("宿主返回 null 走通知降级", "notification", nullOut.get("channel"));

        FakeNotify fallbackFailed = new FakeNotify();
        fallbackFailed.islandOk = false;
        fallbackFailed.notificationOk = false;
        fallbackFailed.islandFallbackSuccess = true;
        Map<String, Object> fbFail = json(toolText(callTool(mcp(fallbackFailed, null),
                "xiaoai_notify", args("text", "降级失败"))));
        T.eq("宿主已降级失败 -> NOT_AVAILABLE", "NOT_AVAILABLE", fbFail.get("code"));
        T.eq("宿主已降级失败不重复发通知", 1, fallbackFailed.channels.size());
    }

    // ------------------------------------------------------------- notify 参数错误

    private static void notifyErrors() {
        FakeNotify host = new FakeNotify();
        AgentMcp mcp = mcp(host, null);

        Map<String, Object> noHost = callTool(new AgentMcp(VERSION), "xiaoai_notify",
                args("text", "x"));
        T.eq("notify host=null isError", Boolean.TRUE, noHost.get("isError"));
        T.eq("notify host=null INTERNAL", "INTERNAL",
                json(toolText(noHost)).get("code"));

        Map<String, Object> badChannel = callTool(mcp, "xiaoai_notify",
                args("text", "x", "channel", "sms"));
        T.eq("notify 非法 channel isError", Boolean.TRUE, badChannel.get("isError"));
        T.eq("notify 非法 channel INVALID_ARGUMENT", "INVALID_ARGUMENT",
                json(toolText(badChannel)).get("code"));
        T.eq("notify 非法 channel 不调用宿主", 0, host.channels.size());

        Map<String, Object> missing = callTool(mcp, "xiaoai_notify", args());
        T.eq("notify 缺 text INVALID_ARGUMENT", "INVALID_ARGUMENT",
                json(toolText(missing)).get("code"));

        Map<String, Object> blank = callTool(mcp, "xiaoai_notify", args("text", "   "));
        T.eq("notify 空白 text INVALID_ARGUMENT", "INVALID_ARGUMENT",
                json(toolText(blank)).get("code"));

        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 2001; i++) tooLong.append('a');
        Map<String, Object> over = callTool(mcp, "xiaoai_notify",
                args("text", tooLong.toString()));
        T.eq("notify text>2000 INVALID_ARGUMENT", "INVALID_ARGUMENT",
                json(toolText(over)).get("code"));
        T.eq("notify 超限不调用宿主", 0, host.channels.size());

        Map<String, Object> numberText = callTool(mcp, "xiaoai_notify", args("text", 5L));
        T.eq("notify 数字 text 按字符串处理", Boolean.FALSE, numberText.get("isError"));
    }

    // ------------------------------------------------------------- notify 截断

    private static void notifyTruncation() {
        FakeNotify host = new FakeNotify();
        AgentMcp mcp = mcp(host, null);

        String longText = repeat("a", 100);
        Map<String, Object> out = json(toolText(callTool(mcp, "xiaoai_notify",
                args("text", longText))));
        T.eq("island 100 字 truncated=true", Boolean.TRUE, out.get("truncated"));
        T.eq("island textLength=100", 100L, out.get("textLength"));
        T.eq("island 宿主收到 60 字", 60, host.texts.get(0).length());
        T.eq("island 宿主收到前缀", repeat("a", 60), host.texts.get(0));

        Map<String, Object> exact = json(toolText(callTool(mcp, "xiaoai_notify",
                args("text", repeat("b", 60)))));
        T.eq("island 60 字不截断", Boolean.FALSE, exact.get("truncated"));
        T.eq("island 60 字完整送达", 60, host.texts.get(host.texts.size() - 1).length());

        Map<String, Object> over = json(toolText(callTool(mcp, "xiaoai_notify",
                args("text", repeat("c", 61)))));
        T.eq("island 61 字截断", Boolean.TRUE, over.get("truncated"));
        T.eq("island 61 字送达 60", 60,
                host.texts.get(host.texts.size() - 1).length());

        Map<String, Object> notif = json(toolText(callTool(mcp, "xiaoai_notify",
                args("text", longText, "channel", "notification"))));
        T.eq("notification 100 字不截断", Boolean.FALSE, notif.get("truncated"));
        T.eq("notification 原样送达", 100,
                host.texts.get(host.texts.size() - 1).length());

        // 代理对不能被切成半个 emoji：59 个 a + emoji + b，最多取 60 个 char。
        String surrogate = repeat("a", 59) + "\uD83D\uDE00" + "b";
        callTool(mcp, "xiaoai_notify", args("text", surrogate));
        String sent = host.texts.get(host.texts.size() - 1);
        T.eq("切代理对时退到安全位置", 59, sent.length());
        T.eq("切代理对不留下半个 emoji", "a", sent.substring(sent.length() - 1));

        FakeNotify fallsBack = new FakeNotify();
        fallsBack.islandOk = false;
        Map<String, Object> fb = json(toolText(callTool(mcp(fallsBack, null),
                "xiaoai_notify", args("text", longText))));
        T.eq("降级通知拿到完整长文本", 100,
                fallsBack.texts.get(fallsBack.texts.size() - 1).length());
        T.eq("降级通知不标记 truncated", Boolean.FALSE, fb.get("truncated"));
    }

    // ------------------------------------------------------------- ask 成功

    private static void askSuccess() {
        FakeAsk ask = new FakeAsk();
        AgentMcp mcp = mcp(null, ask);

        Map<String, Object> tool = callTool(mcp, "xiaoai_ask", args("text", "回复 OK"));
        T.eq("ask isError=false", Boolean.FALSE, tool.get("isError"));
        Map<String, Object> out = json(toolText(tool));
        T.eq("ask ok=true", Boolean.TRUE, out.get("ok"));
        T.eq("ask text", "OK", out.get("text"));
        T.eq("ask sessionId 来自 host", "srv-1", out.get("sessionId"));
        T.eq("ask via=xiaoi", "xiaoi", out.get("via"));
        T.eq("ask 默认 timeoutMs=120000", 120000L, out.get("timeoutMs"));
        T.eq("ask host 收到默认超时", 120000L, ask.lastTimeout);
        T.eq("ask host 收到空 sessionId", "", ask.lastSessionId);
        T.eq("ask host 收到 null context", null, ask.lastContext);

        ask.sessionIdOut = "";
        Map<String, Object> reuse = json(toolText(callTool(mcp, "xiaoai_ask",
                args("text", "继续", "sessionId", "s-1"))));
        T.eq("ask 复用传入 sessionId", "s-1", reuse.get("sessionId"));
        T.eq("ask 透传 sessionId 给 host", "s-1", ask.lastSessionId);

        ask.sessionIdOut = null;
        ask.nextSessionId = "";
        Map<String, Object> emptySid = json(toolText(callTool(mcp, "xiaoai_ask",
                args("text", "无会话"))));
        T.eq("ask 双方都没有 sessionId 时返回空串", "", emptySid.get("sessionId"));

        ask.nextSessionId = "srv-2";
        Map<String, Object> withCtx = json(toolText(callTool(mcp, "xiaoai_ask",
                args("text", "带上下文", "context", "用户喜欢简洁"))));
        T.eq("ask 接受 string context", "用户喜欢简洁", ask.lastContext);
        T.eq("ask string context 仍成功", Boolean.TRUE, withCtx.get("ok"));

        Map<String, Object> ctxObj = Json.obj();
        ctxObj.put("k", "v");
        callTool(mcp, "xiaoai_ask", args("text", "对象上下文", "context", ctxObj));
        T.ok("ask 接受 object context", ask.lastContext instanceof Map);
        T.eq("ask object context 内容", "v",
                ((Map<?, ?>) ask.lastContext).get("k"));
    }

    // ------------------------------------------------------------- ask 错误

    private static void askErrors() {
        FakeAsk ask = new FakeAsk();
        AgentMcp mcp = mcp(null, ask);

        Map<String, Object> noHost = callTool(new AgentMcp(VERSION), "xiaoai_ask",
                args("text", "x"));
        T.eq("ask host=null isError", Boolean.TRUE, noHost.get("isError"));
        T.eq("ask host=null INTERNAL", "INTERNAL", json(toolText(noHost)).get("code"));

        ask.ok = false;
        ask.errorCode = "XIAOI_OFFLINE";
        ask.message = "小爱不在线";
        Map<String, Object> offline = json(toolText(callTool(mcp, "xiaoai_ask",
                args("text", "x"))));
        T.eq("ask XIAOI_OFFLINE", "XIAOI_OFFLINE", offline.get("code"));
        T.eq("ask 离线 retryable", Boolean.TRUE, offline.get("retryable"));
        T.eq("ask 离线 isError", Boolean.TRUE,
                callTool(mcp, "xiaoai_ask", args("text", "x")).get("isError"));

        ask.errorCode = "TIMEOUT";
        ask.message = "";
        T.eq("ask TIMEOUT 映射", "TIMEOUT",
                json(toolText(callTool(mcp, "xiaoai_ask", args("text", "x")))).get("code"));

        ask.errorCode = "CONFLICT";
        ask.message = "会话正忙";
        T.eq("ask CONFLICT 映射", "CONFLICT",
                json(toolText(callTool(mcp, "xiaoai_ask", args("text", "x")))).get("code"));

        ask.errorCode = "SOMETHING_NEW";
        T.eq("ask 未知错误码 -> INTERNAL", "INTERNAL",
                json(toolText(callTool(mcp, "xiaoai_ask", args("text", "x")))).get("code"));

        ask.ok = true;
        ask.nullResult = true;
        T.eq("ask host 返回 null -> INTERNAL", "INTERNAL",
                json(toolText(callTool(mcp, "xiaoai_ask", args("text", "x")))).get("code"));

        ask.nullResult = false;
        ask.throwIt = true;
        T.eq("ask host 抛异常 -> INTERNAL", "INTERNAL",
                json(toolText(callTool(mcp, "xiaoai_ask", args("text", "x")))).get("code"));

        ask.throwIt = false;
        ask.calls = 0;
        T.eq("ask 缺 text INVALID_ARGUMENT", "INVALID_ARGUMENT",
                json(toolText(callTool(mcp, "xiaoai_ask", args()))).get("code"));
        T.eq("ask 空 text 不调用 host", 0, ask.calls);
    }

    // ------------------------------------------------------------- ask 上限

    private static void askLimits() {
        FakeAsk ask = new FakeAsk();
        AgentMcp mcp = mcp(null, ask);

        callTool(mcp, "xiaoai_ask", args("text", "x", "timeoutMs", 5000L));
        T.eq("ask 自定义 timeout 透传", 5000L, ask.lastTimeout);

        callTool(mcp, "xiaoai_ask", args("text", "x", "timeoutMs", 999999L));
        T.eq("ask timeout 上限 180000", 180000L, ask.lastTimeout);

        callTool(mcp, "xiaoai_ask", args("text", "x", "timeoutMs", 0L));
        T.eq("ask timeout=0 回默认", 120000L, ask.lastTimeout);

        callTool(mcp, "xiaoai_ask", args("text", "x", "timeoutMs", -5L));
        T.eq("ask 负 timeout 回默认", 120000L, ask.lastTimeout);

        String eightThousand = repeat("t", 8000);
        Map<String, Object> exact = callTool(mcp, "xiaoai_ask", args("text", eightThousand));
        T.eq("ask text=8000 接受", Boolean.FALSE, exact.get("isError"));
        T.eq("ask text=8000 原样透传", 8000, ask.lastText.length());

        ask.calls = 0;
        T.eq("ask text=8001 拒绝", "INVALID_ARGUMENT",
                json(toolText(callTool(mcp, "xiaoai_ask",
                        args("text", repeat("t", 8001))))).get("code"));
        T.eq("ask 超长 text 不调用 host", 0, ask.calls);

        String exactly8k = repeat("c", 8192);
        ask.ok = true;
        T.eq("ask context=8192 字节接受", Boolean.FALSE,
                callTool(mcp, "xiaoai_ask", args("text", "x", "context", exactly8k))
                        .get("isError"));
        T.eq("ask 边界 context 透传", 8192,
                ((String) ask.lastContext).length());

        ask.calls = 0;
        T.eq("ask context=8193 字节拒绝", "INVALID_ARGUMENT",
                json(toolText(callTool(mcp, "xiaoai_ask",
                        args("text", "x", "context", repeat("c", 8193))))).get("code"));
        T.eq("ask 超长 context 不调用 host", 0, ask.calls);

        StringBuilder chinese = new StringBuilder();
        for (int i = 0; i < 2731; i++) chinese.append('中'); // 8193 UTF-8 字节
        T.eq("ask 按 UTF-8 字节计算 context", "INVALID_ARGUMENT",
                json(toolText(callTool(mcp, "xiaoai_ask",
                        args("text", "x", "context", chinese.toString())))).get("code"));

        Map<String, Object> bigObject = Json.obj();
        bigObject.put("blob", repeat("z", 9000));
        T.eq("ask object context 超限拒绝", "INVALID_ARGUMENT",
                json(toolText(callTool(mcp, "xiaoai_ask",
                        args("text", "x", "context", bigObject)))).get("code"));

        T.eq("ask context 数字类型拒绝", "INVALID_ARGUMENT",
                json(toolText(callTool(mcp, "xiaoai_ask",
                        args("text", "x", "context", 5L)))).get("code"));
        T.eq("ask context 数组类型拒绝", "INVALID_ARGUMENT",
                json(toolText(callTool(mcp, "xiaoai_ask",
                        args("text", "x", "context", Json.arr())))).get("code"));
    }

    // ------------------------------------------------------------- tools/list

    @SuppressWarnings("unchecked")
    private static void toolTable() {
        AgentMcp mcp = new AgentMcp(VERSION);
        Map<String, Object> init = rpc(mcp, "initialize", 1L, null);
        Map<String, Object> initResult = (Map<String, Object>) init.get("result");
        String instructions = Json.optString(initResult, "instructions", "");
        T.ok("instructions 提到 xiaoai_notify", instructions.contains("xiaoai_notify"));
        T.ok("instructions 提到 xiaoai_ask", instructions.contains("xiaoai_ask"));
        T.ok("instructions 说明 notify 不能批准",
                instructions.contains("不能用于危险动作批准"));

        List<Object> tools = (List<Object>) mcp.toolsList().get("tools");
        T.eq("工具总数 6", 6, tools.size());
        T.eq("工具 4 是 notify", "xiaoai_notify",
                ((Map<?, ?>) tools.get(4)).get("name"));
        T.eq("工具 5 是 ask", "xiaoai_ask",
                ((Map<?, ?>) tools.get(5)).get("name"));

        Map<?, ?> notifySchema = (Map<?, ?>) ((Map<?, ?>) tools.get(4)).get("inputSchema");
        Map<?, ?> notifyProps = (Map<?, ?>) notifySchema.get("properties");
        T.ok("notify schema 有 text", notifyProps.containsKey("text"));
        T.ok("notify schema 有 channel", notifyProps.containsKey("channel"));
        T.ok("notify schema 有 force", notifyProps.containsKey("force"));
        T.eq("notify 必填 text", true,
                ((List<?>) notifySchema.get("required")).contains("text"));
        T.eq("notify channel 枚举", 2,
                ((List<?>) ((Map<?, ?>) notifyProps.get("channel")).get("enum")).size());
        T.eq("notify text maxLength 2000", 2000L,
                ((Map<?, ?>) notifyProps.get("text")).get("maxLength"));
        T.eq("notify additionalProperties=false", Boolean.FALSE,
                notifySchema.get("additionalProperties"));

        Map<?, ?> askSchema = (Map<?, ?>) ((Map<?, ?>) tools.get(5)).get("inputSchema");
        Map<?, ?> askProps = (Map<?, ?>) askSchema.get("properties");
        T.ok("ask schema 有 sessionId", askProps.containsKey("sessionId"));
        T.ok("ask schema 有 context", askProps.containsKey("context"));
        T.ok("ask schema 有 timeoutMs", askProps.containsKey("timeoutMs"));
        T.eq("ask 必填 text", true,
                ((List<?>) askSchema.get("required")).contains("text"));
        T.eq("ask text maxLength 8000", 8000L,
                ((Map<?, ?>) askProps.get("text")).get("maxLength"));
        T.eq("ask timeout 上限 180000", 180000L,
                ((Map<?, ?>) askProps.get("timeoutMs")).get("maximum"));
    }

    // ------------------------------------------------------------- BridgeAskHost / 8771

    private static void bridgeAskHost() throws Exception {
        BridgeEndpoint.setPort(0);
        T.eq("8771 未登记 -> XIAOI_OFFLINE", "XIAOI_OFFLINE",
                new BridgeAskHost("tok").ask("hi", "", null, 5000).errorCode);

        final AtomicReference<String> auth = new AtomicReference<String>();
        final AtomicReference<String> lastCmd = new AtomicReference<String>();
        final AtomicReference<String> mode = new AtomicReference<String>("ok");
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger submits = new AtomicInteger();

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/cli", new HttpHandler() {
            @Override
            public void handle(HttpExchange e) {
                auth.set(e.getRequestHeaders().getFirst("Authorization"));
                Map<String, String> q = Http.parseQuery(e.getRequestURI().toString());
                String cmd = q.get("cmd");
                lastCmd.set(cmd);
                String m = mode.get();
                if ("503".equals(m)) {
                    send(e, 503, "ERR cli service not ready");
                    return;
                }
                if ("err".equals(m)) {
                    send(e, 200, "ERR cli service not ready");
                    return;
                }
                boolean open = cmd != null && cmd.contains("session.open");
                if (open) {
                    opens.incrementAndGet();
                    if ("nosid".equals(m)) {
                        send(e, 200, "{\"id\":\"agent\",\"result\":{}}");
                    } else if ("topsid".equals(m)) {
                        send(e, 200, "{\"sessionId\":\"top-1\"}");
                    } else {
                        send(e, 200,
                                "{\"id\":\"agent\",\"result\":{\"sessionId\":\"srv-1\"}}");
                    }
                    return;
                }
                submits.incrementAndGet();
                if ("busy".equals(m)) {
                    send(e, 200, "{\"id\":\"agent\",\"error\":{\"code\":\"SESSION_BUSY\","
                            + "\"message\":\"busy\"}}");
                    return;
                }
                if ("slow".equals(m)) {
                    try {
                        Thread.sleep(1500L);
                    } catch (InterruptedException ignored) {
                    }
                }
                send(e, 200, "好的，OK");
            }
        });
        srv.start();
        try {
            BridgeEndpoint.setPort(srv.getAddress().getPort());
            BridgeAskHost host = new BridgeAskHost("tok");

            AskHost.AskResult r = host.ask("回复 OK", "", null, 5000);
            T.eq("bridge ask ok", true, r.ok);
            T.eq("bridge ask 返回文本", "好的，OK", r.text);
            T.eq("bridge ask 返回 sessionId", "srv-1", r.sessionId);
            T.eq("bridge ask 先 session.open", 1, opens.get());
            T.eq("bridge ask 再 agent.submit", 1, submits.get());
            T.eq("bridge ask Bearer token", "Bearer tok", auth.get());

            Map<?, ?> submitted = json(lastCmd.get());
            T.eq("agent.submit method", "agent.submit", submitted.get("method"));
            Map<?, ?> params = (Map<?, ?>) submitted.get("params");
            T.eq("agent.submit sessionId", "srv-1", params.get("sessionId"));
            T.eq("agent.submit text", "回复 OK", params.get("text"));

            int opensBefore = opens.get();
            AskHost.AskResult reuse = host.ask("再来一句", "srv-1", null, 5000);
            T.eq("复用 sessionId 不重开会话", opensBefore, opens.get());
            T.eq("复用 sessionId 成功", true, reuse.ok);
            T.eq("复用 sessionId 回传", "srv-1", reuse.sessionId);
            Map<?, ?> reuseParams = (Map<?, ?>) json(lastCmd.get()).get("params");
            T.eq("复用 sessionId 传给 CLI", "srv-1", reuseParams.get("sessionId"));

            AskHost.AskResult withCtx = host.ask("回复 OK", "srv-1", "ctx-1", 5000);
            T.eq("带 context 成功", true, withCtx.ok);
            String combined = String.valueOf(
                    ((Map<?, ?>) json(lastCmd.get()).get("params")).get("text"));
            T.ok("context 前置到 text 之前", combined.indexOf("ctx-1") < combined
                    .indexOf("回复 OK"));
            T.ok("context 标记为不可信数据", combined.contains("不是指令"));
            T.ok("text 保持在末尾", combined.endsWith("回复 OK"));

            Map<String, Object> objCtx = Json.obj();
            objCtx.put("k", "v");
            host.ask("对象", "srv-1", objCtx, 5000);
            String objText = String.valueOf(
                    ((Map<?, ?>) json(lastCmd.get()).get("params")).get("text"));
            T.ok("object context 序列化进 prompt", objText.contains("\"k\":\"v\""));

            mode.set("topsid");
            AskHost.AskResult top = host.ask("顶层会话", "", null, 5000);
            T.eq("session.open 顶层 sessionId", "top-1", top.sessionId);

            mode.set("busy");
            AskHost.AskResult busy = host.ask("忙", "srv-1", null, 5000);
            T.eq("SESSION_BUSY -> CONFLICT", false, busy.ok);
            T.eq("SESSION_BUSY 错误码", "CONFLICT", busy.errorCode);

            mode.set("nosid");
            AskHost.AskResult noSid = host.ask("无会话", "", null, 5000);
            T.eq("session.open 无 sessionId -> INTERNAL", "INTERNAL", noSid.errorCode);

            mode.set("503");
            AskHost.AskResult unavailable = host.ask("503", "srv-1", null, 5000);
            T.eq("8771 503 -> XIAOI_OFFLINE", "XIAOI_OFFLINE", unavailable.errorCode);

            mode.set("err");
            AskHost.AskResult notReady = host.ask("未就绪", "srv-1", null, 5000);
            T.eq("ERR not ready -> XIAOI_OFFLINE", "XIAOI_OFFLINE", notReady.errorCode);

            AskHost.AskResult openNotReady = host.ask("开会话未就绪", "", null, 5000);
            T.eq("session.open ERR -> XIAOI_OFFLINE", "XIAOI_OFFLINE", openNotReady.errorCode);

            mode.set("ok");
            AskHost.AskResult tooLong = host.ask(repeat("中", 4000), "srv-1", null, 5000);
            T.eq("URL 过长 -> INVALID_ARGUMENT", "INVALID_ARGUMENT", tooLong.errorCode);

            int submitsBefore = submits.get();
            mode.set("slow");
            AskHost.AskResult timeout = host.ask("慢", "srv-1", null, 400);
            T.eq("超时 -> TIMEOUT", "TIMEOUT", timeout.errorCode);
            T.eq("超时不伪造回复", false, timeout.ok);
            T.ok("慢请求确实发到了 CLI", submits.get() > submitsBefore);
        } finally {
            BridgeEndpoint.setPort(0);
            srv.stop(0);
        }
    }

    // ------------------------------------------------------------- 辅助

    private static AgentMcp mcp(NotifyHost notify, AskHost ask) {
        return new AgentMcp(VERSION, null, null, null, notify, ask);
    }

    private static ActionRegistry registry() {
        return new ActionRegistry();
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = Json.obj();
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                m.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return m;
    }

    private static Map<String, Object> callTool(AgentMcp mcp, String name,
                                                Map<String, Object> arguments) {
        Map<String, Object> params = Json.obj();
        params.put("name", name);
        if (arguments != null) params.put("arguments", arguments);
        Map<String, Object> response = rpc(mcp, "tools/call", 100L, params);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        return result;
    }

    private static Map<String, Object> rpc(AgentMcp mcp, String method, long id,
                                           Map<String, Object> params) {
        Map<String, Object> req = Json.obj();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) req.put("params", params);
        return json(mcp.handle(Json.write(req), registry()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String text) {
        Object v = Json.read(text);
        return (Map<String, Object>) v;
    }

    private static String toolText(Map<String, Object> tool) {
        List<?> content = (List<?>) tool.get("content");
        Map<?, ?> part = (Map<?, ?>) content.get(0);
        return Json.optString(part, "text", "");
    }

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) sb.append(s);
        return sb.toString();
    }

    private static void send(HttpExchange e, int code, String body) {
        try {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(code, data.length);
            OutputStream out = e.getResponseBody();
            try {
                out.write(data);
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        } finally {
            e.close();
        }
    }

    /** 只记录调用，不碰 Android。 */
    private static final class FakeNotify implements NotifyHost {
        final List<String> channels = new ArrayList<String>();
        final List<String> texts = new ArrayList<String>();
        final List<Boolean> forces = new ArrayList<Boolean>();
        boolean islandOk = true;
        boolean notificationOk = true;
        boolean islandFallbackSuccess;
        boolean throwIsland;
        boolean throwAll;
        boolean nullIsland;

        @Override
        public NotifyResult notify(String channel, String text, boolean force) {
            channels.add(channel);
            texts.add(text);
            forces.add(force);
            if (throwAll) throw new IllegalStateException("boom");
            if ("island".equals(channel)) {
                if (throwIsland) throw new IllegalStateException("island boom");
                if (nullIsland) return null;
                if (islandFallbackSuccess) {
                    return notificationOk
                            ? NotifyResult.success("notification", true)
                            : NotifyResult.failure("notification", "NOT_AVAILABLE", true);
                }
                if (!islandOk) {
                    return NotifyResult.failure("island", "XIAOI_OFFLINE", false);
                }
            } else if (!notificationOk) {
                return NotifyResult.failure("notification", "PERMISSION_DENIED", false);
            }
            return NotifyResult.success(channel, false);
        }
    }

    /** 记录参数，按需返回成功/失败。 */
    private static final class FakeAsk implements AskHost {
        int calls;
        String lastText = "";
        String lastSessionId = "";
        Object lastContext;
        long lastTimeout = -1;
        boolean ok = true;
        boolean nullResult;
        boolean throwIt;
        String nextText = "OK";
        String nextSessionId = "srv-1";
        String sessionIdOut = "srv-1";
        String errorCode = "";
        String message = "";

        @Override
        public AskResult ask(String text, String sessionId, Object context, long timeoutMs) {
            calls++;
            lastText = text;
            lastSessionId = sessionId;
            lastContext = context;
            lastTimeout = timeoutMs;
            if (throwIt) throw new IllegalStateException("ask boom");
            if (nullResult) return null;
            if (!ok) return AskResult.failure(errorCode, message);
            String sid = sessionIdOut == null ? nextSessionId : sessionIdOut;
            return AskResult.success(nextText, sid);
        }
    }
}
