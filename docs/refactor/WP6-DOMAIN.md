# WP6：`:domain` / `:application` 拆分（执行计划）

> 状态：**已完成（2026-11-17）**
> 基线：Windows 原生 `C:\Users\Chatxavier\code\xiaoi-plus`，`:core` 已完成（WP5）
> 日期：2026-11-17
> 父计划：[LAYERING.md](LAYERING.md) · 前置：[WP5-CORE.md](WP5-CORE.md)

## 1. 目标

把当前 `:core`（82 文件）拆成：

```
:domain      模型 / 策略 / 协议 DTO / 端口（无 I/O、无 Android、无并发容器）
:application 用例 / 编排 / 纯 JVM 适配器（依赖 domain）
:app         Android / Xposed / UI（依赖 domain + application）
```

依赖规则：

- `:domain` 不依赖 `:application`、`:app`、android、Xposed、`org.json`
- `:application` 不依赖 `:app`、android、Xposed、`org.json`
- `:app -> :domain` + `:app -> :application`
- 包名保持不变（split package 跨 jar；不引入 JPMS）

## 2. 分类原则

### 2.1 `:domain`（53 文件）

- 配置模型/协议：`AppConfig`、ConfigGateway/Result/RevRules/BackupInfo/StorageInfo
- 状态/读模型：`AppStatus`、`BridgeStatus`、`AgentRuntimeStatus`、`StateModel`、
  `IssueResolver`、`IssueInputFactory`、`CapabilityRegistry`、`ActivityFeed`、`ActivityHub`、`NavStack`
- 策略：`BridgePolicy`、`BridgeHttpPolicy`、`XiaoiOverridePolicy`、`UsageSignal`、
  `AgentLanPolicy`、`UidGuard`、`RateLimiter`、`Redact`、`ErrorCatalog`
- 协议/动作 DTO：`ActionDescriptor/Request/Result/Risk`、`Actions`、`AgentError`、
  `CanonicalArgs`、`AlarmTimes`、`AgentValues`、`AppToolProtocol`、`RootCommands`
- 端口：`ActionExecutor`、`NotifyHost`、`AskHost`、`ConfigGateway`
- 基础纯工具：`Json`、`Hash`、`StatusResolver`、`UsageCounter`、`ThemeMode`、
  `LogLevel`、`LogSettings`、`ControlKeys`、`PermissionSnapshot`、`BridgeEndpoint`
- 控制面纯模型：`InstanceRecord`、`BridgeRegistry`、`ControlLogRing`

### 2.2 `:application`（29 文件）

- 配置用例：`ConfigService`、`ConfigDefaults`、`ConfigPrefsPort`、`ConfigEffectsPort`、
  `ConfigLogPort`、`ConfigStore`、`ConfigCodec`
- 桥用例：`BridgeService`、`LeaderElection`（文件锁适配器）
- 运行时端口：`AppRuntime`、`AgentControl`、`StatusProbeStore`、`PermissionReader`、
  `RuntimeConfigImporter`、`ConfigWatcher`
- 动作/协议服务：`ActionRegistry`、`AgentMcp`、`ConfirmationManager`、`AuditLog`、
  `IdempotencyStore`、`FallbackExecutor`、`AgentCallLog`、`AgentHttpServer`、`BridgeAskHost`
- 纯 JVM 传输/探测：`Mcp`、`Http`、`BridgeClient`、`ModelValidator`、`Lan`

> `Usage`（android）与 `Logs`（Xposed）仍留 `:app`。

## 3. 构建改动

- 删除 `:core`；新增 `:domain`、`:application` 两个 `java-library`（`options.release=8`）。
- `settings.gradle.kts`：`include(":domain")`、`include(":application")`。
- `application/build.gradle.kts`：`implementation(project(":domain"))`。
- `app/build.gradle.kts`：
  ```kotlin
  implementation(project(":domain"))
  implementation(project(":application"))
  ```
- `tests/run.sh`：
  - `find domain/src application/src` + `Logs.java` + 桩；
  - **额外一步**：用空 classpath 单独 `javac domain/src`，证明 domain 不依赖 application；
  - application 单独编译（classpath 仅 domain）。
