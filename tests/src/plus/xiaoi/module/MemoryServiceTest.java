package plus.xiaoi.module;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;

import plus.xiaoi.module.application.memory.MemoryService;
import plus.xiaoi.module.application.memory.MemoryStore;
import plus.xiaoi.module.core.Contract;
import plus.xiaoi.module.memory.MemoryFileStore;

/**
 * P2-D：记忆写入（备份 / 原子替换 / 上限 / 失败不破坏原文件）。
 *
 * <p>用仓库内的 {@code tests/.build/memory-test-*} 作为临时目录
 * （{@code run.sh} 每次重建），不写系统临时目录。</p>
 */
public final class MemoryServiceTest {

    private MemoryServiceTest() {}

    private static final String SAMPLE =
            "# 用户画像\n"
            + "## 基本信息\n"
            + "- 机型：Xiaomi 15\n"
            + "- 城市：杭州\n"
            + "# 长期记忆\n"
            + "## 重要事实\n"
            + "- 项目代号 xiaoi-plus\n"
            + "# AI助手身份\n"
            + "## 设定\n"
            + "- 自称小爱\n";

    public static int run() {
        File root = new File("tests/.build/memory-test-" + System.nanoTime());
        try {
            fileStore(root);
            service(root);
            failures(root);
        } catch (Throwable t) {
            T.ok("MemoryServiceTest 套件异常: " + t, false);
        } finally {
            deleteTree(root);
        }
        return T.report("MemoryServiceTest");
    }

    // ------------------------------------------------------------- 文件层

