package plus.xiaoi.module;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 批量落盘：阈值 flush、轮转、UTF-8、空值、构造参数。 */
public final class LogFileSinkTest {

    private LogFileSinkTest() {}

    public static int run() {
        File dir = new File("tests/.build/logsink-" + System.nanoTime());
        dir.mkdirs();
        try {
            File f = new File(dir, "a.log");
            LogFileSink sink = new LogFileSink(f, 1024, 2, 1024);
            sink.append("a");
            T.eq("未达阈值不落盘", 0L, f.exists() ? f.length() : 0L);
            T.eq("pending 计数", 1, sink.pendingLines());
            sink.append("b");
            T.eq("达到行数阈值落盘", "a\nb\n", read(f));
            T.eq("落盘后 pending 清零", 0, sink.pendingLines());

            sink.append("中文");
            sink.flush();
            T.eq("UTF-8 正文", "a\nb\n中文\n", read(f));

            sink.flush();
            T.eq("无 pending 时 flush 是 no-op", "a\nb\n中文\n", read(f));

            File r = new File(dir, "r.log");
            LogFileSink rolling = new LogFileSink(r, 20, 1, 1024);
            rolling.append("1234567890");
            rolling.append("abcdefghij");
            T.ok("超过上限生成 .1", new File(r.getPath() + ".1").exists());
            T.eq("旧文件保留上一批", "1234567890\n", read(new File(r.getPath() + ".1")));
            T.eq("当前文件保留最新", "abcdefghij\n", read(r));

            File n = new File(dir, "n.log");
            LogFileSink nullable = new LogFileSink(n, 100, 10, 100);
            nullable.append(null);
            nullable.flush();
            T.eq("null 行写空行", "\n", read(n));

            T.throwsIt("file=null 拒绝", new Runnable() {
                @Override
                public void run() {
                    new LogFileSink(null, 1, 1, 1);
                }
            });
        } finally {
            delete(dir);
        }
        return T.report("LogFileSinkTest");
    }

    private static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) delete(c);
        }
        f.delete();
    }
}
