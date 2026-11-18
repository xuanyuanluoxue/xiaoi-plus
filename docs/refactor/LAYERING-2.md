# 第二阶段分层（LAYERING-2）：从 3 模块到 6 模块

> 状态：**计划（未执行）**
> 基线：`main` @ v0.15.0（versionCode 22），WP0–WP6 已完成
> 日期：2026-11-18
> 上游：[LAYERING.md](LAYERING.md)（第一阶段，WP0–WP6）· [NEXT.md](NEXT.md)（P0–P5 路线）
> 关系：本文件**取代** NEXT.md 的 P0–P4 并给出更细的工作包；NEXT.md 保留为环境指南。

## 0. 这一阶段要解决什么

第一阶段把「纯逻辑」和「Android」分开了（`:domain` / `:application` / `:app`），但留下四个结构性缺口：

| # | 缺口 | 实证 |
|---|---|---|
| G1 | `:application` 仍直接做 I/O，**导致 `no_io` 门禁加不上** | `ConfigService` 字段是具体类 `ConfigStore`（`ConfigService.java:29,44`）；`Http` / `BridgeClient` / `ModelValidator` / `LeaderElection` 在本模块 |
| G2 | 持久化只有 1/7 被抽象 | 有端口：`config.json`(ConfigGateway)、prefs、幂等表(Persistence)；无端口：日志环形、用量、导出文件、锁文件 |
| G3 | 表现层与适配器同处一模块，`ui` 规则靠 grep 兜 | `ui/` 70 文件与 38 个适配器同在 `:app`；`ui_no_*` 6 条靠文本匹配 |
| G4 | 领域层有 3 处进程级可变静态 | `BridgeEndpoint.sPort/sReloadAction`、`LogSettings.level`、`AgentRuntimeStatus.sSnapshot` |

另外两处「结构未收尾」：旧 Java 页面（`ConfigActivity` 1049 行）与 Compose 两套入口并存；`Bridge` 979 行单类。

## 1. 决策（追加到 LAYERING.md 的 L1–L8）

| 编号 | 决策 | 理由 |
|---|---|---|
| L9 | 目标 **6 模块**：`:domain` / `:application` / `:infra` / `:platform` / `:ui` / `:app` | 每个模块一条可机器校验的边界 |
| L10 | 端口定义在下层（domain/application），**实现全部在上层** | `:infra` 与 `:platform` 都是 adapter，允许依赖 `:application` |
| L11 | `:infra` 允许 `java.io/net/nio`，**禁止 android/xposed** | 「纯 JVM 的基础设施」仍可脱离设备单测 |
| L12 | `:platform` 允许 android/xposed，**禁止 compose 与 ui** | 适配器不认识界面 |
| L13 | `:ui` 允许 compose/miuix，**禁止 infra/platform** | 表现层只认端口 |
| L14 | `:app` 降为壳：清单 + 资源 + 入口 + 组装，软上限 **1500 行** | 壳越薄，回归面越小 |
| L15 | 仍不引入 DI 框架 / 不拆 `:domain` 成多个模块 / 不引入数据库 | 与 L8 一致：Xposed 多进程下手写组装更可控 |

## 2. 目标结构

### 2.1 现状 → 目标

```
现在                                  目标
┌──────────────┐                     ┌──────────────────────────────────────┐
│ :app  108/20.8k│                    │ :app   壳    清单·资源·MainActivity·Hook│
│  ui 70 + 适配 38│                    ├──────────────────────────────────────┤
└───────┬──────┘                     │ :ui        62 文件   Compose 表现层    │
        │                            ├──────────────────────────────────────┤
┌───────▼──────┐                     │ :platform  38 文件   Android 适配器    │
│ :application │                     ├──────────────────────────────────────┤
│   29/5.2k    │                     │ :infra     6 文件    纯 JVM 基础设施   │
└───────┬──────┘                     ├──────────────────────────────────────┤
┌───────▼──────┐                     │ :application 29 文件  用例（零 I/O）   │
│ :domain 53   │                     ├──────────────────────────────────────┤
└──────────────┘                     │ :domain    53 文件  模型/策略/端口     │
                                      └──────────────────────────────────────┘
```

