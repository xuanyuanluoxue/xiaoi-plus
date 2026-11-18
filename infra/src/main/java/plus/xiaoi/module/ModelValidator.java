package plus.xiaoi.module;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * 模型连通性校验（纯 JVM，无 android 依赖，可桌面单测）。
 *
 * 用真实探针代替"字段填没填"的伪校验：向 baseUrl 发一个 max_tokens=1 的
 * 最小 chat/completions 请求，按响应区分：
 *   ok      200 且返回了 choices
 *   auth    401/403：密钥无效或被拒
 *   model   404，或 400/422 且报错提到 model：模型不在该端点
 *   endpoint DNS/连接失败：地址写错或网络不通
 *   timeout 8 秒无响应
 *   http    其它 HTTP 错误（带响应摘要）
 *   empty   200 但没有 choices（可能不是 OpenAI 兼容端点）
 */
public final class ModelValidator {

    private ModelValidator() {}

    public static final class Result {
        public final boolean ok;
        /** ok / auth / model / endpoint / timeout / http / empty */
        public final String code;
        public final String message;
        public final long latencyMs;

        Result(boolean ok, String code, String message, long latencyMs) {
            this.ok = ok;
            this.code = code;
            this.message = message;
            this.latencyMs = latencyMs;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 8000;

    public static Result check(String baseUrl, String apiKey, String model) {
        long t0 = System.currentTimeMillis();
        if (baseUrl == null || baseUrl.trim().length() == 0) {
            return new Result(false, "endpoint", "端点未填写", 0);
        }
        if (model == null || model.trim().length() == 0) {
            return new Result(false, "model", "模型未填写", 0);
        }
        String base = baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base + "/chat/completions";

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && apiKey.trim().length() > 0) {
                c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
            Map<String, Object> body = Json.obj();
            body.put("model", model.trim());
            body.put("max_tokens", Long.valueOf(1));
            body.put("stream", Boolean.FALSE);
            java.util.List<Object> msgs = Json.arr();
            Map<String, Object> m = Json.obj();
            m.put("role", "user");
            m.put("content", "ping");
            msgs.add(m);
            body.put("messages", msgs);
            byte[] bytes = Json.write(body).getBytes("UTF-8");
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.close();

            int code = c.getResponseCode();
            String resp = readAll(c, code >= 400);
            long ms = System.currentTimeMillis() - t0;

            if (code == 200) {
                if (resp.length() == 0) {
                    return new Result(false, "empty", "空响应", ms);
                }
                Object v;
                try {
                    v = Json.read(resp);
                } catch (Throwable t) {
                    return new Result(false, "empty", "响应不是 JSON（可能不是 OpenAI 兼容端点）", ms);
                }
                if (v instanceof Map) {
                    Map<?, ?> o = (Map<?, ?>) v;
                    Object err = o.get("error");
                    if (err != null) {
                        return new Result(false, "model", "上游报错：" + brief(err), ms);
                    }
                    Object choices = o.get("choices");
                    if (choices instanceof java.util.List && !((java.util.List<?>) choices).isEmpty()) {
                        return new Result(true, "ok", "可用 · " + ms + "ms", ms);
                    }
                }
                return new Result(false, "empty", "200 但无 choices", ms);
            }
            if (code == 401 || code == 403) {
                return new Result(false, "auth", "密钥无效或被拒（" + code + "）", ms);
            }
            if (code == 404) {
                return new Result(false, "model", "模型或端点不存在（404）", ms);
            }
            if (code == 400 || code == 422) {
                String msg = brief(resp);
                boolean looksModel = msg.toLowerCase().contains("model");
                return new Result(false, looksModel ? "model" : "http",
                        "请求被拒（" + code + "）：" + msg, ms);
            }
            return new Result(false, "http", "HTTP " + code + "：" + brief(resp), ms);
        } catch (java.net.SocketTimeoutException e) {
            return new Result(false, "timeout", "超时（" + (READ_TIMEOUT_MS / 1000) + "s）",
                    System.currentTimeMillis() - t0);
        } catch (java.net.UnknownHostException e) {
            return new Result(false, "endpoint", "域名解析失败：" + e.getMessage(),
                    System.currentTimeMillis() - t0);
        } catch (Throwable e) {
            return new Result(false, "endpoint", "端点不可达：" + e.getMessage(),
                    System.currentTimeMillis() - t0);
        } finally {
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String readAll(HttpURLConnection c, boolean errorStream) {
        try {
            InputStream in = errorStream ? c.getErrorStream() : c.getInputStream();
            if (in == null) return "";
            byte[] buf = new byte[8192];
            int n, off = 0;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            in.close();
            return new String(buf, 0, off, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String brief(Object o) {
        String s = String.valueOf(o);
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
