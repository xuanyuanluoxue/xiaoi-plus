package plus.xiaoi.module.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 权限快照（纯模型，无 android 依赖）。
 *
 * <p>Android 侧探针（通知/精确闹钟/全屏/电池优化）由 UI 子层实现，这里只保存
 * 探针结论，方便 {@link IssueResolver} 与 JVM 测试复用。缺省 {@link State#UNKNOWN}
 * 表示「未探测」，不当作拒绝，避免在没有标准 API 的机型上制造假问题。</p>
 */
public final class PermissionSnapshot {

    public enum State {
        /** 已授权/已符合。 */
        GRANTED,
        /** 明确拒绝或系统拒绝。 */
        DENIED,
        /** 未探测、无法确认。 */
        UNKNOWN,
        /** 该 Android 版本/设备不需要。 */
        NOT_APPLICABLE
    }

    public final State notifications;
    public final State exactAlarm;
    public final State fullScreenIntent;
    public final State batteryOptimization;
    public final State usageAccess;
    public final State notificationListener;
    public final long capturedAt;

    private PermissionSnapshot(Builder b) {
        notifications = b.notifications;
        exactAlarm = b.exactAlarm;
        fullScreenIntent = b.fullScreenIntent;
        batteryOptimization = b.batteryOptimization;
        usageAccess = b.usageAccess;
        notificationListener = b.notificationListener;
        capturedAt = b.capturedAt;
    }

    /** 全 UNKNOWN：安全默认，不产生权限类 issue。 */
    public static PermissionSnapshot unknown() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 需要用户处理（明确拒绝）的权限名；顺序与诊断页一致。 */
    public List<String> missing() {
        ArrayList<String> out = new ArrayList<>();
        addIfDenied(out, "notifications", notifications);
        addIfDenied(out, "exactAlarm", exactAlarm);
        addIfDenied(out, "fullScreenIntent", fullScreenIntent);
        addIfDenied(out, "batteryOptimization", batteryOptimization);
        addIfDenied(out, "usageAccess", usageAccess);
        addIfDenied(out, "notificationListener", notificationListener);
        return Collections.unmodifiableList(out);
    }

    public int missingCount() {
        return missing().size();
    }

    private static void addIfDenied(List<String> out, String name, State state) {
        if (state == State.DENIED) out.add(name);
    }

    public static final class Builder {
        private State notifications = State.UNKNOWN;
        private State exactAlarm = State.UNKNOWN;
        private State fullScreenIntent = State.UNKNOWN;
        private State batteryOptimization = State.UNKNOWN;
        private State usageAccess = State.UNKNOWN;
        private State notificationListener = State.UNKNOWN;
        private long capturedAt = System.currentTimeMillis();

        public Builder notifications(State s) {
            notifications = state(s);
            return this;
        }

        public Builder exactAlarm(State s) {
            exactAlarm = state(s);
            return this;
        }

        public Builder fullScreenIntent(State s) {
            fullScreenIntent = state(s);
            return this;
        }

        public Builder batteryOptimization(State s) {
            batteryOptimization = state(s);
            return this;
        }

        public Builder usageAccess(State s) {
            usageAccess = state(s);
            return this;
        }

        public Builder notificationListener(State s) {
            notificationListener = state(s);
            return this;
        }

        public Builder capturedAt(long at) {
            capturedAt = at;
            return this;
        }

        public PermissionSnapshot build() {
            return new PermissionSnapshot(this);
        }

        private static State state(State s) {
            return s == null ? State.UNKNOWN : s;
        }
    }
}