### 2.2 依赖方向（唯一合法方向，向下）

```
                    ┌─────────┐
                    │  :app   │   清单 / 资源 / MainActivity / Hook / 组装根
                    └──┬───┬──┘
              ┌────────┘   └────────┐
        ┌─────▼────┐          ┌─────▼──────┐
        │  :ui     │          │ :platform  │
        │ Compose  │          │ Android 适配│
        └─────┬────┘          └──┬──────┬──┘
              │                  │      │
              │            ┌─────▼──┐   │
              │            │ :infra │   │   纯 JVM IO：Http/ConfigStore/…
              │            └────┬───┘   │
              └────────┬────────┘       │
                  ┌────▼─────────┐      │
                  │ :application │◄─────┘   用例（零 IO）
                  └────┬─────────┘
                  ┌────▼─────┐
                  │ :domain  │   模型 / 策略 / 端口
                  └──────────┘
```

### 2.3 每个模块装什么（搬完后）

| 模块 | 文件数（估） | 内容 |
|---|---|---|
| `:domain` | 53 | 模型 / 策略 / 协议 DTO / 端口（不变，仅内部整理见 WP13） |
| `:application` | 29 → 23 | `ConfigService` `BridgeService` `AppRuntime` `ActionRegistry` `ConfirmationManager` `IdempotencyStore` `Mcp` `AgentMcp` `ConfigCodec` `Lan` + 端口 |
| `:infra`（新） | 10 | `Http` `ConfigStore` `FileConfigStore` `BridgeClient` `ModelValidator` `LeaderElection` `Lan` `BridgeAskHost` `AgentHttpServer` + 读取适配器 |
| `:platform`（新） | 38 | `ConfigRepository` `AndroidAppRuntime` `Prefs` `ConfigSync` `StatusCache` `ThemeManager` `AppExecutors` `Logs` `Usage` `Island` `ShareProvider` `PermissionProbe` `XiaoiProcessHostImpl` `AgentService` `AndroidExecutor` `RootExecutor` `AndroidNotifyHost` `AppToolClient` `Agent*Receiver` `Bridge` `BridgeLifecycle` `BridgeRuntime` `BridgeControlService/Binder/Client` `AndroidUidGuard` `Nav` |
| `:ui`（新） | 62 | `ui/screen/*` `ui/components/*`（含 glass）`ui/theme` `ui/status` |
| `:app`（壳） | ~8 | `AndroidManifest.xml` + `res/` + `MainActivity.kt` + `Hook.java` + `XiaoiPlusApp`/组装 |

## 3. 依赖规则矩阵（门禁目标）

| 模块 | 禁止 import | 备注 |
|---|---|---|
| `:domain` | `android.*` `de.robv.*` `org.json.*` `java.io\|net\|nio.*` `java.util.concurrent.*` | 已有 5 条 |
| `:application` | `android.*` `de.robv.*` `org.json.*` **`java.io\|net\|nio.*`（新增）** `java.util.concurrent.*`（新增） | G1 的验收 |
| `:infra` | `android.*` `de.robv.*` | 新增 |
| `:platform` | `androidx.compose.*` `top.yukonga.miuix.*` `plus.xiaoi.module.ui.*` | 新增 |
| `:ui` | `plus.xiaoi.module.infra.*`，以及 `ConfigRepository` / `BridgeControlService` / `AgentService` / `StatusCache` / `ConfigSync` / `PermissionProbe` 等适配器类 | 新增 + 保留已有 `ui_no_*` |
| `:app` | 业务实现类（只允许入口与组装） | 软门禁：行数 + import 白名单 |

Gradle 侧由模块依赖本身保证方向；`scripts/check-layering.sh` 补 import 规则；`tests/run.sh` 保持「空 classpath 单编 `:domain`」。

## 4. 工作包

排序原则：**先端口化，再搬模块，先删后搬（删掉的代码不用搬）**。

