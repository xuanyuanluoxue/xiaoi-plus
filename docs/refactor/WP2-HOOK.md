# WP2：Hook 策略抽取与进程宿主（执行计划）

> 状态：**已完成（2026-11-17）**
> 基线：`refactor/layering` @ `d4d3d4a`（WP0/WP1a/WP1b 已完成）
> 日期：2026-11-17
> 父计划：[LAYERING.md](LAYERING.md) · 相关：[architecture.md](../architecture.md)

## 1. 现实核对（先纠正假设）

开工前重新读了 `Hook.java` / `Bridge.java` / `BridgeControlClient.java`，实际状态与父计划里的
“Hook 翻译器”假设有差异：

| 项 | 实际 |
|---|---|
| `qm.j.buildResponsesRequest` hook | **空实现**，只挂点、不改写 |
| `qm.i.buildOpenAIRequest` hook | **空实现**，同上 |
| `hookResponsePrefix` | 方法存在但**从未调用**（0.15 已知问题：多进程重复注入） |
| `routeByImage` / 图片检测 | **死代码**：第一行 `if (true) return false`，已被 `supportsVision` 取代 |
| `captureOriginalModel` / `toJsonElement` | 仅服务上述死分支，无其他调用者 |
| 真正在跑的改写 | `sm.a` getter（baseUrl/apiKey/model）、`sm.f.getSupportsVision`、`sm.a.getModelProviderId`、模型调用计数 |
| 中心环 | `Hook → Bridge → BridgeControlClient → Hook/Bridge`；`Bridge ↔ BridgeLifecycle` |

因此 WP2 **不再写“请求体翻译器”**（没有请求体改写可抽），改为：

1. **纯策略抽取**：把 getter 覆盖 / vision / provider-id / 计数信号抽成可测纯函数。
2. **删除死代码**：图片回退分支与只服务它的状态字段。
3. **进程宿主端口**：切断 `Hook ↔ Bridge ↔ BridgeControlClient` 的编译期环。
4. **门禁**：新增“Hook 不得 import Bridge、控制面客户端不得 import Hook/Bridge”的检查。

`Bridge ↔ BridgeLifecycle` 保留：它是“运行时门面 + 生命周期状态机”的适配对，不是领域环；
WP3 再把生命周期编排收进 `BridgeService`。

## 2. 目标

### 2.1 目标结构

```
Hook（Xposed 入站适配器）
  │ 只依赖 Config / Usage / Logs / XiaoiProcessHost
  ▼
XiaoiProcessHost（端口，root 包）
  ├─ onProcessStart(config, classLoader)
  ├─ onFacadeReady(classLoader, facade)
  ├─ onConfigReload(config)
  ├─ updateToken(token)
  ├─ updateAccess(lan, bridge)
  ├─ showIsland(text, force)          // force=true 供控制面按钮，不受 showOnce 限制
  ├─ port()
  ├─ isBoundLan()
  ├─ versionName()
  └─ appContext()                     // Object，避免纯 JVM 端口依赖 android.content.Context
  ▲
  │ 实现
XiaoiProcessHostImpl（root 组装）
  ├─ Bridge.onProcessStart / install / updateToken / updateAccess / port / ...
  ├─ Island.show(text, force)
  └─ ConfigReloader（由 Hook 注入，只回调 applyConfig）

BridgeControlClient（控制面客户端）
  └─ 只依赖 XiaoiProcessHost (sHost) + Config/Logs/Json
```

### 2.2 依赖规则（新增门禁）

| 规则 | 路径/文件 | 禁止 |
|---|---|---|
| H1 | `app/src/main/java/plus/xiaoi/module/Hook.java` | 代码中出现 `Bridge.` / `Island.` 或对应 import |
| H2 | `app/src/main/java/plus/xiaoi/module/bridge/BridgeControlClient.java` | 代码中出现 `Hook.` / `Bridge.` / `Island.` 或对应 import |
| H3 | `bridge/BridgeLifecycle.java` | 仍允许 `Bridge`（本 WP 不处理） |

H1/H2 用 `check-consistency.sh` 的定点 grep 实现。注意：

- `Hook` 与 `Bridge` 同包，**本来就没有 import**，必须检查使用点（`Bridge.` / `Island.`），
  并且路径要写全（cwd 是仓库根）。
- 先剥掉 `//` 行注释再匹配，避免注释误伤。
- 同时匹配全限定名 `plus.xiaoi.module.Bridge`。
- grep 无匹配返回 1，脚本 `set -uo pipefail` 无 `-e`；必须用 `! grep ...` 或显式判空，
  不能依赖 grep 失败退出码。
- 实施后做一次负例：临时在 `Hook.java` 加 `Bridge.port();`，门禁必须失败，然后删除。

