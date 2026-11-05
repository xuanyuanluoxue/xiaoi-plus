package plus.xiaoi.module;

/**
 * 全站统一的状态词汇（纯 JVM）。
 *
 * 主页 Hero、配置页状态条、日志/关于页面的提示都从这里取标题，
 * 不再各自拼「已激活 / 可用 / 正常」。
 */
public enum AppStatus {

    NOT_CONFIGURED("未配置"),
    PAUSED("已暂停"),
    ENABLED("已启用"),
    CHECKING("校验中"),
    OK("可用"),
    AUTH("密钥无效"),
    MODEL("模型不存在"),
    ENDPOINT("端点不可达");

    private final String title;

    AppStatus(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    /** 需要用户处理的错误态（红色）。 */
    public boolean isError() {
        return this == AUTH || this == MODEL || this == ENDPOINT;
    }

    /** 已经过真实探针确认可用。 */
    public boolean isOk() {
        return this == OK;
    }

    public boolean isPaused() {
        return this == PAUSED;
    }

    public boolean isNotConfigured() {
        return this == NOT_CONFIGURED;
    }
}
