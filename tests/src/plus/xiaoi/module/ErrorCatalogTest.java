package plus.xiaoi.module;

import plus.xiaoi.module.core.ErrorCatalog;

/**
 * ErrorCatalog 纯 JVM 测试：每个错误码都有 title + action；
 * 超级岛前置判断按「令牌 → MCP → 小爱 → 端口 → 控制面新鲜度」的顺序给出原因。
 */
public final class ErrorCatalogTest {

    private ErrorCatalogTest() {}

    public static int run() {
        for (ErrorCatalog.Code code : ErrorCatalog.Code.values()) {
            ErrorCatalog.Entry e = ErrorCatalog.of(code);
            T.ok("目录项 " + code + " 有 title", e.title.length() > 0);
            T.ok("目录项 " + code + " 有 action", e.action.length() > 0);
            T.ok("目录项 " + code + " message 含 title",
                    e.message().contains(e.title));
            T.ok("目录项 " + code + " detail 两行", e.detail().contains("\n"));
        }
        T.eq("未知 code 有兜底文案", "操作失败；查看日志后重试",
                ErrorCatalog.of(null).message());

        // 前置判断：顺序即用户可行动顺序
        T.eq("无 token 优先 NO_TOKEN", ErrorCatalog.Code.NO_TOKEN,
                ErrorCatalog.islandBlocked(false, true, true, true, 8771));
        T.eq("未开 MCP 优先 MCP_OFF", ErrorCatalog.Code.MCP_OFF,
                ErrorCatalog.islandBlocked(true, false, true, true, 8771));
        T.eq("控制面未连接 → NO_XIAOI", ErrorCatalog.Code.NO_XIAOI,
                ErrorCatalog.islandBlocked(true, true, false, false, 0));
        T.eq("端口 0 → NO_XIAOI", ErrorCatalog.Code.NO_XIAOI,
                ErrorCatalog.islandBlocked(true, true, true, true, 0));
        T.eq("控制面过旧 → CONTROL_OFFLINE", ErrorCatalog.Code.CONTROL_OFFLINE,
                ErrorCatalog.islandBlocked(true, true, true, false, 8771));
        T.eq("全部正常返回 null", null,
                ErrorCatalog.islandBlocked(true, true, true, true, 8771));

        return T.report("ErrorCatalogTest");
    }
}
