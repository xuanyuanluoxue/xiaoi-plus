# 分层与依赖倒置改造：执行计划

> 状态：**WP0–WP6 全部完成（WP6 `:domain` / `:application` 拆分）**
> 基线：`v0.15.0`（versionCode 22，2026-11-11），HEAD `d753fb4`
> 日期：2026-11-16
> 相关：[architecture.md](../architecture.md) · [refactor/PLAN.md](PLAN.md) · [agent/PLAN.md](../agent/PLAN.md)
>
> **当前环境（2026-11-17 起）**：Windows 原生 `C:\Users\Chatxavier\code\xiaoi-plus`
> （WSL 视角 `/mnt/c/Users/Chatxavier/code/xiaoi-plus`）。
> 本文命令里的 `bash scripts/...` 在 Windows 用 Git Bash / WSL 执行；
> Gradle 命令 Windows 用 `gradlew.bat`，Linux/WSL 用 `./gradlew`。
> **下一阶段继续优化见 [NEXT.md](NEXT.md)。**

## 0. 决策与边界

### 0.1 已定决策

| 编号 | 决策 | 理由 |
|---|---|---|
| L1 | **不重写**，用 5 个可发布增量完成分层 | 真机适配知识不可重建；domain 很薄 |
| L2 | 目标三层：domain / application / adapter，但**先接缝后模块** | 目录可随时改，Gradle 模块是最后一步 |
| L3 | 先做 **配置端口**，再做 **Hook 翻译**和**进程宿主** | 配置是 UI 与业务的最大耦合面 |
| L4 | 不改 Hook 目标/顺序/版本白名单 | 设备验证资产，只做等价重构 |
| L5 | 不改 `config.json` v3 / `rev` / `.corrupt` / 只读语义 | 密钥与升级兼容 |
| L6 | 不改 8771/8770 报文、端口、Token/LAN 规则、Binder UID 守卫 | 安全边界与外部契约 |
| L7 | 不改 19 条路由与已验收视觉 | 回归成本高于收益 |
| L8 | 不引入 DI 框架、新语言、新运行时依赖 | Xposed 多进程 + 手工组装更可控 |

### 0.2 明确不做

- 不为了“三层”而把 `ConfigRepository` 原样复制成三个类。
- 不在 WP1 同时改 rev 语义、文件格式、watcher 行为。
- 不在 WP2 恢复已禁用的响应前缀 hook。
- 不在 WP3 改 MCP 工具语义。
- 不在本计划内删除旧 `HomeActivity` / `ConfigActivity`（另立任务）。

## 1. 现状基线（2026-11-16 实测）

| 指标 | 当前值 | 证据 |
|---|---|---|
| `ui/` 内 `ConfigRepository.get(` | **19 处 / 12 文件**（另有 `EditSection` 等 4 处静态编解码调用） | 见 §1.2 |
| 全仓库 `ConfigRepository.get(` | 44 处（ui 19、root 22、agent 2、control 1） | `rg -n "ConfigRepository.get\\("` |
| 写配置调用 | UI 6 文件 8 处 + `ConfigActivity` 3 处 + `ConfigSync` 3 处 | 见 §1.2 |
| 中心环 | `Hook → Bridge → BridgeLifecycle`；`BridgeControlClient → Hook/Bridge` | §4.3 |
| `Mcp.Host` | 11 方法 / 5 类职责 | `Mcp.java:30-65` |
| `Hook.java` | 482 行；无 `org.json` 业务改写；图片回退死代码已删 | `Hook.java` |
| 纯层门禁 | `tests/run.sh` 手写 62 个文件列表 | `tests/run.sh:16-77` |
| Gradle module | 1（`:app`） | `settings.gradle.kts:24` |
| `ConfigRepository` 单测 | **0**（`tests/run.sh` 不编译它） | `tests/run.sh` |
| UI 配置变更订阅 | Compose 侧 **0**；`ConfigSync.start` 只在旧 Activity | `ConfigActivity.java:104` |

### 1.1 已经做对、必须保留

- `ActionExecutor` / `AskHost` / `NotifyHost` / `Now` / `Persistence` 端口。
- `Http` / `Mcp` / `AgentMcp` / `Json` / `ConfigCodec` / `ConfigStore` /
  `BridgePolicy` / `StatusResolver` / `IssueResolver` / `StateModel` 纯 JVM。