| WP | 内容 | 依赖 | 预估 | 风险 | 发布点 |
|---|---|---|---|---|---|
| WP7 | 存储端口化 + `:application` 纯化 | 无 | 1–1.5 天 | 中 | 不发版 |
| WP8 | 新建 `:infra` | WP7 | 1 天 | 低 | 不发版 |
| WP9 | 删旧页面与旧 UI（`:app` 清场） | 无（可与 WP7/WP8 并行） | 1.5 天 | 中 | beta |
| WP10 | 新建 `:platform` | WP8、WP9 | 1.5–2 天 | 中高 | 不发版 |
| WP11 | 新建 `:ui` | WP9、WP10 | 1–1.5 天 | 高（资源/R） | beta |
| WP12 | `:app` 瘦身为壳 + 门禁 | WP10、WP11 | 0.5 天 | 低 | 0.16.0 |
| WP13 | domain 内部分层 + 去静态化 | WP8（可并行） | 1.5–2 天 | 高（面广） | 0.16.0 |

合计约 **8–10 个工作日**，可并行度：WP9 ∥ WP7/WP8；WP13 ∥ WP10/WP11（文件不重叠）。

---

### WP7：存储端口化 + `:application` 纯化 —— ✅ 已完成（2026-11-18）

**目标**：`ConfigService`（唯一的配置用例）不再 import `java.io`，`application_no_io` 门禁上线。

**要做的**

1. 新增 `application/config/ConfigStorePort`（唯一文件 I/O 端口）：
   ```java
   interface ConfigStorePort {
       String path();  boolean exists();  long size();  long lastModified();
       long size(String name);  long lastModified(String name);
       String read();  String readNamed(String name);
       void writeAtomic(String json);        // tmp+fsync+rename
       List<String> listNames(String prefix);
       void backupCorrupt(String raw);  void moveCorruptAside();
       void restoreNamed(String name);  boolean migrateLegacy();
   }
   ```
2. 新增 `core/FileConfigStore implements ConfigStorePort`：把 `ConfigStore` 的受检 `IOException` 收敛成 `ConfigStorePort.Failure`（运行时），用例层因此不必 import `java.io`。
3. `ConfigService` 构造参数 `ConfigStore` → `ConfigStorePort`；删掉构造参数里的 `File legacyExternalFile`（迁移改由端口的 `migrateLegacy()` 负责）；`configFile()` / `legacyBackups()` / `restoreLegacy(File)` 从用例层移除，改由 `ConfigRepository` 用底层 `ConfigStore` 提供过渡门面。
4. 新增门禁 `application_no_io`。**注意**：`Http` / `ConfigStore` / `BridgeClient` / `ModelValidator` / `LeaderElection` / `AgentHttpServer` / `Lan` / `BridgeAskHost` 仍在 `:application`，因此规则上线时基线一次记入 **38 条**（10 个文件），由 WP8 清零。

**验收**

```bash
rg -c 'import java\.(io|net|nio)\.' application/src/main/java/plus/xiaoi/module/application  # 0（用例层）
bash scripts/check-layering.sh --strict   # 通过；application_no_io 基线 38（WP8 清零）
bash tests/run.sh                          # 新增 ConfigPortTest（纯内存端口，28 断言）
gradlew.bat :application:compileJava
```

**回滚**：WP7 是纯签名改造，`ConfigService` 行为不变；单 commit 回滚不影响文件格式。

---

### WP8：新建 `:infra`（纯 JVM 基础设施）—— ✅ 已完成（2026-11-18）

**目标**：`java-library` 模块，**禁止 android/xposed**，承接全部 I/O 实现；**把 `application_no_io` 基线从 38 条清零**。

**移植清单（10 个文件）**

