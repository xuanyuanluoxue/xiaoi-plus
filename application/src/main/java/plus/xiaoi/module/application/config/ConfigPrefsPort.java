package plus.xiaoi.module.application.config;

import plus.xiaoi.module.AppConfig;

/**
 * 配置 prefs 镜像端口（应用层定义，Android 适配器实现）。
 *
 * <p>读：缺文件/损坏时生成骨架所需的默认值；写：保存后供小爱进程跨进程读取。</p>
 */
public interface ConfigPrefsPort {

    ConfigDefaults readDefaults();

    /** 必须同步落盘（commit），返回 false 表示写入失败。 */
    boolean write(AppConfig config);
}