- `BridgeStatus` / `AgentRuntimeStatus` 不可变快照。
- `PermissionProbe → PermissionSnapshot`、`HomeFacts` / `AgentStatus` 纯映射。

### 1.2 配置调用面（按层）

**UI 读**（12 文件 19 处）：

- `ModelStateHolder.kt:72,299`
- `XiaoaiStateHolder.kt:69,99`
- `HomeStateHolder.kt:117`
- `AccessData.kt:58`
- `AccessScreen.kt:157`
- `TokenScreen.kt:132`
- `ConfigManageScreen.kt:90,111,183,207,237`
- `MeScreen.kt:119`
- `LogsScreen.kt:131,147`
- `DiagnosticsScreen.kt:345`
- `LegacyScreen.kt:63`
- `EditSection.java:74,140,222`

**UI 写**（6 文件 8 处）：

- `ModelStateHolder.kt:315`
- `XiaoaiStateHolder.kt:79`
- `AccessScreen.kt:160`
- `TokenScreen.kt:137`
- `ConfigManageScreen.kt:187,212,218`
- `MeScreen.kt:140`
- `LogsScreen.kt:150`

（`ConfigActivity` 3 处、`ConfigSync` 3 处不在 UI 迁移范围。）

### 1.3 配置状态机的已知坑

1. `load()` 有写副作用（迁移回写、`LogSettings.setWire`），且 `isReadOnly/loadError/isSkeleton`
   与 `load()` 有隐式时序耦合，必须合并为 `LoadResult`。
2. `save()` 的 rev 递增不是 CAS；UI 的冲突检查在读写之间有窗口。
3. `resetToDefaultsAfterCorruption()` 写 `rev=1`，破坏 rev 单调性。
4. `ConfigSync.markApplied` 是包级可见，Kotlin 子包用不了，导致 `save`/`saveQuiet` 语义分叉。
5. Compose-only 入口从不启动 `ConfigSync`，FileObserver 不生效。
6. `ConfigRepository.toJson/parse` 是静态门面，UI 可直接改调 `ConfigCodec`。

> 这些坑在 WP1a 只解决“UI 依赖”；WP1b 再解决规则与生命周期。不要在一个提交里混做。

## 2. 目标结构

### 2.1 三层 + 三个组装根

```
domain（纯 JVM，无 android / 无 org.json）
  模型：AppConfig / ConfigSnapshot / BridgeFacts / ActionDescriptor
  策略：BridgePolicy / AgentLanPolicy / StateModel / IssueResolver /
        ConfigRules / Redact / RateLimiter / ErrorCatalog
  端口：ConfigGateway / Clock / ActionExecutor / NotifyHost / AskHost / LogSink

application（纯 JVM，只依赖 domain 与端口）
  ConfigService / BridgeService / StatusService / ActionService / ModelProbe

adapter + composition root（Android）
  入站：Hook / MainActivity / BridgeControlService / AgentHttpServer
  出站：ConfigRepository+ConfigStore / Prefs / XposedBridgeAdapter /
        AndroidExecutor / RootExecutor / AndroidNotifyHost / BridgeAskHost /
        BridgeClient / Logs
  组装根：Hook.handleLoadPackage（voiceassist）
          MainActivity（设置 App）
          AgentService（8770）
  UI：19 screen + StateHolder（只依赖 application 输出）
```

### 2.2 依赖规则（WP0 机器检查）

| 编号 | 规则 |
|---|---|
| D1 | `domain` 不允许 `import android.*`、`import org.json.*` |
| D2 | `application` 不允许 `import android.*`、`import org.json.*` |
| D3 | `application` 不允许 import `adapter`/`ui`/根包平台类 |
| D4 | `ui` 不允许 import `ConfigRepository` 或调用 `ConfigRepository.get(` |
| D5 | `ui` 不允许 import `plus.xiaoi.module.control.*` |
| D6 | `core` 不允许 `import android.*`（`PermissionProbe` 为基线例外，后续搬走） |
| D7 | `core` 不允许 `import plus.xiaoi.module.agent.*`（现状 1 条例外） |
| D8 | 静态单例只在 adapter/infra；application 不得持有业务回调 |

## 3. 工作包总表

