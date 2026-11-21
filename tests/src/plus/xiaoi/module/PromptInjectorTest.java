package plus.xiaoi.module;

import java.util.List;
import java.util.Map;

import plus.xiaoi.module.core.Contract;
import plus.xiaoi.module.core.prompt.PromptTemplate;

/**
 * P2-B：请求体提示词注入（纯函数）。
 *
 * <p>覆盖规划 §3.1 的每一条：追加到 instructions、追加到 chat system、
 * 无 system 时插入、已有标记不重复、超长截断、replace 模式、空配置不改、
 * 非法 JSON 原样返回、变量替换。</p>
 */
public final class PromptInjectorTest {

    private PromptInjectorTest() {}

    private static final PromptTemplate.Vars VARS =
            new PromptTemplate.Vars("2026-11-21", "09:30", "deepseek-v4.1-flash", "M332BF");

    private static final String RESPONSES =
            "{\"model\":\"gpt-5\",\"instructions\":\"原生指令\",\"input\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    public static int run() {
        appendInstructions();
        appendChat();
        idempotent();
        truncation();
        replaceMode();
        memoryInject();
        failures();
        return T.report("PromptInjectorTest");
    }

    // ------------------------------------------------------------- Responses

    private static void appendInstructions() {
        PromptInjector.Result r = PromptInjector.apply(RESPONSES,
                new PromptInjector.Options(true, "append", "每次回答以「收到」开头", false, 20, ""), VARS);
        T.ok("Responses：标记命中", r.changed && "instructions".equals(r.note));
        Map<?, ?> out = map(r.json);
        String inst = (String) out.get("instructions");
        T.ok("Responses：保留原生指令在前", inst.startsWith("原生指令\n\n"));
        T.ok("Responses：带起始标记", inst.contains("<!-- xiaoi-plus:prompt v1 -->"));
        T.ok("Responses：带结束标记", inst.contains("<!-- /xiaoi-plus:prompt v1 -->"));
        T.ok("Responses：正文在其中", inst.contains("每次回答以「收到」开头"));
        T.eq("Responses：其它字段不变", "gpt-5", out.get("model"));
        T.ok("Responses：input 原样保留", r.json.contains("\"input\":[{\"role\":\"user\",\"content\":\"hi\"}]"));

        // 变量替换
        PromptInjector.Result vars = PromptInjector.apply(RESPONSES,
                new PromptInjector.Options(true, "append",
                        "现在是 {{date}} {{time}}，模型 {{model}}，设备 {{device}}", false, 20, ""), VARS);
        String text = (String) map(vars.json).get("instructions");
        T.ok("变量：全部替换",
                text.contains("现在是 2026-11-21 09:30，模型 deepseek-v4.1-flash，设备 M332BF"));
        T.ok("变量：不再残留占位符", !text.contains("{{date}}"));

        // instructions 为空串时不产生前导空行
        PromptInjector.Result blank = PromptInjector.apply(
                "{\"instructions\":\"\",\"model\":\"m\"}",
                new PromptInjector.Options(true, "append", "规则", false, 20, ""), VARS);
        T.eq("Responses：空 instructions 直接写入块",
                "<!-- xiaoi-plus:prompt v1 -->\n规则\n<!-- /xiaoi-plus:prompt v1 -->",
                map(blank.json).get("instructions"));
    }

    // ------------------------------------------------------------- chat

    private static void appendChat() {
        String chat = "{\"model\":\"m\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"你是小爱\"},"
                + "{\"role\":\"user\",\"content\":\"早上好\"}]}";
        PromptInjector.Result r = PromptInjector.apply(chat,
                new PromptInjector.Options(true, "append", "简短回答", false, 20, ""), VARS);
        T.eq("chat：命中 system 消息", "system-message", r.note);
        List<?> msgs = (List<?>) map(r.json).get("messages");
        T.eq("chat：消息数不变", 2, msgs.size());
        String sys = (String) ((Map<?, ?>) msgs.get(0)).get("content");
        T.ok("chat：追加到 system 末尾",
                sys.startsWith("你是小爱\n\n") && sys.contains("简短回答"));
        T.eq("chat：user 消息不变", "早上好", ((Map<?, ?>) msgs.get(1)).get("content"));

        // 没有 system：插到最前
        String noSystem = "{\"messages\":[{\"role\":\"user\",\"content\":\"在吗\"}]}";
        PromptInjector.Result ins = PromptInjector.apply(noSystem,
                new PromptInjector.Options(true, "append", "规则A", false, 20, ""), VARS);
        T.eq("chat：无 system 时插入", "system-inserted", ins.note);
        List<?> after = (List<?>) map(ins.json).get("messages");
        T.eq("chat：插入后消息数 +1", 2, after.size());
        T.eq("chat：新消息 role=system", "system", ((Map<?, ?>) after.get(0)).get("role"));
        T.ok("chat：新消息带块", String.valueOf(((Map<?, ?>) after.get(0)).get("content"))
                .contains("规则A"));

        // 多模态 system（content 是 part 数组）
        String multimodal = "{\"messages\":[{\"role\":\"system\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"原生\"},{\"type\":\"image_url\",\"image_url\":{}}]}]}";
        PromptInjector.Result mm = PromptInjector.apply(multimodal,
                new PromptInjector.Options(true, "append", "规则B", false, 20, ""), VARS);
        List<?> parts = (List<?>) ((Map<?, ?>) ((List<?>) map(mm.json).get("messages")).get(0))
                .get("content");
        T.eq("chat：part 数量不变", 2, parts.size());
        T.ok("chat：text part 被追加",
                String.valueOf(((Map<?, ?>) parts.get(0)).get("text")).contains("规则B"));
        T.eq("chat：image part 原样", "image_url", ((Map<?, ?>) parts.get(1)).get("type"));

        // system 存在但没有 text part
        String noTextPart = "{\"messages\":[{\"role\":\"system\",\"content\":["
                + "{\"type\":\"image_url\",\"image_url\":{}}]}]}";
        PromptInjector.Result nt = PromptInjector.apply(noTextPart,
                new PromptInjector.Options(true, "append", "规则C", false, 20, ""), VARS);
        List<?> ntParts = (List<?>) ((Map<?, ?>) ((List<?>) map(nt.json).get("messages")).get(0))
                .get("content");
        T.eq("chat：无 text part 时补一个", 2, ntParts.size());
        T.eq("chat：补的 part 类型", "text", ((Map<?, ?>) ntParts.get(1)).get("type"));
    }

    // ------------------------------------------------------------- 幂等

    private static void idempotent() {
        PromptInjector.Options o =
                new PromptInjector.Options(true, "append", "规则R", false, 20, "");
        PromptInjector.Result first = PromptInjector.apply(RESPONSES, o, VARS);
        PromptInjector.Result second = PromptInjector.apply(first.json, o, VARS);
        T.eq("幂等：第二次输出不变", first.json, second.json);
        T.ok("幂等：第二次 changed=false", !second.changed);
        T.eq("幂等：标记只出现一次",
                1, count(first.json, "<!-- xiaoi-plus:prompt v1 -->"));

        // 更换提示词时整段替换，旧内容不残留
        PromptInjector.Result changed = PromptInjector.apply(first.json,
                new PromptInjector.Options(true, "append", "新规则", false, 20, ""), VARS);
        T.ok("幂等：换内容后旧的不在", !changed.json.contains("规则R"));
        T.ok("幂等：换内容后新的在", changed.json.contains("新规则"));
        T.eq("幂等：标记仍只有一次",
                1, count(changed.json, "<!-- xiaoi-plus:prompt v1 -->"));

        // chat 链路同样幂等
        String chat = "{\"messages\":[{\"role\":\"system\",\"content\":\"s\"}]}";
        String once = PromptInjector.apply(chat,
                new PromptInjector.Options(true, "append", "x", false, 20, ""), VARS).json;
        String twice = PromptInjector.apply(once,
                new PromptInjector.Options(true, "append", "x", false, 20, ""), VARS).json;
        T.eq("幂等：chat 链路", once, twice);
    }

    // ------------------------------------------------------------- 截断

    private static void truncation() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < Contract.PROMPT_TEXT_MAX + 500; i++) big.append('x');
        PromptInjector.Result r = PromptInjector.apply(RESPONSES,
                new PromptInjector.Options(true, "append", big.toString(), false, 20, ""), VARS);
        String inst = (String) map(r.json).get("instructions");
        int start = inst.indexOf(PromptInjector.MARKER) + PromptInjector.MARKER.length() + 1;
        int end = inst.indexOf(PromptInjector.MARKER_END);
        T.eq("截断：正文长度等于上限", Contract.PROMPT_TEXT_MAX, end - start - 1);

        // 上限可被 Contract 引用（防止文档与实现漂移）
        T.eq("截断：Contract 上限是 8KB", 8192, Contract.PROMPT_TEXT_MAX);
    }

    // ------------------------------------------------------------- replace

    private static void replaceMode() {
        PromptInjector.Result r = PromptInjector.apply(RESPONSES,
                new PromptInjector.Options(true, "replace", "只有我", false, 20, ""), VARS);
        String inst = (String) map(r.json).get("instructions");
        T.ok("replace：完全替换", !inst.contains("原生指令"));
        T.ok("replace：只剩注入块", inst.startsWith(PromptInjector.MARKER));
        T.eq("replace：正文", "只有我", inst.split("\n")[1]);

        // replace 但提示词为空 → 按追加处理（不误删原生指令）
        PromptInjector.Result empty = PromptInjector.apply(RESPONSES,
                new PromptInjector.Options(true, "replace", "  ", false, 20, ""), VARS);
        T.ok("replace：空文本不改动", !empty.changed);

        // 关闭开关 → 即便模式是 replace 也不动
        PromptInjector.Result off = PromptInjector.apply(RESPONSES,
                new PromptInjector.Options(false, "replace", "只有我", false, 20, ""), VARS);
        T.ok("replace：开关关闭时不改", !off.changed);
        T.eq("replace：关闭时原样返回", RESPONSES, off.json);
    }

    // ------------------------------------------------------------- 记忆注入

    private static void memoryInject() {
        String outline = "# 用户画像\n- 杭州\n- 喜欢简洁\n- 第三条\n- 第四条\n";
        PromptInjector.Result r = PromptInjector.apply(RESPONSES,
                new PromptInjector.Options(false, "append", "", true, 2, outline), VARS);
        String inst = (String) map(r.json).get("instructions");
        T.ok("记忆：带围栏", inst.contains(PromptInjector.MEMORY_OPEN)
                && inst.contains(PromptInjector.MEMORY_CLOSE));
        T.ok("记忆：带免责句", inst.contains(PromptInjector.MEMORY_DISCLAIMER));
        T.ok("记忆：只取前 N 行", inst.contains("- 杭州") && inst.contains("# 用户画像"));
        T.ok("记忆：超出 N 行被丢弃", !inst.contains("- 喜欢简洁") && !inst.contains("- 第三条"));
        T.ok("记忆：放在原生指令之后（追加）", inst.indexOf("原生指令") < inst.indexOf(PromptInjector.MEMORY_OPEN));
        T.ok("记忆：没有 prompt 标记时只注入记忆", !inst.contains(PromptInjector.MARKER));

        // 记忆 + 提示词：记忆在前
        PromptInjector.Result both = PromptInjector.apply(RESPONSES,
                new PromptInjector.Options(true, "append", "规则", true, 5, outline), VARS);
        String inst2 = (String) map(both.json).get("instructions");
        T.ok("记忆：与提示词同时开启时记忆在前",
                inst2.indexOf(PromptInjector.MEMORY_OPEN) < inst2.indexOf(PromptInjector.MARKER));

        // 只开记忆但文本为空 → 不改
        PromptInjector.Result noOutline = PromptInjector.apply(RESPONSES,
                new PromptInjector.Options(false, "append", "", true, 5, ""), VARS);
        T.ok("记忆：没有 outline 时不改", !noOutline.changed);
    }

    // ------------------------------------------------------------- 失败即原样

    private static void failures() {
        PromptInjector.Result empty = PromptInjector.apply(RESPONSES, PromptInjector.Options.off(), VARS);
        T.ok("空配置：changed=false", !empty.changed);
        T.eq("空配置：原样返回", RESPONSES, empty.json);

        PromptInjector.Result broken = PromptInjector.apply("{not json",
                new PromptInjector.Options(true, "append", "x", false, 20, ""), VARS);
        T.eq("非法 JSON：原样返回", "{not json", broken.json);
        T.eq("非法 JSON：原因", "invalid-json", broken.note);
        T.ok("非法 JSON：changed=false", !broken.changed);

        PromptInjector.Result arr = PromptInjector.apply("[1,2]",
                new PromptInjector.Options(true, "append", "x", false, 20, ""), VARS);
        T.eq("非对象：原样返回", "[1,2]", arr.json);
        T.eq("非对象：原因", "not-object", arr.note);

        PromptInjector.Result noTarget = PromptInjector.apply("{\"model\":\"m\"}",
                new PromptInjector.Options(true, "append", "x", false, 20, ""), VARS);
        T.ok("无目标字段：不改", !noTarget.changed);
        T.eq("无目标字段：原因", "no-target", noTarget.note);

        PromptInjector.Result nul = PromptInjector.apply(null,
                new PromptInjector.Options(true, "append", "x", false, 20, ""), VARS);
        T.ok("null 输入：不改", !nul.changed);

        // 超长记忆行数参数被夹到合法范围
        PromptInjector.Result clamp = PromptInjector.apply(RESPONSES,
                new PromptInjector.Options(false, "append", "", true, 0, "- a\n- b"), VARS);
        T.ok("记忆：行数 0 回落到默认仍能注入",
                ((String) map(clamp.json).get("instructions")).contains("- a"));
    }

    // ------------------------------------------------------------- 工具

    private static Map<?, ?> map(String json) {
        Object v = Json.read(json);
        if (!(v instanceof Map)) throw new IllegalStateException("not object: " + json);
        return (Map<?, ?>) v;
    }

    private static int count(String text, String needle) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