### 2.3 非目标

- 不恢复响应前缀 hook。
- 不改 hook 目标、安装顺序、版本白名单、`enabled=false` 行为。
- 不改 8771/8770 协议、配置格式、UI。
- 不改 `Bridge ↔ BridgeLifecycle`（WP3）。
- 不引入新依赖。

## 3. 纯策略抽取

新增 `core/hook/`（纯 JVM，无 android）：

### 3.1 `XiaoiOverridePolicy`

```java
public final class XiaoiOverridePolicy {
    /** 启用且配置非空才覆盖；否则返回 null，hook 不 setResult。 */
    public static String override(boolean enabled, String configured);
    public static boolean supportsVision(boolean enabled);
    /** 启用 + marker 才声明 provider id，否则返回 null。 */
    public static String providerId(boolean enabled, boolean marker, String id);
}
```

Hook 用法：

- `hookStr`：`String v = XiaoiOverridePolicy.override(c.enabled(), c.baseUrl()); if (v != null) param.setResult(v);`
- `installVisionHook`：`param.setResult(XiaoiOverridePolicy.supportsVision(Config.current().enabled()))`
  改为仅当返回 true 时 setResult（避免暂停态把 false 覆盖成 false，行为等价）。
- `hookProviderId`：`String id = XiaoiOverridePolicy.providerId(...); if (id != null) param.setResult(id);`

### 3.2 `UsageSignal`

```java
public final class UsageSignal {
    /** messages/tools 任一为 null 按 0；tools 非空或消息数 ≥6 视为主对话。 */
    public static boolean mainCall(int messageCount, int toolCount);
    public static String modelOrFallback(String reported, String fallback);
}
```

`recordLlmCall` 只做反射取值，判定走 `UsageSignal`；`UsageCounter.mainCall` 保留为兼容实现，
`UsageSignal` 内部委托它，避免口径分叉。

## 4. 进程宿主

### 4.1 新增文件（root 包）

| 文件 | 职责 |
|---|---|
| `XiaoiProcessHost.java` | 端口接口；引用 `Config`/`ClassLoader`/基本类型，`appContext()` 返回 `Object` |
| `XiaoiProcess.java` | 进程内单例持有者：`install(host)` / `host()` / `installIfAbsent(host)` |
| `XiaoiProcessHostImpl.java` | 组装实现：委托 Bridge/Island；`ConfigReloader` 回调由 Hook 提供 |

`ConfigReloader` 作为 `XiaoiProcessHostImpl` 的内部接口，避免实现直接 import Hook：

```java
public interface ConfigReloader {
    void reload(Config config);
}
```

### 4.2 Hook 改动

- 新增 `private static XiaoiProcessHost host()`：首次调用时
  `XiaoiProcess.installIfAbsent(new XiaoiProcessHostImpl(new ConfigReloader() { ... }))` 并返回。
  按项目现有风格用匿名类，不使用 lambda。
- `handleLoadPackage`：
  - `sConfig = Config.current();`
  - `host().onProcessStart(sConfig, lp.classLoader);`（替换 `Bridge.onProcessStart`）
- `applyConfig`：`host().updateToken(c.token());`（替换 `Bridge.updateToken`）
- `hookFacade`：`host().onFacadeReady(cl, param.thisObject);`（替换 `Bridge.install`）
- `hookProviderId`：`host().showIsland(c.model(), false);`（替换 `Island.showOnce`）
- 删除 import `Bridge` / `Island`。
- 删除死代码：
  - `routeByImage`、`hasResponseImage`、`hasChatImage`、`isImagePart`
  - `captureOriginalModel`（当前仅由 `hookStr` MODEL 分支调用，但结果只被死分支 `routeByImage` 使用）、`toJsonElement`
  - 字段 `sOriginalModel`、`sImageLogged`、`sTextLogged`
  - `hookStr` 里对 `captureOriginalModel` 的调用
- **保留**：`hookRequestBuilder` / `hookChatRequestBuilder`（空注入点，后续版本可能用）、
  `hookResponsePrefix` 及其状态（已禁用，不在本 WP 删）。

### 4.3 Bridge 改动