| WP | 内容 | 依赖 | 预估 | 可并行 | 发布点 |
|---|---|---|---|---|---|
| WP0 | 边界门禁 + 基线 | 无 | 0.5–1 天 | 否 | 不发版，仅门禁 |
| WP1a | 配置端口 + UI 注入（读+写） | WP0 | 2–3 天 | 否 | beta（行为不变） |
| WP1b | 配置规则上移 + rev/watcher 修复 | WP1a | 3–4 天 | 否 | beta（修 3 个已知坑） |
| WP2 | Hook 翻译器 + 进程宿主 | WP0 | 2–3 天 | 与 WP1b 可并行（文件不重叠） | beta（等价重构） |
| WP3 | 桥/动作应用服务 + Host 拆分 | WP1b、WP2 | 3–4 天 | 否 | beta（协议不变） |
| WP4 | 剩余 UI Context 依赖清理 | WP1a、WP3 | 1–2 天 | 否 | beta |
| WP5 | `:core` → `:domain`/`:application` 模块 | WP1–WP4 | 1–2 天 | 否 | 版本收尾 |

总计约 **1.5–2.5 周**。每个 WP 独立 commit；跨 WP 不改同一文件。

## 4. WP0：边界门禁（现在执行）

### 4.1 目标

让“分层规则”从文档变成 CI 可执行，并且**只拦新增违规**，不阻塞现有开发。

### 4.2 交付物

| 文件 | 内容 |
|---|---|
| `scripts/check-layering.sh` | 扫描器：rule + baseline 比较；`--init` 生成基线；`--strict` 把 stale 也判失败；`--self-test` 用临时目录自测 |
| `scripts/layering-baseline.tsv` | 当前违规基线（约 25 条），格式 `rule<TAB>file<TAB>token<TAB>count` |
| `scripts/check-consistency.sh` | 在死资源检查前调用 `check-layering.sh` |
| `docs/refactor/LAYERING.md` | 本文档更新状态与规则 |

### 4.3 规则与基线

| rule | 路径 | 禁止 | 当前基线 |
|---|---|---|---|
| `core_no_android` | `app/src/main/java/plus/xiaoi/module/core` | `^import android\.` | `core/PermissionProbe.java`（8 条） |
| `core_no_agent` | 同上 | `^import plus\.xiaoi\.module\.agent\.` | `core/IssueInputFactory.java`（1 条） |
| `ui_no_repository_import` | `.../ui` | `^import plus\.xiaoi\.module\.ConfigRepository` | 12 文件 |
| `ui_no_repository_get` | 同上 | `ConfigRepository\.get\(` | 12 文件 19 处 |
| `ui_no_control` | 同上 | `^import plus\.xiaoi\.module\.control\.` | 3 文件 |
| `domain_no_android` | `.../domain`（未来） | `^import android\.` | 0 |
| `domain_no_orgjson` | 同上 | `^import org\.json\.` | 0 |
| `application_no_android` | `.../application`（未来） | `^import android\.` | 0 |
| `application_no_orgjson` | 同上 | `^import org\.json\.` | 0 |

### 4.4 行为

- 当前计数 > 基线 → **新增违规，失败**，打印 `file:line`。
- 当前计数 < 基线 → `stale`；默认告警，`--strict` 失败，推动删基线。
- 基线中不存在的文件/规则 → 视为新增违规。
- `--init` 只允许人工执行；检测到 `CI` 环境时拒绝写基线。
- 扫描只用 `grep -rEn`，兼容 GitHub runner；注释中的 `org.json` 不误伤（匹配 `^import`）。

### 4.5 验收

```bash
bash scripts/check-layering.sh          # 通过
bash scripts/check-layering.sh --strict # 当前基线无 stale，通过
bash scripts/check-layering.sh --self-test
bash scripts/check-consistency.sh       # 全绿，且包含分层检查
```

另做一次手工负例：临时在 `ui/` 新建一个 import `ConfigRepository` 的文件，
脚本必须失败；测试后删除。

## 5. WP1a：配置端口 + UI 注入

### 5.1 目标

UI 只依赖接口与结果对象，不再 `ConfigRepository.get(context)`，不再“先 get 再 get”。
**行为不变**：文件格式、prefs 镜像、损坏/只读/骨架、rev 公式、保存顺序全部保持。

