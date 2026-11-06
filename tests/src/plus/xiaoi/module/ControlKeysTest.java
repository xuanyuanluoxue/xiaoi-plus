package plus.xiaoi.module;

import java.util.HashSet;
import java.util.Set;

import plus.xiaoi.module.core.ControlKeys;

/** 控制面协议常量：版本、方向隔离、Bundle key 唯一性。 */
public final class ControlKeysTest {

    private ControlKeysTest() {}

    public static int run() {
        T.eq("ControlKeys 协议版本", 1, ControlKeys.PROTOCOL_VERSION);
        T.eq("ControlKeys 描述符", "plus.xiaoi.module.control.IBridgeControl",
                ControlKeys.DESCRIPTOR);
        T.eq("ControlKeys 服务类名", "plus.xiaoi.module.control.BridgeControlService",
                ControlKeys.SERVICE_CLASS);
        T.eq("ControlKeys 目标包名", "com.miui.voiceassist", ControlKeys.TARGET_PACKAGE);

        int[] c2s = {ControlKeys.C2S_PULL_CONFIG, ControlKeys.C2S_REGISTER,
                ControlKeys.C2S_REPORT_STATE, ControlKeys.C2S_APPEND_LOGS};
        int[] s2c = {ControlKeys.S2C_RELOAD, ControlKeys.S2C_SHOW_ISLAND,
                ControlKeys.S2C_CLEAR_SAFE_MODE, ControlKeys.S2C_FLUSH_LOGS};
        Set<Integer> seen = new HashSet<Integer>();
        boolean unique = true;
        for (int c : c2s) unique &= seen.add(c);
        for (int c : s2c) unique &= seen.add(c);
        T.ok("控制面消息码全局唯一", unique);

        T.ok("S2C 与 C2S 不重叠", ControlKeys.S2C_RELOAD > ControlKeys.C2S_APPEND_LOGS);

        String[] keys = {ControlKeys.KEY_PROTOCOL, ControlKeys.KEY_OK, ControlKeys.KEY_ERROR,
                ControlKeys.KEY_CONFIG_JSON, ControlKeys.KEY_CONFIG_HASH, ControlKeys.KEY_CALLBACK,
                ControlKeys.KEY_PID, ControlKeys.KEY_PROCESS, ControlKeys.KEY_PORT,
                ControlKeys.KEY_LEGACY_JSON, ControlKeys.KEY_MODEL, ControlKeys.KEY_ENABLED,
                ControlKeys.KEY_HAS_KEY, ControlKeys.KEY_VERSION, ControlKeys.KEY_LOGS,
                ControlKeys.KEY_TEXT};
        Set<String> keySet = new HashSet<String>();
        boolean ok = true;
        for (String k : keys) {
            ok &= k != null && k.length() > 0 && keySet.add(k);
        }
        T.ok("Bundle key 非空且唯一", ok);
        return T.report("ControlKeysTest");
    }
}
