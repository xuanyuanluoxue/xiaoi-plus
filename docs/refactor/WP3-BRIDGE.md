# WP3：桥/动作应用服务与 Host 拆分（执行计划）

> 状态：**已完成（2026-11-17）**
> 基线：`refactor/layering` @ `6bc3c3b`（WP0/WP1/WP2 已完成）
> 日期：2026-11-17
> 父计划：[LAYERING.md](LAYERING.md) · 前置：[WP2-HOOK.md](WP2-HOOK.md)

## 1. 现实核对

| 项 | 实际 |
|---|---|
| `Mcp.Host` | 11 个方法，混了运行时状态、CLI、配置读写、超级岛、日志/统计/待办 |
| `Mcp.handle` / `toolsCall` | 两处都直接接收 god `Host` |
| `BridgeLifecycle` | 直接 import 并调用 `Bridge` 的 12+ 个静态方法，形成 `Bridge ↔ BridgeLifecycle` |
| 生命周期决策 | 决策树内联在 `BridgeLifecycle.tick()`，无纯 JVM 测试 |
| HTTP 路由/限流参数 | 内联在 `Bridge.handle`，日志/统计 clamp 也在内联 |
| `BridgeControlClient` | WP2 已改为只依赖 `XiaoiProcessHost` |
| 外部契约 | 8771 工具表/报文、45 项真机契约、端口/LAN/410 语义不可变 |

## 2. 目标

### 2.1 三个纯 JVM 决策点

```
application/bridge/BridgeService
  └─ next(State) → Decision{CLOSE|WAIT_FACADE|ACQUIRE_LEADER|REBIND|START|NONE}
     （把 BridgeLifecycle.tick 的决策树抽出来，可穷举测试）

core/bridge/BridgeHttpPolicy
  ├─ isLowTrust(path) / isLoopbackOnly(path) / isMcp(path) / isConfig(path)
  ├─ clampLogLimit(limit) / clampStatsDays(days)
  └─ 供 Bridge.handle 使用，不改 socket 行为

Mcp.Hosts（端口集合）
  ├─ RuntimePort：facadeReady / cliReady / port / versionName
  ├─ ConfigPort：config / writeConfig
  ├─ CliPort：callCli
  ├─ IslandPort：showIsland
  └─ TelemetryPort：logs / stats / createTodo / onToolCall
```

### 2.2 适配器

```
Bridge（Android 适配器）
  ├─ socket / Binder / 反射 / 线程池
  ├─ McpHostsImpl（实现 5 个端口）→ Mcp.Hosts
  ├─ Runtime（实现 bridge/BridgeRuntime）→ BridgeLifecycle
  └─ HTTP handle 使用 BridgeHttpPolicy + Mcp.Hosts

BridgeLifecycle（生命周期调度器）
  ├─ 只依赖 bridge/BridgeRuntime 接口 + LeaderElection + BridgeService
  └─ 不再 import Bridge
```

### 2.3 非目标

- 不改 8771 工具名、参数、错误串、工具表顺序。
- 不改 8771/8770 端口、Bearer、LAN 32 hex、`/config` 410、`/island` loopback。
- 不改控制面 Binder 协议、leader 选举/锁文件语义。
- 不改 UI/配置格式。
- 不把 `BridgeLifecycle` 的线程/定时器搬走（仍由适配器持有）。
- 不消除 root↔bridge 的**包级**依赖（`Config`/`Logs` 仍在 root）；只消除
  `Bridge ↔ BridgeLifecycle` 的类型级依赖。`BridgeControlClient ↔ Bridge` 已在 WP2 解决，
  本 WP 不再重复。
- 本 WP **不做** `StatusService`/四维状态组装，留给 WP4 或独立任务。

## 3. 接口设计

### 3.1 `bridge/BridgeRuntime`

```java
public interface BridgeRuntime {
    Context appContext();
    String processName();

    void refreshPolicyFromConfig();
    void ensureCliBinding();

    boolean shouldListen();
    boolean hasFacade();
    boolean hasCli();

    boolean isServerRunning();
    void stopServer();
    boolean isBoundLan();
    boolean bindsLan();

    boolean startServerIfLeader();
    int port();

    /** 控制面：兜底拉配置 / server 状态变化后上报。 */
    void controlRefresh();
    void controlOnBridgeStarted();
}
```

`Bridge` 内新增 `private static final BridgeRuntime RUNTIME = new ...`，全部委托原静态方法；
`BridgeLifecycle.start(appContext(), RUNTIME)`。`BridgeLifecycle` 不再 import `Bridge`。

### 3.2 `application/bridge/BridgeService`

