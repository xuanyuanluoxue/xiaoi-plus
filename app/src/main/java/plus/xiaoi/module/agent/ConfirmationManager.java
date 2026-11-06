package plus.xiaoi.module.agent;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次性确认 token（纯 JVM，内存实现）。
 *
 * <p>risk=confirm 的动作第一次调用不执行，而是签发一个 token：绑定 clientId（空串
 * 归一化为 {@code default}）、动作名、参数 canonical hash、签发时间与过期时间。
 * 客户端带 token 再调用时校验绑定并原子消费；也可以走 {@code xiaoai_confirm}
 * 批准/拒绝。token 只存在内存里，服务重启即失效（持久化留给后续）。</p>
 *
 * <p>安全约定：token 用 {@link SecureRandom} 生成，至少 16 字节；拒绝/过期/已用
 * 返回稳定错误码；token 不写日志、不写审计。token 元数据只保存参数 hash，pending
 * 请求本身留在内存里以便批准后执行；审计/日志只使用 argNames 与 hash，绝不输出
 * 参数值。该类所有方法线程安全。</p>
 */
public final class ConfirmationManager {

    /** 默认有效期：60 秒（PLAN 5.3）。 */
    public static final long DEFAULT_TTL_MS = 60_000L;
    /** 同时在途的确认上限，防止内存无限增长。 */
    public static final int MAX_PENDING = 256;
    /** clientId 为空时的审计/绑定标签。 */
    public static final String DEFAULT_CLIENT = "default";
    /** token 随机字节数：24 字节 = 48 个十六进制字符。 */
    public static final int TOKEN_BYTES = 24;

    private static final int STATE_PENDING = 0;
    private static final int STATE_USED = 1;
    private static final int STATE_DENIED = 2;

    /** 可注入的时钟，测试用；生产用系统时间。 */
    public interface Now {
        long nowMs();
    }

    /** 签发结果。 */
    public static final class Issue {
        public final String token;
        public final long expiresInMs;
        public final String argHash;

        private Issue(String token, long expiresInMs, String argHash) {
            this.token = token;
            this.expiresInMs = expiresInMs;
            this.argHash = argHash;
        }
    }

    /** 消费/拒绝/批准结果；失败时 {@link #error} 非空。 */
    public static final class Result {
        public final boolean ok;
        public final AgentError error;
        public final ActionRequest request;

        private Result(boolean ok, AgentError error, ActionRequest request) {
            this.ok = ok;
            this.error = error;
            this.request = request;
        }

        static Result ok(ActionRequest request) {
            return new Result(true, null, request);
        }

        static Result fail(AgentError.Code code, String message) {
            return new Result(false, AgentError.of(code, message), null);
        }

        public String code() {
            return error == null ? "" : error.code.wire;
        }
    }

    private static final class Pending {
        final String token;
        final String clientId;
        final ActionRequest request;
        final String argHash;
        final long createdAt;
        final long expiresAt;
        int state = STATE_PENDING;

        Pending(String token, String clientId, ActionRequest request, String argHash,
                long createdAt, long expiresAt) {
            this.token = token;
            this.clientId = clientId;
            this.request = request;
            this.argHash = argHash;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        boolean expired(long now) {
            return now >= expiresAt;
        }
    }

    private final long ttlMs;
    private final Now now;
    private final SecureRandom random = new SecureRandom();
    /** 插入序：容量满时淘汰最早签发的一项。 */
    private final LinkedHashMap<String, Pending> pending =
            new LinkedHashMap<String, Pending>(16, 0.75f, false);
    private final Object lock = new Object();

    public ConfirmationManager() {
        this(DEFAULT_TTL_MS, null);
    }

    public ConfirmationManager(long ttlMs) {
        this(ttlMs, null);
    }

    public ConfirmationManager(long ttlMs, Now now) {
        this.ttlMs = ttlMs > 0 ? ttlMs : DEFAULT_TTL_MS;
        this.now = now == null ? new Now() {
            @Override
            public long nowMs() {
                return System.currentTimeMillis();
            }
        } : now;
    }

    public long ttlMs() {
        return ttlMs;
    }

    /**
     * 为一个 risk=confirm 的请求签发 token。不执行动作，不记录参数值。
     *
     * @throws IllegalArgumentException request 为空
     */
    public Issue issue(ActionRequest request) {
        if (request == null) throw new IllegalArgumentException("request 不能为空");
        long nowMs = now.nowMs();
        String argHash = CanonicalArgs.hash(request.args);
        String clientId = normalizeClient(request.clientId);
        synchronized (lock) {
            purgeExpired(nowMs);
            while (pending.size() >= MAX_PENDING) {
                Iterator<String> it = pending.keySet().iterator();
                if (!it.hasNext()) break;
                it.next();
                it.remove();
            }
            String token = newToken();
            pending.put(token, new Pending(token, clientId, request, argHash,
                    nowMs, nowMs + ttlMs));
            return new Issue(token, ttlMs, argHash);
        }
    }

