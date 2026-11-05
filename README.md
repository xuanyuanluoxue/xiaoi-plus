# 小爱同学 +（XiaoAi Plus）

> 一个只在**自己设备**上生效的 LSPosed 模块：给小米「超级小爱」换模型、加标记、留一个本机能力接口。
> **不修改、不重打包、不分发小爱的任何代码或资源。**

> ⚠️ **当前是 0.x 预览版（测试版）**。配置项、能力桥接口和 hook 目标都可能在小版本内调整，
> 升级前先看 [CHANGELOG](CHANGELOG.md) 与 [Release Notes](../../releases)。

> 💬 **交流群**：QQ `1121057362`（问题反馈、版本适配、使用交流）
> 提 issue 也可以，但群里回得更快。

## 它做什么

| 功能 | 说明 | 状态 |
|---|---|---|
| 模型重定向 | 把主对话请求指到你自己的 OpenAI 兼容端点（BYOK） | ✅ 已验证 |
| 模型标识 | 请求头 `X-Model-Provider-Id: xiaoi-plus`，不往提示词里塞文本 | ✅ 已验证 |
| 图片理解 | 让第三方模型正常收到截图/图片（hook `getSupportsVision`），不再被换成占位文本 | ✅ 已验证 |
| 本地能力桥 | 在小爱进程内开一个 `127.0.0.1:8771~8775` 接口（`/health`、`/cli`、`/logs`、`/stats`、`/mcp`、`/config`、`/island`），Bearer token 鉴权 | 🧪 实验性，默认关闭 |
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
4. 保存后设置页会尝试把配置推送给正在运行的小爱；若提示未运行，手动重启小爱：

   ```bash
   adb shell am force-stop com.miui.voiceassist
   ```

> 配置推送成功会热更新，无需重启；推送失败时退回「启动时读一次配置」的旧行为。

### 设置页字段

| 字段 | 填什么 | 示例 |
|---|---|---|
| 服务商 / 端点 | 上游 OpenAI 兼容 base URL，按服务商要求带不带 `/v1` | `https://api.example.com/v1` |
| 模型 ID | 原样传给上游的 `model` 字段 | `deepseek-chat` |
| API Key | 服务商给的 key；跟服务商走，明文存在模块私有目录 | `sk-...` |
| 启用第三方模型 | 暂停只关重定向，不清配置 | 开 |
| 开启本地能力桥 | 实验性，默认关；监听 `127.0.0.1:8771~8775` | 开 |
| 能力桥 Token | 调用方必须带 `Authorization: Bearer <token>`；**留空则拒绝所有桥请求** | 一段长随机串 |

服务商、模型 ID、API Key 三者齐全才会启用；暂停（enabled=false）不会清空这些字段。
模型标识 `X-Model-Provider-Id` 随启用自动发送，不再提供单独开关。

### 配置文件即事实

配置以文件为准：`Android/data/plus.xiaoi.module/files/config.json`（免 root）。
`adb pull/push` 可直接读写；手机文件管理器在 Android 13+ 上通常看不到 `Android/data`，
请用支持该目录的管理器（或 adb）。改完 **1~2 秒自动生效**（App 进程活着时）；
进程被杀则下次打开 App 时应用。运行时变更（MCP `switch_model` 等）不会自动写回文件，
配置页有「从运行中小爱导入」可一键回流——文件永远优先。

带 `enabled` 显式开关：暂停只关重定向、不清配置；无密钥的配置不会被推送。
桥与模型重定向互不影响：不勾桥，重定向照常工作。

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

桥只监听手机自己的回环地址，命令要在**手机上**执行（Termux 或 `adb shell`）：

```bash
curl -s -H "Authorization: Bearer <你设置的 token>" http://127.0.0.1:8771~8775/health
# ok facade=true
```

没有 token 或 token 不对会返回 `401 unauthorized`。桥的边界与风险见 [docs/security.md](docs/security.md#3-本地能力桥实验性默认关闭)。

### MCP 端点（可选，随桥开启）

桥启动后同时暴露一个标准 MCP 服务端：单端点 `POST /mcp`，JSON-RPC 2.0，无会话、无 SSE。

- 协议版本 `2025-03-26`，支持 `initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call`
- 鉴权与 `/cli` 相同：`Authorization: Bearer <token>`；`GET /mcp` 返回 `405`
- 只监听 loopback，和桥同生共死

| 工具 | 参数 | 作用 |
|---|---|---|
| `xiaoai_status` | 无 | 探活：`facade=` / `cli=` / `port=` / `version=` |
| `xiaoai_submit` | `text`（必填）、`sessionId`（可选） | 让小爱执行一句话任务并返回回复；不传 sessionId 自动 `session.open` |
| `xiaoai_cli` | `cmd`（JSON 串） | 原始 CLI 透传，等价 `/cli` |
| `xiaoai_config` | 无 | 读当前生效配置：model / baseUrl / marker / bridge / 密钥是否已设 |
| `xiaoai_switch_model` | `model`（必填）、`baseUrl`/`apiKey`/`marker`/`bridge`（可选） | 热切换模型，写 prefs 后 1 秒内全进程生效，**不用重启小爱、不用开设置页** |
| `xiaoai_logs` | `limit`（默认 80）、`since`（可选） | 读运行日志：最近 300 条环形缓冲，同时落盘 `files/xiaoi_plus.log`（滚动 256KB） |
| `xiaoai_island` | `text`（可选）、`force`（默认 true） | 手动发一次超级岛提示，用于验证岛通路；自动路径在首次走第三方模型时弹一次 |
| `xiaoai_stats` | `days`（默认 7，1~90） | 调用量统计：按天返回三方 / 官方模型的调用次数，主对话与子调用分开 |

手机上自测（`127.0.0.1` 只有手机自己能访问）：

```bash
curl -s -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}' \
  http://127.0.0.1:8771/mcp
```

从电脑接入：先 `adb forward tcp:8771 tcp:8771`，再把 `http://127.0.0.1:8771/mcp`
连同 `Authorization: Bearer <token>` 头注册到你的 MCP 客户端。

### 全程用 MCP 运维（推荐）

日常不需要再打开模块设置页或 LSPosed 管理器：

| 想做什么 | 用哪个工具 |
|---|---|
| 换模型 / 换端点 | `xiaoai_switch_model`（只传 `model` 就复用现有端点与 key） |
| 看当前在跑什么 | `xiaoai_config` |
| 排障 | `xiaoai_logs`（按 `since` 增量拉） |
| 看用量 | `xiaoai_stats`（或 `GET /stats?days=7`） |
| 让小爱干活 | `xiaoai_submit` |
| 确认活着 | `xiaoai_status` |

配置写入小爱的 prefs 后，各进程按 mtime 每 800ms 重读一次，所以切换是热的——
这也修掉了 v0.3.0 里「`/config` 只热更新桥所在进程」的问题。

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

正式发版不要手敲上面这行，用脚本——它会检查工作区、归档到 `dist/`、算 sha256 并生成 Release Notes 草稿：

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

4. 确认日志里不再出现 `provider 标识 hook 完成`，`127.0.0.1:8771~8775` 也不再监听

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
