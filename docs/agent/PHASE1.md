# Phase 1 工作包：Action Plane 骨架与第一批动作

> 状态：**进行中**
> 前置：`docs/agent/PHASE0-REPORT.md`（已完成）
> 目标：App 前台服务 + 8770 + ActionRegistry + 5 个动作
> 日期：2026-11-06

## 1. 范围

| WP | 内容 | 依赖 | 规模 |
|---|---|---|---|
| WP1.1 | 纯 JVM ActionRegistry / ActionDescriptor / 错误码 / capabilities | Phase 0 | M（**已完成**；完成时 796，当前 969） |
| WP1.2 | App 前台服务 + 8770 + MCP 路由 + 5 个动作 | WP1.1 | L（**WP1.2a/WP1.2b 已完成**：服务/8770/5 个动作/root 兜底） |
| WP1.3 | 确认/幂等/审计/notify/sessionId | WP1.2 | M |

WP1.1 先做，保证契约和单测；WP1.2 再接 Android/root；WP1.3 补 Agent 体验。

### WP1.2b 完成记录（2026-11-06）

- `AndroidExecutor` 补齐其余 4 个动作：
  - `app.open`：`package` 走 `getLaunchIntentForPackage`，`intentUri` 走 `Intent.parseUri`，
    统一加 `FLAG_ACTIVITY_NEW_TASK`；未安装/无启动入口/无 Activity → `NOT_AVAILABLE`，
    后台启动限制 → `PERMISSION_DENIED` 或 warnings 说明“无法确认界面是否显示”。
    输出 `launched` / `package` / `component`（解析不到组件时留空）。
  - `alarm.set`：`AlarmClock.ACTION_SET_ALARM` + `EXTRA_HOUR/MINUTES/SKIP_UI/MESSAGE`；
    无时钟 App → `NOT_AVAILABLE`；`skipUi` 无法确认时如实返回 `skippedUi=false` 并 warning。
  - `notification.send`：渠道 `agent_action`（默认重要性，按需创建）；`POST_NOTIFICATIONS`
    或系统通知关闭 → `PERMISSION_DENIED`；`id` 相同覆盖同一条通知；输出 `notificationId`。
  - `media.control`：`volume` 读写 `STREAM_MUSIC`（0~100 映射系统档位）；
    `play/pause/next/prev` 用 `AudioManager.dispatchMediaKeyEvent`，无活跃媒体会话
    或系统不允许 → `NOT_AVAILABLE`，不伪造成功。
- 新增 root 兜底：
  - `RootCommands`（纯 JVM）：固定命令模板 + 包名白名单 `[A-Za-z0-9._]+`（≤200）、
    3s 超时、输出 1KB 截断、`TIMEOUT`/`PERMISSION_DENIED`/`NOT_AVAILABLE` 映射。
  - `RootExecutor`：只用 `su -c` 执行 `monkey -p <pkg> ... 1` 与
    `cmd media_session dispatch play|pause|next|previous`；su 串行化；授权状态缓存，
    `available()` 不阻塞主线程、服务启动不弹 su，首次真正执行时才申请。
  - `FallbackExecutor`（纯 JVM）：`android+root`；**仅** android 返回
    `NOT_AVAILABLE` 才回退 root；成功/`PERMISSION_DENIED`/`TIMEOUT`/`INTERNAL` 不重试，
    避免重复副作用。
- `Actions`：`app.open` 偏好 `android+root/android/root`；`media.control` 偏好
  `android+root/android/shizuku/root`；4 个动作补 `maxLength`（package/intentUri/
  title/text/id/label）。
- `AgentService` 注册全部 5 个动作：device.info/alarm.set/notification.send → android；
  app.open/media.control → `android+root`。没有执行器的动作在 capabilities 显示
  `available=false`。
- 纯 JVM 新增 `AgentActionExecutorsTest` 118 条断言（能力表、Fallback 策略、root 命令
  构造/包名注入/超时映射、4 个动作参数边界）；总断言 967。
- `./tests/run.sh`、`SDK=... ./build.sh`、`scripts/check-consistency.sh` 全绿。
- **真机未验证**：本轮 adb 设备不可达（192.168.0.112:5555 offline，USB 无设备），
  以下项留待设备恢复后补测：app.open 前台/锁屏/后台限制；alarm.set 真实闹钟
  （验证后删除）与 `skipUi`；notification.send 权限通过与拒绝；media.control
  有/无媒体会话、音量读写；8770 `xiaoai_action` 端到端；root 授权/拒绝两条路径。
