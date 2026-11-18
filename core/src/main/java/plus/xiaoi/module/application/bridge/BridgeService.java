package plus.xiaoi.module.application.bridge;

/**
 * 数据面生命周期决策（WP3，纯 JVM）。
 *
 * <p>从 {@code BridgeLifecycle.tick()} 抽出的决策树；只回答“下一步做什么”，
 * 不做锁/线程/socket。执行语义（stopping stale server、acquire 失败 return、
 * 成功后重算）由适配器负责，见 WP3-BRIDGE.md §3.2。</p>
 */
public final class BridgeService {

    public enum Action {
        /** 关闭监听并释放 leader（MCP 关闭/无能力）。 */
        CLOSE,
        /** 没有 facade，宽限期内先让位。 */
        WAIT_FACADE,
        /** 非 leader：先停残留 server，再尝试拿锁；失败必须 return。 */
        ACQUIRE_LEADER,
        /** 已 leader，但绑定模式（LAN/弱 token）变化，需要重绑。 */
        REBIND,
        /** 已 leader，server 未运行，尝试监听。 */
        START,
        /** 已 leader 且 server 状态正确。 */
        NONE
    }

    public static final class State {
        public boolean shouldListen;
        public boolean hasFacade;
        public boolean hasCli;
        public boolean leader;
        /** 无 facade 宽限期是否已过（>= 30s）。 */
        public boolean facadeGraceElapsed;
        public boolean serverRunning;
        public boolean boundLan;
        public boolean bindsLan;
    }

    public static final class Decision {
        public final Action action;
        public final String reason;

        public Decision(Action action, String reason) {
            this.action = action;
            this.reason = reason == null ? "" : reason;
        }
    }

    private BridgeService() {}

    /** 决策顺序必须与 0.15 的 tick 一致；任何顺序调整都可能改变 leader 接管时序。 */
    public static Decision next(State s) {
        if (s == null) return new Decision(Action.CLOSE, "状态为空");
        if (!s.shouldListen) {
            return new Decision(Action.CLOSE, "MCP 关闭或 token 为空");
        }
        if (!s.hasFacade && !s.hasCli) {
            return new Decision(Action.CLOSE, "本进程没有 facade/CLI");
        }
        if (!s.leader) {
            // 现有语义：只看 hasFacade，有 CLI 但没有 facade 的进程也要让位。
            if (!s.hasFacade && !s.facadeGraceElapsed) {
                return new Decision(Action.WAIT_FACADE,
                        "本进程暂无 facade，先等待有 facade 的进程成为 leader");
            }
            return new Decision(Action.ACQUIRE_LEADER, "非 leader，尝试接管");
        }
        if (s.serverRunning && s.boundLan != s.bindsLan) {
            return new Decision(Action.REBIND, "数据面绑定模式变化，重新绑定");
        }
        if (!s.serverRunning) {
            return new Decision(Action.START, "leader 确保监听");
        }
        return new Decision(Action.NONE, "");
    }
}