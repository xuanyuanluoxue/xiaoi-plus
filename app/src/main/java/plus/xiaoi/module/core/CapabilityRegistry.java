package plus.xiaoi.module.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 能力清单静态数据（纯 JVM，无 android 依赖）。
 *
 * <p>条目与标题以 {@code docs/agent/CAPABILITIES.md} 的 A1–A26 / B1–B31 / C1–C10
 * 为准；状态映射：✅ IMPLEMENTED、🧪 PARTIAL、📋 PLANNED、⛔ UNSUPPORTED。
 * 运行时状态不写死在 descriptor 里，由 {@link CapabilityState} 单独承载。</p>
 */
public final class CapabilityRegistry {

    public enum Domain {
        XIAOAI("A", "小爱"),
        SYSTEM("B", "系统"),
        BRIDGE("C", "桥");

        public final String prefix;
        public final String title;

        Domain(String prefix, String title) {
            this.prefix = prefix;
            this.title = title;
        }
    }

    public enum Risk { LOW, MEDIUM, HIGH, CRITICAL }

    public enum Source { IMPLEMENTED, PARTIAL, PLANNED, UNSUPPORTED }

    /** 运行时状态；PLAN §5.2 的枚举，新增只能追加。 */
    public enum CapabilityStatus {
        AVAILABLE,
        PERMISSION_MISSING,
        UNAVAILABLE,
        PLANNED,
        DISABLED,
        UNSUPPORTED
    }

    /** 暴露面常量。 */
    public static final String APP = "app";
    public static final String MCP8771 = "8771";
    public static final String MCP8770 = "8770";
    public static final String EXTERNAL = "external";

    /** 路由白名单（与 SCREENS.md §0 一致），深链只能指向这些页面。 */
    public static final Set<String> ROUTES;

    static {
        LinkedHashSet<String> routes = new LinkedHashSet<>(Arrays.asList(
                "Home", "Xiaoai", "Model", "Access", "Bridge", "Token", "Connect",
                "Capabilities", "CapabilityDetail", "Tools", "Permissions",
                "Me", "Appearance", "ConfigManage", "Diagnostics", "Logs",
                "About", "Licenses", "Legacy"));
        ROUTES = Collections.unmodifiableSet(routes);
    }

    public static final class Descriptor {
        public final String id;
        public final Domain domain;
        public final String title;
        public final String summary;
        public final String channel;
        public final Risk risk;
        public final Source source;
        public final List<String> exposedTo;
        /** 可空的页面路由；非空时必须在 {@link #ROUTES} 内。 */
        public final String deepLink;

        Descriptor(String id, Domain domain, String title, String summary, String channel,
                   Risk risk, Source source, List<String> exposedTo, String deepLink) {
            this.id = id == null ? "" : id;
            this.domain = domain == null ? Domain.XIAOAI : domain;
            this.title = title == null ? "" : title;
            this.summary = summary == null ? "" : summary;
            this.channel = channel == null ? "" : channel;
            this.risk = risk == null ? Risk.LOW : risk;
            this.source = source == null ? Source.PLANNED : source;
            this.exposedTo = exposedTo == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(exposedTo));
            this.deepLink = deepLink == null || deepLink.isEmpty() ? null : deepLink;
        }

        /** 未探测时的状态映射；PARTIAL 归入「需授权/受限」一档。 */
        public CapabilityState defaultState() {
            return withState(defaultStatus(), "", 0L);
        }

        public CapabilityStatus defaultStatus() {
            switch (source) {
                case IMPLEMENTED:
                    return CapabilityStatus.AVAILABLE;
                case PARTIAL:
                    return CapabilityStatus.PERMISSION_MISSING;
                case PLANNED:
                    return CapabilityStatus.PLANNED;
                case UNSUPPORTED:
                default:
                    return CapabilityStatus.UNSUPPORTED;
            }
        }

        public CapabilityState withState(CapabilityStatus status) {
            return withState(status, "", 0L);
        }

