package plus.xiaoi.module.application.memory;

import java.util.List;
import java.util.Map;

import plus.xiaoi.module.Json;
import plus.xiaoi.module.core.Contract;
import plus.xiaoi.module.core.memory.MemoryDoc;

/**
 * 记忆读写用例（P2-D）。
 *
 * <p>职责：<b>策略</b>——解析、分节、上限、只有 append / replace 两种写操作；
 * 真正的读写与备份在 {@link MemoryStore} 的实现里。本类不碰文件、不碰 Android。</p>
 *
 * <p>硬约束：</p>
 * <ul>
 *   <li>单次正文 ≤ {@link Contract#MEMORY_APPEND_MAX_BYTES}；</li>
 *   <li>写后文件 ≤ {@link Contract#MEMORY_FILE_MAX_BYTES}；</li>
 *   <li>只有 {@code MEMORY.md} 可写，{@code outline.md} 只读；</li>
 *   <li>不提供删除整段/清空：没有这样的方法，也没有对应 op。</li>
 * </ul>
 *
 * <p>返回 JSON 文本（MCP / UI 共用），错误统一 {@code {"ok":false,"code":...}}。</p>
 */
public final class MemoryService {

    /** 唯一可写的记忆文件。 */
    public static final String MEMORY_FILE = "MEMORY.md";
    /** 记忆摘要，只读。 */
    public static final String OUTLINE_FILE = "outline.md";

    private final MemoryStore store;

    public MemoryService(MemoryStore store) {
        if (store == null) throw new IllegalArgumentException("store == null");
        this.store = store;
    }

    /** 一次调用的结果；{@code json} 永远是可直接返回给 MCP 的文本。 */
    public static final class Result {
        public final boolean ok;
        public final String code;
        public final String json;

        private Result(boolean ok, String code, String json) {
            this.ok = ok;
            this.code = code;
            this.json = json;
        }

        static Result ok(String code, Map<String, Object> body) {
            body.put("ok", true);
            body.put("code", code);
            return new Result(true, code, Json.write(body));
        }

        static Result fail(String code, String message) {
            Map<String, Object> body = Json.obj();
            body.put("ok", false);
            body.put("code", code);
            body.put("message", message == null ? "" : message);
            return new Result(false, code, Json.write(body));
        }
    }

    // ------------------------------------------------------------- 读

    /**
     * 分节返回 {@code MEMORY.md}。
     *
     * @param limit 每个小节最多返回多少条（{@code <=0} 用默认值，超过上限按上限）
     */
    public Result overview(int limit) {
        String raw = store.read(MEMORY_FILE);
        if (raw == null) {
            Map<String, Object> body = Json.obj();
            body.put("exists", false);
            body.put("file", MEMORY_FILE);
            body.put("bytes", 0L);
            body.put("items", 0L);
            body.put("sections", Json.arr());
            return Result.ok("NO_FILE", body);
        }
        int perSection = clampLimit(limit);
        MemoryDoc doc = MemoryDoc.parse(raw);
        List<Object> sections = Json.arr();
        for (MemoryDoc.Section s : doc.sections()) {
            List<String> items = s.items();
            Map<String, Object> o = Json.obj();
            o.put("level", (long) s.level());
            o.put("title", s.title());
            o.put("count", (long) items.size());
            if (items.size() > perSection) {
                o.put("items", toList(items.subList(0, perSection)));
                o.put("truncated", true);
            } else {
                o.put("items", toList(items));
            }
            sections.add(o);
        }
        Map<String, Object> body = Json.obj();
        body.put("exists", true);
        body.put("file", MEMORY_FILE);
        body.put("bytes", (long) utf8Length(raw));
        body.put("items", (long) doc.itemCount());
        body.put("sections", sections);
        return Result.ok("OK", body);
    }

