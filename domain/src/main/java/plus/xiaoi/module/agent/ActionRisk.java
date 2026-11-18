package plus.xiaoi.module.agent;

/**
 * 动作风险等级（纯 JVM，无 android 依赖）。
 *
 * <p>wire 值与 MCP / capabilities 输出保持一致：safe / confirm / danger。
 * WP1.1 只消费该等级，确认流程在 WP1.3 接入。</p>
 */
public enum ActionRisk {

    /** 只读或可逆的低风险动作，可直接执行。 */
    SAFE("safe"),
    /** 有可见副作用，执行前需要用户确认。 */
    CONFIRM("confirm"),
    /** 默认拒绝的高风险动作，需要 App 内显式授权。 */
    DANGER("danger");

    public final String wire;

    ActionRisk(String wire) {
        this.wire = wire;
    }

    /** 是否有可见副作用（confirm / danger）。 */
    public boolean needsConfirmation() {
        return this != SAFE;
    }

    /** 按 wire 值解析；未知/空值返回 fallback。 */
    public static ActionRisk fromWire(String wire, ActionRisk fallback) {
        if (wire != null) {
            for (ActionRisk r : values()) {
                if (r.wire.equals(wire)) return r;
            }
        }
        return fallback;
    }
}
