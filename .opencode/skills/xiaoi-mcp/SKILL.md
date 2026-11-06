---
name: xiaoi-mcp
description: 通过 xiaoi-plus 的 MCP 能力桥调用手机上的小爱同学：发送一句话任务并取回回复、探活、查看配置/日志/调用量、切模型、弹超级岛。其他 Agent 克隆仓库后按本 skill 连接 http://127.0.0.1:8771/mcp 或局域网地址即可使用。
---

# 调用小爱同学（xiaoi-plus MCP）

xiaoi-plus 是手机上的 LSPosed 模块，把「超级小爱」包装成一个本机/局域网 MCP 服务。
外部 Agent 不需要改手机代码，只要用 Bearer token 调 `POST /mcp`。

## 能做什么

| 工具 | 用途 |
|---|---|
| `xiaoai_submit` | **让手机上的小爱执行一句话任务，返回回复文本** |
| `xiaoai_status` | 探活：facade / CLI 是否就绪、端口、版本 |
| `xiaoai_config` | 读当前模型、端点、enabled、marker、bridge/lan、密钥是否设置 |
| `xiaoai_switch_model` | 热切换已有端点的 `model` / `marker`（不能改端点/密钥） |
| `xiaoai_logs` | 读模块运行日志（最近 300 条） |
| `xiaoai_stats` | 三方/官方模型调用量（按天） |
| `xiaoai_island` | 手动弹一次超级岛提示 |
| `xiaoai_cli` | 原始 CLI 透传，调试用 |

参数与返回示例见 `references/tools.md`。

## 前置条件

1. 手机已安装 xiaoi-plus，LSPosed 已启用，作用域勾选「超级小爱」。
2. 手机 App 配置页：**MCP 接口已开启**，**令牌已设置**，并保存。
3. 小爱进程正在运行（打开过一次小爱）。
4. 二选一连接方式：
   - **USB / 本机**：`adb forward tcp:8771 tcp:8771`，用 `http://127.0.0.1:8771/mcp`
   - **局域网**：手机和电脑同一 Wi-Fi，App 开启「允许局域网访问」且 token 为 32 位十六进制，
     用 App 配置页显示的 `http://<手机IP>:8771/mcp`
5. token 来源：App「复制连接信息」，或本机 `~/.config/xiaoi/token`。

端口通常是 `8771`；被占用时可能是 `8772~8775`，以 App 配置页显示为准。

## 最小调用

```bash
MCP="http://127.0.0.1:8771/mcp"
TOKEN="$(cat "$HOME/.config/xiaoi/token")"   # 不要 echo 到日志

# 1) 探活
curl --noproxy '*' -s -X POST "$MCP" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
       "protocolVersion":"2025-03-26","capabilities":{},
       "clientInfo":{"name":"my-agent","version":"1.0"}}}'

# 2) 让小爱执行一句话
curl --noproxy '*' -s -X POST "$MCP" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
       "name":"xiaoai_submit",
       "arguments":{"text":"打开设置里的电池省电模式"}}}'
```

返回是 JSON-RPC；真正的文本在：

```text
result.content[0].text
```

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
```

`xiaoai_submit` 可能等待小爱多轮工具调用，建议超时 120~180 秒。

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
```

## 安全边界（不要绕过）

- 远程**只能**改 `model` / `marker`；`baseUrl`、`apiKey`、`bridge`、`lan`、`token`、`enabled`
  会返回 `isError=true`。端点和密钥只能在手机 App 里改。
- `POST /config` 固定 `410 Gone`，不要试图通过 HTTP 改配置。
- `/island` 只接受 loopback；局域网请求会被 403。
- 局域网模式必须使用 32 位十六进制 token，短 token 会自动回退仅本机。
- 不要把 token 发到未确认的端口；不要用端口扫描“猜”服务。
- token 轮换后旧客户端立即 401，需要更新到新 token。

## 故障排查

| 现象 | 原因 / 处理 |
|---|---|
| HTTP 401 | token 缺失/错误/已轮换；重新从 App 复制 |
| HTTP 403 `loopback only` | 请求走了局域网但 App 未开 LAN；改 `adb forward` 或开 LAN |
| HTTP 405 | `/mcp` 只接受 POST |
| HTTP 410 | `/config` 已废弃，配置只能在 App 改 |
| HTTP 429 | `/health`/`/island` 限流，等 10 秒 |
| `ERR cli service not ready` | 小爱进程没起来；打开小爱或重启小爱 |
| `session.open failed` | 小爱 CLI/facade 未就绪；重启小爱后重试 |
| 连接被拒 | MCP 未开启、token 为空、端口不对、或手机不在同一网络 |
| `xiaoai_status` 显示 `port=0` | 当前无数据面 leader；确认 MCP 已开且保存 |

先跑顺序：`xiaoai_status` → `xiaoai_config` → `xiaoai_submit`。
`status` 不通时不要直接调 `submit`，先解决连接/令牌。

## 参考

- 工具 schema、返回示例、原始 CLI 说明：`references/tools.md`
- 仓库发版流程：`.opencode/skills/xiaoi-release/SKILL.md`
