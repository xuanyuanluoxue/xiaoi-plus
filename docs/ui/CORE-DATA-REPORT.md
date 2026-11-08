# A3 · Core Data 报告（只读状态 / 探针）

> 分支：`feat/ui-core-data`（基于 main `8e1fc09`）
> 范围：P2 首页/接入页所需的数据与探针层；不含 Compose、不改旧 XML 页面、不改动作行为。
> 验收：`assembleDebug` 成功；`tests/run.sh` 全绿；`check-consistency.sh` 通过。

## 1. 新增 / 修改一览

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `agent/AgentRuntimeStatus.java` | 新增（纯 JVM） | 8770 只读运行快照 |
| `core/PermissionProbe.java` | 新增（Android） | 通知/精确闹钟/全屏/电池/usage/通知监听探针 |
| `core/IssueInputFactory.java` | 新增（纯 JVM） | 事实 → `IssueResolver.Input` |
| `core/ActivityHub.java` | 新增（纯 JVM） | App 级 50 条活动入口 |
| `core/BridgeStatus.java` | 修改（只增字段） | 8771 实际 bind/LAN/连接时间，保留旧 `set(...)` |
| `core/ControlKeys.java` | 修改（只增常量） | `bind_host` / `lan_enabled` 两个可选 Bundle key |
| `bridge/BridgeControlClient.java` | 修改（只增字段） | leader 注册/上报时附带实际 bind 结果 |
| `control/BridgeControlService.java` | 修改（接线） | leader 变更/生命周期/错误写入 `ActivityHub` |
| `agent/AgentService.java` | 修改（接线） | 启动/停止/失败更新 `AgentRuntimeStatus` + `ActivityHub` |
| `agent/AgentHttpServer.java` | 修改（一行） | 绑定失败时写 `AgentRuntimeStatus.lastError` |
| `tests/run.sh` | 修改 | javac 列表加入 3 个新纯 JVM 类（不含 `PermissionProbe`） |
| `tests/src/.../AgentRuntimeStatusTest.java` | 新增测试 | 启动/停止/错误/空快照 |
| `tests/src/.../IssueInputFactoryTest.java` | 新增测试 | READY/PAUSED/INVALID、弱令牌、8770 失败、权限 |
| `BridgeStatusTest` / `ActivityFeedTest` | 补断言 | 新字段默认未知/写入读取；ActivityHub 容量与顺序 |

未改：`MainActivity.kt`、`ui/**`、`app/build.gradle.kts`、`gradle/**`、`AndroidManifest.xml`、`res/**`、旧 XML 页面、`AndroidExecutor`/`Actions` 行为。

## 2. API 与字段语义

### 2.1 `AgentRuntimeStatus`（8770）

`Snapshot` 字段：

- `running`：当前是否由本进程成功启动监听。
- `port`：实际端口；未运行时 0。
- `bindHost`：实际 bind 地址；未知为空串（不猜 `127.0.0.1`）。
- `lanEnabled`：实际是否监听 `0.0.0.0`。
- `startedAt`：本次启动时间；同一实例重复上报保留原值。
- `lastError`：最近一次启动/绑定失败的单行摘要（最多 200 字）。
- `updatedAt`：最近一次事实写入时间。

静态入口：

- `setRunning(int port, String bindHost, boolean lanEnabled)`
- `setStopped()`
- `setError(String error)`
- `get()`（从不返回 null）
- `clear()`（回到“从未收到事实”，诊断/测试用）

未知规则：初始快照 `updatedAt=0`、`running=false`、`port=0`、`bindHost=""`、`lastError=""`；
调用方必须用 `known()` 区分“未知”与“已知停止”。

### 2.2 `BridgeStatus`（8771）

新增只读字段，旧 `set(7 参数)`/`get()`/`clear()` 签名与默认行为不变：

- `bindHost`：空串 = 未知（旧版桥未上报时不伪造 loopback）。
- `lanEnabled`：`null` = 未知；`TRUE/FALSE` = 实际是否绑 `0.0.0.0`。
- `lastConnectedAt`：本进程首次观察到 leader 的时间；0 = 未知。
- `lastDisconnectedAt`：最近一次 leader 断开时间；0 = 未知。

新增入口：

- `set(... , String bindHost, Boolean lanEnabled, long lastConnectedAt, long lastDisconnectedAt)`
- `clear(long lastDisconnectedAt)`

`port <= 0` 一律按断开处理，不保留旧端口/旧 bind 信息。

### 2.3 `PermissionProbe`

`PermissionProbe.capture(Context)` 返回 `PermissionSnapshot`：

| 项 | 数据源 | 低版本/异常 |
| --- | --- | --- |
| notifications | `NotificationManager.areNotificationsEnabled()` | UNKNOWN |
| exactAlarm | API 31+ `canScheduleExactAlarms()` | <31 GRANTED |
| fullScreenIntent | API 34+ `canUseFullScreenIntent()` | <34 UNKNOWN |
| batteryOptimization | `isIgnoringBatteryOptimizations()` | UNKNOWN |
| usageAccess | `AppOpsManager` + `OPSTR_GET_USAGE_STATS` | UNKNOWN |
| notificationListener | `enabled_notification_listeners` 按包名匹配 | UNKNOWN |

所有调用独立 try/catch；异常只降级为 `UNKNOWN`，不伪造 `GRANTED`，不申请权限。

