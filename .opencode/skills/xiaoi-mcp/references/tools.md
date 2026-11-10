# xiaoi-plus MCP 工具参考

协议：JSON-RPC 2.0 over HTTP，单端点 `POST /mcp`，无会话。
鉴权：`Authorization: Bearer <token>`。

## 协议方法

### initialize

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-03-26",
    "capabilities": {},
    "clientInfo": {"name": "my-agent", "version": "1.0"}
  }
}
```

返回：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-03-26",
    "capabilities": {"tools": {"listChanged": false}},
    "serverInfo": {"name": "xiaoi-plus", "version": "0.12.0-beta.1"},
    "instructions": "小爱同学（手机端）能力桥..."
  }
}
```

### tools/list

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
```

### tools/call

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call",
 "params":{"name":"xiaoai_status","arguments":{}}}
```

返回结构：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{"type": "text", "text": "facade=true cli=true port=8771 version=0.12.0-beta.1"}],
    "isError": false
  }
}
```

客户端必须检查 `result.isError`，文本在 `result.content[0].text`。

## 工具

### xiaoai_status

参数：无。

返回示例：

```text
facade=true cli=true port=8771 version=0.12.0-beta.1
```

- `facade=false` 或 `cli=false`：小爱进程未就绪。
- `port=0`：没有数据面 leader（MCP 关闭或正在切换）。

### xiaoai_submit

让小爱执行一句话任务，返回回复文本。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `text` | string | 是 | 要说给小爱的话 |
| `sessionId` | string | 否 | 复用已有会话以保留上下文；不传则自动 `session.open` |

```json
{"name":"xiaoai_submit","arguments":{"text":"把蓝牙关掉"}}
```

```json
{"name":"xiaoai_submit","arguments":{
  "text":"刚才那个再确认一下",
  "sessionId":"<sessionId>"
}}
```

可能耗时较长（小爱内部多轮工具调用），客户端超时建议 ≥120 秒。

失败文本：

- `missing argument: text`
- `session.open failed: ...`
- `ERR cli service not ready`
- 其他 `isError=true` 文本

### xiaoai_todo_create

确定性创建一张小米笔记待办卡片，直接调用 Notes 的 `todo_create` AppTool，不经过 LLM。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | 是 | 待办清单标题 |
| `content` | string | 否 | 每行 `- [ ] 文本`；留空自动生成 `- [ ] <title>` |

```json
{"name":"xiaoai_todo_create","arguments":{"title":"买牛奶"}}
```

```json
{"name":"xiaoai_todo_create","arguments":{
  "title":"购物","content":"- [ ] 牛奶\n- [ ] 鸡蛋"}}
```

成功时 `content[0].text` 原样返回 Notes 的结果文本（通常是含 `noteId` 的 JSON）。
失败时 `isError=true`，`text` 是结构化错误：

```json
{"ok":false,"error":"CALL_TIMEOUT","message":"...","hint":"可改用 xiaoai_submit 兜底..."}
```

常见 `error`：`BIND_REJECTED` / `BIND_TIMEOUT` / `CALL_TIMEOUT` / `CALL_FAILED` /
`PROVIDER_ERROR` / `INVALID_RESPONSE`。
不要用 `xiaoai_submit` 替代确定性路径；只有失败时才按 `hint` 兜底。

### xiaoai_config

参数：无。返回当前生效配置（JSON 字符串，放在 `content[0].text`）：

```json
{
  "model": "deepseek-v4.1-flash",
  "baseUrl": "https://api.example.com/v1",
  "enabled": true,
  "marker": true,
  "markerTemplate": "",
  "bridge": true,
  "lan": false,
  "apiKeySet": true,
  "tokenSet": true,
  "port": 8771,
  "version": "0.12.0-beta.1"
}
```

不会返回 apiKey/token 明文。

### xiaoai_switch_model

热切换模型，只能改 `model` / `marker`。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `model` | string | 是 | 已有端点上的模型 id |
| `marker` | boolean | 否 | 回复开头是否带 `[model=...]` |

```json
{"name":"xiaoai_switch_model",
 "arguments":{"model":"deepseek-v4.1-flash","marker":true}}
```

成功：

```json
{"ok":true,"model":"deepseek-v4.1-flash","baseUrl":"https://...","apiKeySet":true,"changed":true}
```

拒绝：

- 传 `baseUrl` / `apiKey` / `bridge` / `lan` / `token` / `enabled`：
  `端点和密钥只能在手机 App 设置；xiaoai_switch_model 只接受 model / marker（xxx 已拒绝）`
- 其他未知参数：`unsupported argument: xxx`
- 当前没有端点：`missing argument: baseUrl（当前没有可用端点，请在手机 App 设置）`

### xiaoai_island

手动弹一次超级岛提示。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `text` | string | 否 | 提示文案，默认当前模型 |
| `force` | boolean | 否 | 忽略单次限制，默认 true |

```json
{"name":"xiaoai_island","arguments":{"text":"任务完成"}}
```

返回：`{"ok":true,"alreadyShown":false,"text":"任务完成"}`

### xiaoai_logs

读模块运行日志。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `limit` | integer | 否 | 尾部条数，默认 80；服务端上限 300 |
| `since` | integer | 否 | 只返回 seq 大于该值的日志 |

```json
{"name":"xiaoai_logs","arguments":{"limit":20,"since":0}}
```

返回 JSON 数组文本，每项包含时间、级别、消息、`seq`。

### xiaoai_stats

调用量统计。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `days` | integer | 否 | 1~90，默认 7 |

```json
{"name":"xiaoai_stats","arguments":{"days":1}}
```

返回按天聚合的三方/官方调用次数，子调用单独在 `sub`。

### xiaoai_cli

原始 CLI 透传，调试用。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `cmd` | string | 是 | JSON 命令串 |

```json
{"name":"xiaoai_cli",
 "arguments":{"cmd":"{\"id\":\"1\",\"method\":\"auth.status\"}"}}
```

返回小爱 CLI 的原始响应文本。

## 其他 HTTP 端点

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/health` | Bearer | `ok facade=... port=...` |
| GET | `/logs?limit=&since=` | Bearer | 日志 JSON |
| GET | `/stats?days=` | Bearer | 调用量 JSON |
| GET | `/cli?cmd=` | Bearer | 原始 CLI |
| POST | `/mcp` | Bearer | MCP JSON-RPC |
| POST | `/island` | 仅 loopback，无 token | 弹岛 |
| POST | `/config` | 无 | **固定 410 Gone** |

## 安全说明

- 外部 Agent 只应连接 App 显示的端口，不要扫描 8771~8775 逐个发 token。
- 局域网必须使用 32 位十六进制 token；短 token 自动回退仅本机。
- token 轮换后旧客户端立即 401。
- 远程写配置的能力被刻意限制为 `model` / `marker`；端点和密钥只能在手机 App 内改。
- 不要把 `apiKey` / `mcp.token` 写入日志、提交或分享文件。
