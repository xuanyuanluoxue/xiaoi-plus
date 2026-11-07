package plus.xiaoi.module;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import plus.xiaoi.module.core.CapabilityRegistry;
import plus.xiaoi.module.core.CapabilityRegistry.CapabilityState;
import plus.xiaoi.module.core.CapabilityRegistry.CapabilityStatus;
import plus.xiaoi.module.core.CapabilityRegistry.Counts;
import plus.xiaoi.module.core.CapabilityRegistry.Descriptor;
import plus.xiaoi.module.core.CapabilityRegistry.Domain;
import plus.xiaoi.module.core.CapabilityRegistry.Source;
import plus.xiaoi.module.core.CapabilityRegistry.StatusCounts;

/**
 * CapabilityRegistry：67 条、ID 唯一、三域计数、状态映射、find/计数、深链白名单。
 * 运行时状态不写死在 descriptor 里（defaultState 只是未探测视图）。
 */
public final class CapabilityRegistryTest {

    private CapabilityRegistryTest() {}

    public static int run() {
        List<Descriptor> all = CapabilityRegistry.all();
        T.eq("能力总数 67", 67, all.size());

        Set<String> ids = new HashSet<>();
        boolean unique = true;
        boolean prefixOk = true;
        boolean textOk = true;
        boolean linksOk = true;
        boolean exposedOk = true;
        int withLink = 0;
        for (Descriptor d : all) {
            if (!ids.add(d.id)) unique = false;
            if (!d.id.startsWith(d.domain.prefix)) prefixOk = false;
            if (d.title.isEmpty() || d.summary.isEmpty() || d.channel.isEmpty()) {
                textOk = false;
            }
            if (d.exposedTo == null) {
                exposedOk = false;
            }
            if (d.deepLink != null) {
                withLink++;
                if (!CapabilityRegistry.ROUTES.contains(d.deepLink)) linksOk = false;
            }
        }
        T.ok("ID 唯一", unique);
        T.ok("ID 前缀与域一致", prefixOk);
        T.ok("标题/摘要/通道非空", textOk);
        T.ok("exposedTo 非 null", exposedOk);
        T.ok("deepLink 都在路由白名单内", linksOk);
        T.ok("存在可跳转的能力详情", withLink > 0);

        T.eq("小爱域 26 条", 26, CapabilityRegistry.byDomain(Domain.XIAOAI).size());
        T.eq("系统域 31 条", 31, CapabilityRegistry.byDomain(Domain.SYSTEM).size());
        T.eq("桥域 10 条", 10, CapabilityRegistry.byDomain(Domain.BRIDGE).size());
        T.eq("null 域返回空", 0, CapabilityRegistry.byDomain(null).size());

        Counts c = CapabilityRegistry.counts();
        T.eq("可用 25", 25, c.available);
        T.eq("需授权/部分 5", 5, c.permission);
        T.eq("规划 26", 26, c.planned);
        T.eq("不提供 11", 11, c.unsupported);
        T.eq("四档合计 67", 67, c.total());

        // 状态映射：✅→IMPLEMENTED，🧪→PARTIAL，📋→PLANNED，⛔→UNSUPPORTED
        T.eq("A1 ✅", Source.IMPLEMENTED, CapabilityRegistry.find("A1").source);
        T.eq("A15 🧪", Source.PARTIAL, CapabilityRegistry.find("A15").source);
        T.eq("A8 📋", Source.PLANNED, CapabilityRegistry.find("A8").source);
        T.eq("A18 ⛔", Source.UNSUPPORTED, CapabilityRegistry.find("A18").source);
        T.eq("B3 部分实现", Source.PARTIAL, CapabilityRegistry.find("B3").source);
        T.eq("B6 部分实现", Source.PARTIAL, CapabilityRegistry.find("B6").source);
        T.eq("B8 部分实现", Source.PARTIAL, CapabilityRegistry.find("B8").source);
        T.eq("C10 实验", Source.PARTIAL, CapabilityRegistry.find("C10").source);

        // 未探测视图：不把运行时状态写死在 descriptor
        T.eq("A1 默认 AVAILABLE", CapabilityStatus.AVAILABLE,
                CapabilityRegistry.find("A1").defaultStatus());
        T.eq("A15 默认 PERMISSION_MISSING", CapabilityStatus.PERMISSION_MISSING,
                CapabilityRegistry.find("A15").defaultStatus());
        T.eq("A8 默认 PLANNED", CapabilityStatus.PLANNED,
                CapabilityRegistry.find("A8").defaultStatus());
        T.eq("A18 默认 UNSUPPORTED", CapabilityStatus.UNSUPPORTED,
                CapabilityRegistry.find("A18").defaultStatus());

        CapabilityState disabled = CapabilityRegistry.find("A1")
                .withState(CapabilityStatus.DISABLED, "已停用", 123L);
        T.eq("withState 保留 id", "A1", disabled.id);
        T.eq("withState 使用运行时状态", CapabilityStatus.DISABLED, disabled.status);
        T.eq("withState detail", "已停用", disabled.detail);
        T.eq("withState 时间", 123L, disabled.lastCheckedAt);

        List<CapabilityState> defaults = CapabilityRegistry.defaultStates();
        T.eq("默认状态条数", 67, defaults.size());
        StatusCounts sc = CapabilityRegistry.counts(defaults);
        T.eq("状态计数可用", 25, sc.available);
        T.eq("状态计数需授权", 5, sc.permissionMissing);
        T.eq("状态计数规划", 26, sc.planned);
        T.eq("状态计数不提供", 11, sc.unsupported);
        T.eq("状态计数合计", 67, sc.total());

        List<CapabilityState> extra = new ArrayList<>();
        extra.add(CapabilityRegistry.find("A1").withState(CapabilityStatus.AVAILABLE));
        extra.add(CapabilityRegistry.find("A2").withState(CapabilityStatus.DISABLED));
        extra.add(CapabilityRegistry.find("A3").withState(CapabilityStatus.UNAVAILABLE));
        extra.add(null);
        StatusCounts ec = CapabilityRegistry.counts(extra);
        T.eq("额外计数 DISABLED", 1, ec.disabled);
        T.eq("额外计数 UNAVAILABLE", 1, ec.unavailable);
        T.eq("null 状态被忽略", 3, ec.total());
        T.eq("null 状态列表安全", 0, CapabilityRegistry.counts(null).total());

        // find / 标题 / 暴露面
        T.eq("A1 标题", "模型重定向", CapabilityRegistry.find("A1").title);
        T.eq("C7 标题", "执行设备动作", CapabilityRegistry.find("C7").title);
        T.eq("find 忽略大小写", "A1", CapabilityRegistry.find("a1").id);
        T.eq("find 未知返回 null", null, CapabilityRegistry.find("Z99"));
        T.eq("find null 返回 null", null, CapabilityRegistry.find(null));
        T.ok("A4 暴露给 8771", CapabilityRegistry.find("A4").exposedTo
                .contains(CapabilityRegistry.MCP8771));
        T.ok("C1 暴露给外部 Agent", CapabilityRegistry.find("C1").exposedTo
                .contains(CapabilityRegistry.EXTERNAL));

        T.ok("静态表只读", throwsOnAdd(CapabilityRegistry.all()));
        T.ok("路由白名单包含 Model", CapabilityRegistry.isKnownRoute("Model"));
        T.ok("未知路由不在白名单", !CapabilityRegistry.isKnownRoute("Bogus"));

        return T.report("CapabilityRegistryTest");
    }

    private static boolean throwsOnAdd(List<Descriptor> list) {
        try {
            list.add(null);
            return false;
        } catch (UnsupportedOperationException expected) {
            return true;
        }
    }
}
