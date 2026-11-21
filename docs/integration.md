# 接入文案（可直接复制）

## 1. cURL 调用

```bash
# 探活（需要 token）
curl -s -H "Authorization: Bearer <TOKEN>" http://127.0.0.1:8771/health

# 开一个会话
curl -s -H "Authorization: Bearer <TOKEN>" \
  "http://127.0.0.1:8771/cli?cmd=$(python3 -c 'import urllib.parse,json;print(urllib.parse.quote(json.dumps({"id":"1","method":"session.open","params":{}})))')"

# 让小爱执行任务（把 <SID> 换成上一步返回的 sessionId）
curl -s -H "Authorization: Bearer <TOKEN>" \
  "http://127.0.0.1:8771/cli?cmd=$(python3 -c 'import urllib.parse,json;print(urllib.parse.quote(json.dumps({"id":"2","method":"agent.submit","params":{"sessionId":"<SID>","text":"帮我看看现在电量"}})))')"
```

## 2. Python 客户端（零依赖）

```python
import json, urllib.parse, urllib.request

BASE, TOKEN = "http://127.0.0.1:8771", "<TOKEN>"

def call(method, params=None):
    body = {"id": "1", "method": method, "params": params or {}}
    url = f"{BASE}/cli?cmd=" + urllib.parse.quote(json.dumps(body))
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + TOKEN})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())

sid = call("session.open")["sessionId"]
print(call("agent.submit", {"sessionId": sid, "text": "用一句话介绍你自己"}))
```

## 3. Agent 用 skill 文本（SKILL.md）

```markdown
---
name: xiaoai-submit
description: 通过本机 HTTP 桥驱动小爱同学执行任务。触发：需要让小爱查信息、开应用、设提醒时。
---

# 小爱驱动

前置：模块已启用、作用域勾选“超级小爱”、在设置 App 内保存配置（`POST /config` 已停用，返回 410）。

步骤：
1. 打开「小爱同学+」App 保存配置；等页面显示「已保存，等待小爱应用」
2. `GET /health`（带 Bearer token）确认 `ok facade=true`
3. `GET /cli?cmd=<urlencoded {"id","method":"session.open","params":{}}>` 拿 sessionId
4. `GET /cli?cmd=<... "method":"agent.submit","params":{"sessionId","text"}>` 执行
5. 失败先查：小爱是否在运行（`ps -A | grep voiceassist`）、token 是否一致、端口是否 8771–8775
```

## 4. MCP 接入（v0.3.0 起内置，0.16.0 起单端口）

桥同时是标准 MCP 服务端：`POST /mcp`，JSON-RPC 2.0，协议版本 `2025-03-26`。

- 传输：Streamable HTTP，只回 JSON（不提供 SSE，`GET /mcp` → 405）；`OPTIONS` 回 204 预检
- 鉴权：与 `/cli` 相同，`Authorization: Bearer <TOKEN>`
- **只有一个端口 8771**：模块不再开第二个监听。App 进程的设备动作工具经控制面
  Binder 从 8771 转发（`/health` 里 `agent=ipc` 表示已连上，`agent=down` 表示 App 未就绪）。
  端口不要写死：桥从 `8771` 起，被占用会顺延（`8771~8775`），以 `/health` 的 `port=`
  或设置页「连接信息」为准。
- 工具（17 个）：
  - 桥侧 11 个：`xiaoai_status` / `xiaoai_submit` / `xiaoai_todo_create` / `xiaoai_cli` /
    `xiaoai_config` / `xiaoai_switch_model` / `xiaoai_logs` / `xiaoai_island` / `xiaoai_stats` /
    `xiaoai_prompt` / `xiaoai_memory`
  - App 侧 6 个：`xiaoai_capabilities` / `xiaoai_action` / `xiaoai_confirm` / `xiaoai_audit` /
    `xiaoai_notify` / `xiaoai_ask`
  - 语义与 README 的工具表一致；上限/TTL/端口统一来自 `Contract.java`
- 转发失败的错误码：`AGENT_UNAVAILABLE`（App 进程未就绪），带 `retryable:true`
- `xiaoai_logs` / `GET /logs` 是**跨进程合并视图**：顶层带 `scope`（`merged`/`local`）与
  `lastSeq`，每行 `seq/t/level/msg` 后追加 `tag`（来源）、`ts`（epoch 毫秒）、`pid`（写入
  进程）、`proc`（进程名）；旧字段名与顺序不变。控制面不可达时回落 `scope=local`
- `GET /logs?source=file&limit=N` 返回**落盘历史**（`xiaoi_plus.log` 含 `.1` 轮转的尾部，
  `text/plain`，一行一条）；内存缓冲被冲掉后靠它补证据。参数非法按默认上限处理

电脑上接入有两种方式：

```bash
# A. 同一 Wi-Fi 直连（设置页打开「允许局域网访问」，Token 非空）
# MCP URL: http://<手机IP>:8771/mcp
# Header : Authorization: Bearer <TOKEN>

# B. adb 隧道（USB / 无线 adb）
adb forward tcp:8771 tcp:8771
# MCP URL: http://127.0.0.1:8771/mcp
# Header : Authorization: Bearer <TOKEN>
```

局域网模式要求 32 位十六进制 Token；短 token 会自动回退 loopback。
`/island` 裸端点仍只接受手机本机请求（远端返回 403）；`/config` 已停用（固定 410）。

**可选：免鉴权 / 弱密码**（设置页「接入 › 令牌与局域网」，默认都关）。
打开「允许免令牌调用」后本机与局域网都不再要求 `Authorization`；打开「允许弱密码」
后任意非空密码（如 `test1234`）都能作为 Bearer 并绑定 `0.0.0.0`。关闭后 1~2 秒恢复。
风险与边界见 [security.md §3.1](security.md)。
`xiaoai_switch_model` 只能改 model/marker，端点/密钥/开关必须在小爱同学+ App 修改。
手机直连时不要在公共 Wi-Fi 上长期开启；不用时把开关关掉即可立即回到 loopback。

手机上可直接 `curl -d @payload.json`（见上一节）。

## 5. 本机测试脚本

| 脚本 | 用途 |
|---|---|
| `scripts/contract-test.sh --check` | 44 项回归（开免鉴权时 3 项 401 断言按配置跳过）：鉴权、MCP 形状、`tools/list`（own + 合并）、`/island`、工具表；会重启小爱并对比基线 |
| `scripts/mcp-call.sh` | 手工调单个工具：端口发现 + `adb forward` + Bearer 鉴权 |

```bash
bash scripts/mcp-call.sh health                    # GET /health，自证端口
bash scripts/mcp-call.sh init                      # initialize + ping
bash scripts/mcp-call.sh tools                     # 9 个桥侧工具名
bash scripts/mcp-call.sh call xiaoai_status
bash scripts/mcp-call.sh call xiaoai_logs '{"limit":5}'
bash scripts/mcp-call.sh raw '{"jsonrpc":"2.0","id":1,"method":"ping"}'
```

多设备用 `ANDROID_SERIAL=<serial>`；token 默认读 `~/.config/xiaoi/token`；
`XIAOI_PORT=8771` 可跳过端口发现。args 必须是合法 JSON（shell 里用单引号包住）。

默认不调用会花额度或改状态的工具（`xiaoai_submit` / `xiaoai_switch_model` /
`xiaoai_todo_create` / `xiaoai_island`），需要时显式用 `call` 发起。
