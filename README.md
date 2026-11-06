# 小爱同学 +（XiaoAi Plus）

> 一个只在**自己设备**上生效的 LSPosed 模块：给小米「超级小爱」换模型、加标记、留一个本机能力接口。
> **不修改、不重打包、不分发小爱的任何代码或资源。**

> ⚠️ **当前是 0.x 预览版（测试版）**。配置项、能力桥接口和 hook 目标都可能在小版本内调整，
> 升级前先看 [CHANGELOG](CHANGELOG.md) 与 [Release Notes](../../releases)。
>
> 📦 当前测试版：**v0.12.0-beta.1** ·
> [下载 APK](https://github.com/xuanyuanluoxue/xiaoi-plus/releases/tag/v0.12.0-beta.1)

> 💬 **交流群**：QQ `1121057362`（问题反馈、版本适配、使用交流）
> 提 issue 也可以，但群里回得更快。
>
> ✅ 0.12.0-beta.1 的 CI 门禁已覆盖 JVM 单测（1328 断言）、工程一致性与
> APK 签名指纹（未签名/错签名不能进入 Release）。
> ⚠️ 真机 P0 矩阵（控制面拒绝第三方、关闭即停、LAN 弱 token、远程写拒绝、
> beta.4 升级不丢配置、降级警告）仍需发布前补跑；输出前缀暂隐藏，完整 45 项契约需本机设备脚本。

## 它做什么

| 功能 | 说明 | 状态 |
|---|---|---|
| 模型重定向 | 把主对话请求指到你自己的 OpenAI 兼容端点（BYOK） | ✅ 已验证 |
| 模型标识 | 请求头 `X-Model-Provider-Id: xiaoi-plus`，不往提示词里塞文本 | ✅ 已验证 |
| 图片理解 | 让第三方模型正常收到截图/图片（hook `getSupportsVision`），不再被换成占位文本 | ✅ 已验证 |
| 本地能力桥 | 在小爱进程内开一个 `8771~8775` 接口（`/health`、`/cli`、`/logs`、`/stats`、`/mcp`、`/island`），Bearer token 鉴权；默认只绑 `127.0.0.1`；全机只由 leader 进程监听一个端口；`POST /config` 已停用（410） | 🧪 实验性，默认关闭 |
| 局域网 MCP | 显式打开「允许局域网访问」且 token 为 32 位 hex 时才监听 `0.0.0.0`，同一 Wi-Fi 的电脑可直接连；短/弱 token 自动回退 loopback；关闭开关立即回到仅本机 | 🧪 实验性，默认关闭 |
| Agent 动作面 | App 进程内 `8770` MCP 动作面（打开应用/通知/闹钟/音量/设备信息/小爱代理），默认仅本机；独立开关「Agent 动作面允许局域网访问」，复用同一个 token，弱 token 自动回退 | 🧪 实验性，默认关闭 |
| MCP 端点 | `POST /mcp`，标准 JSON-RPC 2.0（`initialize` / `tools/list` / `tools/call`），MCP 客户端可直接接入 | 🧪 实验性，随桥一起开启 |
| 第三方 agent | 通过桥的 `/cli` 或 MCP 工具驱动小爱执行任务 | 🧪 实验性，随桥一起关闭 |

已验证的小爱版本见 [兼容性矩阵](docs/compatibility.md)，目前只有 **8.2.65.4714**。

## 前提

- Android 12+，已 root（KernelSU / Magisk）
- 已安装 **LSPosed**（Zygisk 或 Zygisk Next）
- 小爱版本在 [兼容性矩阵](docs/compatibility.md) 的白名单内（不在白名单＝自动跳过，不会崩）

## 下载与安装

从 [Releases](../../releases) 下载最新 `xiaoi-plus-<version>.apk` 和同名 `.sha256`，先校验再安装：

```bash
sha256sum -c xiaoi-plus-<version>.apk.sha256
adb install -r xiaoi-plus-<version>.apk
```

没有现成包，或想自己构建，见 [从源码构建](#从源码构建)。

升级时保持同一签名才能覆盖安装；换签名需要先 `adb uninstall plus.xiaoi.module`（配置会丢）。

1. 打开 **LSPosed 管理器** → 模块 → 找到「小爱同学plus」→ 打开「启用模块」
2. 点「作用域」，**只勾选「超级小爱」**（`com.miui.voiceassist`）。
   不要勾系统框架、不要勾其他应用——多勾没有好处，只会扩大影响面。
3. 打开「小爱同学plus」应用（就是模块自己的设置页），填写配置并保存
4. 保存后配置写入 App 私有 `config.json`，并通过控制面通知正在运行的小爱重新加载；
   小爱未运行时下次启动会自动拉取。若页面一直未生效，可强制停止小爱再打开：

   ```bash
   adb shell am force-stop com.miui.voiceassist
   ```

> 保存成功只代表文件已写入；页面显示「已保存，等待小爱应用」。真正的运行时状态在
> 小爱下一次拉取配置后生效（通常 1~10 秒），不再有 HTTP 推送失败/重试。
>
> 状态分四维显示：配置是否完整（D1）、端点是否可达（D2）、小爱是否已加载当前 rev
> （D3）、小爱运行时是否真的在用（D4）。**只有 D4 显示「已确认」**；校验模型成功
> 只代表端点可用，不代表小爱已经切换。控制面超过 30 秒没有上报时会退回「等待上报」，
> 不会假装在线。日志页走控制面，不要求开启 MCP。

### 设置页字段

| 字段 | 填什么 | 示例 |
|---|---|---|
| 服务商 / 端点 | 上游 OpenAI 兼容 base URL，按服务商要求带不带 `/v1` | `https://api.example.com/v1` |
| 模型 ID | 原样传给上游的 `model` 字段 | `deepseek-chat` |
| API Key | 服务商给的 key；跟服务商走，明文存在模块私有目录 | `sk-...` |
| 启用第三方模型 | 暂停只关重定向，不清配置 | 开 |
| 开启本地能力桥 | 实验性，默认关；默认监听 `127.0.0.1:8771~8775` | 开 |
| 允许局域网访问 | 需配合 **32 位十六进制** Token；满足时监听 `0.0.0.0`，同一 Wi-Fi 的设备可直连 `/mcp`。短 token/非 hex token 自动回退仅本机 | 关 |
| Agent 动作面允许局域网访问 | 只影响 `8770`；同样要求 **32 位十六进制** Token，弱 token 自动回退仅本机。8770 可执行设备动作，公共 Wi-Fi/公司网络建议关闭 | 关 |
| 能力桥 Token | 调用方必须带 `Authorization: Bearer <token>`；**留空则拒绝所有请求**。局域网要求 32 hex（128 bit） | 一段长随机串 |

服务商、模型 ID、API Key 三者齐全才会启用；暂停（enabled=false）不会清空这些字段。
模型标识 `X-Model-Provider-Id` 随启用自动发送，不再提供单独开关。

### 配置文件即事实

配置以文件为准：`/data/data/plus.xiaoi.module/files/config.json`（App 私有目录，其他应用与
adb 普通用户无法直接读写，密钥不再落在 `Android/data`）。
需要查看/迁移配置时请用 App 内的「分享（已脱敏）」或「完整备份」；旧版放在
`Android/data/plus.xiaoi.module/files/config.json` 的配置会在升级后首次打开 App 时自动迁移，
成功后删除旧文件（若私有目录已有配置，旧文件归档为 `config.json.legacy.<ts>`，不会覆盖）。
文件改完在 App 进程活着时 **1~2 秒自动生效**；进程被杀则下次打开 App 时应用。
运行时变更（MCP `switch_model` 等）不会自动写回文件，配置页有「从运行中小爱导入」可一键回流——
文件永远优先。若文件损坏，App 会保留原文件并生成 `config.json.corrupt.<ts>`，此时保存会被阻止。

带 `enabled` 显式开关：暂停只关重定向、不清配置；无密钥的配置不会用于模型请求。
桥与模型重定向互不影响：不勾桥，重定向照常工作。
MCP 关闭后数据面立刻停止监听；重启小爱也不会恢复，除非在 App 里重新打开。

## 验证是否生效

对小爱说任意一句话，看**请求头与日志**（回复正文不带任何前缀）：

- 走第三方时，模块把小爱原生的 `X-Model-Provider-Id` 头改成 `xiaoi-plus`
- 模型看不到这个标识，它只存在于传输层；App 回复保持干净
- 排查看日志：

```bash
# 1) 模块有没有加载、版本在不在白名单
adb logcat -s VectorLegacyBridge | grep "xiaoi+"

# 2) 是不是配置没保存 / 没重启小爱
adb shell am force-stop com.miui.voiceassist
```

App 内「日志」页直接显示小爱经控制面上报的运行日志，MCP 关闭时也可用；
显示「等待小爱上报日志」表示小爱进程还没连接/上报，不是错误。

日志含义：

| 日志 | 含义 |
|---|---|
| `已启用 model=xxx` | 正常，配置读到了 |
| `配置为空，跳过` | 设置页没保存，或模型名/端点为空 |
| `版本不在白名单: 8.x.x` | 该版本未适配，看 [如何适配新版本](docs/compatibility.md#如何适配新版本) |
| `配置 hook 失败` / `provider 标识 hook 失败` | 混淆名变了，同上 |

标识只在启用重定向时随请求头发送，配置页不再提供单独开关。
v0.5 起标识走请求头，不再往提示词里塞 `[model=...]` 规则——
既不占上下文，也不依赖模型服从；旧会话里偶尔看到前缀是模型在模仿历史，不是模块注入。

### 桥的连通性（可选）

默认只监听手机自己的回环地址，命令要在**手机上**执行（Termux 或 `adb shell`）：

```bash
curl -s -H "Authorization: Bearer <你设置的 token>" http://127.0.0.1:8771~8775/health
# ok facade=true
```

打开「允许局域网访问」并设置 **32 位十六进制** Token 后，监听地址变为 `0.0.0.0`，
同一 Wi-Fi 的电脑可直接：

```bash
curl -s -H "Authorization: Bearer <你设置的 token>" http://<手机IP>:8771/health
# ok facade=true
```

手机 IP 与端口会在设置页 MCP 卡片里直接显示（端口来自控制面，不再扫描）。没有 token
或 token 不对会返回 `401 unauthorized`；短 token/非 hex token 只会绑 loopback。
关闭局域网开关后，非回环请求会立即被拒绝。桥的边界与风险见
[docs/security.md](docs/security.md#3-本地能力桥实验性默认关闭)。

### MCP 端点（可选，随桥开启）

桥启动后同时暴露一个标准 MCP 服务端：单端点 `POST /mcp`，JSON-RPC 2.0，无会话、无 SSE。

- 协议版本 `2025-03-26`，支持 `initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call`
- 鉴权与 `/cli` 相同：`Authorization: Bearer <token>`；`GET /mcp` 返回 `405`
- 默认只监听 loopback；打开「允许局域网访问」且 token 为 32 hex 后同一 Wi-Fi 可直连，
  `/island` 仍只接受本机请求
- `POST /config` 已停用：固定返回 `410 Gone`（不解析 body、不写配置）；配置只能在小爱同学+ App 修改

| 工具 | 参数 | 作用 |
|---|---|---|
| `xiaoai_status` | 无 | 探活：`facade=` / `cli=` / `port=` / `version=` |
| `xiaoai_submit` | `text`（必填）、`sessionId`（可选） | 让小爱执行一句话任务并返回回复；不传 sessionId 自动 `session.open` |
| `xiaoai_cli` | `cmd`（JSON 串） | 原始 CLI 透传，等价 `/cli` |
| `xiaoai_config` | 无 | 读当前生效配置：model / baseUrl / marker / bridge / lan / 密钥是否已设 |
| `xiaoai_switch_model` | `model`（必填）、`marker`（可选） | 热切换已有端点的模型；**拒绝** `baseUrl`/`apiKey`/`bridge`/`lan`（端点和密钥只能在手机 App 设置） |
| `xiaoai_logs` | `limit`（默认 80）、`since`（可选） | 读运行日志：最近 300 条环形缓冲，同时落盘 `files/xiaoi_plus.log`（滚动 256KB） |
| `xiaoai_island` | `text`（可选）、`force`（默认 true） | 手动发一次超级岛提示，用于验证岛通路；自动路径在首次走第三方模型时弹一次 |
| `xiaoai_stats` | `days`（默认 7，1~90） | 调用量统计：按天返回三方 / 官方模型的调用次数，主对话与子调用分开 |

手机上自测（`127.0.0.1` 只有手机自己能访问）：

```bash
curl -s -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}' \
  http://127.0.0.1:8771/mcp
```

从电脑接入有两种方式：

**A. 同一 Wi-Fi 直连（推荐）**

设置页打开「允许局域网访问」，保证 Token 非空并保存。把卡片里显示的
`http://<手机IP>:8771/mcp` 连同 `Authorization: Bearer <token>` 头注册到 MCP 客户端即可，
不需要 adb、不需要 root、不需要数据线。桥随小爱进程启动，请保持小爱在后台运行。

**B. adb 隧道（USB/无线 adb）**

```bash
adb forward tcp:8771 tcp:8771
# MCP URL: http://127.0.0.1:8771/mcp
# Header : Authorization: Bearer <TOKEN>
```

> 局域网模式只开放 `/health`、`/logs`、`/stats`、`/cli`、`/mcp`；
> `/island`（免 token 弹提示）始终只允许本机；`/config` 已停用（410）。
> 注意：`/mcp` 里的 `xiaoai_island` 工具在局域网下仍可用，和其余 MCP 工具一样受
> Bearer token 保护；`xiaoai_switch_model` 只能改模型/前缀，不能改端点、密钥或开关。
> 公共 Wi-Fi / 公司网络建议用 adb 隧道或关闭局域网开关。

### 全程用 MCP 运维（推荐）

日常不需要再打开模块设置页或 LSPosed 管理器：

| 想做什么 | 用哪个工具 |
|---|---|
| 换模型 | `xiaoai_switch_model`（只传 `model` 就复用现有端点与 key） |
| 换端点 / 密钥 / 开关 | 只能在手机「小爱同学+」App 内修改（远程 MCP 拒绝） |
| 看当前在跑什么 | `xiaoai_config` |
| 排障 | `xiaoai_logs`（按 `since` 增量拉） |
| 看用量 | `xiaoai_stats`（或 `GET /stats?days=7`） |
| 让小爱干活 | `xiaoai_submit` |
| 确认活着 | `xiaoai_status` |

配置写入小爱的 prefs 后，各进程按 mtime 每 800ms 重读一次，所以模型切换是热的。
App 保存配置则通过控制面通知小爱重新拉取（不再有 `/config` HTTP 推送）。

只有**首次启用**需要在 LSPosed 里勾一次作用域；之后全部可远程完成。

## 从源码构建

无 Gradle，一条命令（需要 JDK 17、build-tools、`android.jar`、`zip`，见 [docs/build.md](docs/build.md)）：

```bash
chmod +x build.sh && ./build.sh
# 产物：build/xiaoi-plus.apk
```

改代码后先跑测试（纯 JVM，无需设备）：

```bash
./tests/run.sh                 # 桌面单测（Json / Http / Mcp / Logs）
XIAOI_TOKEN=<token> scripts/contract-test.sh   # 真机契约（可选，详见脚本头注释）
scripts/check-all.sh           # 本地全量门禁：单测 + 认知检查 + 真机契约
```

正式发版不要手敲上面这行，用脚本——它会检查工作区、构建、强制校验签名/包名/versionCode/固定指纹、
归档到 `dist/`、算 sha256 并生成 Release Notes 草稿：

```bash
scripts/release.sh
```

版本号在 `app/src/main/AndroidManifest.xml` 的 `android:versionName` / `android:versionCode`；完整发版流程见 **[docs/release.md](docs/release.md)**。

## 完全卸载

1. LSPosed 管理器 → 模块 → 关闭「启用模块」（或取消「超级小爱」的作用域）
2. 在系统里卸载「小爱同学plus」，或：

   ```bash
   adb uninstall plus.xiaoi.module
   ```

3. 强制停止小爱并重新打开：

   ```bash
   adb shell am force-stop com.miui.voiceassist
   ```

4. 确认日志里不再出现 `provider 标识 hook 完成`，`8771~8775` 也不再监听（含局域网模式）

模块只在运行时注入，不写小爱的数据、不改系统分区、不留后台服务；
卸载时私有目录（含 API Key）一并删除。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/build.md](docs/build.md) | 从零构建、产物、常见报错 |
| [docs/release.md](docs/release.md) | 版本号、签名密钥、发 tag、回滚 |
| [docs/security.md](docs/security.md) | 密钥存哪、数据去向、桥的边界、回滚与风险 |
| [docs/compatibility.md](docs/compatibility.md) | 版本矩阵、适配新版本的操作清单 |
| [docs/architecture.md](docs/architecture.md) | hook 点与设计原则 |
| [docs/design-system.md](docs/design-system.md) | UI 声明（令牌 / 组件 / 状态） |
| [docs/ui-architecture.md](docs/ui-architecture.md) | 四页结构与数据流 |
| [docs/remote-debug.md](docs/remote-debug.md) | 远程调试与排障 |
| [CHANGELOG.md](CHANGELOG.md) | 更新日志 |

## 免责声明

- 本项目与小米公司无关，不含任何小米代码或资源
- 仅用于学习与研究，使用风险由使用者自行承担
- 请勿用于分发、二次售卖或绕过任何付费服务
- 修改客户端行为可能导致账号风控、OTA 升级失败等问题

## 许可

GPL-3.0
