package plus.xiaoi.module;

import java.util.List;

import plus.xiaoi.module.core.memory.MemoryDoc;
import plus.xiaoi.module.core.prompt.PromptTemplate;

/**
 * P2-A：domain 的提示词模板与记忆文档解析（纯 JVM）。
 *
 * <p>样本按真机 {@code files/memory/MEMORY.md} 的形状写：三段一级标题 +
 * 若干 {@code ##} 子节 + {@code - 条目}。</p>
 */
public final class PromptMemoryDocTest {

    private PromptMemoryDocTest() {}

    private static final String SAMPLE =
            "# 用户画像\n"
            + "## 基本信息\n"
            + "- 机型：Xiaomi 15\n"
            + "- 城市：杭州\n"
            + "\n"
            + "## 偏好\n"
            + "- 喜欢简洁回答\n"
            + "# 长期记忆\n"
            + "## 重要事实\n"
            + "- 项目代号 xiaoi-plus\n"
            + "## 待办\n"
            + "- 检查日志\n"
            + "# AI助手身份\n"
            + "## 设定\n"
            + "- 自称小爱\n";

    public static int run() {
        template();
        memoryDoc();
        return T.report("PromptMemoryDocTest");
    }

    // ------------------------------------------------------------- PromptTemplate

    private static void template() {
        PromptTemplate.Vars vars =
                new PromptTemplate.Vars("2026-11-21", "09:30", "deepseek-v4.1-flash", "M332BF");
        T.eq("模板：四个变量都替换",
                "今天 2026-11-21 09:30，模型 deepseek-v4.1-flash，设备 M332BF",
                PromptTemplate.render("今天 {{date}} {{time}}，模型 {{model}}，设备 {{device}}", vars));

        T.eq("模板：未知占位符原样保留",
                "保留 {{battery}}",
                PromptTemplate.render("保留 {{battery}}", vars));

        T.eq("模板：变量值里的占位符不会被二次展开",
                "{{date}}",
                PromptTemplate.render("{{model}}", new PromptTemplate.Vars("", "", "{{date}}", "")));

        T.eq("模板：空模板返回空串", "", PromptTemplate.render("", vars));
        T.eq("模板：null 返回空串", "", PromptTemplate.render(null, vars));
        T.eq("模板：null 变量按空串",
                "模型：；",
                PromptTemplate.render("模型：{{model}}；", null));
        T.eq("模板：变量为空时替换为空",
                "x",
                PromptTemplate.render("x{{device}}", PromptTemplate.Vars.empty()));
        T.eq("模板：单个左花括号原样",
                "{ 不是占位符 }",
                PromptTemplate.render("{ 不是占位符 }", vars));
    }

    // ------------------------------------------------------------- MemoryDoc