        public CapabilityState withState(CapabilityStatus status, String detail, long at) {
            return new CapabilityState(id, status, detail, at);
        }
    }

    /** 运行时状态：由 RuntimeProbe 合并后写入，不进入静态表。 */
    public static final class CapabilityState {
        public final String id;
        public final CapabilityStatus status;
        public final String detail;
        public final long lastCheckedAt;

        public CapabilityState(String id, CapabilityStatus status, String detail, long at) {
            this.id = id == null ? "" : id;
            this.status = status == null ? CapabilityStatus.UNAVAILABLE : status;
            this.detail = detail == null ? "" : detail;
            this.lastCheckedAt = at < 0 ? 0L : at;
        }
    }

    /** 静态源计数：可用 / 需授权（部分）/ 规划 / 不提供。 */
    public static final class Counts {
        public final int available;
        public final int permission;
        public final int planned;
        public final int unsupported;

        Counts(int available, int permission, int planned, int unsupported) {
            this.available = available;
            this.permission = permission;
            this.planned = planned;
            this.unsupported = unsupported;
        }

        public int total() {
            return available + permission + planned + unsupported;
        }
    }

    /** 运行时状态计数。 */
    public static final class StatusCounts {
        public final int available;
        public final int permissionMissing;
        public final int unavailable;
        public final int planned;
        public final int disabled;
        public final int unsupported;

        StatusCounts(int available, int permissionMissing, int unavailable, int planned,
                     int disabled, int unsupported) {
            this.available = available;
            this.permissionMissing = permissionMissing;
            this.unavailable = unavailable;
            this.planned = planned;
            this.disabled = disabled;
            this.unsupported = unsupported;
        }

        public int total() {
            return available + permissionMissing + unavailable + planned + disabled + unsupported;
        }
    }

    private static final List<Descriptor> ALL = build();

    private CapabilityRegistry() {}

    public static List<Descriptor> all() {
        return ALL;
    }

    /** 按 ID 查（忽略大小写）；未知返回 null。 */
    public static Descriptor find(String id) {
        if (id == null) return null;
        String key = id.trim();
        for (Descriptor d : ALL) {
            if (d.id.equalsIgnoreCase(key)) return d;
        }
        return null;
    }

    public static List<Descriptor> byDomain(Domain domain) {
        if (domain == null) return Collections.emptyList();
        ArrayList<Descriptor> out = new ArrayList<>();
        for (Descriptor d : ALL) {
            if (d.domain == domain) out.add(d);
        }
        return Collections.unmodifiableList(out);
    }

    public static Counts counts() {
        int available = 0;
        int permission = 0;
        int planned = 0;
        int unsupported = 0;
        for (Descriptor d : ALL) {
            switch (d.source) {
                case IMPLEMENTED:
                    available++;
                    break;
                case PARTIAL:
                    permission++;
                    break;
                case PLANNED:
                    planned++;
                    break;
                case UNSUPPORTED:
                default:
                    unsupported++;
                    break;
            }
        }
        return new Counts(available, permission, planned, unsupported);
    }

    public static StatusCounts counts(List<CapabilityState> states) {
        int available = 0;
        int permission = 0;
        int unavailable = 0;
        int planned = 0;
        int disabled = 0;
        int unsupported = 0;
        if (states != null) {
            for (CapabilityState s : states) {
                if (s == null) continue;
                switch (s.status) {
                    case AVAILABLE:
                        available++;
                        break;
                    case PERMISSION_MISSING:
                        permission++;
                        break;
                    case PLANNED:
                        planned++;
                        break;
                    case DISABLED:
                        disabled++;
                        break;
                    case UNSUPPORTED:
                        unsupported++;
                        break;
                    case UNAVAILABLE:
                    default:
                        unavailable++;
                        break;
                }
            }
        }
        return new StatusCounts(available, permission, unavailable, planned, disabled, unsupported);
    }

    /** 全部条目的默认运行时状态（未探测）。 */
    public static List<CapabilityState> defaultStates() {
        ArrayList<CapabilityState> out = new ArrayList<>(ALL.size());
        for (Descriptor d : ALL) out.add(d.defaultState());
        return Collections.unmodifiableList(out);
    }

    public static boolean isKnownRoute(String route) {
        return route != null && ROUTES.contains(route);
    }

    private static List<Descriptor> build() {
        ArrayList<Descriptor> out = new ArrayList<>(67);

        // A：作用到小爱自身
        out.add(d("A1", Domain.XIAOAI, "模型重定向",
                "把请求的 baseUrl/apiKey/model 指到第三方端点", "LSPosed hook",
                Risk.LOW, Source.IMPLEMENTED, ex(APP), "Model"));
        out.add(d("A2", Domain.XIAOAI, "模型标识",
                "传输层附加 X-Model-Provider-Id，不占提示词", "LSPosed hook",
                Risk.LOW, Source.IMPLEMENTED, ex(APP), "Model"));
        out.add(d("A3", Domain.XIAOAI, "图片理解",
                "打开 getSupportsVision 并保留原图", "LSPosed hook",
                Risk.LOW, Source.IMPLEMENTED, ex(APP), "Model"));
        out.add(d("A4", Domain.XIAOAI, "模型/服务商切换",
                "App 配置 + 控制面 reload；MCP 只改 model/marker", "内置控制面 + MCP",
                Risk.MEDIUM, Source.IMPLEMENTED, ex(APP, MCP8771), "Model"));
        out.add(d("A5", Domain.XIAOAI, "配置热更新",
                "控制面 facade + 文件监听；30 秒无上报回退等待", "内置控制面",
                Risk.LOW, Source.IMPLEMENTED, ex(APP, MCP8771), "Xiaoai"));
        out.add(d("A6", Domain.XIAOAI, "调用统计",
                "hook 请求计数，只计数不含内容", "LSPosed hook",
                Risk.LOW, Source.IMPLEMENTED, ex(APP), "Home"));
        out.add(d("A7", Domain.XIAOAI, "运行日志",
                "内存环形 300 条 + 落盘 + 控制面上报，已脱敏", "内置控制面",
                Risk.LOW, Source.IMPLEMENTED, ex(APP, MCP8771), "Logs"));
        out.add(d("A8", Domain.XIAOAI, "系统提示词追加",
                "在原生 system 后追加一段，每请求一次", "LSPosed hook",
                Risk.MEDIUM, Source.PLANNED, ex(APP), null));
        out.add(d("A9", Domain.XIAOAI, "系统提示词替换",
                "覆盖 instructions/system；高风险，可能破坏工具协议", "LSPosed hook",
                Risk.HIGH, Source.PLANNED, ex(APP), null));
        out.add(d("A10", Domain.XIAOAI, "动态上下文注入",
                "{time}/{battery}/{app} 等变量拼进追加提示词", "hook + 普通 API",
                Risk.MEDIUM, Source.PLANNED, ex(APP), null));
        out.add(d("A11", Domain.XIAOAI, "工具注入（function calling）",
                "ActionDescriptor 映射 tools，解析 tool_calls 后回填", "hook + 8770",
                Risk.HIGH, Source.PLANNED, ex(APP, MCP8770), null));
        out.add(d("A12", Domain.XIAOAI, "轻量工具协议",
                "system 约定 <xiaoi_action> JSON，作为 fallback", "LSPosed hook",
                Risk.MEDIUM, Source.PLANNED, ex(APP), null));
        out.add(d("A13", Domain.XIAOAI, "Skill 包",
                "skill.json 安装启停，按场景注入白名单", "hook + 配置",
                Risk.HIGH, Source.PLANNED, ex(APP), null));
        out.add(d("A14", Domain.XIAOAI, "记忆只读注入",
                "root 读 MEMORY.md 摘要注入；记忆文本不可信", "root",
                Risk.HIGH, Source.PLANNED, ex(APP), null));
        out.add(d("A15", Domain.XIAOAI, "记忆写入/镜像",
                "root 追加 Markdown，不碰加密 DB/向量索引", "root",
                Risk.HIGH, Source.PARTIAL, ex(APP), null));
        out.add(d("A16", Domain.XIAOAI, "多轮会话",
                "xiaoai_ask 返回并复用 sessionId", "小爱内置 CLI",
                Risk.MEDIUM, Source.IMPLEMENTED, ex(MCP8771), null));
        out.add(d("A17", Domain.XIAOAI, "让小爱执行任务",
                "xiaoai_submit → CLI agent.submit，技能由小爱决定", "小爱内置 CLI",
                Risk.MEDIUM, Source.IMPLEMENTED, ex(MCP8771), null));
        out.add(d("A18", Domain.XIAOAI, "代小爱确认授权",
                "技术可调 consent.respond，但属于用户授权，不代签", "小爱内置 CLI",
                Risk.HIGH, Source.UNSUPPORTED, ex(), null));
        out.add(d("A19", Domain.XIAOAI, "ASR 增强",
                "hook 识别结果做热词/纠错/前置改写", "LSPosed hook",
                Risk.MEDIUM, Source.PLANNED, ex(APP), null));
        out.add(d("A20", Domain.XIAOAI, "TTS 增强",
                "合成文本后处理，或改用系统 TTS", "LSPosed hook",
                Risk.MEDIUM, Source.PLANNED, ex(APP), null));
        out.add(d("A21", Domain.XIAOAI, "超级岛提醒",
                "焦点通知 + xiaoai_notify；≤22 字、6 秒、无按钮", "普通 API（8770/8771）",
                Risk.LOW, Source.IMPLEMENTED, ex(APP, MCP8771, MCP8770), "Home"));
        out.add(d("A22", Domain.XIAOAI, "岛交互/按钮",
                "当前 payload 无 actions，不能用来批准危险动作", "未开放",
                Risk.HIGH, Source.UNSUPPORTED, ex(), null));
        out.add(d("A23", Domain.XIAOAI, "输出前缀",
                "改写 delta；多进程重复注入，默认禁用", "LSPosed hook",
                Risk.MEDIUM, Source.UNSUPPORTED, ex(), null));
        out.add(d("A24", Domain.XIAOAI, "配置读取",
                "xiaoai_config 脱敏返回，不含 key/token 明文", "内置控制面",
                Risk.LOW, Source.IMPLEMENTED, ex(APP, MCP8771), "Model"));
        out.add(d("A25", Domain.XIAOAI, "读小爱记忆 DB",
                "加密/自定义格式，root 也需解密；不碰", "—",
                Risk.HIGH, Source.UNSUPPORTED, ex(), null));
        out.add(d("A26", Domain.XIAOAI, "改小爱技能/资源/签名",
                "重打包会破坏升级与完整性，不做", "—",
                Risk.HIGH, Source.UNSUPPORTED, ex(), null));

        // B：作用到系统与其他应用
        out.add(d("B1", Domain.SYSTEM, "设备信息",
                "Build/电池/网络；缺字段不伪造", "普通 Android API",
                Risk.LOW, Source.IMPLEMENTED, ex(APP, MCP8770), "Capabilities"));
        out.add(d("B2", Domain.SYSTEM, "打开应用",
                "launch intent + startActivity；后台启动可能不显示", "普通 Android API",
                Risk.MEDIUM, Source.IMPLEMENTED, ex(APP, MCP8770), "Capabilities"));
        out.add(d("B3", Domain.SYSTEM, "打开应用（兜底）",
                "root monkey 固定模板 + 包名白名单", "root",
                Risk.MEDIUM, Source.PARTIAL, ex(APP, MCP8770), "Capabilities"));
        out.add(d("B4", Domain.SYSTEM, "发通知",
                "NotificationManager + agent_action 渠道，需通知权限", "普通 Android API",
                Risk.MEDIUM, Source.IMPLEMENTED, ex(APP, MCP8770), "Diagnostics"));
        out.add(d("B5", Domain.SYSTEM, "App 内闹钟",
                "setAlarmClock + AgentAlarmReceiver，精确免 Doze", "普通 Android API",
                Risk.LOW, Source.IMPLEMENTED, ex(APP, MCP8770), "Diagnostics"));
        out.add(d("B6", Domain.SYSTEM, "时钟 App 闹钟",
                "ACTION_SET_ALARM + 下一闹钟核对；HyperOS 可能拒绝", "普通 Android API",
                Risk.MEDIUM, Source.PARTIAL, ex(APP, MCP8770), "Diagnostics"));
        out.add(d("B7", Domain.SYSTEM, "音量",
                "setStreamVolume；勿扰/设备策略可能拒绝", "普通 Android API",
                Risk.LOW, Source.IMPLEMENTED, ex(APP, MCP8770), "Capabilities"));
        out.add(d("B8", Domain.SYSTEM, "播放/暂停/切歌",
                "dispatchMediaKeyEvent，依赖当前 MediaSession", "普通 Android API / root",
                Risk.LOW, Source.PARTIAL, ex(APP, MCP8770), "Capabilities"));
        out.add(d("B9", Domain.SYSTEM, "截图",
                "screencap 或 MediaProjection；隐私，每次授权", "root / Shizuku / 用户授权",
                Risk.HIGH, Source.PLANNED, ex(MCP8770), null));
        out.add(d("B10", Domain.SYSTEM, "设置读写",
                "settings get/put；危险键需白名单", "Shizuku / root",
                Risk.HIGH, Source.PLANNED, ex(MCP8770), null));
        out.add(d("B11", Domain.SYSTEM, "前台应用",
                "UsageStatsManager，需要 usage access", "用户授权",
                Risk.HIGH, Source.PLANNED, ex(MCP8770), "Diagnostics"));
        out.add(d("B12", Domain.SYSTEM, "应用列表",
                "PackageManager；升级 target 后需 QUERY_ALL_PACKAGES", "普通 Android API",
                Risk.MEDIUM, Source.PLANNED, ex(MCP8770), null));
        out.add(d("B13", Domain.SYSTEM, "安装/卸载",
                "PackageInstaller 或 pm，默认只走用户确认", "普通 Intent / root / Shizuku",
                Risk.HIGH, Source.PLANNED, ex(MCP8770), null));
        out.add(d("B14", Domain.SYSTEM, "读通知",
                "NotificationListenerService；隐私极高，默认关", "用户授权",
                Risk.HIGH, Source.PLANNED, ex(MCP8770), "Diagnostics"));
        out.add(d("B15", Domain.SYSTEM, "取消/回复通知",
                "listener + RemoteInput；不做正文落盘", "用户授权",
                Risk.HIGH, Source.PLANNED, ex(MCP8770), "Diagnostics"));
        out.add(d("B16", Domain.SYSTEM, "剪贴板",
                "ClipboardManager；后台读取受限", "普通 Android API",
                Risk.MEDIUM, Source.PLANNED, ex(MCP8770), null));
        out.add(d("B17", Domain.SYSTEM, "联系人",
                "ContactsContract；隐私，只读优先", "运行时权限",
                Risk.HIGH, Source.PLANNED, ex(MCP8770), null));
        out.add(d("B18", Domain.SYSTEM, "日历",
                "CalendarContract；读写分开授权", "运行时权限",
                Risk.HIGH, Source.PLANNED, ex(MCP8770), null));
        out.add(d("B19", Domain.SYSTEM, "提醒/待办",
                "CalendarContract 或借小爱技能", "普通 API / 小爱 CLI",
                Risk.MEDIUM, Source.PLANNED, ex(MCP8770), null));
        out.add(d("B20", Domain.SYSTEM, "电话/短信",
                "高风险、审核与误触代价大，不建议", "运行时权限",
                Risk.HIGH, Source.UNSUPPORTED, ex(), null));
        out.add(d("B21", Domain.SYSTEM, "文件读写",
                "SAF / App 私有目录；不做任意路径", "普通 API / root",
                Risk.MEDIUM, Source.PLANNED, ex(MCP8770), null));
        out.add(d("B22", Domain.SYSTEM, "WiFi/蓝牙/数据/飞行模式",
                "svc / cmd / 系统 API；普通 App 不能直接切换", "Shizuku / root",
                Risk.HIGH, Source.PLANNED, ex(MCP8770), null));
        out.add(d("B23", Domain.SYSTEM, "亮度/勿扰/锁屏",
                "Settings / DevicePolicy / cmd；勿扰需 policy access", "WRITE_SETTINGS / Shizuku / root",
                Risk.HIGH, Source.PLANNED, ex(MCP8770), null));
        out.add(d("B24", Domain.SYSTEM, "定位",
                "LocationManager；隐私，默认关", "运行时权限",
                Risk.HIGH, Source.PLANNED, ex(MCP8770), null));
        out.add(d("B25", Domain.SYSTEM, "重启/关机",
                "reboot / svc power；危险，必须确认", "root",
                Risk.HIGH, Source.PLANNED, ex(MCP8770), null));
        out.add(d("B26", Domain.SYSTEM, "UI 自动化（点击/滑动/读屏）",
                "AccessibilityService；权限过大、脆弱，不建议", "用户授权",
                Risk.CRITICAL, Source.UNSUPPORTED, ex(), null));
        out.add(d("B27", Domain.SYSTEM, "文本注入",
                "无障碍 / input text；与 UI 自动化同风险", "用户授权 / Shizuku / root",
                Risk.CRITICAL, Source.UNSUPPORTED, ex(), null));
        out.add(d("B28", Domain.SYSTEM, "任意 shell",
                "明确不做；只保留固定命令族", "root / Shizuku",
                Risk.CRITICAL, Source.UNSUPPORTED, ex(), null));
        out.add(d("B29", Domain.SYSTEM, "录屏/投屏",
                "MediaProjection；隐私，每次授权", "用户授权",
                Risk.HIGH, Source.PLANNED, ex(MCP8770), null));
        out.add(d("B30", Domain.SYSTEM, "米家 IoT / 账号态",
                "需开放平台授权，不在本项目范围", "—",
                Risk.HIGH, Source.UNSUPPORTED, ex(), null));
        out.add(d("B31", Domain.SYSTEM, "支付/账号/风控",
                "不做", "—",
                Risk.CRITICAL, Source.UNSUPPORTED, ex(), null));

        // C：桥与外部 Agent
        out.add(d("C1", Domain.BRIDGE, "外部 Agent 接入",
                "POST /mcp JSON-RPC 2.0 + Bearer；LAN 需 32 hex", "8770 / 8771",
                Risk.MEDIUM, Source.IMPLEMENTED, ex(EXTERNAL, MCP8771, MCP8770), "Access"));
        out.add(d("C2", Domain.BRIDGE, "让小爱执行一句话任务",
                "xiaoai_submit；需要小爱进程", "小爱内置 CLI",
                Risk.MEDIUM, Source.IMPLEMENTED, ex(EXTERNAL, MCP8771), null));
        out.add(d("C3", Domain.BRIDGE, "与小爱多轮对话",
                "xiaoai_ask + sessionId，返回干净文本", "小爱内置 CLI",
                Risk.MEDIUM, Source.IMPLEMENTED, ex(EXTERNAL, MCP8771), null));
        out.add(d("C4", Domain.BRIDGE, "读写/查看配置",
                "config/status/logs/stats，脱敏且不含 key/token", "控制面",
                Risk.LOW, Source.IMPLEMENTED, ex(EXTERNAL, MCP8771), "Capabilities"));
        out.add(d("C5", Domain.BRIDGE, "热切换模型",
                "xiaoai_switch_model，只允许 model/marker", "控制面",
                Risk.MEDIUM, Source.IMPLEMENTED, ex(EXTERNAL, MCP8771), "Model"));
        out.add(d("C6", Domain.BRIDGE, "触发超级岛",
                "xiaoai_island / xiaoai_notify；只提醒不能批准", "8771 / 8770",
                Risk.LOW, Source.IMPLEMENTED, ex(EXTERNAL, MCP8771, MCP8770), "Home"));
        out.add(d("C7", Domain.BRIDGE, "执行设备动作",
                "xiaoai_action + ActionRegistry；5 个动作按权限显示", "8770",
                Risk.HIGH, Source.IMPLEMENTED, ex(EXTERNAL, MCP8770), "Capabilities"));
        out.add(d("C8", Domain.BRIDGE, "危险动作确认",
                "xiaoai_confirm + 60 秒一次性 token，重启失效", "8770",
                Risk.MEDIUM, Source.IMPLEMENTED, ex(EXTERNAL, MCP8770), "Capabilities"));
        out.add(d("C9", Domain.BRIDGE, "幂等与审计",
                "idempotencyKey 24h + xiaoai_audit", "8770",
                Risk.LOW, Source.IMPLEMENTED, ex(EXTERNAL, MCP8770), "Capabilities"));
        out.add(d("C10", Domain.BRIDGE, "原始 CLI 透传",
                "xiaoai_cli 仅用于诊断，不暴露任意 shell", "小爱内置 CLI",
                Risk.HIGH, Source.PARTIAL, ex(EXTERNAL, MCP8771), "Diagnostics"));

        return Collections.unmodifiableList(out);
    }

    private static Descriptor d(String id, Domain domain, String title, String summary,
                                String channel, Risk risk, Source source,
                                List<String> exposedTo, String deepLink) {
        return new Descriptor(id, domain, title, summary, channel, risk, source,
                exposedTo, deepLink);
    }

    private static List<String> ex(String... values) {
        return Arrays.asList(values);
    }
}
