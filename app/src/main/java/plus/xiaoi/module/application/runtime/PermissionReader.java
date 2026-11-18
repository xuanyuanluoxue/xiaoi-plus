package plus.xiaoi.module.application.runtime;

import plus.xiaoi.module.core.PermissionSnapshot;

/** 权限矩阵事实（应用层端口）。 */
public interface PermissionReader {

    /** 读取当前权限快照；实现必须失败即降级，不抛异常。 */
    PermissionSnapshot capture();
}