| 从 | 到 | 说明 |
|---|---|---|
| `application/Http.java` | `infra/http/Http.java` | HTTP 编解码 |
| `application/core/ConfigStore.java` | `infra/store/ConfigStore.java` | 实现 WP7 的 `ConfigStorePort` |
| `application/core/FileConfigStore.java` | `infra/store/FileConfigStore.java` | 端口的文件实现 |
| `application/BridgeClient.java` | `infra/transport/BridgeClient.java` | 调用 8771 |
| `application/ModelValidator.java` | `infra/probe/ModelValidator.java` | 实现 `ModelProbe` 端口（WP11 起 UI 只认端口） |
| `application/bridge/LeaderElection.java` | `infra/lock/LeaderElection.java` | 文件锁 |
| `application/Lan.java` | `infra/net/Lan.java` | 本机 IPv4 枚举（`java.net`） |
| `application/agent/BridgeAskHost.java` | `infra/transport/BridgeAskHost.java` | 出站 HTTP 适配器 |
| `application/agent/AgentHttpServer.java` | `infra/http/AgentHttpServer.java` | **server 是 adapter，不是用例**；依赖 `:application` 的 `ActionRegistry`，方向合法（infra→application） |
| `ConfigCodec.fromStream(InputStream)` | 拆到 `infra` 的读取适配器 | 让 `:application` 的编解码不再出现 `InputStream` |

**同步改动**：`settings.gradle.kts`、`:app` 依赖、`tests/run.sh`（把 `infra/src` 加入第二段 javac）、`scripts/check-layering.sh`（新增 `infra_no_android` / `infra_no_xposed`）、`scripts/check-consistency.sh`（4 处路径）、`docs/architecture.md`。

**验收**

```bash
gradlew.bat :infra:compileJava
bash scripts/check-layering.sh --strict     # infra 规则 0 违规；application_no_io 基线 38 → 0
bash tests/run.sh
bash scripts/contract-test.sh --check       # 47 项不变
```

**风险**：`AgentHttpServer` 依赖 `AgentCallLog`/`AuditLog`（application）→ 若出现循环依赖，把这两个类跟着下沉到 `:infra`（它们本身无 Android 依赖）。

---

### WP9：删旧页面（`:app` 清场，NEXT.md 的 P0）—— ✅ 已完成（0.15.1，2026-11-18）

**目标**：只剩 Compose 一套入口；`ConfigActivity` 引用为 0。

**删除清单**

```
app/src/main/java/plus/xiaoi/module/ConfigActivity.java      1049 行
app/src/main/java/plus/xiaoi/module/HomeActivity.java
app/src/main/java/plus/xiaoi/module/AboutActivity.java
app/src/main/java/plus/xiaoi/module/LogsActivity.java
app/src/main/java/plus/xiaoi/module/BaseActivity.java
app/src/main/java/plus/xiaoi/module/ui/config/*.java          6 文件 / 53 KB
app/src/main/java/plus/xiaoi/module/ui/spike/SpikeActivity.kt
app/src/main/java/plus/xiaoi/module/ui/screen/me/LegacyScreen.kt
app/src/main/res/layout/*（旧页面布局）
app/src/main/res/drawable/bg_chip*.xml · bg_input.xml（若仅旧页面用）
```

**连带修改**（容易漏，逐条核对）

- `MainActivity.kt`：删 `Routes.LEGACY` 与 `openLegacy()`
- `MeScreen.kt`：删「旧版入口」
- `AndroidManifest.xml`：删 4 个 `<activity>` 条目
- `scripts/check-consistency.sh`：`BaseActivity.showMessage` 的提示规则要改写（Compose 侧统一 `SnackbarHost`），否则门禁指向已删类
- **`AGENTS.md` 第「提示统一 `BaseActivity.showMessage()`」一条同步改**（否则后续 Agent 会照旧规则写代码）
- `tests/run.sh`：若 `LogsTest` 依赖旧类，补桩
- `README.md` / `CHANGELOG.md`

**验收**

```bash
rg -n "ConfigActivity|HomeActivity|BaseActivity" app/src           # 0
bash scripts/check-consistency.sh && bash tests/run.sh
gradlew.bat :app:assembleRelease && bash scripts/verify-apk.sh <apk>
# 真机：6 条一级路由 + 导出/导入/诊断/日志/主题 手工过一遍
bash scripts/ui-screenshot.sh --route Home --review
```