    /**
     * 校验并一次性消费 token。绑定不符（clientId/action/args）不泄露细节，
     * 统一返回 CONFIRMATION_DENIED；过期/已用分别返回 EXPIRED / CONFLICT。
     */
    public Result consume(String token, ActionRequest request) {
        if (token == null || token.trim().length() == 0 || request == null) {
            return Result.fail(AgentError.Code.CONFIRMATION_DENIED, "确认无效");
        }
        long nowMs = now.nowMs();
        synchronized (lock) {
            Pending p = pending.get(token);
            if (p == null) {
                return Result.fail(AgentError.Code.CONFIRMATION_DENIED, "确认无效");
            }
            if (p.expired(nowMs)) {
                pending.remove(token);
                return Result.fail(AgentError.Code.CONFIRMATION_EXPIRED, "确认已过期");
            }
            if (p.state == STATE_USED) {
                return Result.fail(AgentError.Code.CONFLICT, "确认已被使用");
            }
            if (p.state == STATE_DENIED) {
                return Result.fail(AgentError.Code.CONFIRMATION_DENIED, "确认已被拒绝");
            }
            if (!p.clientId.equals(normalizeClient(request.clientId))
                    || !p.request.action.equals(request.action)
                    || !p.argHash.equals(CanonicalArgs.hash(request.args))) {
                return Result.fail(AgentError.Code.CONFIRMATION_DENIED,
                        "确认与当前请求不匹配");
            }
            p.state = STATE_USED;
            return Result.ok(p.request);
        }
    }

    /**
     * 拒绝一个待确认 token。返回 {@code CONFIRMATION_DENIED}（request 非空表示
     * 确实标记了拒绝，可用于审计）；重复拒绝同样返回 DENIED，已用返回 CONFLICT。
     */
    public Result deny(String token) {
        long nowMs = now.nowMs();
        synchronized (lock) {
            Lookup found = lookup(token, nowMs);
            if (found.error != null) return found.error;
            Pending value = found.value;
            value.state = STATE_DENIED;
            return new Result(false, AgentError.of(AgentError.Code.CONFIRMATION_DENIED,
                    "确认已被拒绝：" + value.request.action), value.request);
        }
    }

    /**
     * 供 {@code xiaoai_confirm(approve=true)} 使用：原子取走 pending 请求并标记已用。
     * 返回后同一 token 再消费/批准都只能得到 CONFLICT（未过期时）。
     */
    public Result takeApproved(String token) {
        long nowMs = now.nowMs();
        synchronized (lock) {
            Lookup found = lookup(token, nowMs);
            if (found.error != null) return found.error;
            Pending value = found.value;
            value.state = STATE_USED;
            return Result.ok(value.request);
        }
    }

    /** 当前保留的 token 数（含已用/已拒绝，未过期的）。测试/诊断用。 */
    public int size() {
        long nowMs = now.nowMs();
        synchronized (lock) {
            purgeExpired(nowMs);
            return pending.size();
        }
    }

    /** 清空全部状态（测试/服务停止用）。 */
    public void clear() {
        synchronized (lock) {
            pending.clear();
        }
    }

    // ------------------------------------------------------------- 内部

    private static final class Lookup {
        final Pending value;
        final Result error;

        Lookup(Pending value, Result error) {
            this.value = value;
            this.error = error;
        }
    }

    private Lookup lookup(String token, long nowMs) {
        if (token == null || token.trim().length() == 0) {
            return new Lookup(null, Result.fail(AgentError.Code.CONFIRMATION_DENIED,
                    "确认无效"));
        }
        Pending p = pending.get(token);
        if (p == null) {
            return new Lookup(null, Result.fail(AgentError.Code.CONFIRMATION_DENIED,
                    "确认无效"));
        }
        if (p.expired(nowMs)) {
            pending.remove(token);
            return new Lookup(null, Result.fail(AgentError.Code.CONFIRMATION_EXPIRED,
                    "确认已过期"));
        }
        if (p.state == STATE_USED) {
            return new Lookup(null, Result.fail(AgentError.Code.CONFLICT,
                    "确认已被使用"));
        }
        if (p.state == STATE_DENIED) {
            return new Lookup(null, Result.fail(AgentError.Code.CONFIRMATION_DENIED,
                    "确认已被拒绝"));
        }
        return new Lookup(p, null);
    }

    private void purgeExpired(long nowMs) {
        Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expired(nowMs)) it.remove();
        }
    }

    private String newToken() {
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] bytes = new byte[TOKEN_BYTES];
            random.nextBytes(bytes);
            String token = hex(bytes);
            if (!pending.containsKey(token)) return token;
        }
        // 理论上不可达；仍保证唯一性，绝不返回重复 token。
        String token;
        do {
            byte[] bytes = new byte[TOKEN_BYTES];
            random.nextBytes(bytes);
            token = hex(bytes);
        } while (pending.containsKey(token));
        return token;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private static String normalizeClient(String clientId) {
        String s = clientId == null ? "" : clientId.trim();
        return s.length() == 0 ? DEFAULT_CLIENT : s;
    }
}
