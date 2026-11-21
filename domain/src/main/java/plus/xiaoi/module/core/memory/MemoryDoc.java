package plus.xiaoi.module.core.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 小爱记忆文件（{@code files/memory/MEMORY.md}）的分节解析与生成（纯 JVM）。
 *
 * <p>真实文件是普通 Markdown：一级 {@code #} 三段（用户画像 / 长期记忆 / AI助手身份），
 * 二级 {@code ##} 是子节，正文一行一条（通常是 {@code - 条目}）。本类只做三件事：</p>
 *
 * <ol>
 *   <li><b>解析</b>：保留原文顺序与空行，{@link #render()} 能还原出原文
 *       （换行统一为 {@code \n}，结尾是否带换行按原文保留）；</li>
 *   <li><b>追加</b>：往某个小节尾部加条目；小节不存在时新建（路径用 {@code 一级/二级}，
 *       缺的层级一起补出来）；</li>
 *   <li><b>替换小节</b>：只换该小节正文，保留标题与其它小节。</li>
 * </ol>
 *
 * <p><b>不提供</b>删除整段/清空文件——破坏性操作没有入口。</p>
 *
 * <p>条目规范化：一行一条；空行丢弃；不以 {@code - / * / + / 1.} 开头的行自动补
 * {@code "- "}。以 {@code #} 开头的行同样补 {@code "- "}，避免用户文本被解析成新标题。</p>
 */
public final class MemoryDoc {

    /** 一个标题 + 它到下一个标题之间的正文行。 */
    public static final class Section {
        private final int level;
        private final String title;
        private final List<String> lines;

        Section(int level, String title, List<String> lines) {
            this.level = level;
            this.title = title == null ? "" : title;
            this.lines = lines == null ? new ArrayList<String>() : lines;
        }

        /** {@code #} 的个数（1..6）。 */
        public int level() {
            return level;
        }

        /** 去掉 {@code #} 与首尾空白后的标题。 */
        public String title() {
            return title;
        }

        /** 正文行（只读视图，含原文件里的空行）。 */
        public List<String> lines() {
            return Collections.unmodifiableList(lines);
        }

        /** 正文里的非空行数（粗略的“条目数”）。 */
        public int itemCount() {
            int n = 0;
            for (String line : lines) {
                if (line.trim().length() > 0) n++;
            }
            return n;
        }

        /** 正文里去掉空行后的条目。 */
        public List<String> items() {
            List<String> out = new ArrayList<String>();
            for (String line : lines) {
                if (line.trim().length() > 0) out.add(line);
            }
            return out;
        }

        Section copy() {
            return new Section(level, title, new ArrayList<String>(lines));
        }
    }

    private final List<String> preamble;
    private final List<Section> sections;
    private final boolean trailingNewline;

    private MemoryDoc(List<String> preamble, List<Section> sections, boolean trailingNewline) {
        this.preamble = preamble;
        this.sections = sections;
        this.trailingNewline = trailingNewline;
    }

    // ------------------------------------------------------------- 解析

    /**
     * 解析 Markdown。{@code text} 为 null/空时返回空文档。
     *
     * <p>只认 {@code #} 开头的行（允许前导空格，最多 6 个 {@code #} 后必须跟空白）；
     * 第一个标题之前的内容作为 preamble 原样保留。</p>
     */
    public static MemoryDoc parse(String text) {
        String src = text == null ? "" : text;
        boolean trailing = src.endsWith("\n");
        List<String> raw = new ArrayList<String>();
        if (src.length() > 0) {
            String[] parts = src.split("\n", -1);
            int count = parts.length;
            // split 的最后一个元素在“以换行结尾”时是空串，不算一行。
            if (trailing) count--;
            for (int i = 0; i < count; i++) {
                String line = parts[i];
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                raw.add(line);
            }
        }

        List<String> pre = new ArrayList<String>();
        List<Section> secs = new ArrayList<Section>();
        Section current = null;
        for (String line : raw) {
            Heading h = Heading.of(line);
            if (h != null) {
                current = new Section(h.level, h.title, new ArrayList<String>());
                secs.add(current);
            } else if (current == null) {
                pre.add(line);
            } else {
                current.lines.add(line);
            }
        }
        return new MemoryDoc(pre, secs, trailing);
    }

    /** 一个标题行。 */
    private static final class Heading {
        final int level;
        final String title;

        private Heading(int level, String title) {
            this.level = level;
            this.title = title;
        }

        static Heading of(String line) {
            if (line == null) return null;
            String s = line.trim();
            int i = 0;
            while (i < s.length() && s.charAt(i) == '#') i++;
            if (i == 0 || i > 6) return null;
            if (i == s.length()) return null;          // 只有井号，不是标题
            if (!Character.isWhitespace(s.charAt(i))) return null;
            String title = s.substring(i).trim();
            if (title.length() == 0) return null;
            return new Heading(i, title);
        }
    }

    // ------------------------------------------------------------- 读取

    /** 第一个标题之前的内容（通常是空的）。 */
    public List<String> preamble() {
        return Collections.unmodifiableList(preamble);
    }

    /** 全部标题小节（扁平顺序，层级见 {@link Section#level()}）。 */
    public List<Section> sections() {
        return Collections.unmodifiableList(sections);
    }

    /** 非空正文行总数。 */
    public int itemCount() {
        int n = 0;
        for (Section s : sections) n += s.itemCount();
        return n;
    }

    /** 全文（解析后再生成；对合法输入与原文一致）。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (String line : preamble) sb.append(line).append('\n');
        for (Section s : sections) {
            sb.append(hash(s.level)).append(' ').append(s.title).append('\n');
            for (String line : s.lines) sb.append(line).append('\n');
        }
        if (!trailingNewline && sb.length() > 0) sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private static String hash(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append('#');
        return sb.toString();
    }

    // ------------------------------------------------------------- 修改

    /**
     * 往小节尾部追加条目（返回新文档，不修改本对象）。
     *
     * @param section 小节路径，例如 {@code 长期记忆} 或 {@code 长期记忆/重要事实}
     * @param text    一条或多条文本；按行拆成条目（见类注释）
     */
    public MemoryDoc append(String section, String text) {
        List<String> add = toLines(text);
        if (add.isEmpty()) return this;
        List<Section> next = copySections();
        int idx = find(section);
        if (idx >= 0) {
            next.get(idx).lines.addAll(localTail(next.get(idx)), add);
            return done(next);
        }
        appendNewSection(next, section, add);
        return done(next);
    }

    /**
     * 替换小节正文（保留标题）。小节不存在时按 {@link #append} 的规则新建。
     *
     * @param section 小节路径
     * @param text    新正文；空字符串会清空该小节正文（但保留标题）
     */
    public MemoryDoc replaceSection(String section, String text) {
        List<String> body = toLines(text);
        List<Section> next = copySections();
        int idx = find(section);
        if (idx >= 0) {
            next.get(idx).lines.clear();
            next.get(idx).lines.addAll(body);
            return done(next);
        }
        appendNewSection(next, section, body);
        return done(next);
    }

    /** 只读查询：小节是否存在。 */
    public boolean has(String section) {
        return find(section) >= 0;
    }

    private MemoryDoc done(List<Section> next) {
        // 写入类操作一律规范化成「以换行结尾」，避免后续追加时粘行。
        return new MemoryDoc(new ArrayList<String>(preamble), next, true);
    }

    private List<Section> copySections() {
        List<Section> out = new ArrayList<Section>();
        for (Section s : sections) out.add(s.copy());
        return out;
    }

    /** 小节最后一条非空行之后的位置（小节自己的 lines 下标），避免插在尾部空行之后。 */
    private static int localTail(Section section) {
        int n = section.lines.size();
        while (n > 0 && section.lines.get(n - 1).trim().length() == 0) n--;
        return n;
    }

    /** 新建缺失的标题层级并写入正文。 */
    private void appendNewSection(List<Section> next, String section, List<String> body) {
        List<String> segs = segments(section);
        if (segs.isEmpty()) return;
        int existing = 0;
        int cursor = -1;
        int level = 0;
        for (String seg : segs) {
            int child = findChild(next, cursor, level, seg);
            if (child < 0) break;
            existing++;
            cursor = child;
            level = next.get(child).level;
        }
        // 已有父节时插在父节子树末尾（下一条同级或更高级标题之前），否则追加到文末。
        int insertAt = existing == 0 ? next.size() : endOf(next, cursor);
        ensureBlankBefore(next, insertAt);
        int newLevel = existing == 0 ? 1 : Math.min(level + 1, 6);
        Section target = null;
        for (int k = existing; k < segs.size(); k++) {
            target = new Section(newLevel, segs.get(k), new ArrayList<String>());
            next.add(insertAt++, target);
            newLevel = Math.min(newLevel + 1, 6);
        }
        if (target != null) target.lines.addAll(body);
    }

    /** 插入位置前一条正文不是空行时补一个空行，让新标题与上一节隔开。 */
    private static void ensureBlankBefore(List<Section> next, int insertAt) {
        if (insertAt <= 0 || next.isEmpty()) return;
        List<String> lines = next.get(Math.min(insertAt, next.size()) - 1).lines;
        if (lines.isEmpty()) return;
        if (lines.get(lines.size() - 1).trim().length() == 0) return;
        lines.add("");
    }

    // ------------------------------------------------------------- 查找

    /** 返回匹配的小节下标；-1 = 不存在。 */
    private int find(String section) {
        List<String> segs = segments(section);
        if (segs.isEmpty()) return -1;
        int cursor = -1;
        int level = 0;
        for (String seg : segs) {
            int child = findChild(sections, cursor, level, seg);
            if (child < 0) return -1;
            cursor = child;
            level = sections.get(child).level;
        }
        return cursor;
    }

    /** 在 parent 的子树里找标题等于 title 且层级更深的第一个小节。 */
    private static int findChild(List<Section> secs, int parent, int parentLevel, String title) {
        int from = parent + 1;
        int to = parent < 0 ? secs.size() : endOf(secs, parent);
        for (int i = from; i < to; i++) {
            Section s = secs.get(i);
            if (s.level > parentLevel && s.title.equals(title)) return i;
        }
        return -1;
    }

    /** 该小节的子树结束下标（不含）。 */
    private static int endOf(List<Section> secs, int idx) {
        int level = secs.get(idx).level;
        for (int i = idx + 1; i < secs.size(); i++) {
            if (secs.get(i).level <= level) return i;
        }
        return secs.size();
    }

    private static List<String> segments(String section) {
        List<String> out = new ArrayList<String>();
        if (section == null) return out;
        for (String part : section.split("/")) {
            String s = part.trim();
            if (s.length() > 0) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------- 条目规范化

    /** 多行文本 → 条目列表（丢弃空行，必要时补 {@code "- "}）。 */
    static List<String> toLines(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        String[] raw = text.split("\n", -1);
        for (String r : raw) {
            String line = r == null ? "" : r;
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            int end = line.length();
            while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) end--;
            line = line.substring(0, end);
            if (line.length() == 0) continue;
            out.add(asItem(line));
        }
        return out;
    }

    private static String asItem(String line) {
        String t = line.trim();
        if (isListMarker(t)) return line;
        return "- " + line;
    }

    private static boolean isListMarker(String t) {
        if (t.length() == 0) return false;
        if (t.equals("-") || t.equals("*") || t.equals("+") || t.equals(">")) return true;
        if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") || t.startsWith("> ")) {
            return true;
        }
        // 1. / 1) 形式的有序列表
        int i = 0;
        while (i < t.length() && Character.isDigit(t.charAt(i))) i++;
        if (i == 0 || i >= t.length()) return false;
        char c = t.charAt(i);
        if ((c != '.' && c != ')')) return false;
        return i + 1 < t.length() && Character.isWhitespace(t.charAt(i + 1));
    }
}
