package plus.xiaoi.module;

import plus.xiaoi.module.application.bridge.BridgeService;

/** WP3：数据面生命周期决策树穷举。 */
public final class BridgeServiceTest {

    private BridgeServiceTest() {}

    public static int run() {
        BridgeService.State s;

        s = state();
        s.shouldListen = false;
        s.leader = true;
        s.serverRunning = true;
        eq("关闭优先于一切", BridgeService.Action.CLOSE, s);

        s = state();
        s.hasFacade = false;
        s.hasCli = false;
        eq("无 facade/CLI → CLOSE", BridgeService.Action.CLOSE, s);

        // 非 leader：只看 hasFacade；有 CLI 但无 facade 也要等待
        s = state();
        s.hasFacade = false;
        s.hasCli = true;
        s.facadeGraceElapsed = false;
        eq("无 facade + CLI + 宽限期内 → WAIT", BridgeService.Action.WAIT_FACADE, s);

        s = state();
        s.hasFacade = false;
        s.hasCli = true;
        s.facadeGraceElapsed = true;
        eq("无 facade + CLI + 宽限期外 → ACQUIRE", BridgeService.Action.ACQUIRE_LEADER, s);

        s = state();
        s.hasFacade = true;
        s.facadeGraceElapsed = false;
        eq("有 facade → 直接 ACQUIRE", BridgeService.Action.ACQUIRE_LEADER, s);

        // leader 分支
        s = state();
        s.leader = true;
        s.serverRunning = true;
        s.boundLan = true;
        s.bindsLan = false;
        eq("绑定模式变化 → REBIND", BridgeService.Action.REBIND, s);

        s = state();
        s.leader = true;
        s.serverRunning = false;
        eq("server 未运行 → START", BridgeService.Action.START, s);

        s = state();
        s.leader = true;
        s.serverRunning = true;
        s.boundLan = false;
        s.bindsLan = false;
        eq("server 已在正确模式 → NONE", BridgeService.Action.NONE, s);

        // 非 leader 的残留 server 不改变决策（由执行层先 stopServer）
        s = state();
        s.serverRunning = true;
        eq("非 leader + 残留 server → ACQUIRE", BridgeService.Action.ACQUIRE_LEADER, s);

        BridgeService.Decision d = BridgeService.next(null);
        T.eq("null state → CLOSE", BridgeService.Action.CLOSE, d.action);

        d = BridgeService.next(state());
        T.ok("决策带原因文案", d.reason.length() > 0 || d.action == BridgeService.Action.NONE);

        return T.report("BridgeServiceTest");
    }

    private static void eq(String name, BridgeService.Action expected, BridgeService.State s) {
        T.eq(name, expected, BridgeService.next(s).action);
    }

    private static BridgeService.State state() {
        BridgeService.State s = new BridgeService.State();
        s.shouldListen = true;
        s.hasFacade = true;
        s.hasCli = true;
        s.leader = false;
        s.facadeGraceElapsed = true;
        s.serverRunning = false;
        s.boundLan = false;
        s.bindsLan = false;
        return s;
    }
}