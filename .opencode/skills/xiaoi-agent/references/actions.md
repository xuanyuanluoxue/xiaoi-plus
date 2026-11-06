# xiaoi-agent 动作参考

端点：`POST http://127.0.0.1:8770/mcp`（先 `adb forward tcp:8770 tcp:8770`）
鉴权：`Authorization: Bearer <mcpToken>`

所有工具返回 `result.content[0].text`，内容是 JSON 字符串；`result.isError` 表示失败。

## xiaoai_capabilities

```json
{"name":"xiaoai_capabilities","arguments":{"includeUnavailable":true}}
```

返回：

```json
{"actions":[
  {"name":"device.info","risk":"safe","requiredCapability":"none","available":true,
   "executors":[{"name":"android","available":true}],
   "inputSchema":{...},"outputSchema":{...},"examples":[...]}
]}
```

先看 `available`；`executors=[]` 表示当前没有实现/权限。

## xiaoai_action

公共参数：

| 参数 | 说明 |
|---|---|
| `action` | 动作名 |
| `args` | 动作参数对象 |
| `context` | 本次上下文（string/object，≤8KB，不持久化） |
| `sessionId` | 小爱会话（ask 用） |
| `idempotencyKey` | 写动作幂等键，24h |
| `dryRun` | 只返回计划 |
| `confirmToken` | 危险动作确认 token |
| `clientId` | 审计标签 |
| `requestId` | 调用方请求 id |
| `timeoutMs` | 动作超时 |

### app.open

```json
{"action":"app.open","args":{"package":"com.tencent.mm"}}
{"action":"app.open","args":{"intentUri":"intent://scan/#Intent;scheme=zxing;end"}}
```

返回：`{"launched":true,"package":"...","component":"..."}`

风险：`safe`。后台/锁屏可能被 HyperOS 限制；Android 失败时会尝试 root `monkey`。

### device.info

```json
{"action":"device.info","args":{"fields":["model","battery","network","uptime"]}}
```

返回：`{"fields":{"model":"...","battery":83,"charging":false,"network":"wifi","uptime":12345}}`

风险：`safe`。`foregroundApp` 当前不提供。

### alarm.set

```json
{"action":"alarm.set","args":{"hour":7,"minute":0,"label":"起床","skipUi":true}}
```

返回：`{"scheduled":true,"skippedUi":false}`

风险：`safe`。`skipUi` 在不同时钟 App 上可能无效；以系统闹钟列表为准。

### notification.send

```json
{"action":"notification.send","args":{"title":"Agent","text":"任务完成","id":"task-123"}}
```

返回：`{"notificationId":"..."}`

风险：`confirm`，需要 confirm token。需要 `POST_NOTIFICATIONS`。

### media.control

```json
{"action":"media.control","args":{"command":"volume","volume":40}}
{"action":"media.control","args":{"command":"play"}}
```

返回：`{"command":"volume","volume":40}`

风险：`safe`。音量普通权限可用；播放/暂停依赖 MediaSession，失败时尝试 root `cmd media_session`。

## xiaoai_confirm

```json
{"name":"xiaoai_confirm","arguments":{"confirmToken":"<token>","approve":true}}
{"name":"xiaoai_confirm","arguments":{"confirmToken":"<token>","approve":false}}
```

返回：批准 → 动作结果；拒绝 → `CONFIRMATION_DENIED`。
token 一次性、60 秒；重复使用 `CONFLICT`，过期 `CONFIRMATION_EXPIRED`。

## xiaoai_notify

```json
{"name":"xiaoai_notify","arguments":{
  "channel":"island","text":"构建完成","force":true}}
```

返回：`{"ok":true,"channel":"island","fallback":false,"truncated":false,"textLength":4}`

`channel=notification` 可跳过岛直接发普通通知。岛失败自动降级，`fallback=true`。

## xiaoai_ask

```json
{"name":"xiaoai_ask","arguments":{
  "text":"帮我看一下今天天气",
  "context":"PC Agent 的本次上下文",
  "timeoutMs":120000}}
```

返回：

```json
{"ok":true,"text":"...","sessionId":"...","via":"xiaoi","timeoutMs":120000}
```

- `sessionId` 可用于下一轮；
- 小爱不在线 → `XIAOI_OFFLINE`；
- 会话不可用/忙碌 → `CONFLICT`；
- 超时 → `TIMEOUT`；
- `context` 只是本次上下文，不作为指令执行。

## xiaoai_audit

```json
{"name":"xiaoai_audit","arguments":{"limit":50}}
```

返回：

```json
{"entries":[
  {"auditId":"aud_...","time":...,"clientId":"...","action":"device.info",
   "executor":"android","risk":"safe","argNames":["fields"],"argHash":"...",
   "ok":true,"errorCode":null,"elapsedMs":2,"requestId":"...","event":"action"}
]}
```

不返回参数值、context、token、apiKey。

## 错误信封

```json
{"ok":false,"action":"app.open","code":"NOT_AVAILABLE",
 "message":"...","retryable":false,"warnings":[],"elapsedMs":1,
 "requestId":"req..."}
```

`isError=true` 时以 `code` 为准。
