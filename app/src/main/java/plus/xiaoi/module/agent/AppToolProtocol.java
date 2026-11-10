package plus.xiaoi.module.agent;

import java.util.List;
import java.util.Map;

import plus.xiaoi.module.Json;

/**
 * 小米笔记 AppToolProvider 的纯 JVM 协议层：请求构造、响应解析、结构化错误。
 *
 * 只依赖 {@link Json}，因此可以在桌面单测里逐字节验证。Android 侧的 binder
 * 发现/绑定/超时在 {@link AppToolClient}，两边共用这里的常量与结果模型。
 */
public final class AppToolProtocol {

    public static final String ACTION_PROVIDER =
            "com.aios.osbot.action.APP_TOOL_PROVIDER";
    public static final String NOTES_PACKAGE = "com.miui.notes";
    public static final String NOTES_SERVICE =
            "com.miui.notes.agent.tool.provider.NoteToolService";
    public static final String TOOL_NAME = "todo_create";

    /** bindService 到 onServiceConnected 的上限。 */
    public static final long BIND_TIMEOUT_MS = 5000L;
    /** 单次 handleRequest 的上限；binder 调用本身不可中断，超时后只放弃结果。 */
    public static final long CALL_TIMEOUT_MS = 5000L;

    public static final String HINT =
            "可改用 xiaoai_submit 兜底（会经过 LLM，不保证一次成功）";

    private AppToolProtocol() {}

    /** 工具执行结果；失败时 text 已经是结构化 JSON。 */
    public static final class Result {
        public final boolean ok;
        public final String text;
        public final String code;

        private Result(boolean ok, String text, String code) {
            this.ok = ok;
            this.text = text == null ? "" : text;
            this.code = code == null ? "" : code;
        }
    }

    public static Result ok(String text) {
        return new Result(true, text, "OK");
    }

    public static Result error(String code, String message) {
        return error(code, message, "");
    }

    public static Result error(String code, String message, String detail) {
        return new Result(false, errorJson(code, message, detail), code);
    }

    /** content 留空时的确定性默认值：一项，文本就是 title。 */
    public static String defaultContent(String title) {
        return "- [ ] " + (title == null ? "" : title.trim());
    }

    /** 构造 Notes provider 需要的 JSON-RPC tools/call 请求。 */
    public static String buildTodoRequest(String title, String content) {
        Map<String, Object> args = Json.obj();
        args.put("title", title == null ? "" : title);
        args.put("content", content == null ? "" : content);

        Map<String, Object> params = Json.obj();
        params.put("name", TOOL_NAME);
        params.put("arguments", args);

        Map<String, Object> req = Json.obj();
        req.put("jsonrpc", "2.0");
        req.put("id", 1L);
        req.put("method", "tools/call");
        req.put("params", params);
        return Json.write(req);
    }

    /**
     * 解析 provider 返回：
     * {"result":{"content":[{"type":"text","text":"..."}],"isError":false}}。
     * JSON-RPC error / 业务 isError / 空 content 都转成结构化错误，不抛异常。
     */
    public static Result parseResponse(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return error("EMPTY_RESPONSE", "Notes provider 返回空响应");
        }
        Object rootValue;
        try {
            rootValue = Json.read(raw);
        } catch (Throwable t) {
            return error("INVALID_RESPONSE", "Notes provider 返回的不是 JSON", brief(raw));
        }
        if (!(rootValue instanceof Map)) {
            return error("INVALID_RESPONSE", "Notes provider 响应不是 JSON 对象", brief(raw));
        }
        Map<?, ?> root = (Map<?, ?>) rootValue;

        Object rpcError = root.get("error");
        if (rpcError instanceof Map) {
            Map<?, ?> e = (Map<?, ?>) rpcError;
            long code = Json.optLong(e, "code", 0);
            String message = Json.optString(e, "message", "");
            return error("PROVIDER_ERROR", "Notes JSON-RPC error " + code + ": " + message,
                    brief(raw));
        }

        Object resultValue = root.get("result");
        if (!(resultValue instanceof Map)) {
            return error("INVALID_RESPONSE", "Notes 响应缺少 result 对象", brief(raw));
        }
        Map<?, ?> result = (Map<?, ?>) resultValue;

        Object contentValue = result.get("content");
        if (!(contentValue instanceof List) || ((List<?>) contentValue).isEmpty()) {
            return error("EMPTY_RESULT", "Notes 响应缺少 result.content[0].text", brief(raw));
        }
        Object first = ((List<?>) contentValue).get(0);
        if (!(first instanceof Map)) {
            return error("INVALID_RESPONSE", "Notes result.content[0] 不是对象", brief(raw));
        }
        String text = Json.optString((Map<?, ?>) first, "text", "");
        if (text == null || text.trim().length() == 0) {
            return error("EMPTY_RESULT", "Notes 返回的文本为空", brief(raw));
        }
        if (Json.optBoolean(result, "isError", false)) {
            return error("PROVIDER_ERROR", "Notes 工具执行失败", text);
        }
        return ok(text);
    }

    /** 统一的结构化错误；MCP 会把它放进 content[0].text 并置 isError=true。 */
    public static String errorJson(String code, String message, String detail) {
        Map<String, Object> o = Json.obj();
        o.put("ok", Boolean.FALSE);
        o.put("error", code == null ? "UNKNOWN" : code);
        o.put("message", message == null ? "" : message);
        if (detail != null && detail.length() > 0) o.put("detail", brief(detail));
        o.put("hint", HINT);
        return Json.write(o);
    }

    private static String brief(String s) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > 500 ? oneLine.substring(0, 500) + "…" : oneLine;
    }
}