```java
public final class BridgeService {
    public enum Action { CLOSE, WAIT_FACADE, ACQUIRE_LEADER, REBIND, START, NONE }

    public static final class State {
        public boolean shouldListen;
        public boolean hasFacade;
        public boolean hasCli;
        public boolean leader;
        public boolean facadeGraceElapsed;
        public boolean serverRunning;
        public boolean boundLan;
        public boolean bindsLan;
    }

    public static final class Decision {
        public final Action action;
        public final String reason;
    }

    public static Decision next(State s);
}
```

决策顺序（与现有 `tick()` 等价）：

1. `!shouldListen` → `CLOSE`（MCP 关闭或 token 为空）
2. `!hasFacade && !hasCli` → `CLOSE`（本进程没有 facade/CLI）
3. `!leader && !hasFacade && !facadeGraceElapsed` → `WAIT_FACADE`
4. `!leader` → `ACQUIRE_LEADER`
5. `serverRunning && boundLan != bindsLan` → `REBIND`
6. `!serverRunning` → `START`
7. 其余 → `NONE`

`BridgeLifecycle` 在 `ACQUIRE_LEADER` 成功后重算一次 `next()`，再执行 REBIND/START。
`ACQUIRE_LEADER` 的执行约定（与现有 tick 等价，必须写进实现）：

1. 若 `serverRunning`，先 `stopServer()`（非 leader 残留监听的兜底清理）；
2. `acquireIfPossible()` 失败 → `logAcquireFailure` 并 **return**，不得继续 REBIND/START；
3. 成功 → 清 `sLastAcquireError`、记录日志、置 `leader=true`，再重算 `next()`；
4. 重算若返回 `CLOSE`，按 CLOSE 处理（有意收窄：配置在本轮刚被关掉）。

`ensureLeader()` 仍在 `tick()` 内、决策之前执行；`sLeader` 未就绪直接 return，
不得把决策前移到锁初始化之前。

每轮副作用顺序保持现有语义，且在计算 State 之前执行：

```
controlRefresh() → refreshPolicyFromConfig() → ensureCliBinding() → 计算 State
```

`REBIND` 执行：`stopServer()` → `controlOnBridgeStarted()`（先上报 port=0）→ 下一轮 START
成功后再次 `controlOnBridgeStarted()`。

`apply(runtime)`：若 runtime 非空先更新静态引用；`sStarted=false` 时 `start(ctx, runtime)`。
`stop()` 在 runtime 为空时只释放锁/停调度器。`Bridge` 仍调用静态类（不改成实例），
因此 `updateToken/updateAccess` 后的 tick 时序不变。

### 3.3 `core/bridge/BridgeHttpPolicy`

```java
public final class BridgeHttpPolicy {
    public static boolean isLowTrust(String path);   // /health /island
    public static boolean isLoopbackOnly(String path); // /island
    public static boolean isMcp(String path);
    public static boolean isConfig(String path);
    public static int clampLogLimit(int limit);      // 1..300，非法回 80
    public static int clampStatsDays(int days);      // 1..90，非法回 7
}
```

`Bridge.handle` 用它替换内联判断；错误文案/状态码不变。

### 3.4 `Mcp.Hosts` 拆分

```java
public interface RuntimePort { boolean facadeReady(); boolean cliReady(); int port(); String versionName(); }
public interface ConfigPort { Snapshot config(); boolean writeConfig(Snapshot next); }
public interface CliPort { String callCli(String cmd); }
public interface IslandPort { boolean showIsland(String text, boolean force); }
public interface TelemetryPort {
    String logs(int limit, long since);
    String stats(int days);
    AppToolProtocol.Result createTodo(String title, String content);
    default void onToolCall(String tool, boolean ok, long elapsedMs, int argCount) {}
}

public static final class Hosts {
    public final RuntimePort runtime;
    public final ConfigPort config;
    public final CliPort cli;
    public final IslandPort island;
    public final TelemetryPort telemetry;
    public Hosts(RuntimePort r, ConfigPort c, CliPort cli, IslandPort i, TelemetryPort t);
}
```

- `Mcp.handle(String, Hosts)` / `Mcp.toolsCall(Map, Hosts)`。
- 删除 `Mcp.Host`（或保留 `@Deprecated` 兼容？本 WP 直接删除，调用点只有 Bridge/McpTest）。
- `Bridge` 内新增 `McpHostsImpl implements` 五个端口，并在 `Mcp.Hosts HOSTS` 中把同一实例填五次。
- `McpTest.FakeHost` 改为实现五个端口，并提供 `Mcp.Hosts hosts()`；测试调用改为 `h.hosts()`。

## 4. 文件清单

新增：

- `app/src/main/java/plus/xiaoi/module/bridge/BridgeRuntime.java`
- `app/src/main/java/plus/xiaoi/module/application/bridge/BridgeService.java`
- `app/src/main/java/plus/xiaoi/module/core/bridge/BridgeHttpPolicy.java`
- `tests/src/plus/xiaoi/module/BridgeServiceTest.java`
- `tests/src/plus/xiaoi/module/BridgeHttpPolicyTest.java`