**风险**：旧页面里可能藏着未迁移的小功能（如「兼容模式」的只读信息已在 `DiagnosticsScreen`）。删前用 `ui/SCREENS.md` 与 19 路由清单逐项对照。

---

### WP10：新建 `:platform`（Android 适配器）—— ✅ 已完成（2026-11-18）

**目标**：38 个适配器文件离开 `:app`；`:app` 只剩清单/资源/入口。

**前置**：`xposedStubJar` 任务从 `app/build.gradle.kts` 移到 **`:platform`**（它有 android bootClasspath，桩里的 `XC_LoadPackage` 需要 `ApplicationInfo`），`:app` 消费同一个 jar（`compileOnly(files(...))` + `dependsOn(":platform:xposedStubJar")`）。

**移植清单**

| 组 | 文件 |
|---|---|
| 存储 | `ConfigRepository` |
| 运行时 | `AndroidAppRuntime` `Prefs` `ConfigSync` `StatusCache` `ThemeManager` `AppExecutors` `Usage` `Nav` |
| 出站 | `Island` `Logs` `ShareProvider` `PermissionProbe` `XiaoiProcessHostImpl` `XiaoiProcess` |
| 8770 | `agent/AgentService` `AndroidExecutor` `RootExecutor` `AndroidNotifyHost` `AppToolClient` `AgentAlarmReceiver` `AgentBootReceiver` |
| 8771 / 控制面 | `Bridge` `BridgeLifecycle` `BridgeRuntime` `BridgeControlClient` `BridgeControlService` `BridgeControlBinder` `AndroidUidGuard` |

**清单归属**：`Service` / `Receiver` / `Provider` 条目**留在 `:app` 的 AndroidManifest**（壳的职责），`:platform` 不产出 manifest，避免 `exported` 与 `PermissionProbe` 之外的第二份真相。

**验收**

```bash
gradlew.bat :platform:compileDebugKotlin        # 或 compileJava
bash scripts/check-layering.sh --strict          # platform_no_compose / platform_no_ui 为 0
bash tests/run.sh
bash scripts/contract-test.sh --check
adb install -r ... && 手工：8770 开关、8771 开关、控制面 reload、超级岛
```

**风险**：`:platform` 拆出后 `R8`/`proguard-rules` 位置需确认（保持在 `:app`）；`Bridge` 依赖 `XSharedPreferences`，`compileOnly` 必须在 platform 生效。

---

### WP11：新建 `:ui`（Compose 表现层）—— ✅ 已完成（2026-11-18）

**目标**：62 个 UI 文件独立成 android-library，`:ui` 不依赖 infra/platform。

**前置条件（WP9 完成后实测）**：`ui/` 里 `R.*` 引用已从 41 降到 **2 个文件**（`SpikeActivity` 与 `SponsorScreen`）；处理这 2 处（搬 1 个 drawable + 2 个 color 到 `:ui/res`，或改参数注入）后才能拆。

**移植清单**：`ui/screen/*`（31）`ui/components/*`（含 `glass/`）`ui/theme/*` `ui/status/*` + 相应 `res/`（theme/color/drawable）。

**依赖**：`implementation(project(":application"))` + compose/miuix（从 `:app` 的依赖块平移）。

**验收**

```bash
bash scripts/check-layering.sh --strict          # ui_no_infra / ui_no_platform / 既有 6 条 ui_no_* 全 0
gradlew.bat :ui:compileDebugKotlin
gradlew.bat :app:assembleRelease
bash scripts/ui-screenshot.sh --route Home --review   # 6 条一级路由视觉不变
```

**风险（本阶段最高）**：`glass/` 用了 `miuix-blur`（minSdk 33 的 AAR，`uses-sdk tools:overrideLibrary` 在 `:app` 清单里）；`:ui` 作为 library 时该 override 仍要在最终清单生效——需实测合并后的 manifest，`assembleRelease` 后用 `aapt2 dump xmltree` 核对。

---

### WP12：`:app` 瘦身为壳 —— ✅ 已完成（2026-11-18）

