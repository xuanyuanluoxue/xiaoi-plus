package plus.xiaoi.module;

import java.io.File;

import plus.xiaoi.module.bridge.LeaderElection;

/**
 * leader 文件锁：同 JVM 重入/释放 + 跨进程持有/进程死亡后接管。
 * 跨进程用例会启动一个 java 子进程，验证「kill 掉持有者后 OS 释放锁」。
 */
public final class LeaderElectionTest {

    private LeaderElectionTest() {}

    public static int run() {
        System.out.println("-- LeaderElection");
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "xiaoi-leader-test-" + System.nanoTime());
        try {
            if (!dir.mkdirs()) {
                T.ok("创建测试目录", false);
                return T.report("LeaderElectionTest");
            }
            sameJvm(new File(dir, "same.lock"));
            crossProcess(dir);
        } catch (Throwable t) {
            T.ok("LeaderElection 套件异常: " + t, false);
        } finally {
            deleteTree(dir);
        }
        return T.report("LeaderElectionTest");
    }

    private static void sameJvm(File lockFile) {
        LeaderElection a = new LeaderElection(lockFile, "pid=a");
        LeaderElection b = new LeaderElection(lockFile, "pid=b");

        T.ok("初始不是 leader", !a.isLeader());
        T.ok("a 获取锁", a.acquireIfPossible());
        T.ok("a 是 leader", a.isLeader());
        T.ok("a 重复获取仍成功（幂等）", a.acquireIfPossible());
        T.ok("同 JVM 第二个实例不能获取", !b.acquireIfPossible());
        T.ok("b 仍不是 leader", !b.isLeader());
        T.ok("锁文件已创建", lockFile.isFile());

        a.release();
        T.ok("a 释放后不是 leader", !a.isLeader());
        T.ok("b 可接管", b.acquireIfPossible());
        T.ok("b 接管后是 leader", b.isLeader());
        b.release();
        T.ok("b 重复释放安全", !b.isLeader());

        T.ok("a 释放后可再次获取", a.acquireIfPossible());
        a.release();

        // null / 父目录不存在
        LeaderElection none = new LeaderElection(null);
        T.ok("null 锁文件不崩溃且非 leader", !none.acquireIfPossible());
        T.ok("null 锁文件记录原因", none.lastError().length() > 0);

        File nested = new File(lockFile.getParentFile(), "nested/deep/bridge.lock");
        LeaderElection n = new LeaderElection(nested, "pid=n");
        T.ok("父目录不存在时自动创建并获取", n.acquireIfPossible());
        T.ok("嵌套锁文件已创建", nested.isFile());
        n.release();
        T.ok("嵌套锁释放", !n.isLeader());
    }

    private static void crossProcess(File dir) throws Exception {
        File lock = new File(dir, "cross.lock");
        File ready = new File(dir, "probe.ready");
        if (ready.exists() && !ready.delete()) {
            T.ok("清理 ready 文件", false);
            return;
        }

        String javaBin = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
        Process child = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(javaBin,
                    "-cp", System.getProperty("java.class.path"),
                    "plus.xiaoi.module.LeaderProbe", lock.getAbsolutePath(),
                    ready.getAbsolutePath());
            pb.redirectErrorStream(true);
            child = pb.start();

            String state = waitForReady(ready, 6000L);
            T.eq("子进程成为 leader", "LEADER", state);

            LeaderElection parent = new LeaderElection(lock, "pid=parent");
            T.ok("子进程持锁时父进程不能获取", !parent.acquireIfPossible());

            child.destroyForcibly();
            child.waitFor();
            child = null;

            boolean acquired = false;
            long deadline = System.currentTimeMillis() + 5000L;
            while (System.currentTimeMillis() < deadline) {
                if (parent.acquireIfPossible()) {
                    acquired = true;
                    break;
                }
                Thread.sleep(50L);
            }
            T.ok("持有者死亡后 5s 内接管", acquired);
            T.ok("接管后是 leader", parent.isLeader());
            parent.release();
        } finally {
            if (child != null) {
                try {
                    child.destroyForcibly();
                    child.waitFor();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String waitForReady(File ready, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String s = LeaderProbe.readFile(ready);
            if (s.length() > 0) return s;
            Thread.sleep(30L);
        }
        return "";
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