### 5.2 新增 API（`core/config/`）

```java
public interface ConfigGateway {
    ConfigLoadResult loadState();                 // 原子携带 config/readOnly/error/skeleton
    ConfigSaveResult saveIfRev(AppConfig next, long expectedRev); // expectedRev<0 = 不检查
    ConfigSaveResult saveIfRevQuiet(AppConfig next, long expectedRev);
    List<ConfigBackupInfo> backupInfos();         // legacy.<ts>，按 mtime 倒序
    List<ConfigBackupInfo> corruptInfos();        // corrupt.<ts>，仅取证不可恢复
    AppConfig restore(ConfigBackupInfo backup) throws Exception;
    AppConfig resetAfterCorruption() throws Exception;
    ConfigStorageInfo storage();                  // path/size/mtime，供“兼容模式”展示
    String validate(AppConfig config);
    void applyToTarget(AppConfig config);         // watcher/启动对齐用
}
```

```java
public final class LoadResult {
    public final AppConfig config;   // 永不为 null；骨架/损坏时是内存骨架
    public final boolean readOnly;
    public final String error;       // 空串 = 正常
    public final boolean skeleton;
}

public final class SaveResult {
    public final AppConfig saved;    // 成功时为写后配置
    public final long conflictRev;   // >=1 = 冲突，saved=null
    public final String error;       // 校验/IO 失败
    public boolean ok();
}
```

`BackupInfo` 只暴露 `name/lastModified/size`，不把 `File` 交给 UI。

### 5.3 实现方式

- `ConfigRepository implements ConfigGateway`；`loadState()` 内部调用 `load()` 并一次性
  读取 `readOnly/loadError/skeletonFlag`，消除 getter 时序耦合。
- `save(next, expectedRev)`：`synchronized` 内读文件 rev，`expectedRev>=0 && current != expectedRev`
  → 返回 conflict；否则沿用现有 `writeWithRev/writeFile/writePrefs/markApplied/requestReload`。
- `save(next)` 保留为 `expectedRev=-1` 的便捷重载（旧调用点不破坏）。
- 静态 `parse/toJson` 不再由 UI 调用：UI 改调 `ConfigCodec.fromStream/toJson`。
- 新增 `ui/components/LocalConfigGateway.kt`：
  `staticCompositionLocalOf<ConfigGateway> { error("ConfigGateway not provided") }`。
- `MainActivity.XiaoiApp`：`val gateway = remember(context) { ConfigRepository.get(context) }`，
  在现有 `CompositionLocalProvider` 中 provide。
- StateHolder 改造：
  - `HomeStateHolder(context, gateway)`（Context 仅留给 `PermissionProbe`）。
  - `ModelStateHolder(context, gateway)`（Context 仍用于 `AgentService`/`StatusCache`）。
  - `XiaoaiStateHolder(context, gateway)`。
  - `AccessData.rememberAccessConfig()` 直接读 `LocalConfigGateway.current`。
- 页面写路径全部改成 `gateway.save(..., baseRev)`；保留“保存后 reload/刷新”的现有 UX。
- `LegacyScreen` 的 `new ConfigStore(...)` 删除，改用 `gateway.storage()`/`gateway.backups()`。

### 5.4 文件清单

新增：

- `app/src/main/java/plus/xiaoi/module/core/config/ConfigGateway.java`
- `.../core/config/ConfigLoadResult.java`
- `.../core/config/ConfigSaveResult.java`
- `.../core/config/ConfigBackupInfo.java`
- `.../core/config/ConfigStorageInfo.java`
- `app/src/main/java/plus/xiaoi/module/ui/components/LocalConfigGateway.kt`
- `tests/src/plus/xiaoi/module/ConfigGatewayRulesTest.java`（纯规则：conflict/rev 判定）

修改：