    /** 注入用文本：优先 {@code outline.md}，没有就退回 {@code MEMORY.md}；最多取 N 行。 */
    public String injectText(int maxLines) {
        int max = maxLines < 1 ? Contract.MEMORY_INJECT_LINES_DEFAULT : maxLines;
        if (max > Contract.MEMORY_INJECT_LINES_MAX) max = Contract.MEMORY_INJECT_LINES_MAX;
        String text = store.read(OUTLINE_FILE);
        if (text == null || text.trim().length() == 0) text = store.read(MEMORY_FILE);
        if (text == null) return "";
        if (text.trim().length() == 0) return "";
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (String line : lines) {
            if (taken >= max) break;
            sb.append(line).append('\n');
            taken++;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- 写

    /** 追加条目到小节（小节不存在时新建）。 */
    public Result append(String section, String text) {
        return write("append", section, text);
    }

    /** 替换小节正文（保留标题；小节不存在时新建）。 */
    public Result replace(String section, String text) {
        return write("replace", section, text);
    }

    private Result write(String op, String section, String text) {
        String title = section == null ? "" : section.trim();
        if (title.length() == 0) return Result.fail("MISSING_SECTION", "缺少小节名，例如「长期记忆/重要事实」");
        if (title.length() > Contract.MEMORY_SECTION_MAX) {
            return Result.fail("SECTION_TOO_LONG",
                    "小节名过长（上限 " + Contract.MEMORY_SECTION_MAX + " 字符）");
        }
        String body = text == null ? "" : text;
        if (body.trim().length() == 0) return Result.fail("MISSING_TEXT", "缺少要写入的内容");
        int textBytes = utf8Length(body.trim());
        if (textBytes > Contract.MEMORY_APPEND_MAX_BYTES) {
            return Result.fail("TEXT_TOO_LARGE",
                    "单次写入过大（" + textBytes + " 字节，上限 " + Contract.MEMORY_APPEND_MAX_BYTES + "）");
        }

        String raw = store.read(MEMORY_FILE);
        if (raw != null && utf8Length(raw) > Contract.MEMORY_FILE_MAX_BYTES) {
            return Result.fail("FILE_TOO_LARGE",
                    "记忆文件已超过 " + Contract.MEMORY_FILE_MAX_BYTES + " 字节，拒绝继续追加");
        }
        MemoryDoc doc = MemoryDoc.parse(raw == null ? "" : raw);
        String next = "append".equals(op)
                ? doc.append(title, body).render()
                : doc.replaceSection(title, body).render();
        int nextBytes = utf8Length(next);
        if (nextBytes > Contract.MEMORY_FILE_MAX_BYTES) {
            return Result.fail("FILE_TOO_LARGE",
                    "写入后会超过 " + Contract.MEMORY_FILE_MAX_BYTES + " 字节上限（" + nextBytes + "）");
        }
        String backup = store.write(MEMORY_FILE, next);
        if (backup == null) return Result.fail("WRITE_FAILED", "写入失败，原文件未改动");

        MemoryDoc after = MemoryDoc.parse(next);
        Map<String, Object> result = Json.obj();
        result.put("op", op);
        result.put("section", title);
        result.put("file", MEMORY_FILE);
        result.put("bytes", (long) nextBytes);
        result.put("items", (long) after.itemCount());
        result.put("backup", backup == null ? "" : backup);
        return Result.ok("append".equals(op) ? "APPENDED" : "REPLACED", result);
    }

    // ------------------------------------------------------------- 工具

    private static int clampLimit(int limit) {
        if (limit <= 0) return Contract.MEMORY_GET_LIMIT_DEFAULT;
        return Math.min(limit, Contract.MEMORY_GET_LIMIT_MAX);
    }

    private static List<Object> toList(List<String> items) {
        List<Object> out = Json.arr();
        for (String s : items) out.add(s);
        return out;
    }

    /** UTF-8 字节数；编码不可用时按最坏情况估算（宁可拒绝也不越界）。 */
    static int utf8Length(String s) {
        if (s == null) return 0;
        try {
            return s.getBytes("UTF-8").length;
        } catch (Throwable t) {
            return s.length() * 3;
        }
    }
}