修改：

- `Mcp.java`：拆端口；`handle/toolsCall` 改 `Hosts`
- `Bridge.java`：`McpHostsImpl` + `RUNTIME` + `BridgeHttpPolicy` + `Mcp.Hosts`
- `bridge/BridgeLifecycle.java`：只依赖 `BridgeRuntime` + `BridgeService`
- `tests/src/plus/xiaoi/module/McpTest.java`：FakeHost 实现五端口
- `tests/run.sh`、`TestMain.java`：加入新纯类/测试
- `scripts/check-consistency.sh`：H3（`BridgeLifecycle` 不得依赖 `Bridge`）
- `docs/refactor/LAYERING.md`、`docs/README.md`、`docs/refactor/README.md`、`CHANGELOG.md`

## 5. 测试

### 5.1 `BridgeServiceTest`（穷举决策树）

- 关闭 / 空 token → CLOSE
- 无 facade/CLI → CLOSE
- 非 leader + 无 facade + 宽限期内 → WAIT_FACADE（**即使 hasCli=true 也等待**，与现有 tick 一致）
- 非 leader + 无 facade + 宽限期外 → ACQUIRE_LEADER
- 非 leader + hasFacade=true → 直接 ACQUIRE_LEADER（无宽限）
- leader + 绑定模式变化 → REBIND
- leader + server 未运行 → START
- leader + server 已运行且模式一致 → NONE
- 非 leader 但有残留 server → 执行 ACQUIRE_LEADER 前先 stopServer（由生命周期测试或注释锁定）

### 5.2 `BridgeHttpPolicyTest`

- 低信任路径只含 `/health`、`/island`
- loopback-only 只含 `/island`
- `/mcp`、`/config`
- `clampLogLimit`：0/负数/301 → 80；1/300 保留
- `clampStatsDays`：0/负数/91 → 7；1/90 保留

### 5.3 `McpTest`

- 现有黄金串必须逐字节不变。
- FakeHost 五端口拆分后，工具行为/错误串/`onToolCall` 计数不变。

### 5.4 门禁

- H3：`BridgeLifecycle` 不得出现 `Bridge.`、`Bridge#`、全限定名或 static import；
  复用 WP2 的 `strip_line_comments`，并加 static import 正则。
- 现有 H1/H2 保持
- `check-layering --strict`
- `assembleRelease` + `verify-apk`
- 真机 P0：
  - 8771 `/health`（Bearer）、`/mcp` tools/list/call、`/logs`、`/stats`、`/cli`；
  - `/config` 410；`/island` 非 loopback → 403；`/health` 连续请求触发 429；
  - 弱 token + LAN → 回退 loopback；换 32 hex 后同端口 REBIND 到 0.0.0.0；
  - MCP 关闭/清空 token → 端口关闭、释放锁、控制面 port=0；
  - leader 接管：杀掉 leader 后 10~30s 新 leader 监听，旧端口释放，非 leader port=0；
  - 控制面 `register/reportState` 的 port/bindHost/LAN/versionName 与 HTTP `/health` 一致。

## 6. 风险与回滚

| 风险 | 缓解 |
|---|---|
| Mcp 端口拆分改变工具输出 | 保留全部逻辑，只改取值来源；McpTest 黄金串锁死 |
| 生命周期决策时序变化 | 纯 `BridgeService` 穷举现有分支；`tick` 仍按原顺序执行 |
| BridgeRuntime 空导致生命周期不启动 | `Bridge` 静态 `RUNTIME` 永远非空；`start` 只接受非空 runtime |
| 循环依赖未彻底消除 | 明确只消除类型级；包级 root↔bridge 保留并记录 |
| `/health`、`/island` 限流行为变化 | `BridgeHttpPolicy` 只做大写路径判定/数值 clamp，不改限流桶 |

回滚：WP3 独立 commit；保留 `Mcp.Host` 删除前的 git 历史；BridgeLifecycle 可回退到直接 static 调用。

## 7. 子 Agent 编排

| 步骤 | 执行者 | 白名单 |
|---|---|---|
| 计划审核 | 只读 reviewer | 无 |
| `BridgeService`/`BridgeHttpPolicy` + 测试 | 实现子 Agent（可选） | `application/bridge/*`、`core/bridge/*`、对应 tests |
| Mcp 端口拆分 | 主 Agent 串行 | `Mcp.java`、`Bridge.java`、`McpTest.java` |
| 生命周期端口化 | 主 Agent 串行 | `BridgeLifecycle.java`、`BridgeRuntime.java`、`Bridge.java` |
| 门禁/文档 | 主 Agent | `check-consistency.sh`、docs、CHANGELOG |