- 未做（WP1.3）：confirm token 签发/校验、幂等、审计、`xiaoai_notify`、LAN、
  Shizuku、alarm/notification 的 root 路径、`xiaoai_ask` 代理。

### WP1.2a 完成记录

- 新增 `AgentMcp`（纯 JVM JSON-RPC 路由：initialize / ping / tools/list / tools/call，
  工具 `xiaoai_capabilities` / `xiaoai_action`）。
- 新增 `AgentHttpServer`：只绑 `127.0.0.1:8770`，`POST /mcp`（Bearer）、`GET /health`；
  accept 单线程 + 请求有界池（2/4/16），通知回 202。
- 新增 `AgentService`（前台服务，渠道 `agent_service`）+ `AgentBootReceiver`（BOOT_COMPLETED）。
  开关复用 `mcpEnabled` + `mcpToken`，不新增设置页字段。
- 新增 `AndroidExecutor`，只实现 `device.info`（型号/厂商/Android/SDK/电量/充电/网络/uptime，
  支持 `fields` 过滤）；其余 4 个动作在能力表显示不可用。
- 纯 JVM `AgentMcpTest` 55 条断言；WP1.2a 时总断言 851。
- 未做：其余 4 个动作、confirm/幂等/审计/notify、LAN、xiaoai_ask 代理（WP1.2b/1.3）。

### WP1.1 完成记录

- 新增 `plus.xiaoi.module.agent`：ActionDescriptor / ActionRequest / ActionResult /
  ActionRegistry / AgentError / Actions / AgentValues。
- 5 个动作描述已注册；`requiredCapability` 作为执行器准入条件（root 动作不能用 android 顶替）。
- 单测 132 条；WP1.1 完成时 796，WP1.2a 后 851，WP1.2b 后 969；`build.sh`、`check-consistency.sh` 通过。
- 未实现 Android/Root 执行器、8770 服务、confirm 签发/幂等/审计；属于 WP1.2/1.3。

## 2. WP1.1：纯 JVM 契约层

> 状态：**已完成**（2026-11-06）
> 代码：`app/src/main/java/plus/xiaoi/module/agent/`
> 测试：`tests/src/plus/xiaoi/module/AgentActionRegistryTest.java`（126 断言）
> 验证：`./tests/run.sh`、`build.sh`、`scripts/check-consistency.sh` 全绿
> 未做：真实 Android/root 执行、confirm token 校验/签发、幂等存储、审计落库（留给 WP1.2/WP1.3）

### 目标

不依赖 Android，先定义稳定的动作描述、注册表、参数校验、能力清单和错误码。
后续 Android/root executor 只实现接口，不再改协议。

### 新增

```text
app/src/main/java/plus/xiaoi/module/agent/
├── ActionDescriptor.java
├── ActionRequest.java
├── ActionResult.java
├── ActionRegistry.java      # 含 capabilitiesJson / 参数校验 / 执行流水线
├── ActionExecutor.java
├── ActionRisk.java
├── AgentError.java
├── Actions.java             # 5 个内置动作描述
└── AgentValues.java         # 不可变集合拷贝（包内辅助）
```

### ActionDescriptor

字段：

- `name`（如 `app.open`）
- `version`
- `description`
- `inputSchema` / `outputSchema`（JSON 字符串或 Map）
- `executorPreference`（`android` / `shizuku` / `root`）
- `requiredCapability`（`none` / `shell` / `root`）
- `risk`（`safe` / `confirm` / `danger`）
- `idempotent`
- `timeoutMs`
- `examples`

### ActionExecutor

```java
public interface ActionExecutor {
    String name();                 // android / shizuku / root
    boolean available();           // 当前设备/权限是否可用
    ActionResult execute(ActionRequest req) throws Exception;
}
```

WP1.1 只提供接口和 fake executor，用于测试；Android/root 实现在 WP1.2。

### ActionRegistry

- `register(ActionDescriptor, ActionExecutor)`
- `find(name)`
- `list()`
- `capabilitiesJson(boolean includeUnavailable)`
- 校验：
  - 未知动作 → `UNKNOWN_ACTION`
  - 参数缺失/类型不符 → `INVALID_ARGUMENT`
  - executor 不可用 → `PERMISSION_DENIED` / `NOT_AVAILABLE`
  - `dryRun` → 返回计划，不执行
  - `risk=confirm` → 返回 `NEEDS_CONFIRMATION`（WP1.3 接确认）
  - 超时/异常 → `TIMEOUT` / `INTERNAL`

### 第一批动作描述

只注册描述，不实现 Android 执行：

