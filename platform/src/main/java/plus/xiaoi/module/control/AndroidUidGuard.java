package plus.xiaoi.module.control;

import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import plus.xiaoi.module.core.ControlKeys;

/**
 * Android 侧 UID 查询包装：只向 {@link UidGuard} 提供「UID → 包名列表」。
 *
 * 注意：本 App 的 targetSdk=26，不受 Android 11 包可见性过滤影响；
 * 即使将来升级 targetSdk，显式组件 bind 也仍然可用，但这里需要在
 * manifest 里声明 &lt;queries&gt; 才能稳定查到 com.miui.voiceassist。
 */
final class AndroidUidGuard {

    private static final Set<String> ALLOWED = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(ControlKeys.TARGET_PACKAGE)));

    private final PackageManager pm;
    private final UidGuard.DecisionCache cache = new UidGuard.DecisionCache();

    AndroidUidGuard(PackageManager pm) {
        this.pm = pm;
    }

    /**
     * @return true 仅当该 UID 名下确实挂着 com.miui.voiceassist。
     *         查询异常、UID 非法、包列表为空、缓存过期后重新判断仍不匹配 → false。
     */
    boolean isAllowed(int uid) {
        if (pm == null || uid <= 0) return false;
        String[] packages;
        try {
            packages = pm.getPackagesForUid(uid);
        } catch (Throwable t) {
            return false;
        }
        if (packages == null || packages.length == 0) return false;
        try {
            return cache.resolve(uid, packages, ALLOWED, System.currentTimeMillis());
        } catch (Throwable t) {
            return false;
        }
    }
}