    private static void memoryDoc() {
        MemoryDoc doc = MemoryDoc.parse(SAMPLE);
        T.eq("记忆：小节数", 8, doc.sections().size());
        T.eq("记忆：一级标题顺序",
                "用户画像,长期记忆,AI助手身份",
                join(topLevel(doc)));
        T.eq("记忆：条目数", 6, doc.itemCount());
        T.eq("记忆：round-trip 逐字节一致", SAMPLE, doc.render());

        MemoryDoc.Section prefs = section(doc, "偏好");
        T.eq("记忆：二级节取到条目", "[- 喜欢简洁回答]", prefs.items().toString());

        // 追加到已有二级节：插在该节末尾（不是文件末尾）
        MemoryDoc appended = doc.append("用户画像/偏好", "不喜欢被追问");
        List<String> prefsItems = section(appended, "偏好").items();
        T.eq("记忆：追加到二级节", 2, prefsItems.size());
        T.eq("记忆：追加内容自动补列表符", "- 不喜欢被追问", prefsItems.get(1));        T.ok("记忆：追加不影响后面的小节",
                appended.render().contains("# 长期记忆\n## 重要事实\n- 项目代号 xiaoi-plus"));
        T.ok("记忆：追加后新条目在下一节之前",
                appended.render().indexOf("不喜欢被追问") < appended.render().indexOf("# 长期记忆"));

        // 追加到已有二级节且该节尾部有空行：条目应贴着上一条
        MemoryDoc blankTail = MemoryDoc.parse("# A\n## B\n- x\n\n# C\n- y\n");
        String blankOut = blankTail.append("A/B", "- z").render();
        T.eq("记忆：跳过尾部空行插入", "# A\n## B\n- x\n- z\n\n# C\n- y\n", blankOut);

        // 追加到已有的一级节（没有二级路径）
        MemoryDoc top = doc.append("AI助手身份", "更短");
        T.ok("记忆：追加到一级节",
                top.render().endsWith("# AI助手身份\n- 更短\n## 设定\n- 自称小爱\n"));

        // 追加到不存在的小节：建在父节子树里，而不是文件末尾
        MemoryDoc created = doc.append("长期记忆/归档", "- 2026 归档");
        String createdText = created.render();
        T.ok("记忆：新建二级节落在父节内",
                createdText.indexOf("## 归档") > createdText.indexOf("# 长期记忆"));
        T.ok("记忆：新建二级节在下一个一级标题之前",
                createdText.indexOf("## 归档") < createdText.indexOf("# AI助手身份"));
        T.eq("记忆：新建二级节标题层级", 2, section(created, "归档").level());

        // 追加到完全不存在的一级节：文末新建
        MemoryDoc fresh = doc.append("临时笔记", "第一条");
        T.ok("记忆：新建一级节", fresh.render().endsWith("# 临时笔记\n- 第一条\n"));

        // 空文件
        MemoryDoc empty = MemoryDoc.parse("");
        T.eq("记忆：空文件 render 为空", "", empty.render());
        T.eq("记忆：空文件新建节", "# 用户画像\n- 第一条\n",
                empty.append("用户画像", "第一条").render());

        // 特殊字符与换行
        MemoryDoc special = empty.append("长期记忆", "含 \"引号\" 与 \\ 反斜杠\n第二行\n# 看起来像标题");
        String specialText = special.render();
        T.ok("记忆：多行拆成多条", special.itemCount() == 3);
        T.ok("记忆：井号行不会变成标题", !specialText.contains("\n# 看起来像标题"));
        T.ok("记忆：井号行被列表化", specialText.contains("- # 看起来像标题"));
        T.ok("记忆：引号与反斜杠保留", specialText.contains("- 含 \"引号\" 与 \\ 反斜杠"));

        // 已有列表符不重复加
        MemoryDoc bullets = empty.append("A", "- 已有\n* 星号\n1. 有序\n正文");
        String bulletsText = bullets.render();
        T.ok("记忆：已有列表符不重复", !bulletsText.contains("- - 已有"));
        T.ok("记忆：星号保留", bulletsText.contains("* 星号"));
        T.ok("记忆：有序列表保留", bulletsText.contains("1. 有序"));
        T.ok("记忆：普通行补列表符", bulletsText.contains("- 正文"));

        // 替换小节
        MemoryDoc replaced = doc.replaceSection("长期记忆/待办", "- 新待办 A\n- 新待办 B");
        T.eq("记忆：替换小节正文", "- 新待办 A|- 新待办 B", join(section(replaced, "待办").items(), "|"));
        T.ok("记忆：替换保留标题", replaced.render().contains("## 待办\n- 新待办 A\n- 新待办 B\n"));
        T.ok("记忆：替换不动其它小节", replaced.render().contains("- 项目代号 xiaoi-plus"));
        T.ok("记忆：替换不动原对象", doc.render().contains("- 检查日志"));

        // 替换不存在的小节 = 新建
        MemoryDoc replacedNew = doc.replaceSection("新节", "- 内容");
        T.ok("记忆：替换不存在的小节会新建", replacedNew.has("新节"));

        // CRLF 输入
        MemoryDoc crlf = MemoryDoc.parse("# A\r\n- x\r\n");
        T.eq("记忆：CRLF 归一为 LF", "# A\n- x\n", crlf.render());

        // 空文本：不产生空条目
        T.eq("记忆：空文本不写入", doc.render(), doc.append("用户画像", "   \n\n").render());

        T.ok("记忆：has 命中", doc.has("长期记忆/重要事实"));
        T.ok("记忆：has 未命中", !doc.has("不存在的节"));
    }

    private static List<String> topLevel(MemoryDoc doc) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (MemoryDoc.Section s : doc.sections()) {
            if (s.level() == 1) out.add(s.title());
        }
        return out;
    }

    private static MemoryDoc.Section section(MemoryDoc doc, String title) {
        for (MemoryDoc.Section s : doc.sections()) {
            if (s.title().equals(title)) return s;
        }
        throw new IllegalStateException("没有小节: " + title);
    }

    private static String join(List<String> items) {
        return join(items, ",");
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }
}