- `app.open`：`package` / `intentUri`
- `device.info`：`fields?`
- `alarm.set`：`hour`、`minute`、`label?`、`skipUi?`
- `notification.send`：`title`、`text`、`id?`
- `media.control`：`command`、`volume?`

### 测试

新增 `AgentActionRegistryTest`：

- 5 个动作都能列出
- capabilities JSON 含 schema/risk/availability
- 未知动作、缺参、类型错
- dryRun 不调用 executor
- executor 不可用
- 超时/异常映射
- 幂等键透传（先只校验字段）

### 约束

- 纯 JVM，不能 import android。
- 使用现有 `Json` 工具或 `org.json`（测试可用）。
- 不改现有 MCP 工具/协议。
- Java 1.8；无第三方依赖。
- `./tests/run.sh`、`build.sh`、`check-consistency.sh` 全绿。

## 3. WP1.2：App 前台服务与动作

> 状态：**进行中**；WP1.2a（服务 + 8770 + `device.info`）与 WP1.2b（其余 4 个动作 + root 兜底）
> 已完成；WP1.3 能力（confirm/幂等/审计/notify）未做。
> 代码：`app/src/main/java/plus/xiaoi/module/agent/`；测试：`tests/src/.../AgentMcpTest.java`、
> `AgentActionExecutorsTest.java`
> 验证：单测、`build.sh`、`check-consistency.sh`、真机 8770/鉴权/device.info 已通过；
> WP1.2b 的真机动作验证待设备恢复（见上）。

### 目标

App 进程内 `ActionService` 监听 `8770`，暴露 MCP：

- `xiaoai_capabilities`
- `xiaoai_action`
- `xiaoai_notify`
- `xiaoai_confirm`（可先占位）

### 实现

- 前台服务：`ActionService`，`startForeground`，通知渠道 `agent_service`。
- 开机自启：`BootReceiver` + `RECEIVE_BOOT_COMPLETED`；用户停止后不自动恢复。
- 权限：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`。
- 8770：默认 loopback；token 复用 `mcpToken`；LAN 开关默认关。
- `AndroidExecutor`：
  - `app.open`：`getLaunchIntentForPackage` + `FLAG_ACTIVITY_NEW_TASK`
  - `device.info`：Build/BatteryManager/ConnectivityManager
  - `alarm.set`：`AlarmClock.ACTION_SET_ALARM`（`skipUi` 兼容性待验证）
  - `notification.send`：NotificationManager + channel
  - `media.control`：AudioManager 音量；播放控制走 Shizuku/root 或后续
- `RootExecutor`：只用于 settings/screenshot/memory（WP1.2 可先留接口）。
- `xiaoai_notify`：island 优先，通过 8771 `xiaoai_island` 代理；失败降级普通通知。
- 设置页开关：`agent_plane`（默认关；开启后启动服务）。
- 单 API key：复用 `mcpToken`；启动时校验非空。

### 验收

- 小爱未运行时 8770 可连，`xiaoai_capabilities` 返回 5 个动作（按权限显示）。
- 5 个动作在真机成功/失败路径明确。
- 重启手机后服务自启（已允许自启动）。
- 用户停止后不自启。
- 无 root 时动作返回 `PERMISSION_DENIED`。
- 8770 错 token 401；LAN 关闭时非 loopback 拒绝。

## 4. WP1.3：Agent 体验

- `xiaoai_confirm` + confirm token（绑定 clientId/action/参数 hash，一次性，60s）。
- `xiaoai_action` 的 `idempotencyKey`（24h）。
- 审计日志（动作、参数摘要、executor、结果、耗时；不含正文/密钥）。
- `xiaoai_ask` 代理 8771，返回 `sessionId`；处理 `SESSION_BUSY`。
- `xiaoai_job_status`（长动作异步）。
- 结构化错误码统一。

## 5. 测试与发布

- 纯 JVM：WP1.1 单测；WP1.3 确认/幂等/审计。
- 真机：5 动作、锁屏、无 root、root、Shizuku、小爱未运行、开机自启。
- 发布：先 `0.13.0-agent.1` beta；`agent_plane` 默认关，开启后验证。
- 文档/skill：更新 `skill/xiaoi-mcp`、新增 `skill/xiaoi-agent`。

## 6. 明确不做

- 不做 ASR/TTS/唤醒词。
- 不做支付/账号/风控。
- 不做任意 shell/CLI 透传。
- 不改 8771 现有工具行为（只加代理）。
- 不在 WP1 做小爱记忆写入（Phase 5，实验）。
