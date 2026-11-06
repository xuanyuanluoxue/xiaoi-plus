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
│   （可选）8771~8775 桥         │  → /health /cli /mcp，Bearer token
│     默认 127.0.0.1；可显式开局域网 │
└─────────────────────────────┘
```

## 设计原则

1. **失败即降级**：所有 hook 包 try/catch，异常只写日志，不抛回小爱
2. **版本白名单**：未验证版本直接 no-op，避免升级后 bootloop
3. **最小侵入**：只改请求参数与提示词，不动 UI、不拦截用户操作
4. **默认不暴露**：桥默认只绑 loopback，token 为空一律 401；
   局域网必须显式打开开关且 token 非空

## MCP 端点的位置

`POST /mcp` 由能力桥进程内的同一个 HTTP server 提供，不新增 hook、不扩大作用域。
它只是把已有的 `/cli`（CliDispatcher 透传）按 MCP 的 `tools/call` 语义包装了一层，
因此权限边界与 `/cli` 完全一致：同一 Bearer token、同样的本机/局域网准入规则、
同一个“驱动小爱”的能力。裸端点 `/config` 和 `/island` 始终只允许 loopback；
MCP 的 `xiaoai_switch_model` / `xiaoai_island` 工具在局域网下仍可用，但必须带 token。

## 控制面（0.12.0 WP1，增量）

设置 App 暴露一个**显式 ComponentName 的 bound Service**（`plus.xiaoi.module.control.BridgeControlService`，
无 intent-filter，`exported=true`）。小爱各进程在 `Bridge.install` 后通过 Binder 绑定它：

- 每个 transaction 首行 `Binder.getCallingUid()`，只允许 UID 名下挂 `com.miui.voiceassist`
  的调用（`PackageManager.getPackagesForUid`）；其他 UID 抛 `SecurityException`。
- `C2S_PULL_CONFIG` 在 App 的 HandlerThread 上读 `config.json`，返回 JSON + SHA-256。
- `C2S_REGISTER` 保存 callback `Messenger`、pid、进程名、端口、0.11 `legacyJson`。
- `C2S_REPORT_STATE` 保存 model/enabled/port/version/pid/process；`C2S_APPEND_LOGS` 只进内存环形缓冲。
- App → 小爱侧预留 `reload` / `showIsland` / `clearSafeMode` / `flushLogs`。

WP1 是**增量**：旧 `BridgePusher` 与 `POST /config` 原样保留，控制面只负责拉配置/状态；
绑定失败按 2s→5s→10s→20s→30s 退避重试，不影响小爱主流程。模块 prefs 的
`control_plane=false` 可整体关闭控制面，回到 beta.4 行为。

## 配置事实源（0.12.0 WP2）

`config.json` 的唯一事实源是 App 私有目录 `getFilesDir()/config.json`（schema v3，新增 `rev`）。
App 是唯一写者：保存时 `rev` 自增，写入走 tmp + fsync + rename（不先删目标）。
target prefs 与模块 prefs 只保留 legacy 兼容键 + `cfg_rev`，作为降级缓存，不再反向覆盖文件。
解析失败时保留原文件、生成 `config.json.corrupt.<ts>`、进入只读态（保存被阻止），不会静默回默认。
beta.4 的外部 `config.json` 在首次加载时一次性迁移到私有目录。旧 HTTP 推送仍保留（WP3 切换）。

## 为什么不用改包

小爱是 priv-app，能力建立在平台签名上。重打包会失去所有 signature 权限，
且分发修改版系统应用存在著作权风险。模块方案不改原包，只在运行时注入。
