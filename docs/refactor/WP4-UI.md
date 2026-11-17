# WP4：UI 运行时端口与剩余 Context 清理（执行计划）

> 状态：**待审核 → 通过后执行**
> 基线：`refactor/layering` @ `5a0236e`（WP0–WP3 已完成）
> 日期：2026-11-17
> 父计划：[LAYERING.md](LAYERING.md) · 前置：[WP3-BRIDGE.md](WP3-BRIDGE.md)

## 1. 现实核对

UI 已经通过 `ConfigGateway` 去掉了配置仓库的静态依赖，但仍直接调用以下 Android 服务/静态工具：

| 依赖 | 调用点 | 数量 |
|---|---|---|
| `AgentService.startIfEnabled(context)` | AccessScreen、TokenScreen、MeScreen、ModelStateHolder、ConfigManageScreen | 7 |
| `PermissionProbe.capture(context)` | HomeStateHolder、PermissionsScreen、DiagnosticsScreen | 3 |
| `StatusCache.probe/saveProbe/fingerprint` | HomeStateHolder、ModelStateHolder、XiaoaiStateHolder、DiagnosticsScreen | 4 |
| `ConfigSync.importFromRuntime(context)` | ConfigManageScreen、`ui/config/EditSection.java` | 2 |
| `ConfigSync.start(context)` | MainActivity | 1 |
| `ThemeManager` | MainActivity、`ui/config/AppearanceSection.java` | 平台偏好，单独评估 |

这些调用让 StateHolder 必须持有 `Context`，无法在桌面/预览里用 fake 替换；
`ConfigManageScreen` 同时承担文件 IO、Agent 服务与运行时导入三类职责。

## 2. 目标

### 2.1 运行时端口

新增 `application/runtime/`（纯 JVM）：

```java
public interface AgentControl {
    void startIfEnabled();
    void stop();
}

public interface StatusProbeStore {
    /** 端点+密钥+模型的指纹；纯计算，供 UI 与缓存共用。 */
    String fingerprint(String baseUrl, String apiKey, String model);
    void save(String fingerprint, StatusResolver.Probe probe);
    StatusResolver.Probe read(String fingerprint);
}

public interface PermissionReader {
    PermissionSnapshot capture();
}

public interface RuntimeConfigImporter {
    boolean importFromRuntime();
}

public interface ConfigWatcher {
    void start();
}

public final class AppRuntime {
    public final AgentControl agent;
    public final StatusProbeStore status;
    public final PermissionReader permissions;
    public final RuntimeConfigImporter configImport;
    public final ConfigWatcher watcher;
    // 构造器非空校验
}
```

Android 实现 `AndroidAppRuntime`（root）：

- `AgentControl` → `AgentService.startIfEnabled/stop`
- `StatusProbeStore` → `StatusCache`（委托，不复制 TTL/指纹规则）
- `PermissionReader` → `PermissionProbe.capture`
- `RuntimeConfigImporter` → `ConfigSync.importFromRuntime`
- `ConfigWatcher` → `ConfigSync.start`

### 2.2 Compose 注入

新增 `ui/components/LocalAppRuntime.kt`：

```kotlin
val LocalAppRuntime = staticCompositionLocalOf<AppRuntime> {
    error("AppRuntime 未提供：请在 XiaoiApp 注入")
}
```

`XiaoiApp` 与 `LocalConfigGateway` 一起 provide；runtime 用 `applicationContext` 创建，
Activity 重建后仍指向同一进程服务。

### 2.3 UI 改造

