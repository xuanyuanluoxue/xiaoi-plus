package plus.xiaoi.module.bridge;

import android.content.Context;

/**
 * 数据面生命周期运行时端口（WP3）。
 *
 * <p>{@code BridgeLifecycle} 只依赖本接口；Android 侧由 {@code Bridge} 提供实现。
 * 这样 {@code Bridge ↔ BridgeLifecycle} 的类型级双向依赖被切断，
 * 生命周期状态机可以单独审查/测试。</p>
 *
 * <p>方法均为“当前事实/立即动作”，实现方不得阻塞超过一次 socket 操作。</p>
 */
public interface BridgeRuntime {

    Context appContext();

    String processName();

    /** 每轮从共享配置刷新 token/LAN/MCP 策略。 */
    void refreshPolicyFromConfig();

    /** Context 晚就绪时补绑 CLI。 */
    void ensureCliBinding();

    boolean shouldListen();

    boolean hasFacade();

    boolean hasCli();

    boolean isServerRunning();

    void stopServer();

    boolean isBoundLan();

    boolean bindsLan();

    boolean startServerIfLeader();

    int port();

    /** 控制面兜底拉一次配置。 */
    void controlRefresh();

    /** server 状态变化后上报 port/bindHost。 */
    void controlOnBridgeStarted();
}