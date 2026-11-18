# WP5：`:core` 物理模块（执行计划）

> 状态：**已完成（2026-11-17）**
> 基线：Windows 原生仓库 `D:\code\Android\xiaoi-plus`（WSL 路径 `/mnt/c/...`）
> 日期：2026-11-17
> 父计划：[LAYERING.md](LAYERING.md) · 前置：[WP4-UI.md](WP4-UI.md)

## 1. 现实核对

- 工程仍是单 `:app` module；`tests/run.sh` 用 **83 个手写源码文件**证明纯层边界。
- 这 83 个文件中只有 `Logs.java` 依赖 Xposed 桩；其余 82 个在无 `android.jar`
  的 `javac` 下可编译，是天然的 `:core` 闭包。
- `Logs` 被 app 侧 Bridge/Hook/AgentService 使用；`:core` 不依赖它。
- `:app` 已能通过 Windows JDK 21 + Android Studio/SDK 构建；C: 空间约 23G，
  构建缓存已由 Windows Gradle 生成。
- 原 git 历史已丢失；本 WP 在恢复基线上继续。

## 2. 目标

1. 新增 `:core` `java-library`（Java 8 源码级别），承载 82 个纯 JVM 类。
2. `:app` 依赖 `:core`；`Logs.java` 留在 app。
3. 包名保持不变（避免反射/R8/字符串类名改动），允许跨 jar 的 split package。
4. `tests/run.sh` 改为 `find core/src` + `Logs.java` + 测试源码，不再维护 83 行清单。
5. 分层门禁增加“core module 无 android/Xposed/org.json”。
6. 验收构建/单测/一致性/分层/APK，Windows 原生构建必须通过。

### 非目标

- 不拆 `:domain` / `:application`（本 WP 只做 `:core`；拆细留后续）。
- 不移动 `Logs`、`Config`、`ConfigRepository`、`Bridge`、`Hook`、UI、Android 适配器。
- 不改包名、不改 Xposed 入口、不改 R8 keep 类名。
- 不改业务行为、协议、配置格式。

## 3. 模块边界

```
:core (java-library, Java 8)
  plus.xiaoi.module
    AppConfig / ConfigCodec / Json / Hash / Lan / ModelValidator /
    StatusResolver / UsageCounter / AppStatus / BridgePolicy ...
  plus.xiaoi.module.core.**（纯类；PermissionProbe 除外）
  plus.xiaoi.module.application.**（ConfigService / BridgeService / AppRuntime 端口）
  plus.xiaoi.module.agent.**（纯协议/动作类；Android 执行器除外）
  plus.xiaoi.module.bridge.LeaderElection
  plus.xiaoi.module.control.{UidGuard,InstanceRecord,BridgeRegistry,ControlLogRing}

:app (android.application)
  Hook / Bridge / Config / ConfigRepository / ConfigSync / Logs /
  AgentService / AndroidExecutor / PermissionProbe / Usage / UI / Android 适配器
```

`Usage.java` **不是纯类**（`import android.content.Context`），必须留在 app；
`:core` 只收 `tests/run.sh` 的 83 个文件去掉 `Logs.java` 后的 **82** 个。

依赖方向：`:app -> :core`；`:core` 不依赖 `:app`、android、Xposed、org.json。

## 4. 文件与构建改动

新增：

- `core/build.gradle.kts`（`java-library`，UTF-8，Java 8）
- `docs/refactor/WP5-CORE.md`（本文）

修改：

- `settings.gradle.kts`：`include(":core")`
- `app/build.gradle.kts`：`implementation(project(":core"))`
- `tests/run.sh`：`find core/src/main/java -name '*.java'` + `Logs.java` + Xposed 桩
- `build.sh`：javac 的 `find` 加 `core/src/main/java`（legacy 构建也不能坏）
- `scripts/check-layering.sh`：新增 core module 规则；`application_no_*` 路径改 core；
  删除已无意义的 `core_no_agent` 规则
- `scripts/check-consistency.sh`：`getIdentifier`/`new Thread`/`HttpURLConnection`/`Toast`
  扫描路径加 `core/src/main/java`
- `docs/ci.md`、`docs/build.md`、`README.md`、`CHANGELOG.md`
- `docs/refactor/LAYERING.md` 状态与路径

移动：

- 从 `app/src/main/java/plus/xiaoi/module/` 移动到 `core/src/main/java/plus/xiaoi/module/`
  的 **82** 个文件（`tests/run.sh` 清单去掉 `Logs.java`）。
- 保留在原地的 Android/Xposed/UI 文件：`Logs.java`、`Config.java`、`ConfigRepository.java`、
  `ConfigSync.java`、`Bridge.java`、`Hook.java`、`AndroidAppRuntime.java`、`AgentService.java`、
  `AndroidExecutor.java`、`PermissionProbe.java`、`Island.java`、`Usage.java`。
- `Usage` 明确留 app；`UsageCounter` 是纯类，随 82 个文件移动。

### 4.1 门禁/基线处理

- `core_no_agent`（app/core/IssueInputFactory）迁移后文件不在 app 路径：
  **删除该规则与基线行**；`core` 内 `core -> agent` 是模块内部依赖，不再视为违规。
- `core_no_android` 保留 app 路径（`PermissionProbe` 仍在 app），基线 8 行不变。
- 新增 `core_module_no_android` / `core_module_no_xposed` / `core_module_no_orgjson`，
  路径 `core/src/main/java`，迁移后应为 0。
