package plus.xiaoi.module.application.config;

/** 配置写入后的外部效应（纯 JVM 端口）：回填已应用哈希、请求小爱 reload。 */
public interface ConfigEffectsPort {

    /** 保存成功后回填 watcher 的内容哈希，避免自己写文件触发回环。 */
    void onApplied();

    /** 请求控制面通知小爱重新拉配置；控制面不在线时返回 false。 */
    boolean requestReload();
}