package plus.xiaoi.module;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import plus.xiaoi.module.bridge.LeaderElection;

/**
 * 测试辅助进程：拿到文件锁后写 ready 文件并持有，父进程用 kill 验证 OS 释放锁。
 * 用法：java plus.xiaoi.module.LeaderProbe <lockFile> <readyFile>
 */
public final class LeaderProbe {

    private LeaderProbe() {}

    public static void main(String[] args) throws Exception {
        LeaderElection election = new LeaderElection(new File(args[0]), "probe");
        boolean leader = election.acquireIfPossible();
        FileOutputStream out = new FileOutputStream(args[1]);
        try {
            out.write((leader ? "LEADER" : "BUSY").getBytes("UTF-8"));
            out.flush();
        } finally {
            out.close();
        }
        if (!leader) {
            System.exit(2);
            return;
        }
        // 持锁等待父进程 kill -9 / destroyForcibly。
        Thread.sleep(30_000L);
    }

    static String readFile(File f) {
        FileInputStream in = null;
        try {
            if (!f.isFile()) return "";
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            return new String(buf, 0, Math.max(n, 0), "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
