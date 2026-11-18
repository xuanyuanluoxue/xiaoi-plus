package plus.xiaoi.module.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 主执行器 + root 兜底的组合执行器（纯 JVM）。
 *
 * <p>只在一个条件下回退：主执行器返回 {@code NOT_AVAILABLE}。成功、
 * {@code PERMISSION_DENIED}、{@code TIMEOUT}、{@code INTERNAL} 等一律原样返回，
 * 避免对可能有副作用的动作重复执行（例如通知、闹钟）。</p>
 *
 * <p>执行器名是 {@code android+root}；capabilities 会显示组合执行器的可用性，
 * 细节（root 是否已授权）由 {@link RootExecutor#available()} 缓存决定，
 * 首次真正需要 root 时才申请授权，不在服务启动时弹 su。</p>
 */
public final class FallbackExecutor implements ActionExecutor {

    private final ActionExecutor primary;
    private final ActionExecutor fallback;

    public FallbackExecutor(ActionExecutor primary, ActionExecutor fallback) {
        if (primary == null) throw new IllegalArgumentException("primary 不能为空");
        if (fallback == null) throw new IllegalArgumentException("fallback 不能为空");
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String name() {
        return primary.name() + "+" + fallback.name();
    }

    @Override
    public boolean available() {
        try {
            if (primary.available()) return true;
        } catch (Throwable ignored) {
        }
        try {
            return fallback.available();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public ActionResult execute(ActionRequest request) throws Exception {
        ActionResult first = primary.execute(request);
        if (!shouldFallback(first)) return first;

        ActionResult second;
        try {
            second = fallback.execute(request);
        } catch (ActionExecutor.TimeoutException t) {
            throw t;
        } catch (Exception t) {
            // 用主执行器的失败结果兜底，但保留“已尝试 root”的事实，避免调用方
            // 误以为只是 android 失败。
            return withWarning(first, "root 兜底执行失败（" + t.getClass().getSimpleName() + "）");
        }
        if (second == null) return first;
        if (second.ok) {
            return withWarning(second, "android 返回 NOT_AVAILABLE，已通过 root 完成");
        }
        List<String> warnings = new ArrayList<String>(second.warnings);
        warnings.add("android 与 root 均失败；root 错误码 "
                + (second.error == null ? "INTERNAL" : second.error.code.wire));
        return new ActionResult(false, second.action, name(), second.result, warnings,
                second.error, second.elapsedMs, second.requestId, second.auditId);
    }

    private static boolean shouldFallback(ActionResult r) {
        if (r == null) return false;
        return !r.ok && r.error != null && r.error.code == AgentError.Code.NOT_AVAILABLE;
    }

    private ActionResult withWarning(ActionResult r, String warning) {
        List<String> warnings = new ArrayList<String>(r.warnings);
        if (!warnings.contains(warning)) warnings.add(warning);
        return new ActionResult(r.ok, r.action, name(),
                r.result, warnings, r.error, r.elapsedMs, r.requestId, r.auditId);
    }
}
