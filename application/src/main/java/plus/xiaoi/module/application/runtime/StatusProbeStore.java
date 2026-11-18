package plus.xiaoi.module.application.runtime;

import plus.xiaoi.module.StatusResolver;

/**
 * 端点校验结论缓存（应用层端口）。
 *
 * <p>read/save 直接收 baseUrl/apiKey/model，由实现内部计算指纹并校验 TTL；
 * 调用方不需要、也不应该自己拼指纹。</p>
 */
public interface StatusProbeStore {

    StatusResolver.Probe read(String baseUrl, String apiKey, String model);

    void save(String baseUrl, String apiKey, String model, StatusResolver.Probe probe);
}