同一 working tree 不允许并行写；子 Agent 只改白名单；主 Agent 审 diff + 跑门禁。

## 8. 完成定义

1. `Mcp.Host` 不再存在；`Mcp` 只依赖 5 个小端口。
2. `BridgeLifecycle` 不 import `Bridge`；H3 通过。
3. `BridgeService` 决策树有穷举测试；现有 `tick` 顺序不变。
4. McpTest 黄金串全绿；45 项真机契约不变。
5. 单测/一致性/分层/构建/APK 全绿。
6. 文档与执行记录更新。

## 9. 自审清单

| # | 检查项 | 结论 |
|---|---|---|
| R1 | 是否保持 8771 报文/工具表逐字节不变 | 通过：只改取值来源，McpTest 黄金串 |
| R2 | 生命周期决策是否与现有 tick 等价 | 待核对：`hasFacade` 与 `hasCli` 的宽限期语义必须锁定 |
| R3 | 是否真正删除 god 接口 | 通过：删除 `Mcp.Host`，Bridge/McpTest 同步 |
| R4 | BridgeLifecycle 是否只依赖接口 | 通过：`BridgeRuntime` |
| R5 | 端口拆分是否会复制对象/状态 | 通过：Bridge 内同一实例填 5 个端口 |
| R6 | 包级环是否诚实标注 | 通过：只消除类型级，root↔bridge 保留 |
| R7 | 测试是否桌面可跑 | 通过：BridgeService/BridgeHttpPolicy 纯 JVM；McpTest 已有桩 |
| R8 | 回滚是否可行 | 通过：独立 commit；旧代码在 git 历史 |
| R9 | 是否触碰安全边界 | 通过：端口/LAN/410/UID/leader 锁均不改 |
| R10 | 是否遗漏 McpTest 调用点 | 通过：全仓库仅 Bridge/McpTest 使用 `Mcp.Host` |

> 审核结论：**通过（R2 需在实现时用测试锁定现有宽限期语义）**。
> 审核人：主 Agent（基于 Mcp/Bridge/BridgeLifecycle 全文复核）。

## 10. 复核修订（2026-11-17，独立 reviewer）

| 发现 | 修订 |
|---|---|
| 决策树丢失「非 leader 但 server 仍在跑」的兜底 | 明确 `ACQUIRE_LEADER` 执行前先 `stopServer()`，失败 return |
| 测试项写反了宽限期（hasCli 也等待） | 改为 WAIT_FACADE，并加 hasFacade=true 直接 acquire 的用例 |
| `apply/stop` 在 runtime 未注入时行为未定义 | 新增 `apply(runtime)`；`stop()` null 保护；Bridge 仍用静态类 |
| `ensureLeader` 早退未建模 | 明确仍在 tick 内、决策之前；`sLeader` 未就绪直接 return |
| ACQUIRE 失败路径未写 | 明确失败只记日志并 return，不进入 REBIND/START |
| 每轮副作用顺序未锁定 | 明确 `controlRefresh → refreshPolicy → ensureCliBinding → State` |
| H3 门禁可绕过 | 复用剥注释 + 使用点 + 全限定名 + static import |
| REBIND 二次上报未写明 | 明确 stop 后先报 port=0，START 成功再报 |
| BridgeHttpPolicy 大小写/顺序 | 明确精确 `equals`、限流判定仍在路由之前 |
| clampLogLimit 可能误接到 Mcp | 明确只用于 Bridge.handle；不改 Mcp 的 logs 参数 |
| WP3 范围虚高 | 移除 WP2 已完成的 Client↔Bridge；明确不做 StatusService |
| 真机 P0 不足 | 补 CLOSE/REBIND/429/403/leader 接管/端口上报对照 |

## 11. 执行记录（2026-11-17）

| 项 | 结果 |
|---|---|
| `Mcp.Host` | 删除；拆为 Runtime/Config/Cli/Island/Telemetry + `Mcp.Hosts` |
| `Bridge` | `McpHostsImpl` 同一实例填 5 端口；HTTP 策略走 `BridgeHttpPolicy` |
| `BridgeLifecycle` | 只依赖 `BridgeRuntime` + `BridgeService`；H3 负例验证 |
| `BridgeService` | 11 条决策树断言（含宽限期/残留 server/绑定模式） |
| `BridgeHttpPolicy` | 20 条路径/clamp 断言 |
| `McpTest` | 黄金串全绿；FakeHost 五端口复用同一实例 |
| 全量 | 单测/一致性/分层/assembleRelease/verify-apk 全绿 |
| 真机 | **未执行**（按 §5.4 P0 清单验收） |

残留（明确保留）：
- root↔bridge 包级依赖（`Config`/`Logs` 在 root）；
- `StatusService`/四维状态组装未在本 WP 实施，留给 WP4 或独立任务。