package plus.xiaoi.module.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import plus.xiaoi.module.Json;

/**
 * 第一批内置动作描述（纯 JVM，只描述不执行）。
 *
 * <p>App 的 CapabilityRegistry / MCP 层直接复用这些 descriptor；真正的 Android / root
 * 执行器由 WP1.2 注册，注册表不会因为执行器不同而改 schema。</p>
 *
 * <p>参数约定见 PLAN 6.3：app.open 的 package / intentUri 二选一（用 anyOf 表达），
 * alarm.set 的 hour/minute 做范围校验，media.control 的 command 用 enum 限定。</p>
 */
public final class Actions {

    private Actions() {}

    /** 五个内置动作，顺序固定：app.open / device.info / alarm.set / notification.send / media.control。 */
    public static List<ActionDescriptor> builtins() {
        List<ActionDescriptor> out = new ArrayList<ActionDescriptor>();
        out.add(appOpen());
        out.add(deviceInfo());
        out.add(alarmSet());
        out.add(notificationSend());
        out.add(mediaControl());
        return Collections.unmodifiableList(out);
    }

    public static ActionDescriptor appOpen() {
        Map<String, Object> props = Json.obj();
        Map<String, Object> pkg = prop("string", "要打开的应用包名，例如 com.tencent.mm");
        pkg.put("maxLength", 200);
        props.put("package", pkg);
        Map<String, Object> uri = prop("string", "要打开的 intent URI；与 package 二选一");
        uri.put("maxLength", 2048);
        props.put("intentUri", uri);

        Map<String, Object> schema = objectSchema(props, required());
        List<Object> anyOf = Json.arr();
        anyOf.add(requiredOnly("package"));
        anyOf.add(requiredOnly("intentUri"));
        schema.put("anyOf", anyOf);

        Map<String, Object> out = Json.obj();
        out.put("launched", prop("boolean", "系统是否接受了启动请求"));
        out.put("package", prop("string", "实际打开的包名"));
        out.put("component", prop("string", "实际启动的组件（可能为空）"));

        List<Object> examples = Json.arr();
        examples.add(example("package", "com.tencent.mm"));
        examples.add(example("intentUri", "intent://scan/#Intent;scheme=zxing;end"));

        return ActionDescriptor.builder("app.open")
                .description("打开一个应用或 intent。package 与 intentUri 至少提供一个；"
                        + "受 HyperOS 后台启动限制，锁屏/后台可能失败。")
                .inputSchema(schema)
                .outputSchema(objectSchema(out, required()))
                .executorPreference("android+root", "android", "root")
                .requiredCapability(ActionDescriptor.CAP_NONE)
                .risk(ActionRisk.SAFE)
                .idempotent(true)
                .timeoutMs(5000)
                .examples(examples)
                .build();
    }

    public static ActionDescriptor deviceInfo() {
        Map<String, Object> props = Json.obj();
        Map<String, Object> fields = prop("array", "要读取的字段；省略表示常用字段");
        fields.put("items", prop("string", "字段名，例如 model / battery / network / foregroundApp"));
        props.put("fields", fields);

        Map<String, Object> out = Json.obj();
        out.put("fields", prop("object", "字段名到值的映射；拿不到的字段会省略"));

        List<Object> examples = Json.arr();
        examples.add(example("fields", list("model", "battery", "network")));

        return ActionDescriptor.builder("device.info")
                .description("读取设备信息：型号、Android 版本、电量、充电、网络、前台应用。"
                        + "前台应用需要 root 或 usage stats，拿不到时省略。")
                .inputSchema(objectSchema(props, required()))
                .outputSchema(objectSchema(out, required()))
                .executorPreference("android")
                .requiredCapability(ActionDescriptor.CAP_NONE)
                .risk(ActionRisk.SAFE)
                .idempotent(true)
                .timeoutMs(3000)
                .examples(examples)
                .build();
    }

