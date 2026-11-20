package plus.xiaoi.module;

import plus.xiaoi.module.application.hook.HookStatus;

/**
 * hook 就绪度：聚合规则、xiaoai_status 摘要、版本惰性补读、改写是否生效。
 *
 * <p>关键负例：没装载任何 hook 时必须是 none；从未重置过（App 进程）时不追加摘要，
 * 免得把「不适用」报成 none 误导排查。改写状态必须区分「装上了」和「生效了」：
 * 只有 getter 真被读过才算 hit，只观察到官方调用则是 none。</p>
 */
public final class HookStatusTest {

    private HookStatusTest() {}

    /** 可编排的假版本来源：模拟「早期读不到、后来读到」的时序。 */
    private static final class FakeProbe implements HookStatus.Probe {
        String version;
        boolean whitelisted;
        boolean explode;

        FakeProbe(String version, boolean whitelisted) {
            this.version = version;
            this.whitelisted = whitelisted;
        }

        @Override
        public String versionName() {
            if (explode) throw new IllegalStateException("boom");
            return version;
        }

        @Override
        public boolean whitelisted(String v) {
            return whitelisted;
        }
    }

    public static int run() {
        // 从未装载：摘要为空，状态行保持原样
        HookStatus.setProbe(null);
        T.eq("未重置时摘要为空", "", HookStatus.summary());
        T.eq("未重置时 hooks=none", HookStatus.HOOKS_NONE, HookStatus.hooks());
        T.eq("未重置时改写未知", "?", HookStatus.rewrite());

        // 白名单命中但一个 hook 都没挂上（理论上不该发生）
        HookStatus.reset("8.2.70.8421", HookStatus.COMPAT_WHITELIST);
        T.eq("白名单但零 hook", HookStatus.HOOKS_NONE, HookStatus.hooks());
        T.eq("白名单摘要",
                "hooks=none compat=whitelist xiaoai=8.2.70.8421 rewrite=?",
                HookStatus.summary());

        // 只挂上配置 → partial
        HookStatus.markConfig(true);
        T.eq("只挂配置 → partial", HookStatus.HOOKS_PARTIAL, HookStatus.hooks());

        // 核心四项全中 → ok（调用统计不计入门槛）
        HookStatus.markProvider(true);
        HookStatus.markVision(true);
        HookStatus.markFacade(true);
        T.eq("核心四项 → ok", HookStatus.HOOKS_OK, HookStatus.hooks());
        T.eq("ok 摘要",
                "hooks=ok compat=whitelist xiaoai=8.2.70.8421 rewrite=?",
                HookStatus.summary());

        // 只有调用统计（不影响 ok 门槛，但属于 partial）
        HookStatus.reset("8.3.0", HookStatus.COMPAT_WHITELIST);
        HookStatus.markUsage(true);
        T.eq("只有统计 → partial", HookStatus.HOOKS_PARTIAL, HookStatus.hooks());

        // 版本不在白名单：必须 none，且仍上报真实版本，便于对方判断要不要适配
        HookStatus.reset("8.3.0", HookStatus.COMPAT_NONE);
        T.eq("不在白名单 → none", HookStatus.HOOKS_NONE, HookStatus.hooks());
        T.eq("不在白名单仍报版本",
                "hooks=none compat=none xiaoai=8.3.0 rewrite=?", HookStatus.summary());

        // 早期加载拿不到版本且没有版本来源：保持 probe + 未知
        HookStatus.setProbe(null);
        HookStatus.reset("", HookStatus.COMPAT_PROBE);
        T.eq("无来源时摘要", "hooks=none compat=probe xiaoai=? rewrite=?", HookStatus.summary());

        // 有来源但此刻仍读不到（Application 未就绪）→ 仍是 probe
        HookStatus.reset("", HookStatus.COMPAT_PROBE);
        HookStatus.setProbe(new FakeProbe(null, false));
        T.eq("来源返回 null 仍未知",
                "hooks=none compat=probe xiaoai=? rewrite=?", HookStatus.summary());

        // 上报时补读到真实版本：compat 从 probe 修正为 none（这才是真结论）
        HookStatus.reset("", HookStatus.COMPAT_PROBE);
        HookStatus.setProbe(new FakeProbe("8.3.0", false));
        T.eq("补读后修正为 none",
                "hooks=none compat=none xiaoai=8.3.0 rewrite=?", HookStatus.summary());

        // 补读到白名单版本 → 修正为 whitelist
        HookStatus.reset("", HookStatus.COMPAT_PROBE);
        HookStatus.setProbe(new FakeProbe("8.2.70.8421", true));
        T.eq("补读后修正为 whitelist",
                "hooks=none compat=whitelist xiaoai=8.2.70.8421 rewrite=?", HookStatus.summary());

        // 补读只做一次：后续 hook 打点不会把版本冲掉
        HookStatus.markConfig(true);
        T.eq("补读结果保持",
                "hooks=partial compat=whitelist xiaoai=8.2.70.8421 rewrite=?",
                HookStatus.summary());

        // 版本来源自己炸掉：不许冒泡，保持未知
        HookStatus.reset("", HookStatus.COMPAT_PROBE);
        FakeProbe boom = new FakeProbe("9.9.9", true);
        boom.explode = true;
        HookStatus.setProbe(boom);
        T.eq("来源异常不外抛",
                "hooks=none compat=probe xiaoai=? rewrite=?", HookStatus.summary());

        // ---- 改写是否生效 ----
        HookStatus.setProbe(null);
        HookStatus.reset("8.2.70.8421", HookStatus.COMPAT_WHITELIST);
        // 只有官方调用、getter 一次没被读 → 装上了但没生效
        HookStatus.markCall();
        T.eq("有调用无改写 → none", "none", HookStatus.rewrite());
        T.eq("none 摘要含 rewrite=none",
                "hooks=none compat=whitelist xiaoai=8.2.70.8421 rewrite=none",
                HookStatus.summary());
        T.ok("none 时 rewriteHit=false", !HookStatus.rewriteHit());

        // getter 被读到 → hit，且不可逆（有调用之后依然 hit）
        HookStatus.markRewrite();
        T.eq("改写命中 → hit", "hit", HookStatus.rewrite());
        T.ok("hit 时 rewriteHit=true", HookStatus.rewriteHit());
        HookStatus.markCall();
        T.eq("命中后再有调用仍 hit", "hit", HookStatus.rewrite());

        // reset 必须清掉改写状态，否则换进程/重装后会误报 hit
        HookStatus.reset("8.2.70.8421", HookStatus.COMPAT_WHITELIST);
        T.eq("reset 清空改写状态", "?", HookStatus.rewrite());
        T.ok("reset 后 rewriteHit=false", !HookStatus.rewriteHit());

        // reset 也要清掉 hook 打点（进程内重复 handleLoadPackage）
        T.eq("reset 清空打点", HookStatus.HOOKS_NONE, HookStatus.hooks());

        return T.report("HookStatusTest");
    }
}