- `build.sh`：javac find domain + application。
- `check-consistency.sh`：扫描路径加 domain + application，移除 core。
- `check-layering.sh`：
  - `domain_no_android/xposed/orgjson`、`domain_no_io`、`domain_no_concurrent`；
  - `application_no_android/xposed/orgjson`；
  - 旧 `core_module_*` 删除；`domain_no_*`/`application_no_*` 改到新目录；
  - `domain -> application` 由“domain 单独 javac + `:domain:compileJava`”保证，
    不依赖 import 规则（split package 下同包引用没有 import）；
  - 基线收尾应只剩 PermissionProbe 8 行 + ui_no_control 3 行。
- CI / pre-push：显式跑 `:domain:compileJava`（或 `tests/run.sh` 的 domain-only javac）。
- `docs`/CHANGELOG/README 更新模块图。

## 4. 验收

```bash
bash tests/run.sh
bash scripts/check-consistency.sh
bash scripts/check-layering.sh --strict
# Windows 原生
gradlew.bat :domain:compileJava :application:compileJava :app:assembleRelease --offline
bash scripts/verify-apk.sh app/build/outputs/apk/release/app-release.apk
```

- `:domain` 无 I/O/Android/Xposed/org.json，且不 import application。
- `:application` 无 Android/Xposed/org.json，且不 import app。
- 单测全绿；APK 签名/包名/versionCode/指纹不变；R8 正常。

## 5. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 分类错误导致 domain 依赖 application | 编译 + `check-layering` 双门禁；按 jdeps 依赖图调整 |
| 跨 jar split package | Java classpath/D8 支持；包名不变；全量 APK 验证 |
| `ConfigCodec`/`ConfigStore` 移 application 后 app 找不到 | app 依赖 application；compile 兜底 |
| tests/run.sh 漏类 | `find domain+application`，不手写清单 |
| Windows 离线构建 | `options.release=8`，不用 toolchain；已验证 Windows 构建链路 |

回滚：WP6 独立 commit；把 domain/application 文件合并回 `:core` 即可。

## 6. 自审清单

| # | 检查项 | 结论 |
|---|---|---|
| R1 | domain 是否真的无 I/O | 通过：逐类核对；`Json`/`Hash`/`UsageCounter` 只 java.util/text/security |
| R2 | domain 是否不依赖 application | 通过：domain 单独 javac + `:domain:compileJava`（不靠 import） |
| R3 | application 是否不依赖 app | 通过：编译 + 门禁 |
| R4 | app 是否只加两个依赖 | 通过 |
| R5 | R8/Xposed 入口是否不变 | 通过：包名/类名/Hook 不变 |
| R6 | 测试清单是否自动 | 通过：find 两个源码目录 |
| R7 | 真机/契约是否不受影响 | 通过：协议/端口/配置不动 |
| R8 | 回滚是否可行 | 通过：独立 commit + 文件移回 |
| R9 | 是否过度拆分 | 通过：只拆 domain/application，不建 infra 第四模块 |
| R10 | Windows 构建是否可复现 | 通过：JDK 21 + release=8 |

> 审核结论：**通过（已按独立复核修订）**。
> 审核人：主 Agent + 独立 reviewer（只读）。
>
> 复核修订：
> 1. `ConfigStorageInfo` 明确归 domain；计数修正为 53 + 29；
> 2. `application` 显式依赖 `domain`；
> 3. split package 下 import 规则不可靠，改为 domain 单独 javac/`:domain:compileJava`；
> 4. 新增 `domain_no_io` / `domain_no_concurrent`；
> 5. `check-consistency` 路径从 core 替换为 domain+application，不是追加；
> 6. `BridgeEndpoint/LogSettings/AgentRuntimeStatus` 是进程内注册表/设置，保留在 domain 并注明；
> 7. CI/pre-push 显式编译 `:domain`。

## 7. 执行记录（2026-11-17）

| 项 | 结果 |
|---|---|
| `:domain` | 53 个文件，空 classpath 单独 `javac` 通过；无 I/O/并发/Android/Xposed/org.json |
| `:application` | 29 个文件，依赖 `:domain`；无 Android/Xposed/org.json |
| Gradle | `settings` include domain/application；app 依赖两者；`core` 删除 |
| 测试 | `tests/run.sh` 先单编 domain，再合并 compilation；全绿 |
| 门禁 | 新增 domain/application 规则；基线仍 11 行 |
| Windows | `:domain:compileJava` + `:application:compileJava` + `:app:assembleRelease --offline` 成功 |
| APK | 1.9M，签名/包名/versionCode/指纹不变 |
| 真机 | **未执行** |

结果：`app -> domain + application`，`application -> domain`；`:domain` 不依赖 application，
由“空 classpath 单编 + `:domain:compileJava`”证明，而不是 import 规则。