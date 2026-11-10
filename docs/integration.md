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

## 4. MCP 接入（v0.3.0 起内置）

桥同时是标准 MCP 服务端：`POST /mcp`，JSON-RPC 2.0，协议版本 `2025-03-26`。

- 传输：Streamable HTTP，只回 JSON（不提供 SSE，`GET /mcp` → 405）
- 鉴权：与 `/cli` 相同，`Authorization: Bearer <TOKEN>`
- 工具（9 个）：`xiaoai_status` / `xiaoai_submit` / `xiaoai_todo_create` / `xiaoai_cli` /
  `xiaoai_config` / `xiaoai_switch_model` / `xiaoai_logs` / `xiaoai_island` / `xiaoai_stats`，
  语义与 README 的 MCP 工具表一致；`xiaoai_todo_create` 直接绑定 Notes provider，确定性建待办

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
`/mcp` 的 `xiaoai_island` 工具在局域网下可用（需 Bearer token）；
`xiaoai_switch_model` 只能改 model/marker，端点/密钥/开关必须在小爱同学+ App 修改。
手机直连时不要在公共 Wi-Fi 上长期开启；不用时把开关关掉即可立即回到 loopback。

手机上可直接 `curl -d @payload.json`（见上一节）。
