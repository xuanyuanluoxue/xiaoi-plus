---
name: xiaoi-agent
description: 通过 xiaoi-plus App 进程的 8770 动作面调用手机能力：设备信息、打开应用、闹钟、通知、媒体控制、确认 token、幂等、审计、超级岛提醒，以及 8771 小爱对话代理。其他 Agent 克隆仓库后按本 skill 使用。
---

# 用 xiaoi-plus 驱动手机（Agent Action Plane）

xiaoi-plus 现在有两层能力：

| 端点 | 位置 | 用途 |
|---|---|---|
| `8770` | App 进程 | **Agent 动作面**：device.info / app.open / alarm / notification / media / notify / ask / confirm / audit |
| `8771` | 小爱进程 | 旧版小爱 MCP：submit / status / config / logs / stats / island |

`8770` 不依赖小爱对话进程；`8771` 只在小爱运行时可用。两者共用同一个 `mcpToken`。

> 当前 8770 只监听 `127.0.0.1`。电脑上先建立隧道：
> `adb forward tcp:8770 tcp:8770`
> 之后用 `http://127.0.0.1:8770/mcp`。LAN 监听后续再开。

## 前置条件

1. 手机安装 xiaoi-plus，LSPosed 启用，作用域勾选「超级小爱」。
2. App 配置页：MCP 接口开启、令牌已设置、保存。
3. 打开一次 App（或开机自启）让 `AgentService` 启动。
4. token 来源：App「复制连接信息」或 `~/.config/xiaoi/token`。

## 探活

```bash
MCP="http://127.0.0.1:8770/mcp"
TOKEN="$(cat "$HOME/.config/xiaoi/token")"

curl --noproxy '*' -s -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8770/health
# ok agent=true port=8770
```

无 token / 错 token → 401。

## 工具

| 工具 | 用途 |
|---|---|
| `xiaoai_capabilities` | 列出动作、schema、risk、权限、当前可用性 |
| `xiaoai_action` | 执行动作（通用入口） |
| `xiaoai_confirm` | 批准/拒绝待确认动作 |
| `xiaoai_audit` | 读脱敏审计摘要 |
| `xiaoai_notify` | 超级岛提醒，失败降级普通通知 |
| `xiaoai_ask` | 代理 8771 小爱，返回 `text + sessionId` |

### 查看能力

```bash
curl --noproxy '*' -s -X POST "$MCP" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
       "name":"xiaoai_capabilities","arguments":{"includeUnavailable":true}}}'
```

返回 `result.content[0].text`，里面是 `{"actions":[...]}`。先看 `available`，不要盲调不可用动作。

### 执行动作

```bash
# 设备信息
{"name":"xiaoai_action","arguments":{
  "action":"device.info","args":{"fields":["model","battery","network"]}}}

# 打开应用
{"name":"xiaoai_action","arguments":{
  "action":"app.open","args":{"package":"com.tencent.mm"}}}

# 闹钟
{"name":"xiaoai_action","arguments":{
  "action":"alarm.set","args":{"hour":7,"minute":0,"label":"起床","skipUi":true}}}

# 通知（risk=confirm，需要确认）
{"name":"xiaoai_action","arguments":{
  "action":"notification.send","args":{"title":"Agent","text":"任务完成"}}}

# 媒体
{"name":"xiaoai_action","arguments":{
  "action":"media.control","args":{"command":"volume","volume":40}}}
```

### 危险动作确认

`notification.send` 等 `risk=confirm` 动作第一次调用不会执行，返回：

```json
{
  "ok": false,
  "code": "NEEDS_CONFIRMATION",
  "confirmToken": "<一次性 token>",
  "expiresInMs": 60000,
  "summary": {"action": "notification.send", "risk": "confirm", "argNames": ["title","text"]}
}
```

两种确认方式：

