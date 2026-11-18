package plus.xiaoi.module.application.config;

/** 配置层日志端口（纯 JVM；测试用内存实现，Android 用 Log.w）。 */
public interface ConfigLogPort {

    void warn(String tag, String message);

    ConfigLogPort NONE = new ConfigLogPort() {
        @Override
        public void warn(String tag, String message) {
        }
    };
}