package plus.xiaoi.module;

import java.util.ArrayList;
import java.util.List;

import plus.xiaoi.module.core.config.ConfigBackupInfo;
import plus.xiaoi.module.core.config.ConfigLoadResult;
import plus.xiaoi.module.core.config.ConfigRevRules;
import plus.xiaoi.module.core.config.ConfigSaveResult;
import plus.xiaoi.module.core.config.ConfigStorageInfo;

/** WP1a：配置端口与 rev 纯规则。 */
public final class ConfigGatewayRulesTest {

    private ConfigGatewayRulesTest() {}

    public static int run() {
        // rev 冲突：expectedRev < 0 = 覆盖，>=0 必须与文件一致
        T.ok("expectedRev<0 不冲突", !ConfigRevRules.conflict(-1L, 9L));
        T.ok("rev 相等不冲突", !ConfigRevRules.conflict(5L, 5L));
        T.ok("rev 不等冲突", ConfigRevRules.conflict(4L, 5L));
        T.ok("骨架 rev=0 可保存", !ConfigRevRules.conflict(0L, 0L));

        // 已存在的文件 rev=0（v2）按 1 处理；文件不存在才是 0
        T.eq("文件 rev=0 归一为 1", 1L, ConfigRevRules.normalizeFileRev(0L, true));
        T.eq("文件 rev=5 保留", 5L, ConfigRevRules.normalizeFileRev(5L, true));
        T.eq("文件不存在 rev=0", 0L, ConfigRevRules.normalizeFileRev(0L, false));
        T.ok("v2 内存 rev=1 可保存", !ConfigRevRules.conflict(1L,
                ConfigRevRules.normalizeFileRev(0L, true)));

        // 下一次 rev：max(memory,file,1)+1，单调递增
        T.eq("nextRev 取文件 rev", 8L, ConfigRevRules.nextRev(3L, 7L));
        T.eq("nextRev 空文件从 2 开始", 2L, ConfigRevRules.nextRev(0L, 0L));
        T.eq("nextRev 负数归一", 2L, ConfigRevRules.nextRev(-5L, -1L));

        AppConfig cfg = new AppConfig();
        cfg.rev = 7L;

        ConfigSaveResult ok = ConfigSaveResult.success(cfg);
        T.ok("SaveResult success", ok.ok());
        T.ok("SaveResult success 无冲突", !ok.conflict());
        T.eq("SaveResult success 保留 rev", 7L, ok.saved.rev);

        ConfigSaveResult conflict = ConfigSaveResult.conflict(9L);
        T.ok("SaveResult conflict", conflict.conflict());
        T.ok("SaveResult conflict 无 saved", !conflict.ok());
        T.eq("SaveResult conflict rev", 9L, conflict.conflictRev);

        ConfigSaveResult failed = ConfigSaveResult.failure("坏配置");
        T.ok("SaveResult failure", !failed.ok() && !failed.conflict());
        T.eq("SaveResult failure error", "坏配置", failed.error);

        ConfigLoadResult normal = new ConfigLoadResult(cfg, false, null, false);
        T.ok("LoadResult 正常", normal.ok() && !normal.readOnly && !normal.skeleton);
        T.eq("LoadResult 空 error 归一", "", normal.error);

        ConfigLoadResult corrupt = new ConfigLoadResult(cfg, true, "损坏", true);
        T.ok("LoadResult 损坏只读", !corrupt.ok() && corrupt.readOnly);

        ConfigBackupInfo legacy = new ConfigBackupInfo("config.json.legacy.1", 100L, 10L, true);
        T.ok("BackupInfo 可恢复", legacy.restorable);
        T.eq("BackupInfo 负 size 归一", 0L, new ConfigBackupInfo("x", -1L, -1L, false).size);

        ConfigStorageInfo info = new ConfigStorageInfo("/tmp/config.json", true, 42L, 99L);
        T.ok("StorageInfo exists", info.exists);
        T.eq("StorageInfo path", "/tmp/config.json", info.path);

        // 网关接口至少可被实现（编译期检查 + 列表默认值）
        List<ConfigBackupInfo> list = new ArrayList<ConfigBackupInfo>();
        list.add(legacy);
        T.eq("备份列表 size", 1, list.size());

        return T.report("ConfigGatewayRulesTest");
    }
}