**目标**：`:app` 只剩 8 个左右文件，业务代码软上限 1500 行。

**内容**：`AndroidManifest.xml` + `res/` + `MainActivity.kt` + `Hook.java` + `XiaoiPlusApplication`（若需要）+ 三个组装根接线。

**门禁**：`scripts/check-consistency.sh` 增加一条「`:app` 非清单/资源文件行数 ≤ 1500」的软检查（超限告警，`--strict` 失败）。

**验收**：全量门禁 + 契约测试 + 真机 P0（权限矩阵、8770 启停、8771 LAN/leader、配置导入导出、watcher）。

---

### WP13：domain 内部分层 + 去静态化 —— ✅ 去静态化 + 消环已完成（2026-11-18）

**目标**：`core ↔ agent` 包环消除；domain 内无 `static` 可变字段。

**落地结果（与计划不同的地方照实记）**

计划里说「3 处静态」，实际排查出 **4 处**（漏了 `BridgeStatus`），做法统一为
**「不可变快照留在 domain，最新快照的持有者上移到应用层」**：

| 原位置 | 快照（domain，纯数据） | 持有者（上层，进程状态） |
|---|---|---|
| `BridgeEndpoint` | —（无快照） | 端口/Reload 回调整体移到 `:infra` 的 `core.BridgeEndpoint`（与 `BridgeClient` 同模块） |
| `LogSettings` | —（纯档位枚举） | 移到 `:application` 的 `application.log.LogSettings` |
| `AgentRuntimeStatus.Snapshot` | `core.AgentRuntimeSnapshot` | `application.agent.AgentRuntimeStatus` |
| `BridgeStatus.Snapshot` | `core.BridgeSnapshot` | `application.bridge.BridgeStatus` |

**消环（2 条边，均为单点）**

- `core → agent`：`IssueInputFactory` 改成接收 `core.AgentRuntimeSnapshot`（同包），不再 import `agent`
- `agent → core`：`AgentLanPolicy` 移到 `core`（它与 `BridgePolicy` 共用同一套令牌强度规则）

结果：`core → agent = 0`、`agent → core = 0`，domain 内 `static volatile` = 0。

**门禁**：`core_no_agent` / `agent_no_core`（两条 import 规则，基线必须 0）+ 一致性检查
「领域层无进程级可变状态」（同时断言 4 个类不许回到 domain）。

**包重组（`model/policy/port/protocol/registry`）——本轮不做**

理由：只改包名要动 **234 处 import**，零行为收益，而当前没有测试机可做回归；
包环已经由上面两条规则**在结构上**挡死。等有设备时再作为纯机械重构单独做。

**验收**：`tests/run.sh` 2030 断言全绿、`check-layering --strict`（0 基线）、
一致性、六模块构建、`verify-apk` 全绿。

## 5. 每个 WP 的完成定义（DoD）

1. 代码 + 测试 + 文档三处同步（含 `AGENTS.md` 规则变更）。
2. `bash tests/run.sh` 全绿，断言数只增不减。
3. `bash scripts/check-consistency.sh` 全绿（含 `check-layering --strict`）。
4. `gradlew.bat :domain:compileJava :application:compileJava :infra:compileJava :platform:compileJava :ui:compileKotlin :app:assembleRelease` 全绿。
5. `bash scripts/verify-apk.sh` PASS（指纹不变）。
6. 涉及运行时的 WP 跑 `contract-test.sh --check`（47 项）+ 对应真机项。
7. `scripts/layering-baseline.tsv` **只减不增**。
8. 独立 commit，message 前缀 `refactor(layering2): WPn …`。