- `application_no_*` 改指 `core/src/main/java/.../application`。
- 收尾 `check-layering.sh --init`，基线应只剩：PermissionProbe 8 行 + ui_no_control 3 行。

## 5. 验收

```bash
# Windows 原生
cmd.exe /c "C:\Users\Chatxavier\code\xiaoi-win-build.bat :app:assembleRelease --offline"
# WSL（脚本/单测）
bash tests/run.sh
bash scripts/check-consistency.sh
bash scripts/check-layering.sh --strict
bash scripts/verify-apk.sh app/build/outputs/apk/release/app-release.apk
```

- `:app:assembleRelease` 成功，APK 签名/包名/versionCode/指纹不变。
- 单测全绿（含 McpTest 黄金串、ConfigServiceTest、AppRuntimeTest）。
- `:core` 单独 `:core:compileJava` 成功；无 android/Xposed/org.json。
- `core/build.gradle.kts` 用 `options.release = 8`，**不使用 toolchain**，避免 Windows
  离线构建寻找 JDK 8；UTF-8 编码显式设置。
- R8 产物正常，Xposed 入口仍为 `plus.xiaoi.module.Hook`。

## 6. 风险与回滚

| 风险 | 缓解 |
|---|---|
| split package 跨 jar 在 D8/R8 下异常 | Java classpath/D8 支持；包名不变；构建 + APK 全量验证 |
| 误移动 Android/Xposed 依赖类 | 用 `tests/run.sh` 闭包做白名单；移动前 grep android/Xposed |
| tests/run.sh 漏类 | 改为 `find core/src`，不再手写清单 |
| check-consistency 路径漏扫 core | 同步更新所有 `app/src/main/java` 扫描路径 |
| Windows 构建离线缓存缺失 | 已有 JDK 21 + 在线构建成功；验收用 `--offline` 或在线均可 |
| 原 git 历史已丢失 | 本 WP 仍独立 commit；bundle 备份 |

回滚：WP5 独立 commit；把 82 个文件移回 app、删除 `:core` 依赖即可。

## 7. 子 Agent 编排

| 步骤 | 执行者 | 白名单 |
|---|---|---|
| 计划审核 | 只读 reviewer | 无 |
| 文件移动 + Gradle | 主 Agent 串行 | 全部移动文件、settings/app/core 构建文件 |
| 测试/门禁/文档 | 主 Agent | tests/scripts/docs/CHANGELOG |

不做并行写：移动文件是全局操作，子 Agent 只做只读审查。

## 8. 自审清单

| # | 检查项 | 结论 |
|---|---|---|
| R1 | 82 文件是否真的无 android/Xposed | 通过：tests/run.sh javac 闭包，仅 Logs 例外 |
| R2 | 包名是否保持不变 | 通过：不重命名包 |
| R3 | `Logs` 是否留在 app | 通过：唯一 Xposed 依赖 |
| R4 | `:app -> :core` 是否单向 | 通过：core 不 import app |
| R5 | R8/Xposed 入口是否安全 | 通过：类名/包名不变，keep 规则不变 |
| R6 | 测试清单是否自动 | 通过：find core/src |
| R7 | Windows 构建是否可复现 | 通过：JDK 21 + 已成功构建 |
| R8 | 回滚是否可行 | 通过：独立 commit + 文件移回 |
| R9 | 是否与 WP4 端口冲突 | 通过：AppRuntime 端口在 core，Android 实现在 app |
| R10 | 是否诚实标注未拆 domain/application | 通过：非目标明确 |

> 审核结论：**通过（已按独立复核修订）**。
> 审核人：主 Agent + 独立 reviewer（只读）。
>
> 复核修订：
> 1. `Usage.java` 依赖 android，明确留 app；core 只收 82 个；
> 2. 补 `build.sh` 的 core 源码路径；
> 3. `check-layering` 路径/基线同步：删除 `core_no_agent`，新增 core module 规则，
>    `application_no_*` 改 core；收尾基线应为 11 行；
> 4. `check-consistency` 四个扫描点补 `core/src/main/java`；
> 5. core 用 `options.release=8`，不用 toolchain，避免 Windows 离线找 JDK 8；
> 6. 同步更新 LAYERING/WORKPACKAGES 的 WP5 状态与路径；
> 7. split package / R8 / Xposed 入口已核对，无阻塞。

## 9. 执行记录（2026-11-17）

| 项 | 结果 |
|---|---|
| `:core` | `java-library`，82 个纯 JVM 文件，`options.release=8` |
| 留 app | `Logs`、`Usage`、`Config`、`ConfigRepository`、`ConfigSync`、`Bridge`、`Hook`、Android 适配器、UI |
| Gradle | `settings` include `:core`；`app` `implementation(project(":core"))` |
| 测试 | `tests/run.sh` 改为 `find core/src` + `Logs.java` + 桩；83 个测试源全绿 |
| 门禁 | `build.sh`/`check-consistency` 扫描 core；layering 新增 core 规则，基线 11 行 |
| Windows | JDK 21 `:app:assembleRelease --offline` 成功；APK 1.9M 签名/包名/versionCode/指纹不变 |
| 真机 | **未执行** |

结果：`:app -> :core` 单向；`:core` 无 android/Xposed/org.json；
`Logs.java` 仍在 app，Xposed 入口仍为 `plus.xiaoi.module.Hook`。