    public static ActionDescriptor alarmSet() {
        Map<String, Object> props = Json.obj();
        props.put("hour", numberProp("integer", "小时，0~23", 0, 23));
        props.put("minute", numberProp("integer", "分钟，0~59", 0, 59));
        Map<String, Object> label = prop("string", "闹钟标签");
        label.put("maxLength", 200);
        props.put("label", label);
        props.put("skipUi", prop("boolean", "是否尝试跳过系统闹钟界面（兼容性待真机验证）"));

        Map<String, Object> out = Json.obj();
        out.put("requested", prop("boolean", "是否已向系统提交闹钟请求"));
        out.put("scheduled", prop("boolean", "是否确认创建；无法确认时为 false"));
        out.put("skippedUi", prop("boolean", "是否跳过了系统界面"));

        List<Object> examples = Json.arr();
        Map<String, Object> ex = example("hour", 7L);
        ex.put("minute", 0L);
        ex.put("label", "起床");
        examples.add(ex);

        return ActionDescriptor.builder("alarm.set")
                .description("设置闹钟。默认打开系统闹钟界面；skipUi 在部分 ROM 上可能无效。")
                .inputSchema(objectSchema(props, required("hour", "minute")))
                .outputSchema(objectSchema(out, required()))
                .executorPreference("android")
                .requiredCapability(ActionDescriptor.CAP_NONE)
                .risk(ActionRisk.SAFE)
                .idempotent(false)
                .timeoutMs(3000)
                .examples(examples)
                .build();
    }

    public static ActionDescriptor notificationSend() {
        Map<String, Object> props = Json.obj();
        Map<String, Object> title = prop("string", "通知标题");
        title.put("maxLength", 200);
        props.put("title", title);
        Map<String, Object> text = prop("string", "通知正文");
        text.put("maxLength", 4000);
        props.put("text", text);
        Map<String, Object> id = prop("string", "可选的通知 id，用于覆盖同一条通知");
        id.put("maxLength", 64);
        props.put("id", id);

        Map<String, Object> out = Json.obj();
        out.put("notificationId", prop("string", "实际使用的通知 id"));

        List<Object> examples = Json.arr();
        Map<String, Object> ex = example("title", "Agent");
        ex.put("text", "任务已完成");
        examples.add(ex);

        return ActionDescriptor.builder("notification.send")
                .description("发送一条本机通知。需要 Android 13+ 的 POST_NOTIFICATIONS 权限；"
                        + "属于有可见副作用的动作，执行前需要确认。")
                .inputSchema(objectSchema(props, required("title", "text")))
                .outputSchema(objectSchema(out, required()))
                .executorPreference("android")
                .requiredCapability(ActionDescriptor.CAP_NONE)
                .risk(ActionRisk.CONFIRM)
                .idempotent(false)
                .timeoutMs(3000)
                .examples(examples)
                .build();
    }

    public static ActionDescriptor mediaControl() {
        Map<String, Object> props = Json.obj();
        Map<String, Object> command = prop("string", "媒体命令");
        command.put("enum", list("play", "pause", "next", "prev", "volume"));
        props.put("command", command);
        props.put("volume", numberProp("integer", "音量，0~100；command=volume 时使用", 0, 100));

        Map<String, Object> out = Json.obj();
        out.put("command", prop("string", "已执行的命令"));
        out.put("volume", prop("integer", "当前音量（0~100）"));

        List<Object> examples = Json.arr();
        Map<String, Object> ex = example("command", "volume");
        ex.put("volume", 40L);
        examples.add(ex);
        examples.add(example("command", "play"));

        return ActionDescriptor.builder("media.control")
                .description("控制媒体：播放/暂停/上一首/下一首/音量。"
                        + "无特权时只有音量稳定可用，播放控制取决于系统 MediaSession。")
                .inputSchema(objectSchema(props, required("command")))
                .outputSchema(objectSchema(out, required()))
                .executorPreference("android+root", "android", "shizuku", "root")
                .requiredCapability(ActionDescriptor.CAP_NONE)
                .risk(ActionRisk.SAFE)
                .idempotent(false)
                .timeoutMs(3000)
                .examples(examples)
                .build();
    }

    // ------------------------------------------------------------- schema 辅助

    private static Map<String, Object> objectSchema(Map<String, Object> props,
                                                    List<Object> required) {
        Map<String, Object> s = Json.obj();
        s.put("type", "object");
        s.put("properties", props);
        s.put("required", required);
        s.put("additionalProperties", Boolean.FALSE);
        return s;
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = Json.obj();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> numberProp(String type, String description,
                                                  long min, long max) {
        Map<String, Object> p = prop(type, description);
        p.put("minimum", min);
        p.put("maximum", max);
        return p;
    }

    private static Map<String, Object> requiredOnly(String name) {
        Map<String, Object> s = Json.obj();
        s.put("required", list(name));
        return s;
    }

    private static List<Object> required(String... names) {
        return list(names);
    }

    private static List<Object> list(Object... values) {
        List<Object> l = Json.arr();
        if (values != null) {
            for (Object v : values) l.add(v);
        }
        return l;
    }

    private static Map<String, Object> example(String key, Object value) {
        Map<String, Object> m = Json.obj();
        m.put(key, value);
        return m;
    }
}