- `ConfigRepository.java`（implements + loadState/save 重载 + storage/backups 适配）
- `MainActivity.kt`（provide）
- `HomeStateHolder.kt`、`ModelStateHolder.kt`、`XiaoaiStateHolder.kt`
- `HomeScreen.kt`、`ModelScreen.kt`、`XiaoaiScreen.kt`（构造 holder 时传 gateway）
- `AccessData.kt`、`AccessScreen.kt`、`TokenScreen.kt`
- `ConfigManageScreen.kt`、`DiagnosticsScreen.kt`、`LegacyScreen.kt`
- `LogsScreen.kt`、`MeScreen.kt`、`MeSupport.kt`
- `ui/config/EditSection.java`（parse/toJson → `ConfigCodec`；load → gateway）
- `scripts/layering-baseline.tsv`（删除 `ui_*` 条目）
- `tests/run.sh`（加入新纯类）
- `CHANGELOG.md`（内部重构，不发版也记一条）

### 5.5 验收

```bash
rg -n "ConfigRepository" app/src/main/java/plus/xiaoi/module/ui/   # 0
bash scripts/check-layering.sh --strict                            # ui 规则 0 基线
bash tests/run.sh                                                  # 全绿 + 新规则用例
bash scripts/check-consistency.sh                                  # 全绿
gradlew.bat :app:assembleRelease --offline                         # 构建通过（Linux/WSL: ./gradlew）
```

真机回归（行为不变断言）：

- 首页/小爱/接入/我的四 tab 打开正常，状态文案不变。
- 模型页改模型 → 保存 → 重启 App 后仍在；rev 冲突仍提示。
- 令牌页保存 → 8771 `/health` 带新 token 200。
- 配置损坏 → 只读提示，保存被阻止；`config.json.corrupt.*` 仍生成。
- 旧 `ConfigActivity` 入口仍可用（暂不迁移）。

### 5.6 风险与回滚

| 风险 | 缓解 |
|---|---|
| StateHolder 改造导致草稿丢失/重建后 rev 基线错 | gateway 是进程单例；`ModelDraftCache` 逻辑不动；主题 recreation 手工验证 |
| `save` 增加 expectedRev 改变旧写入行为 | 旧调用传 `-1`；只有 Model 页传真实 rev；WP1a 不改 rev 公式 |
| CompositionLocal 默认 error 在预览/旧入口崩溃 | 预览需要时提供 no-op；旧 Java 页面不走 Compose 树 |
| UI 写路径漏改导致一处仍静态依赖 | check-layering 的 `ui_no_repository_*` 基线清零作为硬门禁 |

回滚：WP1a 独立 commit；`ConfigRepository` 原有方法全部保留，回滚不影响文件格式。

### 5.7 落地差异（2026-11-16，已实现）

- 接口方法实际命名为 `saveIfRev` / `saveIfRevQuiet`（避免与既有 `void save(AppConfig)`
  只差返回类型而无法重载）；备份投影为 `backupInfos()` / `corruptInfos()`。
- `ConfigLoadResult/ConfigSaveResult/ConfigBackupInfo/ConfigStorageInfo` 放在
  `core/config/`；`ConfigRevRules` 抽为纯函数并补 27 条断言。
- UI 的 `ConfigRepository.toJson/parse` 全部改走 `ConfigCodec`；
  `EditSection` 构造时注入网关；`ConfigActivity` 仍用 `ConfigRepository`（旧入口）。
- 修复 v2 `rev=0` 的兼容窗口：已存在文件在 rev 比较时归一为 1，
  避免“载入 rev=1、保存却与文件 rev=0 冲突”。
- 基线从 37 行收缩到 12 行（仅剩 `core/PermissionProbe`、`core/IssueInputFactory`
  与 UI 对 `control.BridgeControlService` 的 3 处依赖）。

## 6. WP1b：配置规则上移（已实现）

- 新增 `application/config/ConfigService`（纯 JVM）：校验 → rev CAS → 原子写 →
  prefs 镜像 → 回填已应用 → reload。
- 端口：`ConfigPrefsPort`（默认值/镜像写）、`ConfigEffectsPort`（markApplied/reload）、
  `ConfigLogPort`（日志），Android 实现全部收在 `ConfigRepository` 内。
- `ConfigRepository` 降为组装点 + 旧签名门面；旧调用方
  （ConfigActivity / ConfigSync / BridgeControlService / AgentService）不变。