    private static void fileStore(File root) {
        MemoryFileStore store = new MemoryFileStore(root);
        T.eq("文件：缺文件读回 null", null, store.read("MEMORY.md"));
        T.eq("文件：首次写入无备份", "", store.write("MEMORY.md", SAMPLE));
        T.eq("文件：读回内容", SAMPLE, store.read("MEMORY.md"));
        T.ok("文件：目录已创建", root.isDirectory());

        // 第二次写入产生备份
        String backup = store.write("MEMORY.md", SAMPLE + "- 新条目\n");
        T.ok("文件：备份名带时间戳", backup != null && backup.startsWith("MEMORY.md.bak-"));
        T.ok("文件：备份存在", new File(root, backup).isFile());
        T.eq("文件：备份内容是旧版本", SAMPLE, store.read(backup));
        T.eq("文件：主文件已更新", SAMPLE + "- 新条目\n", store.read("MEMORY.md"));

        // 备份保留上限
        MemoryFileStore keepTwo = new MemoryFileStore(root, 2);
        for (int i = 0; i < 4; i++) keepTwo.write("MEMORY.md", SAMPLE + "- 第 " + i + " 条\n");
        List<File> backups = keepTwo.backups("MEMORY.md");
        T.ok("文件：备份份数不超上限", backups.size() <= 2);

        // 临时文件不残留
        boolean leftover = false;
        File[] all = root.listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.getName().contains(".tmp-")) leftover = true;
            }
        }
        T.ok("文件：未残留临时文件", !leftover);
    }

    // ------------------------------------------------------------- 用例层

    private static void service(File root) {
        File dir = new File(root, "svc");
        MemoryFileStore store = new MemoryFileStore(dir);
        MemoryService svc = new MemoryService(store);
        store.write("MEMORY.md", SAMPLE);
        svc.append("用户画像", "- 操作系统：Android");
        store.write("outline.md", "# 摘要\n- 杭州\n- 喜欢简洁\n");

        MemoryService.Result got = svc.overview(10);
        T.ok("用例：overview 成功", got.ok);
        Map<?, ?> body = map(got.json);
        T.eq("用例：文件存在", true, body.get("exists"));
        T.eq("用例：有 6 个小节", 6, ((List<?>) body.get("sections")).size());
        T.eq("用例：条目数", 5L, body.get("items"));

        // 每小节条数上限 + truncated（基本信息原本 2 条）
        MemoryService.Result limited = svc.overview(1);
        List<?> sections = (List<?>) map(limited.json).get("sections");
        Map<?, ?> basics = (Map<?, ?>) sections.get(1);
        T.eq("用例：小节最多返回 1 条", 1, ((List<?>) basics.get("items")).size());
        T.eq("用例：超出标记 truncated", true, basics.get("truncated"));
        T.eq("用例：count 是真实条数", 2L, basics.get("count"));

        // 追加到已有小节
        MemoryService.Result added = svc.append("长期记忆/重要事实", "第二条事实");
        T.ok("用例：append 成功", added.ok);
        Map<?, ?> addedBody = map(added.json);
        T.eq("用例：append 码", "APPENDED", addedBody.get("code"));
        T.ok("用例：append 带备份名",
                String.valueOf(addedBody.get("backup")).startsWith("MEMORY.md.bak-"));
        T.ok("用例：追加内容已落盘", store.read("MEMORY.md").contains("- 第二条事实"));
        T.ok("用例：自动补列表符", store.read("MEMORY.md").contains("- 第二条事实"));

        // 替换小节
        MemoryService.Result replaced = svc.replace("长期记忆/重要事实", "- 只有这一条");
        T.eq("用例：replace 码", "REPLACED", map(replaced.json).get("code"));
        String onDisk = store.read("MEMORY.md");
        T.ok("用例：替换后旧条目消失", !onDisk.contains("第二条事实"));
        T.ok("用例：替换保留标题", onDisk.contains("## 重要事实\n- 只有这一条"));
        T.ok("用例：outline 未被写", store.read("outline.md").contains("- 喜欢简洁"));

        // 注入文本：优先 outline
        String inject = svc.injectText(2);
        T.ok("用例：注入优先 outline", inject.contains("- 杭州") && !inject.contains("自称小爱"));
        T.eq("用例：注入按行数裁剪", 2, inject.split("\n").length);
        // 没有 outline 时退回 MEMORY.md
        deleteTree(dir);
        svc.append("用户画像", "- 只有记忆");
        T.ok("用例：无 outline 时退回 MEMORY.md", svc.injectText(5).contains("- 只有记忆"));
    }

    // ------------------------------------------------------------- 上限 / 失败

    private static void failures(File root) {
        File dir = new File(root, "fail");
        MemoryFileStore store = new MemoryFileStore(dir);
        MemoryService svc = new MemoryService(store);
        svc.append("用户画像", "- 原始条目");
        String before = store.read("MEMORY.md");

        // 单次过大
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < Contract.MEMORY_APPEND_MAX_BYTES + 10; i++) big.append('a');
        MemoryService.Result tooBig = svc.append("用户画像", big.toString());
        T.ok("上限：单次超限被拒", !tooBig.ok);
        T.eq("上限：单次超限错误码", "TEXT_TOO_LARGE", tooBig.code);
        T.eq("上限：被拒后文件未改动", before, store.read("MEMORY.md"));

        // 文件总量过大
        File huge = new File(dir, "MEMORY.md");
        writeRaw(huge, bigFile(Contract.MEMORY_FILE_MAX_BYTES + 100));
        MemoryService.Result fileTooBig = svc.append("用户画像", "- 再加一条");
        T.ok("上限：文件超限被拒", !fileTooBig.ok);
        T.eq("上限：文件超限错误码", "FILE_TOO_LARGE", fileTooBig.code);

        // 参数校验
        T.eq("参数：缺小节", "MISSING_SECTION", svc.append("  ", "x").code);
        T.eq("参数：缺内容", "MISSING_TEXT", svc.append("用户画像", "   ").code);
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < Contract.MEMORY_SECTION_MAX + 1; i++) longName.append('节');
        T.eq("参数：小节名超长", "SECTION_TOO_LONG", svc.append(longName.toString(), "x").code);

        // 写入失败：端口返回 null → 用例报错且原文件不动
        MemoryService failing = new MemoryService(new FailingStore(before));
        MemoryService.Result failedWrite = failing.append("用户画像", "- 不应写入");
        T.ok("失败：写失败被拒绝", !failedWrite.ok);
        T.eq("失败：错误码", "WRITE_FAILED", failedWrite.code);

        // 目录不可用（父路径是普通文件）→ 读写都安全返回
        File notDir = new File(root, "not-a-dir");
        writeRaw(notDir, "x");
        MemoryFileStore broken = new MemoryFileStore(notDir);
        T.eq("失败：目录不可用时读 null", null, broken.read("MEMORY.md"));
        T.eq("失败：目录不可用时写 null", null, broken.write("MEMORY.md", "x"));

        // 只读大纲：MemoryService 没有写 outline 的方法，写操作只落 MEMORY.md
        MemoryFileStore ok = new MemoryFileStore(new File(root, "ro"));
        MemoryService ro = new MemoryService(ok);
        ok.write("outline.md", "# 摘要\n- x\n");
        ro.append("用户画像", "- y");
        T.ok("边界：outline 保持只读", "# 摘要\n- x\n".equals(ok.read("outline.md")));
        T.ok("边界：outline 未进 MEMORY.md", !ok.read("MEMORY.md").contains("摘要"));
    }

    /** 写成功不了、读正常的端口。 */
    private static final class FailingStore implements MemoryStore {
        private final String content;

        FailingStore(String content) {
            this.content = content;
        }

        @Override
        public String read(String name) {
            return "MEMORY.md".equals(name) ? content : null;
        }

        @Override
        public String write(String name, String content) {
            return null;
        }
    }

    // ------------------------------------------------------------- 工具

    private static Map<?, ?> map(String json) {
        Object v = Json.read(json);
        if (!(v instanceof Map)) throw new IllegalStateException("not object: " + json);
        return (Map<?, ?>) v;
    }

    private static String bigFile(int bytes) {
        StringBuilder sb = new StringBuilder("# 长期记忆\n");
        while (sb.length() < bytes) sb.append("- 填充行 用于撑大文件\n");
        return sb.toString();
    }

    private static void writeRaw(File f, String text) {
        FileOutputStream out = null;
        try {
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
        } catch (Throwable t) {
            throw new IllegalStateException("写测试文件失败: " + f, t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        f.delete();
    }
}