- 新增 `private static volatile XiaoiProcessHost sHost;`
- `install(ClassLoader, Object)` 保留，委托 `install(cl, facade, XiaoiProcess.host())`。
- 新增 `install(ClassLoader, Object, XiaoiProcessHost)`：保存 host 后走原流程。
- `onProcessStart(Config, ClassLoader)` 保留，委托新重载并取 `XiaoiProcess.host()`。
- 新增 `onProcessStart(Config, ClassLoader, XiaoiProcessHost)`：先 `sHost = host`，再走原流程。
- `startControlPlane()` → `BridgeControlClient.start(appContext(), sHost)`。
- `writeConfig(Mcp.Snapshot)`：保持现有顺序 **落盘 → 更新内存 → 热更新 → 重算 access**：
  ```java
  XiaoiProcessHost host = sHost;
  if (host == null) return false;              // 先判空，避免只写了一半
  if (!writeTargetPrefs(c)) return false;
  Config.setCurrent(c);
  host.onConfigReload(c);
  updateAccess(cur.lan(), cur.bridge());       // 仍用旧 cur 的 lan/bridge
  return true;
  ```
  删除 `Hook.applyConfig` 与 `import Hook`。

### 4.4 BridgeControlClient 改动

- 新增 `private static volatile XiaoiProcessHost sHost;`
- `start(Context)` 保留，委托 `start(ctx, XiaoiProcess.host())`。
- 新增 `start(Context, XiaoiProcessHost)`：保存 host 后走原流程。
- 全部 `Bridge.*` 替换为 `sHost.*`：
  - `Bridge.port()` → `sHost.port()`
  - `Bridge.isBoundLan()` → `sHost.isBoundLan()`
  - `Bridge.versionName()` → `sHost.versionName()`
  - `Bridge.appContext()` → `(Context) sHost.appContext()`（try/catch，失败按 null）
  - `Island.show(model, true)` → `sHost.showIsland(model, true)`（replace `Island` import）
- `applyIfChanged`：`sHost.onConfigReload(c); sHost.updateAccess(c.lan(), c.bridge());`
- 删除 import `Bridge` / `Hook` / `Island`。
- 若 `sHost == null`（极端早期/错误接线）：`start` 只记日志并返回，不发请求；
  `applyIfChanged` 记日志并跳过，不伪造成功。

## 5. 测试

### 5.1 纯 JVM

新增 `tests/src/plus/xiaoi/module/XiaoiHookPolicyTest.java`：

- override：enabled=false → null；空串 → null；正常 → 原值。
- vision：enabled=false → false；true → true。
- providerId：disabled / marker=false → null；正常 → id。
- UsageSignal：tools 非空 → 主对话；messages>=6 → 主对话；其余 → 子调用；
  reported model 为空时回退 fallback。

> 不新增 `XiaoiProcessTest`：`XiaoiProcessHost` 仍引用 `Config`（Android），
> `tests/run.sh` 没有 android.jar；宿主接线由 Gradle 编译 + 真机验证。
> 策略类加入 `tests/run.sh` 固定清单，并在 `TestMain` 注册。

### 5.2 门禁

在 `check-consistency.sh` 增加：

```bash
# H1: Hook 不依赖 Bridge/Island
if grep -qE '^import plus\.xiaoi\.module\.(Bridge|Island);' Hook.java; then fail; fi
# H2: 控制面客户端不依赖 Hook/Bridge
if grep -qE '^import plus\.xiaoi\.module\.(Hook|Bridge);' bridge/BridgeControlClient.java; then fail; fi
```

### 5.3 回归

- `tests/run.sh`、`check-consistency.sh`、`check-layering --strict`、`assembleRelease`、`verify-apk`。
- 真机 P0：
  - 模型重定向仍生效（第三方模型回复）；
  - 暂停后回到官方模型；
  - provider id 请求头仍为 `xiaoi-plus`；
  - 图片请求不再被占位文本替换；
  - 控制面拉配置 / reload 正常；
  - `/mcp xiaoai_switch_model` 后 hook 热更新仍生效；
  - 控制面「显示超级岛」连续点击两次都生效（`force=true` 语义）；
  - 控制面实例信息仍上报 port/bindHost/LAN/versionName；
  - `write_config` 后小爱 target prefs 已落盘（不只是内存热更新）。

## 6. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 宿主为 null 导致控制面不启动 | `installIfAbsent` 在 `handleLoadPackage` 首行调用；Bridge 保留旧重载兜底；null 只记日志不崩溃 |
| `XiaoiProcessHostImpl` 调用顺序变化 | 保持原调用点顺序；宿主只做委托 |
| 删除死代码误删仍在用的方法 | 删除前 grep 全仓库引用；仅删 0 引用 + 已确认死分支 |
| 控制面 `Bridge.isBoundLan` 替换为 host 后语义变化 | host 直接委托同一个 Bridge 方法；测试/真机核对绑定信息 |
| 编译期环未完全消失 | H1/H2 门禁；**明确残留**：`Bridge → BridgeControlClient` 单向保留，`Bridge ↔ BridgeLifecycle` 与 root↔bridge 包级依赖留给 WP3 |

回滚：WP2 独立 commit；保留旧 `install`/`onProcessStart`/`start` 重载，
删除新增类即可回到原路径。