- 修复：`resetAfterCorruption` 的 rev 从 prefs 记录的上一次 rev 前进，不再硬编码 1。
- 修复：`restoreLegacy` 的 rev 同时参考恢复前文件 rev/prefs rev，避免恢复旧备份导致 rev 回退。
- 修复：Compose 入口 `MainActivity.onCreate` 启动 `ConfigSync`，外部改文件可刷新。
- `save` = 文件 + prefs + 回填哈希 + reload；`saveQuiet` = 文件 + prefs + reload（不回填）。
- 测试：`ConfigServiceTest` 覆盖骨架/保存/冲突/强制/静默/损坏/迁移/恢复/重置/
  applyLocal/applyToTarget/storage/v2 rev=0，共 52 条断言。

验收：上述场景有纯 JVM 测试；`ConfigRepository` 不再承载规则；单测/一致性/构建全绿。

### 6.1 落地差异与遗留

- `ConfigService` 仍是单进程内同步对象；跨进程写者由“App 唯一写者”约束保证。
- `legacyExternalFile` 在 `ConfigRepository` 构造时解析一次；若极端情况下外部目录
  当时不可用，本次进程内不会再次尝试迁移（原实现每次 load 重算）。后续可用
  `LegacyFilePort` 消除，暂不影响常规设备。
- `resetAfterCorruption` 的 rev 下限来自 prefs 的 `cfg_rev`；如果 prefs 写失败且
  文件被外部改坏，极端情况下仍可能回退，WP2 可把 rev 落盘到独立的小文件。

## 7. WP2：Hook 策略抽取 + 进程宿主（已实现）

- 现实核对后取消“请求体翻译器”：请求 hook 实际为空，图片回退是死代码。
- 新增纯策略 `core/hook/XiaoiOverridePolicy` / `UsageSignal`（16 条断言）。
- 新增 `XiaoiProcessHost` / `XiaoiProcess` / `XiaoiProcessHostImpl`；
  `BridgeControlClient` 只依赖 host，`Bridge` 不再 import Hook。
- 删除图片回退死代码与 3 个状态字段；`Hook` 565 → 482 行。
- 新增 H1/H2 门禁（Hook 不依赖 Bridge/Island；控制面客户端不依赖 Hook/Bridge/Island），
  双负例验证。
- 明确保留：`Bridge → BridgeControlClient` 单向、`Bridge ↔ BridgeLifecycle`、
  root↔bridge 包级依赖，留给 WP3。
- 详细计划与执行记录：[WP2-HOOK.md](WP2-HOOK.md)。

## 8. WP3：桥/动作应用服务（已实现）

- `BridgeService`（纯 JVM）承接生命周期决策树；`BridgeLifecycle` 只依赖
  `BridgeRuntime` 端口，不再 import `Bridge`。
- `Mcp.Host` 删除，拆为 `RuntimePort` / `ConfigPort` / `CliPort` /
  `IslandPort` / `TelemetryPort` + `Mcp.Hosts`。
- `BridgeHttpPolicy`（纯 JVM）承接 HTTP 路径与 limit/days clamp。
- 新增 H3 门禁（BridgeLifecycle 不依赖 Bridge），并做负例验证。
- `StatusService`/四维状态组装**未在本 WP 实施**，留给 WP4 或独立任务。
- 详细计划与执行记录：[WP3-BRIDGE.md](WP3-BRIDGE.md)。

## 9. WP4：运行时端口与 UI Context 清理（已实现）

- 新增 `AppRuntime` 五端口（Agent/StatusProbe/Permission/ConfigImport/ConfigWatcher），
  `AndroidAppRuntime` 统一 applicationContext。
- Compose 通过 `LocalAppRuntime` 注入；Home/Model/Xiaoai StateHolder 去掉 `Context`。
- 接入/令牌/我的/配置管理/诊断/权限页改走端口；剪贴板/Intent/包信息等平台能力保留。
- 旧 `EditSection` 去掉 Context，注入 `RuntimeConfigImporter`。
- 新增 4 条 `ui_no_*` 门禁与 `AppRuntimeTest` 9 条断言。
- `ThemeManager` 端口化顺延；`ui_no_control` 3 条保留。
- 详细计划与执行记录：[WP4-UI.md](WP4-UI.md)。

## 10. WP5：`:core` 物理模块（已实现）

