package plus.xiaoi.module;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import plus.xiaoi.module.control.UidGuard;

/** UID 白名单判定与 5 秒缓存（纯 JVM，不查询真机 PackageManager）。 */
public final class UidGuardTest {

    private static final Set<String> VOICE = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("com.miui.voiceassist")));

    private UidGuardTest() {}

    public static int run() {
        T.ok("匹配白名单包", UidGuard.allows(VOICE,
                new String[]{"com.miui.voiceassist"}));

        T.ok("同一 UID 多包，其中之一命中",
                UidGuard.allows(VOICE,
                        new String[]{"com.other", "com.miui.voiceassist"}));

        T.ok("不匹配拒绝", !UidGuard.allows(VOICE,
                new String[]{"com.android.shell"}));

        T.ok("空包列表拒绝", !UidGuard.allows(VOICE, new String[0]));
        T.ok("null 包列表拒绝", !UidGuard.allows(VOICE, null));
        T.ok("null 白名单拒绝", !UidGuard.allows(null,
                new String[]{"com.miui.voiceassist"}));
        T.ok("空白名单拒绝", !UidGuard.allows(Collections.<String>emptySet(),
                new String[]{"com.miui.voiceassist"}));

        // ---------------------------------------------------------- 缓存语义
        UidGuard.DecisionCache cache = new UidGuard.DecisionCache();
        T.ok("首次判断允许", cache.resolve(10123,
                new String[]{"com.miui.voiceassist"}, VOICE, 1000L));

        // 5 秒内即使白名单变空，也复用带包名的旧结论
        T.ok("TTL 内复用允许结论", cache.resolve(10123,
                new String[]{"com.miui.voiceassist"}, Collections.<String>emptySet(), 6000L));

        // 超过 5 秒必须重新判断（现在白名单为空 → 拒绝）
        T.ok("TTL 过期重新判断", !cache.resolve(10123,
                new String[]{"com.miui.voiceassist"}, Collections.<String>emptySet(), 6001L));

        // 同一 UID 包名变化必须立即失效
        UidGuard.DecisionCache c2 = new UidGuard.DecisionCache();
        T.ok("包变化前允许", c2.resolve(2000,
                new String[]{"com.miui.voiceassist"}, VOICE, 0L));
        T.ok("同 UID 包名变化后拒绝", !c2.resolve(2000,
                new String[]{"com.android.shell"}, VOICE, 100L));

        cache.clear();
        T.ok("clear 后重新判断", !cache.resolve(10123,
                new String[]{"com.miui.voiceassist"}, Collections.<String>emptySet(), 7000L));
        return T.report("UidGuardTest");
    }
}