| 文件 | 改动 |
|---|---|
| `HomeStateHolder` | 去掉 `Context`；构造函数 `(ConfigGateway, AppRuntime)`；权限/状态缓存走 runtime |
| `ModelStateHolder` | 去掉 `Context`；构造函数 `(ConfigGateway, AppRuntime)`；校验缓存/8770 走 runtime |
| `XiaoaiStateHolder` | 去掉 `Context`；构造函数 `(ConfigGateway, AppRuntime)`；状态缓存走 runtime |
| `HomeScreen`/`ModelScreen`/`XiaoaiScreen` | 从 `LocalAppRuntime` 取实例传给 holder |
| `AccessScreen`/`TokenScreen` | 8770 启动走 `runtime.agent`；TokenScreen 的剪贴板仍用 Context |
| `MeScreen` | 8770 启动走 runtime；版本/复制仍用 Context |
| `ConfigManageScreen` | Agent/运行时导入走 runtime；文件 IO/分享仍用 Context |
| `DiagnosticsScreen` | 权限/状态缓存走 runtime；包名/设置 Intent 仍用 Context |
| `PermissionsScreen` | 权限矩阵走 runtime；设置 Intent 仍用 Context |
| `MainActivity` | provide runtime；`ConfigSync.start` 改 `runtime.watcher.start()` |
| `ui/config/EditSection.java` | 构造注入 `RuntimeConfigImporter`；删除 `ConfigSync` 直连 |
| `ConfigActivity` | 构造 `AndroidAppRuntime.create(this)` 传给 EditSection |

### 2.4 明确不做

- 不迁移 `ThemeManager`：它是 UI 偏好（prefs），不是业务服务；主 Activity 仍直接读写。
- 不迁移 `MeSupport` 的剪贴板/包信息/Intent：这些是平台 UI 能力，留在 UI 适配层。
- 不迁移旧 `ConfigActivity` / `HomeActivity` 的其余逻辑（过渡入口）。
- 不引入 `StatusService`：四维状态已由 `StateModel` / `IssueInputFactory` 组装，
  再加一层会变成空转发；如未来需要跨页面复用再单独提取。

## 3. 文件清单

新增：

- `app/src/main/java/plus/xiaoi/module/application/runtime/AgentControl.java`
- `.../StatusProbeStore.java`
- `.../PermissionReader.java`
- `.../RuntimeConfigImporter.java`
- `.../ConfigWatcher.java`
- `.../AppRuntime.java`
- `app/src/main/java/plus/xiaoi/module/AndroidAppRuntime.java`
- `app/src/main/java/plus/xiaoi/module/ui/components/LocalAppRuntime.kt`
- `tests/src/plus/xiaoi/module/AppRuntimeTest.java`（bundle 非空/fake 端口契约）

修改：

- `MainActivity.kt`、三个 StateHolder、对应 Screen、Access/Me/ConfigManage/Diagnostics/Permissions
- `ui/config/EditSection.java`、`ConfigActivity.java`
- `scripts/check-layering.sh`（新增 4 条 UI 规则）
- `tests/run.sh`、`TestMain.java`
- 文档/CHANGELOG

## 4. 门禁

`check-layering.sh` 新增（`ui/` 目录）：

| rule | 禁止 |
|---|---|
| `ui_no_agent_service` | `^import plus\.xiaoi\.module\.agent\.AgentService` |
| `ui_no_permission_probe` | `^import plus\.xiaoi\.module\.core\.PermissionProbe` |
| `ui_no_status_cache` | `^import plus\.xiaoi\.module\.StatusCache` |
| `ui_no_config_sync` | `^import plus\.xiaoi\.module\.ConfigSync` |

迁移完成后基线中不应再有这 4 类条目；旧 `ui/config/EditSection.java` 也要清零。
`ui_no_control`（BridgeControlService）仍保留 3 条，留给后续。

## 5. 测试与真机

### 5.1 纯 JVM

- `AppRuntimeTest`：bundle 五个端口非空；fake `AgentControl` 调用计数；
  `StatusProbeStore` 默认 fake 指纹/读写；`PermissionReader` 返回快照；
  `RuntimeConfigImporter` 返回布尔；`ConfigWatcher.start` 计数。