## 6. 风险总表

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | `:ui` 拆出后资源/R 合并顺序变化 | 视觉回归、`R.style` 失效 | WP9 先把 R 引用降到 0；`aapt2 dump xmltree` 核对；6 路由截图留档 |
| R2 | `miuix-blur` 的 `uses-sdk override` 在 library 模式下失效 | 低版本设备崩溃 | 保持 override 在 `:app` 清单，装 API 26 模拟器验一次 |
| R3 | `exported` Service/Receiver 拆模块后清单合并出错 | 安全（导出了不该导出的组件） | 清单集中在 `:app`；`verify-apk` 增加 `aapt2 dump badging` 组件比对 |
| R4 | Xposed 桩被误打进 APK | 体积/校验异常 | 桩是 `compileOnly`，不进 dex；2026-11-18 实测 APK 只有 1 处对 `robv/android/xposed` 的**调用引用**（`Logs.java` 调框架方法，运行时由 LSPosed 提供），无桩类本体 |
| R5 | `AgentHttpServer` 下沉 `:infra` 造成 `infra↔application` 环 | 编译失败 | 允许 `infra→application`（单向）；出现环就把 `AgentCallLog`/`AuditLog` 一起下沉 |
| R6 | 删旧页面时漏掉未迁移的小功能 | 功能缺失 | 按 `docs/ui/SCREENS.md` + 20 路由逐项对照；真机手工过 6 类操作 |
| R7 | WP13 去静态化打乱契约测试假设 | 47 项红色 | 先补断言再改；每子步骤单独 commit |

## 7. 明确不做

- 不引入 DI 框架（Hilt/Koin）、不引入数据库（SQLite/Room）。
- 不拆 `:domain` 为多个 Gradle 模块（只做包内分层）。
- 不改 8771 / 8770 报文、端口、Token/LAN 规则、Binder UID 守卫。
- 不改 `config.json` v3 格式与 rev 语义。
- 不改 Hook 目标、顺序、版本白名单。
- 不新增运行时依赖（除已有 Compose/Miuix）。

## 8. 版本与发布

| 阶段 | 版本 | 内容 |
|---|---|---|
| WP7–WP8 | 不发版 | 内部重构，行为不变 |
| WP9 | `0.15.1` beta | 旧页面移除（用户可感知） |
| WP10–WP11 | 不发版 | 内部重构 |
| WP12–WP13 | `0.16.0` | 第二阶段收尾 |

## 9. 执行记录（执行时填）