## 7. 子 Agent 编排

| 步骤 | 执行者 | 文件白名单 |
|---|---|---|
| 计划审核 | 只读 reviewer（可选） | 无（不改文件） |
| 纯策略 + 测试 | 实现子 Agent（可选） | `core/hook/*`、`tests/src/XiaoiHookPolicyTest.java` |
| 宿主接线 | 主 Agent 串行 | `Hook.java`、`Bridge.java`、`BridgeControlClient.java`、`XiaoiProcess*` |
| 门禁/文档 | 主 Agent | `check-consistency.sh`、`docs/refactor/*`、`CHANGELOG.md` |

约束：同一 working tree 不允许两个写 Agent 并行；子 Agent 只改白名单文件；
主 Agent 审 diff 并跑全部门禁。

## 8. 完成定义

1. H1/H2 门禁通过；`Hook` 无 Bridge/Island import；`BridgeControlClient` 无 Hook/Bridge import。
2. 新纯策略测试全绿；全量 JVM 单测全绿。
3. `assembleRelease` + `verify-apk` 通过。
4. 真机 P0 清单通过或明确记录未验项。
5. 文档/CHANGELOG 更新，WP2 执行记录写回父计划。

## 9. 自审清单

| # | 检查项 | 结论 |
|---|---|---|
| R1 | 是否按真实代码修正了“请求翻译器”假设 | 通过：改为策略抽取 + 死代码清理 + 宿主 |
| R2 | 是否保留 hook 目标/顺序/版本白名单 | 通过：仅替换调用目标与删死代码 |
| R3 | 断环是否真的减少反向依赖 | 通过：Client 只依赖 host 接口，Bridge 不再 import Hook |
| R4 | null host 是否有安全降级 | 通过：只记日志、不崩溃、不发请求 |
| R5 | 是否误删响应前缀实现 | 通过：明确保留，仅删图片回退死分支 |
| R6 | 测试是否可桌面运行 | 通过：策略/持有者纯 JVM；宿主实现仅编译期验证 |
| R7 | 门禁是否可执行 | 通过：定点 grep + 现有脚本 |
| R8 | 风险是否有回滚 | 通过：保留旧重载 + 独立 commit |
| R9 | 是否与 WP3 冲突 | 通过：Bridge↔Lifecycle 明确留给 WP3 |
| R10 | 真机验证是否列全 | 通过：P0 六项 + 未验记录 |

> 审核结论：**通过，可执行（已按独立复核修订）**。
> 审核人：主 Agent + 独立 reviewer（只读）。
>
> 独立复核发现并已修订：
> 1. H1/H2 不能查 import（同包无 import），改为查使用点 + 全限定名 + 剥注释 + 全路径；
> 2. 端口补 `showIsland(text, force)`，控制面强制显示超级岛不降级；
> 3. 取消无法编译的 `XiaoiProcessTest`，只保留纯策略测试并明确测试清单/注册；
> 4. `writeConfig` 明确保留“落盘 → setCurrent → 热更新 → 旧 access”的顺序；
> 5. 明确残留 `Bridge → BridgeControlClient`、`Bridge ↔ BridgeLifecycle`、包级环，不宣称彻底无环；
> 6. 策略抽取保留 `Config.current() == null` 防护，vision 判断留在 afterHookedMethod 内；
> 7. 修正 `captureOriginalModel` 仍被 `hookStr` 调用的事实，删除时同步删调用点。

## 10. 执行记录（2026-11-17）

| 项 | 结果 |
|---|---|
| 纯策略 | 新增 `core/hook/XiaoiOverridePolicy`、`UsageSignal`；16 条断言 |
| 宿主 | 新增 `XiaoiProcessHost` / `XiaoiProcess` / `XiaoiProcessHostImpl` |
| Hook | 565 → 482 行；删除图片回退死代码与 3 个字段；不再引用 Bridge/Island |
| Bridge | 接收/保存 host；`writeConfig` 保留落盘顺序；不再引用 Hook |
| BridgeControlClient | 全部 Bridge/Hook/Island 调用改走 host；保留 force 岛语义 |
| 门禁 | H1/H2 双负例验证（临时注入 `Bridge.port()` / `Hook.applyConfig` 均失败） |
| 全量 | 单测/一致性/分层/assembleRelease/verify-apk 全绿 |
| 真机 | **未执行**（见 §5.3，需装 APK 后按 P0 清单验收） |

残留（明确保留，WP3 处理）：
- `Bridge → BridgeControlClient` 单向依赖仍在；
- `Bridge ↔ BridgeLifecycle` 双向依赖仍在；
- root 包 ↔ `bridge` 包存在包级双向依赖（host 接口仍在 root，且引用 `Config`）。