1. 新增 `:core` `java-library`，承载 82 个纯 JVM 类（`tests/run.sh` 闭包去掉 `Logs`）。
2. 包名不变；`:app` `implementation(project(":core"))`；Xposed 桩继续 `compileOnly`。
3. `Logs`（Xposed）与 `Usage`（android）留在 app。
4. `tests/run.sh` 改为 `find core/src` + `Logs.java`；`build.sh`/一致性/分层路径同步。
5. 后续已由 WP6 拆成 `:domain` + `:application`。
- 详细计划与记录：[WP5-CORE.md](WP5-CORE.md)。

## 10.1 WP6：`:domain` / `:application`（已实现）

1. `:core` 82 文件拆成 `:domain` 53 + `:application` 29；包名不变。
2. `:app -> {domain, application}`，`:application -> domain`。
3. `:domain` 无 I/O/并发/Android/Xposed/org.json；`tests/run.sh` 用空 classpath
   单独编译 domain，不依赖 import 规则。
4. `:application` 放用例、协议、纯 JVM 传输/文件适配器。
5. 详细计划与记录：[WP6-DOMAIN.md](WP6-DOMAIN.md)。

## 11. 子 Agent 编排

### 11.1 原则

1. **默认串行**：同一 working tree 内不允许两个 Agent 同时改文件。
2. **只读并行**：探索/审查子 Agent 可并行，产出摘要，不改文件。
3. **写操作白名单**：实现子 Agent 只能改任务书列出的文件；超出即拒绝。
4. **合并权在主 Agent**：子 Agent 的输出必须经主 Agent 审 diff + 跑门禁后合并。
5. **worktree 仅在必要时**：WP2 的 `Hook` 与 WP1b 的配置文件不重叠，可各用 worktree；
   worktree 需复制 `local.properties`，并设置 `JAVA_HOME`（JDK 21）。

### 11.2 本计划内的分工

| 阶段 | 主 Agent | 子 Agent（只读/审查） | 子 Agent（实现，可选） |
|---|---|---|---|
| 计划 | 写 WP0–WP5、文件清单、验收 | 1 个 reviewer 审可行性/遗漏 | 无 |
| WP0 | 写脚本 + 基线 + 负例 | 1 个 reviewer 检查误伤/绕过 | 无 |
| WP1a | 改 gateway/UI/测试 | 1 个 reviewer 审 UI 行为差异 | 无（文件共享多，串行） |
| WP1b | 改 service/adapter/测试 | 1 个 reviewer 审 rev/损坏语义 | 可选：测试用例子 Agent（只写 `tests/src`） |
| WP2 | 接线 + 合并 | 1 个 reviewer 审 hook 顺序 | 可选：翻译器 + golden case（白名单：`Hook.java`、`core/translate/*`、`tests/src/*`） |
| WP3 | 接线 + 合并 | 1 个 reviewer 审协议/鉴权 | 可选：Host 拆分（白名单：`Mcp.java`、`AgentMcp.java`、`tests/src`） |
| WP4 | UI 清理 | 1 个 reviewer 审预览/重建 | 无 |
| WP5 | Gradle + 门禁 | 1 个 reviewer 审 R8/打包 | 无 |

### 11.3 子 Agent 任务书模板

```text
仓库：C:\Users\Chatxavier\code\xiaoi-plus（WSL: /mnt/c/Users/Chatxavier/code/xiaoi-plus；worktree: <path>）
只允许修改：<文件白名单>
禁止修改：<文件黑名单>
目标：<一句话>
验收命令：<命令>
约束：不改变行为、不改协议、不改配置格式；只跑指定命令
输出：改动文件清单 + diff 摘要 + 验收命令输出 + 未解决风险
```

## 12. 每个 WP 的完成定义（DoD）

1. 代码 + 测试 + 文档同步。
2. `bash tests/run.sh` 全绿。
3. `bash scripts/check-consistency.sh` 全绿（含 `check-layering --strict`）。
4. `gradlew.bat :app:assembleRelease --offline` 成功（Linux/WSL 用 `./gradlew`）。
5. 涉及运行时的 WP 补真机 P0 清单与结果（可写 `docs/ui/DEVICE-CHECK-*.md`）。
6. `scripts/layering-baseline.tsv` 只减不增（该 WP 对应的行必须删除）。
7. 独立 commit，commit message 带 `refactor(layering): WPn ...`。

## 13. 审核清单（本轮自审）

