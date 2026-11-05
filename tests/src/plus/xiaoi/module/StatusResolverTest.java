package plus.xiaoi.module;

/** 状态推导（纯 JVM）：规则顺序、探针映射、错误分类。 */
public final class StatusResolverTest {

    private StatusResolverTest() {}

    private static StatusResolver.Input in(boolean provider, boolean model, boolean key,
                                           boolean enabled, boolean checking,
                                           StatusResolver.Probe probe) {
        StatusResolver.Input i = new StatusResolver.Input();
        i.hasProvider = provider;
        i.hasModel = model;
        i.keySet = key;
        i.enabled = enabled;
        i.checking = checking;
        i.probe = probe;
        return i;
    }

    public static int run() {
        // 1. 缺服务商 / 模型
        T.eq("无服务商 → 未配置", AppStatus.NOT_CONFIGURED,
                StatusResolver.resolve(in(false, false, true, false, false,
                        StatusResolver.Probe.UNKNOWN)).status);
        T.eq("无模型 → 未配置", AppStatus.NOT_CONFIGURED,
                StatusResolver.resolve(in(true, false, true, true, false,
                        StatusResolver.Probe.UNKNOWN)).status);

        // 2. 暂停
        T.eq("暂停且无密钥 → 未配置引导", AppStatus.NOT_CONFIGURED,
                StatusResolver.resolve(in(true, true, false, false, false,
                        StatusResolver.Probe.UNKNOWN)).status);
        T.eq("暂停有密钥 → 已暂停", AppStatus.PAUSED,
                StatusResolver.resolve(in(true, true, true, false, false,
                        StatusResolver.Probe.UNKNOWN)).status);

        // 3. 启用但无密钥
        T.eq("启用无密钥 → 已启用", AppStatus.ENABLED,
                StatusResolver.resolve(in(true, true, false, true, false,
                        StatusResolver.Probe.UNKNOWN)).status);

        // 4. 校验中（优先于上次结论）
        T.eq("校验中", AppStatus.CHECKING,
                StatusResolver.resolve(in(true, true, true, true, true,
                        StatusResolver.Probe.OK)).status);

        // 5. 探针结果
        T.eq("探针 OK", AppStatus.OK,
                StatusResolver.resolve(in(true, true, true, true, false,
                        StatusResolver.Probe.OK)).status);
        T.eq("探针 auth", AppStatus.AUTH,
                StatusResolver.resolve(in(true, true, true, true, false,
                        StatusResolver.Probe.AUTH)).status);
        T.eq("探针 model", AppStatus.MODEL,
                StatusResolver.resolve(in(true, true, true, true, false,
                        StatusResolver.Probe.MODEL)).status);
        T.eq("探针 endpoint", AppStatus.ENDPOINT,
                StatusResolver.resolve(in(true, true, true, true, false,
                        StatusResolver.Probe.ENDPOINT)).status);

        // 6. 未校验
        T.eq("未校验 → 已启用", AppStatus.ENABLED,
                StatusResolver.resolve(in(true, true, true, true, false,
                        StatusResolver.Probe.UNKNOWN)).status);

        // 7. 文案不为空且随状态变化
        T.ok("未配置有副文案", StatusResolver.resolve(in(false, false, false, false, false,
                StatusResolver.Probe.UNKNOWN)).detail.length() > 0);
        T.ok("可用文案含「重新校验」", StatusResolver.resolve(in(true, true, true, true, false,
                StatusResolver.Probe.OK)).detail.contains("重新校验"));

        // 8. 状态 → 探针的回路
        T.eq("OK 回投影", StatusResolver.Probe.OK, StatusResolver.probeOf(AppStatus.OK));
        T.eq("AUTH 回投影", StatusResolver.Probe.AUTH, StatusResolver.probeOf(AppStatus.AUTH));
        T.eq("MODEL 回投影", StatusResolver.Probe.MODEL, StatusResolver.probeOf(AppStatus.MODEL));
        T.eq("ENDPOINT 回投影", StatusResolver.Probe.ENDPOINT,
                StatusResolver.probeOf(AppStatus.ENDPOINT));
        T.eq("已暂停回投影 UNKNOWN", StatusResolver.Probe.UNKNOWN,
                StatusResolver.probeOf(AppStatus.PAUSED));

        // 9. 枚举语义
        T.ok("AUTH 是错误态", AppStatus.AUTH.isError());
        T.ok("OK 是成功态", AppStatus.OK.isOk());
        T.ok("PAUSED 是暂停态", AppStatus.PAUSED.isPaused());
        T.eq("标题统一", "可用", AppStatus.OK.title());

        // 10. 校验指纹：同配置稳定、配置变化即失配
        String fp1 = Hash.sha256Hex("https://a|sk|m");
        T.eq("指纹稳定", fp1, Hash.sha256Hex("https://a|sk|m"));
        T.ok("换密钥指纹变化", !fp1.equals(Hash.sha256Hex("https://a|sk2|m")));
        T.ok("换模型指纹变化", !fp1.equals(Hash.sha256Hex("https://a|sk|m2")));
        T.eq("空串哈希长度", 64, Hash.sha256Hex("").length());

        return T.report("StatusResolverTest");
    }
}
