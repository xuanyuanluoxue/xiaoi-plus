---
name: xiaoi-mcp
description: 通过 xiaoi-plus 的 MCP 端点调用手机上的小爱同学：发送一句话任务并取回回复/会话、探活、查看配置/日志/调用量、切模型、弹超级岛，也能执行设备动作。0.16.0 起只有一个端口 8771，15 个工具（桥 9 + 设备动作 6）都从这里调用；端口以 /health 或设置页为准。
---

# 调用小爱同学（xiaoi-plus MCP）

> **0.16.0 起只有一个端口 8771**：App 进程不再单独监听，设备动作工具从同一个端点
> 提供（共 15 个：桥 9 + 设备动作 6），客户端只配一个地址即可。
> 端口不要写死：被占用会顺延（`8771~8775`），**以 `/health` 的 `port=` 或设置页为准**；
> `/health` 的 `agent=ipc|down` 表示设备动作是否可用。
>
> 只想驱动设备动作（不需要小爱对话）时仍可看 `skill/xiaoi-agent`。

xiaoi-plus 是手机上的 LSPosed 模块，把「超级小爱」包装成一个本机/局域网 MCP 服务。
外部 Agent 不需要改手机代码，只要用 Bearer token 调 `POST /mcp`。

## 能做什么

桥侧（小爱进程）9 个：

| 工具 | 用途 |
|---|---|
| `xiaoai_submit` | **让手机上的小爱执行一句话任务**；默认回文本，`structured:true` 回 `{ok,text,sessionId}` |
| `xiaoai_todo_create` | **确定性创建小米笔记待办**（不经过 LLM；content 可省略） |
| `xiaoai_status` | 探活：facade / CLI 是否就绪、端口、版本 |
| `xiaoai_config` | 读当前模型、端点、enabled、marker、bridge/lan、`authMode`、密钥是否设置 |
| `xiaoai_switch_model` | 热切换已有端点的 `model` / `marker`（不能改端点/密钥），回 `lastGoodModel` |
| `xiaoai_logs` | 读模块运行日志（缺省 80 条，`limit=0` 返回空，上限 300） |
| `xiaoai_stats` | 三方/官方模型调用量（按天，上限 90，超出回 `clamped:true`） |
| `xiaoai_island` | 手动弹一次超级岛提示（deprecated，建议 `xiaoai_notify`） |
| `xiaoai_cli` | 原始 CLI 透传，调试用 |

App 侧（动作面）6 个：

| 工具 | 用途 |
|---|---|
| `xiaoai_capabilities` | 列出设备动作、参数 schema、风险与当前可用性 |
| `xiaoai_action` | 执行设备动作（`risk=confirm/danger` 需确认） |
| `xiaoai_confirm` | 批准/拒绝待确认动作；token 一次性、60 秒过期 |
| `xiaoai_audit` | 动作审计摘要（不含参数值/token/apiKey） |
| `xiaoai_notify` | 发提醒：超级岛优先，失败自动降级普通通知 |
| `xiaoai_ask` | 代理手机小爱并返回 `sessionId`，支持多轮与 `context` |

参数与返回示例见 `references/tools.md`。

## 前置条件

1. 手机已安装 xiaoi-plus，LSPosed 已启用，作用域勾选「超级小爱」。
2. 手机「接入 › 令牌与局域网」：**MCP 接口已开启**，**令牌已设置**，并保存。
3. 小爱进程正在运行（打开过一次小爱）；要用 App 侧工具还需「设备操作接口」在跑。
4. 二选一连接方式：
   - **USB / 本机**：`adb forward tcp:8771 tcp:8771`，用 `http://127.0.0.1:8771/mcp`
   - **局域网**：手机和电脑同一 Wi-Fi，在「接入 › 令牌与局域网」开启「允许局域网访问」，
     用「接入 › 连接信息」显示的 MCP 地址（`http://<手机IP>:<port>/mcp`）
5. token 来源：「接入 › 连接信息」的“复制连接信息”，或本机 `~/.config/xiaoi/token`。

端口不是固定值：桥首选 `8771`（被占用会顺延到
`8771~8775`）。**以「接入 › 端口与服务」或 `/health` 里的 `port=` 为准**，
不要写死端口。

## 最小调用

```bash
MCP="http://127.0.0.1:8771/mcp"     # 动作面端口等价
TOKEN="$(cat "$HOME/.config/xiaoi/token")"   # 不要 echo 到日志

# 0) 探活（/health 现在是 GET，不是 MCP 方法）
curl --noproxy '*' -s -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8771/health"
# ok facade=true port=8771 auth=token tools=9 agent=ipc version=0.16.0

# 1) 握手
curl --noproxy '*' -s -X POST "$MCP" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
       "protocolVersion":"2025-03-26","capabilities":{},
       "clientInfo":{"name":"my-agent","version":"1.0"}}}'

# 2) 让小爱执行一句话（默认纯文本）
curl --noproxy '*' -s -X POST "$MCP" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
       "name":"xiaoai_submit",
       "arguments":{"text":"打开设置里的电池省电模式"}}}'

# 3) 需要多轮时拿 sessionId
curl --noproxy '*' -s -X POST "$MCP" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
       "name":"xiaoai_submit",
       "arguments":{"text":"记住 42","structured":true}}}'
# → text 里是 {"ok":true,"text":"...","sessionId":"..."}
```

返回是 JSON-RPC；真正的文本在 `result.content[0].text`。
`result.isError=true` 表示工具执行失败，文本里是原因。

## Python 客户端（标准库，无第三方依赖）