| # | 检查项 | 结论 |
|---|---|---|
| R1 | 计划是否保留全部设备验证资产（hook/leader/UID/配置格式） | 通过：L4–L7 明确不改 |
| R2 | 每个 WP 是否可独立发布、独立回滚 | 通过：WP0–WP5 均独立 commit |
| R3 | 验收是否机器可验证 | 通过：rg/脚本/单测/构建四类 |
| R4 | 是否低估配置状态机风险 | 通过：拆 WP1a/WP1b；列出 6 个坑 |
| R5 | UI 注入是否会造成草稿/rev 基线丢失 | 通过：gateway 为进程单例，草稿逻辑不动 |
| R6 | 子 Agent 是否可能冲突/越界 | 通过：默认串行 + 文件白名单 + 主 Agent 合并 |
| R7 | 是否有“为分层而分层”的空转发 | 通过：WP1a 只加端口与结果对象，规则仍留原处；WP1b 才上移 |
| R8 | 是否与现有安全/契约冲突 | 通过：不改 Binder/HTTP/Token/LAN；45 项契约不变 |
| R9 | 基线数字是否准确 | **修正**：UI 为 19 处/12 文件（原文档 18/11） |
| R10 | 是否遗漏 Compose watcher 不启动 | **已纳入** WP1b |

> 审核结论：**通过，可开始 WP0 与 WP1a**；WP1b 起每步完成后再审一次。
> 审核人：主 Agent（基于 3 个只读勘察结果 + 代码复核）；独立 reviewer 子 Agent 因限流未执行，
> WP1a 完成后补一次独立审查。

## 14. 执行记录

| 日期 | WP | 内容 | 门禁 |
|---|---|---|---|
| 2026-11-16 | 计划 | 本文档 | 一致性检查通过（文档阶段） |
| 2026-11-16 | WP0 | `check-layering.sh` + 基线 + 一致性接入 + 负例/自测 | 一致性、`--strict`、`--self-test` 全绿 |
| 2026-11-16 | WP1a | `ConfigGateway` + 结果对象 + UI 注入 + rev 校验 + 23 条纯规则用例 | UI 静态调用 19/12 → 0；基线 37 → 12 行；单测/一致性/assembleRelease/APK 校验全绿 |
| 2026-11-17 | WP1b | `ConfigService` + prefs/effects/log 端口 + reset/restore rev 修复 + Compose watcher | 52 条配置用例；单测/一致性/分层/构建/APK 校验全绿 |
| 2026-11-17 | WP2 | 纯策略 + 死代码清理 + `XiaoiProcessHost` + H1/H2 门禁 | Hook 565→482；16 条策略用例；双负例；单测/一致性/分层/构建/APK 全绿 |
| 2026-11-17 | WP3 | `Mcp.Hosts` 拆分 + `BridgeRuntime` + `BridgeService` + `BridgeHttpPolicy` | 11+20 条新用例；H3 负例；单测/一致性/分层/构建/APK 全绿 |
| 2026-11-17 | WP4 | `AppRuntime` 五端口 + Android 适配 + UI 去 Context | `AppRuntimeTest` 9 条；UI 4 类静态依赖清零；单测/一致性/分层/构建/APK 全绿 |
| 2026-11-17 | WP5 | `:core` java-library（82 文件）+ Gradle/测试/门禁路径 | `:core:compileJava` + Windows `assembleRelease` 成功；单测/一致性/分层/APK 全绿 |
| 2026-11-17 | WP6 | 拆 `:domain`（53）+ `:application`（29）；app 依赖两者 | domain 空 classpath 单编 + Windows 三模块构建/APK 全绿 |

## 15. 后续优化（2026-11-17 起）

WP0–WP6 已完成本轮分层目标；下一阶段按 ROI 继续：

1. P0：删除旧 `ConfigActivity` / `HomeActivity`（约 -1.5k 行）
2. P1：拆 `:infra`，把 `Http`/`ConfigStore`/`BridgeClient`/`ModelValidator`/`LeaderElection`
   移出 `:application`
3. P2：清掉 `ui_no_control` 3 条（控制面查询端口化）
4. P3：domain 内部按 `model/policy/protocol/registry` 整理，消包级环
5. P4：拆 `Bridge`（socket / CLI / router）
6. P5：真机 P0 与 git 历史恢复

详见 [NEXT.md](NEXT.md)（Windows 继续开发指南）。