### 2.4 `IssueInputFactory`

核心入口：

```java
IssueInputFactory.ConfigFacts facts =
        IssueInputFactory.ConfigFacts.from(appConfig, configValid, readOnly);

IssueResolver.Input in = IssueInputFactory.from(
        facts, BridgeStatus.get(), AgentRuntimeStatus.get(),
        PermissionProbe.capture(context), now);

List<IssueResolver.Issue> issues = IssueInputFactory.issues(
        facts, BridgeStatus.get(), AgentRuntimeStatus.get(),
        permissions, now);
```

`ConfigFacts` 显式承载：`configValid/readOnly/mcpEnabled/lanRequested`、服务商/模型/密钥/base/enabled、
`model/rev/token`、可选 `tokenStrength`、端点 `probe`。`ConfigFacts.from(AppConfig, ...)`
只做字段映射，不读文件。

派生规则：

- D1~D4：统一调用 `StateModel.resolve(...)`，不在本类改写判断。
- 8771：快照连接且新鲜 → `RUNNING`；连接过但超过 30s → `WAITING`；
  无快照时 MCP 开启 → `WAITING`，关闭 → `OFF`；不从 8770 推断。
- 8770：运行 → `RUNNING`；有 `lastError` → `FAILED`；已知停止 → `OFF`；
  从未收到事实且 MCP 开启 → `UNKNOWN`。
- 令牌：空白 → `NONE`；32 位十六进制 → `STRONG`；其它非空 → `WEAK`。

未知规则：null 快照/权限按“没有事实”处理；不读全局静态作为隐式输入（调用方显式传
`BridgeStatus.get()` / `AgentRuntimeStatus.get()`）。

### 2.5 `ActivityHub`

- 固定容量 50，内存环形缓冲；`info/warn/error(domain, text)`、`recent(n)`、`count()`、`clear()`。
- 写入前去除换行并截断 240 字；不输出密钥/令牌。
- `BridgeControlService`：创建/销毁、8771 leader 连接/断开/绑定地址变化、拒绝调用、
  读取配置异常、回调失效。
- `AgentService`：创建/停止、8770 启动成功/失败、MCP 关闭不启动、LAN 弱令牌回退说明。
- 未把 `ActivityFeed/ActivityHub` 放进 `BridgeStatus` 或 `StateModel`。

## 3. 测试

`./tests/run.sh`：全部通过，`0` 失败。

新增断言 **104 条**：

| 套件 | 新增 |
| --- | ---: |
| `AgentRuntimeStatusTest` | 36 |
| `IssueInputFactoryTest` | 41 |
| `BridgeStatusTest` | 19 |
| `ActivityFeedTest` | 8 |

覆盖点：8770 空快照/启动/重复上报/地址变化/停止/错误/清理；IssueInputFactory 的
READY/PAUSED/INVALID、弱令牌 + LAN、8770 FAILED、权限 DENIED、null 输入、陈旧 8771、
令牌强度、AppConfig 映射；BridgeStatus 新字段默认未知、扩展写入、断开/清理；ActivityHub
容量 50、顺序、级别/域、换行清理。

`PermissionProbe` 依赖 Android 类，未加入 `tests/run.sh`；其分支通过代码审查与
`assembleDebug` 验证。

## 4. 已知风险 / 边界

1. **旧版小爱进程不发送 bind 字段**：`BridgeStatus.bindHost=""`、`lanEnabled=null`。
   页面必须显示“未知”，不能回退成“仅本机”。
2. **8771 没有“绑定失败”事实源**：控制面只看到 leader 端口；桥未上报时只能记
   `WAITING`，不伪造 `FAILED`。8770 的 `FAILED` 来自 `AgentRuntimeStatus.lastError`。
3. **连接/断开时间**由 App 进程观察 leader 得到，不是桥侧 socket 时间；进程重启后归零。
4. **`AgentRuntimeStatus` 是进程内静态快照**：App 被杀后自然丢失，不会持久化旧状态。
5. **权限探针依赖 ROM 实现**：usage access / 全屏 Intent 在部分机型可能始终 UNKNOWN；
   这是刻意的降级，不制造假问题。
6. **活动事件不去重**：leader 频繁切换会产生多条；容量 50，旧事件自动淘汰。

## 5. 下游页面用法

- 首页：`StateModel` 的四维结果 + `IssueInputFactory.issues(...)` 取前 3 条；
  `verified()` 才是“已确认”，D2 OK 只能写“端点可用”。
- 接入页：8771 用 `BridgeStatus.Snapshot`（`port/bindHost/lanEnabled/lastConnectedAt`），
  8770 用 `AgentRuntimeStatus.Snapshot`；两行分开渲染，未知显示“—”。
- 诊断页：`PermissionProbe.capture(context)` 后按 `PermissionSnapshot.missing()` 展示；
  不要自行把 UNKNOWN 当 DENIED。
- 首页/日志页：`ActivityHub.recent(n)` 按旧→新渲染；只展示事实，不补写营销文案。
- 需要刷新时重新调用 `capture/get/from`，不要缓存成配置事实。

## 6. 验收记录

- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug --offline`：成功。
- `./tests/run.sh`：全部通过（新增 104 条断言）。
- `./scripts/check-consistency.sh`：通过（无裸线程/Toast/getIdentifier/死资源）。
- 未在真机改用户配置；未 push、未 merge。