1. 带 token 再次调用同一动作：
```json
{"name":"xiaoai_action","arguments":{
  "action":"notification.send",
  "args":{"title":"Agent","text":"任务完成"},
  "confirmToken":"<token>"}}
```
2. 调 `xiaoai_confirm`：
```json
{"name":"xiaoai_confirm","arguments":{"confirmToken":"<token>","approve":true}}
```

- token 一次性、60 秒过期；重复使用返回 `CONFLICT`，过期返回 `CONFIRMATION_EXPIRED`。
- 拒绝：`approve=false`，返回 `CONFIRMATION_DENIED`。
- 超级岛只做提醒，不能批准危险动作。

### 幂等

写动作带 `idempotencyKey`：

```json
{"name":"xiaoai_action","arguments":{
  "action":"notification.send",
  "args":{"title":"Agent","text":"任务完成"},
  "idempotencyKey":"task-123-notify"}}
```

24 小时内同 key + 同参数返回首次结果，不重复执行；同 key 不同参数返回 `CONFLICT`。
当前幂等状态在内存中，App 服务重启会丢。

### 提醒（超级岛）

```json
{"name":"xiaoai_notify","arguments":{
  "channel":"island","text":"构建完成","force":true}}
```

- 超级岛约 6 秒，右侧大字约 22 字符，正文建议 ≤60 字；
- 小爱不在或岛失败 → 自动降级普通通知，返回 `fallback=true`；
- 两条都失败 → `NOT_AVAILABLE`。

### 代理小爱对话

```json
{"name":"xiaoai_ask","arguments":{
  "text":"帮我看一下今天天气",
  "context":"PC Agent 的本次上下文（可选，≤8KB）",
  "timeoutMs":120000}}
```

返回：

```json
{"ok":true,"text":"...","sessionId":"...","via":"xiaoi","timeoutMs":120000}
```

- 小爱不在线 → `XIAOI_OFFLINE`；
- 带 `sessionId` 可复用会话，但 CLI 连接态可能拒绝，失败返回 `CONFLICT`；
- `context` 只用于本次请求，手机不持久化；
- 不要让小爱执行短信/支付/删除等危险任务。

## 错误码

`INVALID_ARGUMENT`、`UNKNOWN_ACTION`、`NOT_AVAILABLE`、`PERMISSION_DENIED`、
`NEEDS_CONFIRMATION`、`CONFIRMATION_DENIED`、`CONFIRMATION_EXPIRED`、`TIMEOUT`、
`CONFLICT`、`XIAOI_OFFLINE`、`INTERNAL`、`BUSY`。

`result.isError=true` 时，文本里是 `ok=false` 的 JSON；用 `code` 判断。

## 安全边界

- 8770 当前只绑 loopback，必须 `adb forward`；不要把它暴露到公网。
- 一个 token 共用；不要提交到 Git。skill 复制带 token 时只放本机临时文件。
- 动作白名单；没有任意 shell。root 只用于固定的 `monkey` / `media_session` 命令。
- `risk=confirm/danger` 必须确认；`danger` 默认拒绝。
- 审计只记参数名/哈希，不记参数值、token、context。
- 记忆真相在 PC Agent；手机端不保存长期记忆。

## 故障排查

| 现象 | 处理 |
|---|---|
| 连接被拒 | App 未启动 / MCP 未开 / token 为空 / 没 `adb forward` |
| 401 | token 错误或已轮换 |
| 8770 不监听 | 打开 App；确认 MCP 开、token 非空、服务未被系统杀 |
| `NOT_AVAILABLE` | 动作未实现/设备不支持；看 capabilities |
| `PERMISSION_DENIED` | 缺通知权限或需要 root |
| `XIAOI_OFFLINE` | 8771 小爱不在线；`ask` 需要小爱，`action` 不需要 |
| `NEEDS_CONFIRMATION` | 先 `xiaoai_confirm(approve=true)` 或带 token 重试 |

先跑顺序：`/health` → `xiaoai_capabilities` → `xiaoai_action`。
动作 schema 与返回示例见 `references/actions.md`；旧版 8771 工具见 `skill/xiaoi-mcp/`。
