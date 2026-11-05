# 架构说明

```
┌─────────────────────────────┐
│ 设置页（模块自己的进程）      │
│  SharedPreferences: xiaoi_plus│
└──────────────┬──────────────┘
               │ XSharedPreferences（LSPosed 提供跨进程读）
┌──────────────▼──────────────┐
│ 小爱进程（com.miui.voiceassist）│
│  Hook：                       │
│   sm.a.getBaseUrl/getApiKey   │  → 模型重定向
│   qm.j.buildResponsesRequest  │  → instructions 追加标记规则
│   （可选）127.0.0.1:8771~8775 桥    │  → /health /cli /mcp，Bearer token
└─────────────────────────────┘
```

## 设计原则

1. **失败即降级**：所有 hook 包 try/catch，异常只写日志，不抛回小爱
2. **版本白名单**：未验证版本直接 no-op，避免升级后 bootloop
3. **最小侵入**：只改请求参数与提示词，不动 UI、不拦截用户操作
4. **默认关闭**：能力桥默认不启动，需要用户显式打开

## MCP 端点的位置

`POST /mcp` 由能力桥进程内的同一个 HTTP server 提供，不新增 hook、不扩大作用域。
它只是把已有的 `/cli`（CliDispatcher 透传）按 MCP 的 `tools/call` 语义包装了一层，
因此权限边界与 `/cli` 完全一致：同一 Bearer token、同一 loopback 限制、同一个"驱动小爱"的能力。

## 为什么不用改包

小爱是 priv-app，能力建立在平台签名上。重打包会失去所有 signature 权限，
且分发修改版系统应用存在著作权风险。模块方案不改原包，只在运行时注入。