```python
import json, os, urllib.request

URL = os.environ.get("XIAOI_MCP_URL", "http://127.0.0.1:8771/mcp")
TOKEN = open(os.path.expanduser("~/.config/xiaoi/token")).read().strip()

def rpc(method, params=None, rid=1, timeout=120):
    body = json.dumps({"jsonrpc": "2.0", "id": rid,
                       "method": method, "params": params or {}}).encode()
    req = urllib.request.Request(URL, data=body, headers={
        "Content-Type": "application/json",
        "Authorization": "Bearer " + TOKEN,
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())

def tool(name, args=None):
    r = rpc("tools/call", {"name": name, "arguments": args or {}}, timeout=180)
    result = r.get("result", {})
    text = (result.get("content") or [{}])[0].get("text", "")
    return result.get("isError", False), text

print(tool("xiaoai_status"))
print(tool("xiaoai_submit", {"text": "把屏幕亮度调到 50%"}))
print(tool("xiaoai_ask", {"text": "现在几点"}))       # 直接拿 sessionId
```

`xiaoai_submit` / `xiaoai_ask` 可能等待小爱多轮工具调用，建议超时 120~180 秒。

## 常用操作

```bash
# 探活
{"name":"xiaoai_status","arguments":{}}

# 复用会话（有上下文）
{"name":"xiaoai_submit","arguments":{"text":"刚才那个设置再确认一下","sessionId":"<id>"}}

# 切模型（只能 model / marker）
{"name":"xiaoai_switch_model","arguments":{"model":"deepseek-v4.1-flash","marker":true}}

# 最近 20 条日志
{"name":"xiaoai_logs","arguments":{"limit":20}}

# 最近 1 天调用量
{"name":"xiaoai_stats","arguments":{"days":1}}

# 发提醒
{"name":"xiaoai_notify","arguments":{"text":"构建完成","channel":"island"}}

# 执行设备动作（先看能力）
{"name":"xiaoai_capabilities","arguments":{}}
{"name":"xiaoai_action","arguments":{"action":"device.info","args":{}}}
```

## 契约值（0.16.0 起单一来源 `Contract.java`）

| 项 | 值 |
|---|---|
| 请求体上限 | 64 KB；超限 `413 payload too large` |
| `xiaoai_submit.text` | ≤4000 字符 |
| `xiaoai_ask.text` / `context` | ≤8000 字符 / ≤8 KB |
| `xiaoai_notify.text` | ≤2000 字符 |
| `xiaoai_todo_create` | `title` ≤200、`content` ≤8192 |
| `xiaoai_logs.limit` | 缺省 80，`0`=空，上限 300 |
| `xiaoai_stats.days` | 缺省 7，上限 90（超出回 `clamped:true`） |
| `xiaoai_audit.limit` | 1~200，缺省 50 |
| 确认 token TTL | 60 秒，一次性 |

## 安全边界（不要绕过）

- 远程**只能**改 `model` / `marker`；`baseUrl`、`apiKey`、`bridge`、`lan`、`token`、`enabled`
  会返回 `isError=true`。端点和密钥只能在手机 App 里改。
- `POST /config` 固定 `410 Gone`，不要试图通过 HTTP 改配置。
- `/island` 只接受 loopback；局域网请求会被 403。
- 局域网模式默认要求 32 位十六进制 token，短 token 会自动回退仅本机。
  （App 里可以显式打开「允许弱密码」/「免鉴权」，那是**测试便利选项**，默认关闭。）
- 未知参数不再静默忽略：会返回 `unknown argument: xxx（可用：...）` + `isError=true`，
  不要靠“多传几个字段试试”来猜参数名。
- 端点带 CORS 头（`Access-Control-Allow-Origin: *`），浏览器也可直接调用；
  但**免鉴权 + 局域网 + 浏览器**同时成立时，任何网页都能驱动设备，见
  `docs/security.md §3.1.1`。
- 不要把 token 发到未确认的端口；不要用端口扫描“猜”服务。
- token 轮换后旧客户端立即 401，需要更新到新 token。

## 故障排查

| 现象 | 原因 / 处理 |
|---|---|
| HTTP 401 | token 缺失/错误/已轮换；重新从 App 复制 |
| HTTP 403 `loopback only` | 请求走了局域网但 App 未开 LAN；改 `adb forward` 或开 LAN |
| HTTP 405 | `/mcp` 只接受 POST；`GET /mcp` 恒 405 |
| HTTP 410 | `/config` 已废弃，配置只能在 App 改 |
| HTTP 413 | 请求体 >64 KB，把大文本拆成多次调用 |
| HTTP 429 | `/health`/`/island` 限流，等 10 秒 |
| `unknown argument: xxx` | 参数名写错；看返回里的「可用：」清单 |
| `AGENT_UNAVAILABLE` | App 进程/AgentService 没在跑（`/health` 里 `agent=down`）；先把 App 打开 |
| `AGENT_UNAVAILABLE` | 设备动作需要 App 进程：打开 App 后重试（`/health` 的 `agent=down` 可确认） |
| `ERR cli service not ready` | 小爱进程没起来；打开小爱或重启小爱 |
| `session.open failed` | 小爱 CLI/facade 未就绪；重启小爱后重试 |
| 连接被拒 | MCP 未开启、token 为空、端口不对、或手机不在同一网络 |
| `xiaoai_status` 显示 `port=0` | 当前无数据面 leader；确认 MCP 已开且保存 |

先跑顺序：`xiaoai_status` → `xiaoai_config` → `xiaoai_submit`。
`status` 不通时不要直接调 `submit`，先解决连接/令牌。

## 参考

- 工具 schema、返回示例、原始 CLI 说明：`references/tools.md`
- 仓库发版流程：`.opencode/skills/xiaoi-release/SKILL.md`
