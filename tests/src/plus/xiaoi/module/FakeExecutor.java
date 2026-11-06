package plus.xiaoi.module;

import java.util.List;
import java.util.Map;

import plus.xiaoi.module.agent.ActionExecutor;
import plus.xiaoi.module.agent.ActionRequest;
import plus.xiaoi.module.agent.ActionResult;

/**
 * 仅测试用的执行器：记录调用，可设置可用性、延迟、异常与自定义返回。
 * 放在 tests/ 下，不进入 APK。
 */
public final class FakeExecutor implements ActionExecutor {

    public final String name;

    public boolean available = true;
    public int calls;
    public ActionRequest last;
    public long delayMs;
    public Exception failure;
    public ActionResult response;
    public boolean returnNull;

    public FakeExecutor(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public ActionResult execute(ActionRequest request) throws Exception {
        calls++;
        last = request;
        if (delayMs > 0) Thread.sleep(delayMs);
        if (failure != null) throw failure;
        if (returnNull) return null;
        if (response != null) return response;
        Map<String, Object> result = Json.obj();
        result.put("echo", request.action);
        return ActionResult.ok(request.action, name, result);
    }
}
