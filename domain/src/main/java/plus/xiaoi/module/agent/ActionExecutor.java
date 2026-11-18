package plus.xiaoi.module.agent;

/**
 * 动作执行器接口（纯 JVM）。
 *
 * <p>Android / root / Shizuku 实现都在 WP1.2 之后接入；WP1.1 只用 fake 覆盖契约。
 * 执行器只负责“怎么执行”，参数校验、确认、超时映射、错误信封由
 * {@link ActionRegistry} 统一处理。</p>
 */
public interface ActionExecutor {

    /** 执行器名：android / shizuku / root。 */
    String name();

    /** 当前设备/权限下是否可用；抛异常视为不可用。 */
    boolean available();

    /**
     * 执行一次动作。
     *
     * <p>失败可以直接返回 {@link ActionResult#failure}，也可以抛异常；异常由 registry
     * 映射成稳定错误码。执行器自身无法在预算内完成时应抛 {@link TimeoutException}。</p>
     */
    ActionResult execute(ActionRequest request) throws Exception;

    /** 执行器自身超时；registry 统一映射为 TIMEOUT。 */
    final class TimeoutException extends Exception {
        public TimeoutException(String message) {
            super(message);
        }
    }
}
