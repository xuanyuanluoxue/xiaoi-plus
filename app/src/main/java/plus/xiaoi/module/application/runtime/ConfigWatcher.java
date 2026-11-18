package plus.xiaoi.module.application.runtime;

/**
 * config.json 文件监听（应用层端口）。
 *
 * <p>启动包含首次加载/迁移/apply 副作用，必须在 Activity onCreate 等明确的
 * 生命周期点调用，不能在 composition 期间隐式触发。</p>
 */
public interface ConfigWatcher {

    void start();
}