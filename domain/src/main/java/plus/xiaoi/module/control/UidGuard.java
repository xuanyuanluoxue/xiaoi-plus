package plus.xiaoi.module.control;

import java.util.Arrays;
import java.util.Collection;

/**
 * 控制面 UID 授权判断（纯 JVM，无 android 依赖）。
 *
 * 真正的 PackageManager 查询放在 {@link AndroidUidGuard}，这里只回答
 * 「这个 UID 的包列表里有没有白名单包」以及一个有界的结论缓存。
 *
 * 缓存约束（与 PLAN.md 2.2 对齐）：
 *  - TTL 最多 5 秒，不缓存长期授权；
 *  - 缓存条目必须带 UID 当时的包名列表，包名变化立即失效；
 *  - 只缓存布尔结论，不缓存 PackageManager 对象。
 */
public final class UidGuard {

    /** 授权结论缓存时间上限；超过必须重新判断。 */
    public static final long CACHE_TTL_MS = 5000L;

    private UidGuard() {}

    /**
     * 纯判断：UID 对应的任一包在白名单内即允许。
     * 空名单 / 空包列表 / uid 未知（包列表为 null）一律拒绝。
     */
    public static boolean allows(Collection<String> allowedPackages, String[] packagesForUid) {
        if (allowedPackages == null || allowedPackages.isEmpty()) return false;
        if (packagesForUid == null || packagesForUid.length == 0) return false;
        for (String pkg : packagesForUid) {
            if (pkg != null && allowedPackages.contains(pkg)) return true;
        }
        return false;
    }

    /**
     * 单 UID 结论缓存。调用方每次仍可查询 PackageManager（调用频率低：
     * 只在 bind/register/pull 时），这里只避免重复做包名比对，并保证结论最多活 5 秒。
     */
    public static final class DecisionCache {

        private boolean has;
        private int uid = -1;
        private String[] packages;
        private boolean allowed;
        private long decidedAt;

        public synchronized boolean resolve(int uid, String[] packagesForUid,
                                            Collection<String> allowedPackages, long nowMs) {
            if (has
                    && this.uid == uid
                    && nowMs >= decidedAt
                    && nowMs - decidedAt <= CACHE_TTL_MS
                    && Arrays.equals(this.packages, packagesForUid)) {
                return allowed;
            }
            boolean decision = allows(allowedPackages, packagesForUid);
            this.has = true;
            this.uid = uid;
            this.packages = packagesForUid == null ? null : packagesForUid.clone();
            this.allowed = decision;
            this.decidedAt = nowMs;
            return decision;
        }

        public synchronized void clear() {
            has = false;
            uid = -1;
            packages = null;
            allowed = false;
            decidedAt = 0L;
        }
    }
}