| 日期 | WP | 内容 | 门禁结果 |
|---|---|---|---|
| 2026-11-18 | 计划建立 | — | — |
| 2026-11-18 | WP7 | `ConfigStorePort` + `FileConfigStore`；`ConfigService` 不再 import `java.io`（构造参数去掉 `File`，迁移改由端口 `migrateLegacy()`）；`ConfigRepository` 保留 `configFile()/legacyBackups()` 过渡门面；新增 `ConfigPortTest`（纯内存端口，28 断言）；新增门禁 `application_no_io`（基线 38 条） | 单测 2017 断言（48 套件）；分层 `--strict`、一致性、三模块构建、`verify-apk` PASS；真机配置读写 rev 31→33、字段不变、无崩溃 |
| 2026-11-18 | WP8 | 新建 `:infra`（9 文件）：`Http`/`ConfigStore`/`FileConfigStore`/`BridgeClient`/`ModelValidator`/`LeaderElection`/`Lan`/`AgentHttpServer`/`BridgeAskHost`；`ConfigCodec.fromStream` 与 `ConfigRepository.parse/toJson` 删除；新增 `infra_no_android`/`infra_no_xposed` 门禁，`application_no_io` 基线 38 → **0**，总基线 49 → **11** | 单测 2017 断言；分层 `--strict`、一致性、四模块构建、`verify-apk` PASS；真机契约 **47/47** |
| 2026-11-18 | WP9 | 删旧 Java 页面（`ConfigActivity` 1049 行 / `HomeActivity` / `AboutActivity` / `LogsActivity` / `BaseActivity` / `Nav` / `ui/config` 6 文件 / `SpikeActivity` / `LegacyScreen`，约 -1.5k 行）；4 处「打开 App」改指 `MainActivity`；删 6 布局 + 22 drawable + `dimens.xml` + `Widget.*`/`Dialog` 样式，`strings.xml` 只留 `app_name`；路由 20 → 19；版本 `0.15.1` (23) | 单测 2017 断言；一致性（死资源清零）、分层 `--strict`、构建、`verify-apk`（versionCode 23）全绿；真机：4 个 tab 正常、`我的` 无「兼容模式」、契约 **47/47**、无崩溃 |
| 2026-11-18 | WP10 | 新建 `:platform` Android library（namespace `.platform`），搬 30 个适配器（配置/桥/控制面/8770/通知/执行器/`PermissionProbe`/`ShareProvider`/`ThemeManager`/`Logs`）；`:app` 只剩清单+资源+`MainActivity`+`Hook`；Xposed 桩改由 `:platform` 产出；新增 `AppLaunch` 替代 `MainActivity` 直接引用；新增 `platform_no_compose`/`platform_no_miuix`/`platform_no_ui` 门禁，基线 11 → **3 行** | 单测 2017 断言；分层 `--strict`、一致性、五模块构建、`verify-apk` PASS；真机：启动 328ms、`我的` 页正常、契约 **47/47**、无崩溃 |
| 2026-11-18 | WP11 | 新增 `:ui` Android library（namespace `.ui`），搬 62 个 Compose 文件 + `sponsor_wechat`；`:app` 只剩 `MainActivity.kt` + `Hook.java` + 清单 + 资源；前置把 7 处 platform/infra 耦合端口化（`ControlLogStore`/`UiPrefs`/`BackgroundTasks`/`NetworkInfo`/`UsageStats`/`ModelProbe`/`FileShare.AUTHORITY`），`AppRuntime` 6 → **11 端口**；删 `GlassSupport.userEnabled` 死代码；新增 `ui_no_infra`/`ui_no_bridge_pkg` 与 authority 一致性检查，**基线清零（0 行）** | 单测 2017+ 断言；分层 `--strict`（0 基线）、一致性、六模块构建、`verify-apk` PASS；真机：启动 104ms、进程存活、契约 **47/47**、无崩溃 |
| 2026-11-18 | WP12 | `:app` 壳软门禁：一致性检查断言 app 源码 ≤ 8 文件 / ≤ 1500 行（当前 **2 文件 / 975 行**），业务代码必须放 `:ui`/`:platform` | 一致性通过 |
| 2026-11-18 | WP13 | domain 去静态化 + 消环：`BridgeEndpoint` → `:infra`；`LogSettings` → `:application`；`AgentRuntimeStatus`/`BridgeStatus` 拆成「domain 不可变快照 + application 持有者」；`AgentLanPolicy` → `core`、`IssueInputFactory` 改用同包快照 → `core↔agent` 边清零；新增 `core_no_agent`/`agent_no_core` 门禁与「领域层无进程级可变状态」检查（包重组按记录推迟） | 单测 2030 断言；分层 `--strict`（0 基线）、一致性、六模块构建、`verify-apk` PASS |
| 2026-11-18 | 复验 | 真机复验 WP11/WP13（M332BF / Android 17）：`ControlLogStore` 日志页 93 条实时刷新、`UiPrefs` 玻璃开关读写、`NetworkInfo` 本机 IP 192.168.0.118 + 默认端口、`AgentRuntimeSnapshot` 接入页 8770「运行中 · LAN · 0.0.0.0」、控制面 3 个进程注册 port=8771 rev=33；契约 47/47。修复 `contract-test.sh` 端口发现：带 token 时按 `facade=` 自证身份、无 token 时用 `/stats` 排除 8770（Agent 动作面与桥的 `/health` 401 体相同，曾误判到 8770） | 全部通过 |：`BridgeEndpoint` → `:infra`；`LogSettings` → `:application`；`AgentRuntimeStatus`/`BridgeStatus` 拆成「domain 不可变快照 + application 持有者」；`AgentLanPolicy` → `core`、`IssueInputFactory` 改用同包快照 → `core↔agent` 边清零；新增 `core_no_agent`/`agent_no_core` 门禁与「领域层无进程级可变状态」检查（包重组按记录推迟） | 单测 2030 断言；分层 `--strict`（0 基线）、一致性、六模块构建、`verify-apk` PASS |
