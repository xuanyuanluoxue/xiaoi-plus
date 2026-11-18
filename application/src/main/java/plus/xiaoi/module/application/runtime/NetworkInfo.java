package plus.xiaoi.module.application.runtime;

/** 本机连接信息端口（WP11）：默认端口与本机 IPv4，UI 不认识 Bridge / Lan / AgentHttpServer。 */
public interface NetworkInfo {

    /** 8771 能力桥的起始端口（未运行时展示用）。 */
    int defaultBridgePort();

    /** 8770 设备操作接口的默认端口。 */
    int defaultAgentPort();

    /** 本机 IPv4；取不到返回空串。 */
    String lanIpv4();
}
