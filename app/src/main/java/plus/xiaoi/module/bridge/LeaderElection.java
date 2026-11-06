package plus.xiaoi.module.bridge;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

/**
 * 全机唯一数据面 leader 选举（文件锁，纯 JVM，可桌面测试）。
 *
 * 小爱所有进程共享 App 私有目录下的 {@code files/xiaoi_bridge.lock}：
 * <ul>
 *   <li>{@code RandomAccessFile("rw")} + {@code FileChannel.tryLock()}；拿到锁 = leader</li>
 *   <li>进程死亡时内核自动释放 fcntl 锁，其他进程下一次重试即可接管</li>
 *   <li>同一进程内的第二个实例会触发 {@link OverlappingFileLockException}，
 *       这里安全地当作「未获得」，不会崩溃</li>
 * </ul>
 *
 * 锁文件**永不删除**：删除被锁住的文件会让另一个进程新建同名文件并成功加锁，
 * 从而出现两个 leader。文件内容只写 pid/进程名等诊断信息，绝不写 token/apiKey。
 *
 * 本类不启动线程、不做重试；由 {@link BridgeLifecycle} 每 10s 调一次
 * {@link #acquireIfPossible()}。
 */
public final class LeaderElection {

    private final File lockFile;
    private final String ownerTag;

    private RandomAccessFile raf;
    private FileChannel channel;
    private FileLock lock;
    private volatile String lastError = "";

    public LeaderElection(File lockFile) {
        this(lockFile, "");
    }

    /**
     * @param lockFile 共享锁文件；null/不可写时 {@link #acquireIfPossible()} 恒为 false
     * @param ownerTag 写入锁文件的诊断信息（pid/进程名），不得包含密钥
     */
    public LeaderElection(File lockFile, String ownerTag) {
        this.lockFile = lockFile;
        this.ownerTag = ownerTag == null ? "" : ownerTag;
    }

    /** 尝试成为 leader；已被其他进程（或同进程另一实例）持有时返回 false。 */
    public synchronized boolean acquireIfPossible() {
        if (isLeader()) return true;
        if (lockFile == null) {
            lastError = "lock file is null";
            return false;
        }
        try {
            open();
            FileLock acquired;
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // 同 JVM 内已有实例持锁：属于预期情况，不升级为错误。
                closeQuietly();
                lastError = "";
                return false;
            }
            if (acquired == null) {
                // 其他进程持有；关闭句柄，等下一轮重试。
                closeQuietly();
                lastError = "";
                return false;
            }
            lock = acquired;
            writeOwnerTag();
            lastError = "";
            return true;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            closeQuietly();
            return false;
        }
    }

    /** 释放 leader 锁；幂等。 */
    public synchronized void release() {
        FileLock l = lock;
        lock = null;
        if (l != null) {
            try {
                l.release();
            } catch (Throwable ignored) {
            }
        }
        closeQuietly();
    }

    /** 当前是否持有锁（锁被外部失效时返回 false）。 */
    public synchronized boolean isLeader() {
        FileLock l = lock;
        if (l == null) return false;
        try {
            return l.isValid();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 最近一次获取失败原因（不含密钥）；成功/正常竞争时为空串。 */
    public String lastError() {
        return lastError;
    }

    public File lockFile() {
        return lockFile;
    }

    private void open() throws Exception {
        if (raf != null && channel != null && channel.isOpen()) return;
        closeQuietly();
        File parent = lockFile.getParentFile();
        if (parent != null && !parent.exists()) {
            // 失败也让 RandomAccessFile 抛出真实异常，由调用方记录。
            parent.mkdirs();
        }
        raf = new RandomAccessFile(lockFile, "rw");
        channel = raf.getChannel();
    }

    private void writeOwnerTag() {
        if (ownerTag.length() == 0) return;
        try {
            byte[] bytes = ownerTag.getBytes("UTF-8");
            raf.seek(0);
            raf.setLength(0);
            raf.write(bytes);
        } catch (Throwable ignored) {
            // 诊断信息写失败不影响 leader 身份。
        }
    }

    private void closeQuietly() {
        if (channel != null) {
            try {
                channel.close();
            } catch (Throwable ignored) {
            }
            channel = null;
        }
        if (raf != null) {
            try {
                raf.close();
            } catch (Throwable ignored) {
            }
            raf = null;
        }
    }
}
