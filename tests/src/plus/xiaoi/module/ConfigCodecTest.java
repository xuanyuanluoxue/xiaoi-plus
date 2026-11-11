package plus.xiaoi.module;

import java.util.List;
import java.util.Map;

/** ConfigCodec 测试：round-trip / 归一化 / 校验 / 迁移 / 未知字段。 */
public final class ConfigCodecTest {

    private ConfigCodecTest() {}

    private static final String CANONICAL =
            "{\"version\":3,\"rev\":1,\"enabled\":true,\"marker\":true,\"markerTemplate\":\"[模型: {model}] \","
            + "\"logLevel\":\"standard\","
            + "\"activeProviderId\":\"crazyai\",\"activeModelId\":\"your-model\","
            + "\"mcp\":{\"enabled\":true,\"lan\":false,\"agentLan\":false,\"token\":\"tok-123\"},"
            + "\"providers\":[{\"id\":\"crazyai\",\"name\":\"CrashMail\","
            + "\"baseUrl\":\"https://api.example.com/v1\",\"apiKey\":\"sk-abc\","
            + "\"models\":[{\"id\":\"your-model\",\"name\":\"GLM\"},"
            + "{\"id\":\"deepseek-v4.1-flash\",\"name\":\"DS\"}]}]}";

    public static int run() throws Exception {
        // ------------------------------------------------------------ round-trip（逐字节）
        try {
            AppConfig c = ConfigCodec.fromJson(CANONICAL);
            eq("round-trip byte identical", CANONICAL, ConfigCodec.toJson(c));
        } catch (Throwable t) {
            ok("round-trip 不应抛异常: " + t, false);
        }

        // ------------------------------------------------------------ 解析字段
        AppConfig c = ConfigCodec.fromJson(CANONICAL);
        eq("enabled", true, c.enabled);
        eq("marker", true, c.marker);
        eq("markerTemplate 保留尾部空格", "[模型: {model}] ", c.markerTemplate);
        eq("activeProviderId", "crazyai", c.activeProviderId);
        eq("activeModelId", "your-model", c.activeModelId);
        eq("mcpEnabled", true, c.mcpEnabled);
        eq("mcpLan 默认 false", false, c.mcpLan);
        eq("agentLan 默认 false", false, c.agentLan);
        eq("mcpToken", "tok-123", c.mcpToken);
        eq("providers 数", 1, c.providers.size());
        eq("provider name", "CrashMail", c.providers.get(0).name);
        eq("models 数", 2, c.providers.get(0).models.size());
        eq("version", 3, c.version);
        eq("rev", 1L, c.rev);
        ok("正常文件不是骨架", !c.skeleton);

        // 旧文件的 themeMode 属于 UI 偏好，已迁到 prefs：读时忽略、写时不再输出
        AppConfig legacy = ConfigCodec.fromJson(
                "{\"themeMode\":2,\"providers\":[{\"id\":\"p\",\"baseUrl\":\"https://a\","
                + "\"apiKey\":\"k\",\"models\":[{\"id\":\"m\",\"name\":\"M\"}]}]}");
        T.ok("themeMode 不进 extra", !legacy.extra.containsKey("themeMode"));
        T.ok("themeMode 不再写回", !ConfigCodec.toJson(legacy).contains("themeMode"));

        // ------------------------------------------------------------ 归一化
        // 悬空 activeProviderId → 第一个服务商
        AppConfig n = ConfigCodec.fromJson(
                "{\"activeProviderId\":\"gone\",\"activeModelId\":\"x\","
                + "\"providers\":[{\"id\":\"p1\",\"baseUrl\":\"https://a\",\"apiKey\":\"k\","
                + "\"models\":[{\"id\":\"m1\",\"name\":\"M1\"}]}]}");
        eq("悬空 provider 修正", "p1", n.activeProviderId);
        eq("悬空 model 修正", "m1", n.activeModelId);

        // 服务商缺 id → 生成
        AppConfig n2 = ConfigCodec.fromJson(
                "{\"providers\":[{\"name\":\"A\",\"baseUrl\":\"https://a\",\"apiKey\":\"k\","
                + "\"models\":[{\"id\":\"m1\"}]}]}");
        eq("缺 id 生成", "p0", n2.providers.get(0).id);
        eq("name 为空回填 id", "A", n2.providers.get(0).name);

        // 模型去重 + 空 id 丢弃 + 空白裁剪
        AppConfig n3 = ConfigCodec.fromJson(
                "{\"providers\":[{\"id\":\" p1 \",\"baseUrl\":\" https://a \",\"apiKey\":\" k \","
                + "\"models\":[{\"id\":\" m1 \"},{\"id\":\"m1\"},{\"id\":\"\"}]}]}");
        AppConfig.Provider p3 = n3.providers.get(0);
        eq("provider id 裁剪", "p1", p3.id);
        eq("baseUrl 裁剪", "https://a", p3.baseUrl);
        eq("apiKey 裁剪", "k", p3.apiKey);
        eq("模型去重+空丢弃", 1, p3.models.size());
        eq("模型 id 裁剪", "m1", p3.models.get(0).id);
        eq("模型 name 回填 id", "m1", p3.models.get(0).name);

        // 空 providers
        AppConfig n4 = ConfigCodec.fromJson("{\"providers\":[]}");
        eq("空 providers 保留 mcp 结构", "", n4.activeProviderId);
        eq("空 providers 无异常", 0, n4.providers.size());

        // ------------------------------------------------------------ 校验
        eq("暂停合法", null, ConfigCodec.validate(ConfigCodec.fromJson("{\"enabled\":false}")));
        eq("无服务商", "没有可用的服务商",
                ConfigCodec.validate(ConfigCodec.fromJson("{\"enabled\":true}")));
        eq("无模型", "没有可用的模型", ConfigCodec.validate(ConfigCodec.fromJson(
                "{\"enabled\":true,\"providers\":[{\"id\":\"p1\",\"baseUrl\":\"https://a\",\"apiKey\":\"k\"}]}")));
        eq("坏端点", "接口地址必须是 http(s)", ConfigCodec.validate(ConfigCodec.fromJson(
                "{\"enabled\":true,\"providers\":[{\"id\":\"p1\",\"baseUrl\":\"ftp://a\",\"apiKey\":\"k\","
                + "\"models\":[{\"id\":\"m1\"}]}]}")));
        eq("缺密钥", "密钥不能为空", ConfigCodec.validate(ConfigCodec.fromJson(
                "{\"enabled\":true,\"providers\":[{\"id\":\"p1\",\"baseUrl\":\"https://a\",\"apiKey\":\"\","
                + "\"models\":[{\"id\":\"m1\"}]}]}")));
        eq("密钥误填成 token", "密钥填成了 MCP Token（复制错了字段）",
                ConfigCodec.validate(ConfigCodec.fromJson(
                        "{\"enabled\":true,\"mcp\":{\"enabled\":true,\"token\":\"tok\"},"
                        + "\"providers\":[{\"id\":\"p1\",\"baseUrl\":\"https://a\",\"apiKey\":\"tok\","
                        + "\"models\":[{\"id\":\"m1\"}]}]}")));
        eq("完整配置校验通过", null, ConfigCodec.validate(ConfigCodec.fromJson(CANONICAL)));

        // 局域网访问：必须带 token；暂停模型时这条规则仍然生效（桥可独立运行）
        AppConfig lanOk = ConfigCodec.fromJson(
                "{\"enabled\":false,\"mcp\":{\"enabled\":true,\"lan\":true,\"token\":\"tok\"}}");
        eq("lan 解析", true, lanOk.mcpLan);
        eq("暂停但局域网有 token 合法", null, ConfigCodec.validate(lanOk));
        String lanOut = ConfigCodec.toJson(lanOk);
        ok("lan 写回", lanOut.contains("\"mcp\":{\"enabled\":true,\"lan\":true,\"agentLan\":false,\"token\":\"tok\"}"));
        AppConfig lanNoToken = ConfigCodec.fromJson(
                "{\"enabled\":false,\"mcp\":{\"enabled\":true,\"lan\":true,\"token\":\"\"}}");
        eq("局域网无 token 被拒",
                "局域网访问必须设置 MCP 令牌（留空会让同一 Wi-Fi 下的设备直接控制小爱）",
                ConfigCodec.validate(lanNoToken));

        // 8770 Agent 局域网：旧 JSON 缺字段按 false；round-trip；空 token 明确被拒
        AppConfig agentDefault = ConfigCodec.fromJson(
                "{\"enabled\":false,\"mcp\":{\"enabled\":true,\"lan\":false,\"token\":\"tok\"}}");
        eq("旧 JSON 缺 agentLan 按 false", false, agentDefault.agentLan);
        ok("旧 JSON 写回 agentLan=false",
                ConfigCodec.toJson(agentDefault).contains("\"agentLan\":false"));
        AppConfig agentOn = ConfigCodec.fromJson(
                "{\"enabled\":false,\"mcp\":{\"enabled\":true,\"lan\":false,\"agentLan\":true,"
                + "\"token\":\"0123456789abcdef0123456789abcdef\"}}");
        eq("agentLan 解析", true, agentOn.agentLan);
        eq("agentLan 不影响 mcpLan", false, agentOn.mcpLan);
        ok("agentLan round-trip", ConfigCodec.toJson(agentOn)
                .contains("\"agentLan\":true"));
        eq("agentLan round-trip 再次解析", true,
                ConfigCodec.fromJson(ConfigCodec.toJson(agentOn)).agentLan);
        eq("agentLan 有 token 合法", null, ConfigCodec.validate(agentOn));
        AppConfig agentNoToken = ConfigCodec.fromJson(
                "{\"enabled\":false,\"mcp\":{\"enabled\":true,\"agentLan\":true,\"token\":\"\"}}");
        eq("Agent 局域网无 token 被拒",
                "Agent 动作面开放局域网必须设置 MCP 令牌（留空会让同一 Wi-Fi 下的设备执行设备动作）",
                ConfigCodec.validate(agentNoToken));
        AppConfig agentDisabled = ConfigCodec.fromJson(
                "{\"enabled\":false,\"mcp\":{\"enabled\":false,\"agentLan\":true,\"token\":\"\"}}");
        eq("MCP 关闭时 agentLan 不触发校验", null, ConfigCodec.validate(agentDisabled));

        // ------------------------------------------------------------ 默认值（升级不丢配置）
        AppConfig d = ConfigCodec.defaults("https://old/v1", "old-model", "sk-old", true, true, "tok");
        eq("defaults 沿用端点", "https://old/v1", d.providers.get(0).baseUrl);
        eq("defaults 沿用密钥", "sk-old", d.providers.get(0).apiKey);
        eq("defaults 沿用模型", "old-model", d.activeModelId);
        eq("defaults 补充默认模型", 2, d.providers.get(0).models.size());
        eq("defaults 默认暂停", false, d.enabled);
        eq("defaults mcp", true, d.mcpEnabled);
        eq("defaults 骨架 rev=0", 0L, d.rev);

        AppConfig d2 = ConfigCodec.defaults("", "", "", true, false, "");
        eq("defaults 空端点回退", "https://api.deepseek.com/v1", d2.providers.get(0).baseUrl);
        eq("defaults 空模型回退", "deepseek-chat", d2.activeModelId);
        eq("defaults 空密钥", "", d2.providers.get(0).apiKey);
        eq("defaults 单模型", 1, d2.providers.get(0).models.size());

        // ------------------------------------------------------------ 脱敏文件识别
        AppConfig masked = ConfigCodec.fromJson(
                "{\"mcp\":{\"token\":\"***\"},\"providers\":[{\"id\":\"p\",\"apiKey\":\"***\","
                + "\"models\":[{\"id\":\"m\"}]}]}");
        ok("脱敏 token 可识别", ConfigCodec.containsMaskedSecrets(masked));
        ok("正常配置不误判", !ConfigCodec.containsMaskedSecrets(ConfigCodec.fromJson(CANONICAL)));

        // ------------------------------------------------------------ 版本、rev 与未知字段
        AppConfig v3 = ConfigCodec.fromJson(
                "{\"version\":3,\"rev\":7,\"customFlag\":true,\"future\":{\"a\":1}}");
        eq("高版本保留", 3, v3.version);
        eq("rev 解析", 7L, v3.rev);
        String out3 = ConfigCodec.toJson(v3);
        ok("高版本写回 3", out3.contains("\"version\":3"));
        ok("rev 写回", out3.contains("\"rev\":7"));
        ok("未知字段 customFlag 保留", out3.contains("\"customFlag\":true"));
        ok("未知字段 future 保留", out3.contains("\"future\":{\"a\":1}"));

        // v2（无 rev）迁移：schema 升到 3，rev 归一为 1，未知字段仍保留
        AppConfig v2 = ConfigCodec.fromJson(
                "{\"version\":2,\"legacyFlag\":\"keep\",\"providers\":[{\"id\":\"p\","
                + "\"baseUrl\":\"https://a\",\"apiKey\":\"k\",\"models\":[{\"id\":\"m\"}]}]}");
        eq("v2 升级到 3", 3, v2.version);
        eq("v2 缺 rev 归一为 1", 1L, v2.rev);
        String v2Out = ConfigCodec.toJson(v2);
        ok("v2 写回 version 3", v2Out.contains("\"version\":3,"));
        ok("v2 写回 rev 1", v2Out.contains("\"rev\":1,"));
        ok("v2 未知字段保留", v2Out.contains("\"legacyFlag\":\"keep\""));

        AppConfig noVer = ConfigCodec.fromJson("{}");
        eq("缺 version 归一到 3", 3, noVer.version);
        eq("缺 rev 归一为 1", 1L, noVer.rev);

        // 骨架 / 规范化：内存默认 rev=0；负 rev 归零
        AppConfig skeleton = new AppConfig();
        eq("骨架 version", 3, skeleton.version);
        eq("骨架 rev=0", 0L, skeleton.rev);
        skeleton.skeleton = true;
        ok("skeleton 仅内存标记", skeleton.skeleton);
        ok("skeleton 不参与序列化", !ConfigCodec.toJson(skeleton).contains("skeleton"));
        AppConfig neg = new AppConfig();
        neg.rev = -5;
        eq("负 rev 归零", 0L, ConfigCodec.normalize(neg).rev);

        // ------------------------------------------------------------ 转义
        AppConfig esc = new AppConfig();
        esc.activeProviderId = "p1";
        esc.activeModelId = "m1";
        AppConfig.Provider ep = new AppConfig.Provider("p1", "P", "https://a?x=\"1\"", "sk-\"q\"");
        ep.models.add(new AppConfig.ModelItem("m1", "M"));
        esc.providers.add(ep);
        String escOut = ConfigCodec.toJson(esc);
        ok("字符串引号被转义", escOut.contains("sk-\\\"q\\\""));
        try {
            AppConfig back = ConfigCodec.fromJson(escOut);
            eq("转义 round-trip", "sk-\"q\"", back.providers.get(0).apiKey);
        } catch (Throwable t) {
            ok("转义解析失败: " + t, false);
        }

        // ------------------------------------------------------------ 非法输入
        throwsIt("空串抛异常", new Runnable() { public void run() { try { ConfigCodec.fromJson(""); } catch (Throwable t) { throw new RuntimeException(t); } } });
        throwsIt("数组抛异常", new Runnable() { public void run() { try { ConfigCodec.fromJson("[1]"); } catch (Throwable t) { throw new RuntimeException(t); } } });
        throwsIt("坏 JSON 抛异常", new Runnable() { public void run() { try { ConfigCodec.fromJson("{oops}"); } catch (Throwable t) { throw new RuntimeException(t); } } });

        // ------------------------------------------------------------ 便捷断言
        eq("toJson 后再次解析稳定", ConfigCodec.toJson(c), ConfigCodec.toJson(ConfigCodec.fromJson(ConfigCodec.toJson(c))));

        return T.report("ConfigCodecTest");
    }

    private static void ok(String n, boolean v) { T.ok(n, v); }
    private static void eq(String n, Object e, Object a) { T.eq(n, e, a); }
    private static void throwsIt(String n, Runnable r) { T.throwsIt(n, r); }
}
