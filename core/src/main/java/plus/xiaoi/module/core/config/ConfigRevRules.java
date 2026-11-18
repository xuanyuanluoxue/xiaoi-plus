package plus.xiaoi.module.core.config;

/**
 * 配置 rev 的纯规则（WP1a）。
 *
 * <p>从 {@code ConfigRepository} 抽出，便于桌面单测；本阶段不改变公式，
 * 只把“什么时候冲突/下一次 rev 是多少”变成可验证的函数。</p>
 */
public final class ConfigRevRules {

    private ConfigRevRules() {}

    /** expectedRev < 0 = 调用方显式覆盖（不检查）；否则必须与文件 rev 完全一致。 */
    public static boolean conflict(long expectedRev, long currentFileRev) {
        return expectedRev >= 0 && expectedRev != currentFileRev;
    }

    /** 已存在的文件按已初始化处理：v2 无 rev / rev=0 归一为 1；文件不存在才是 0。 */
    public static long normalizeFileRev(long rawRev, boolean exists) {
        return exists ? Math.max(rawRev, 1L) : 0L;
    }

    /** 每次保存：rev = max(内存 rev, 文件 rev, 1) + 1；保持单调递增。 */
    public static long nextRev(long memoryRev, long fileRev) {
        return Math.max(Math.max(memoryRev, fileRev), 1L) + 1L;
    }
}