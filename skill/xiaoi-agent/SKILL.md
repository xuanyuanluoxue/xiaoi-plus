---
name: xiaoi-agent
description: 通过 xiaoi-plus App 进程的动作面调用手机能力：设备信息、打开应用、闹钟、通知、媒体控制、确认 token、幂等、审计、超级岛提醒，以及小爱对话代理。0.16.0 起动作面与桥端（8771）工具相同、各 15 个；动作面端口从 8770 起、被占用会顺延，以 /health 或设置页为准。其他 Agent 克隆仓库后按本 skill 使用。
---

# 用 xiaoi-plus 驱动手机（Agent Action Plane）

xiaoi-plus 现在有两层能力（**0.16.0 起是单端口统一**：两个端点互相补齐工具，
拿到的是同一套 15 个工具，配哪个都行）：

| 端点 | 位置 | 用途 |
|---|---|---|
| 动作面（`8770` 起，可能顺延） | App 进程 | **Agent 动作面**：device.info / app.open / alarm / notification / media / notify / ask / confirm / audit；也转发小爱的对话与运维工具 |
| `8771` | 小爱进程 | 小爱 MCP：submit / status / config / logs / stats / island；也转发 App 侧动作工具 |

动作面不依赖小爱对话进程；`8771` 只在小爱运行时可用。两者共用同一个 `mcpToken`。
**端口不是固定值**：动作面首选 `8770`，但该端口常被小爱自身的本地服务占用，此时会顺延到
`8772~8779`；以「接入 › 端口与服务」或 `/health` 的 `port=` 为准。

> 默认只监听 `127.0.0.1`。电脑上先建立隧道（把 `8770` 换成 `/health` 显示的实际端口）：
> `adb forward tcp:8770 tcp:8770` → `http://127.0.0.1:8770/mcp`。
> 「接入 › 令牌与局域网」可独立开启「Agent 动作面允许局域网访问」；同一 Wi-Fi 直接用
> `http://<手机IP>:<实际端口>/mcp`。默认要求 32 位十六进制 token，弱 token 会自动回退仅本机
> （App 里可显式打开「允许弱密码」/「免鉴权」，属测试便利选项，默认关闭）。

## 前置条件

1. 手机安装 xiaoi-plus，LSPosed 启用，作用域勾选「超级小爱」。
2. 「接入 › 令牌与局域网」：MCP 接口开启、令牌已设置、保存（旧版配置页仅为过渡入口）。
3. 打开一次 App（或开机自启）让 `AgentService` 启动。
4. token 来源：「接入 › 连接信息」的“复制连接信息”，或 `~/.config/xiaoi/token`。

## 探活

```bash
MCP="http://127.0.0.1:8770/mcp"        # 以 /health 或设置页显示的实际端口为准
TOKEN="$(cat "$HOME/.config/xiaoi/token")"

curl --noproxy '*' -s -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8770/health
# ok agent=true port=8770 bind=0.0.0.0 auth=token tools=6 version=0.16.0
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

- 动作面默认只绑 loopback；LAN 需在 App 内显式开启，且 token 默认必须是 32 位十六进制。
- LAN 打开后，同一 Wi-Fi 的设备凭 token 可执行设备动作；公共/公司网络建议关闭。
- 一个 token 共用；不要提交到 Git。skill 复制带 token 时只放本机临时文件。
- 动作白名单；没有任意 shell。root 只用于固定的 `monkey` / `media_session` 命令。
- `risk=confirm/danger` 必须确认；`danger` 默认拒绝。
- 审计只记参数名/哈希，不记参数值、token、context。
- 记忆真相在 PC Agent；手机端不保存长期记忆。

## 故障排查

| 现象 | 处理 |
|---|---|
| 连接被拒 | App 未启动 / MCP 未开 / token 为空 / 没 `adb forward` 或 LAN 未开 |
| 401 | token 错误或已轮换 |
| 动作面不监听 | 打开 App；确认 MCP 开、token 非空、服务未被系统杀 |
| `NOT_AVAILABLE` | 动作未实现/设备不支持；看 capabilities |
| `PERMISSION_DENIED` | 缺通知权限或需要 root |
| `XIAOI_OFFLINE` | 8771 小爱不在线；`ask` 需要小爱，`action` 不需要 |
| `NEEDS_CONFIRMATION` | 先 `xiaoai_confirm(approve=true)` 或带 token 重试 |

先跑顺序：`/health` → `xiaoai_capabilities` → `xiaoai_action`。
动作 schema 与返回示例见 `references/actions.md`；旧版 8771 工具见 `skill/xiaoi-mcp/`。
