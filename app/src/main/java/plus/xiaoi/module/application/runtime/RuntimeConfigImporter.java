package plus.xiaoi.module.application.runtime;

/** 从运行中的小爱导入配置（应用层端口）。 */
public interface RuntimeConfigImporter {

    /** @return true = 已导入并写入 config.json。 */
    boolean importFromRuntime();
}