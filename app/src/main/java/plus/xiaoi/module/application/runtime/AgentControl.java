package plus.xiaoi.module.application.runtime;

/** 8770 设备操作接口的启停（应用层端口）。 */
public interface AgentControl {

    /** 按当前配置启动或停止 8770 服务；内部自行判断 token/开关。 */
    void startIfEnabled();

    /** 显式停止 8770 服务。 */
    void stop();
}