- 既有 `ConfigGatewayRulesTest` / `ConfigServiceTest` / `McpTest` 保持全绿。

### 5.2 真机 P0

- 首页权限/状态徽章与诊断页一致；权限矩阵仍显示真实结论。
- 模型页校验后主页 D2 结论同步；10 分钟 TTL 行为不变。
- 接入页开关外部接入 → 8770 启动/停止行为不变；令牌页保存后重连行为不变。
- 配置管理：导入运行时配置、还原/重置后 8770 对齐行为不变。
- 主题切换/Activity 重建后页面状态、runtime 不丢。
- 外部改 config.json 后 watcher 仍生效（MainActivity 启动路径）。

## 6. 风险与回滚

| 风险 | 缓解 |
|---|---|
| StateHolder 去 Context 后某些平台调用漏改 | 编译期兜底；逐文件 grep；全量构建 |
| Runtime 在 Activity 重建后失效 | 在 XiaoiApp `remember(context)` 创建，内部只持 applicationContext |
| CompositionLocal 默认 error 在预览崩溃 | 与 ConfigGateway 一致；预览需要时提供 fake |
| 旧 ConfigActivity 入口找不到 importer | 构造时传 `AndroidAppRuntime.create(this)`，与 Compose 共用 |
| 权限/状态缓存行为变化 | 端口只委托原静态实现，不改 TTL/指纹 |

回滚：WP4 独立 commit；保留原静态调用在 git 历史；新增端口可整体删除。

## 7. 子 Agent 编排

| 步骤 | 执行者 | 白名单 |
|---|---|---|
| 计划审核 | 只读 reviewer | 无 |
| 端口/Android 适配器/测试 | 实现子 Agent（可选） | `application/runtime/*`、`AndroidAppRuntime.java`、`AppRuntimeTest.java` |
| UI 迁移 | 主 Agent 串行 | StateHolder/Screen/MainActivity/EditSection |
| 门禁/文档 | 主 Agent | `check-layering.sh`、docs、CHANGELOG |

同一 working tree 不允许并行写；子 Agent 只改白名单；主 Agent 审 diff + 跑门禁。

## 8. 完成定义

1. UI 不再 import `AgentService` / `PermissionProbe` / `StatusCache` / `ConfigSync`。
2. 三个 StateHolder 不再需要 `Context`（TokenScreen 等仍需平台 Context 的页面除外）。
3. 分层基线对应条目清零；全量门禁/构建/APK 通过。
4. 真机 P0 通过或明确记录未验项。
5. 文档与执行记录更新。

## 9. 自审清单

| # | 检查项 | 结论 |
|---|---|---|
| R1 | 是否把平台 UI 能力误当业务服务 | 通过：剪贴板/Intent/包信息/主题保留 |
| R2 | 端口是否只是空转发 | 通过：Agent/权限/缓存/导入/监听都有真实 Android 实现 |
| R3 | StateHolder 去 Context 是否可行 | 待实现核对：Model/Xiaoai/Home 的 Context 仅用于上述 4 类 |
| R4 | Activity 重建后 runtime 是否稳定 | 通过：applicationContext + remember |
| R5 | 旧 Java 入口是否被遗漏 | 通过：EditSection 注入 importer；ConfigActivity 构造传入 |
| R6 | 是否改变 TTL/指纹/权限语义 | 通过：委托原实现 |
| R7 | 门禁是否可执行 | 通过：check-layering 目录规则，迁移后基线清零 |
| R8 | 回滚是否可行 | 通过：独立 commit，新增类可删除 |
| R9 | 是否与 WP5 冲突 | 通过：runtime 在 application 层，物理拆模块时可直接搬 |
| R10 | 真机清单是否覆盖 | 通过：权限/状态/8770/导入/主题/watcher |

> 审核结论：**通过，可执行**。
> 审核人：主 Agent（基于 UI 调用面与 4 个 Android 工具 API 复核）。