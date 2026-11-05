# 交接说明（2026-11-05）

> 状态：**可交接**。版本 0.11.0-beta.4（versionCode 15），工程化收敛 P0~P3b 完成。
> JVM 单测 416 断言全绿，真机契约 45/45 全绿。
>
> **下一步：0.12.0 核心重构**。计划已冻结，见
> [docs/refactor/PLAN.md](docs/refactor/PLAN.md)；工作包与子 Agent 指令见
> [docs/refactor/WORKPACKAGES.md](docs/refactor/WORKPACKAGES.md)。
> 重构完成前，beta.4 继续作为测试版，不升级为正式版。

## 1. 这是什么

LSPosed 模块「小爱同学plus」+ 自带设置 App（`plus.xiaoi.module`）：
把小米超级小爱（`com.miui.voiceassist` 8.2.65.4714）的主对话请求重定向到你自己的
OpenAI 兼容端点，并开一个 MCP 接口给本机或同一局域网的电脑端 Agent 用。

## 2. 设备与环境

| 项 | 值 |
|---|---|
| 手机 | 小米 HyperOS 设备（机型与系统版本因环境而异） |
| 连接 | USB adb 或无线 adb（IP 与串号属于本机环境，换成你自己的） |
| 小爱 | 8.2.65.4714（版本白名单精确匹配，不在名单自动 no-op） |
| 模块 | 0.11.0-beta.4（测试版），LSPosed 已启用，作用域只勾「超级小爱」 |
| 当前模型 | 任意 OpenAI 兼容端点与模型（BYOK） |
| MCP token | 存在 `config.json` 的 `mcp.token`，同时在 `~/.config/xiaoi/token`（本机，不进仓库） |

## 3. 配置在哪、怎么改

```
/storage/emulated/0/Android/data/plus.xiaoi.module/files/config.json
```

- `adb pull/push` 可直接改；Android 13+ 文件管理器通常看不到 `Android/data`
- 改完 App 进程活着时 **1~2 秒自动生效**；进程被杀则下次打开 App 应用
- 关键字段：`enabled`（暂停不清配置）、`activeProviderId/activeModelId`、
  `providers[].apiKey`、`mcp.enabled/token`、`markerTemplate`
- 运行时变更（MCP `switch_model`）不回写文件；配置页有「从运行中小爱导入」

## 4. 常用验证命令

```bash
./tests/run.sh                     # JVM 单测（416 断言，无需设备）
./scripts/check-all.sh             # 单测 + aoci check + 真机契约
XIAOI_TOKEN=$(cat ~/.config/xiaoi/token) ./scripts/contract-test.sh --check  # 契约 45 项
./scripts/ime.sh on|off|type "文本" # adb 输入法（中文/符号），用完 off
```

设备内探测：

```bash
adb shell am force-stop com.miui.voiceassist   # 重启小爱（模块重新加载）
adb forward tcp:8771 tcp:8771
curl -s -X POST http://127.0.0.1:8771/mcp \
  -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"xiaoai_config","arguments":{}}}'
```

## 5. 本轮完成（P0~P4）

- **P0 收口**：升级不丢密钥；`marker_tpl` 漏写修复；删死代码 SettingsActivity；文档纠偏
- **P1 数据层**：`ConfigCodec`（纯 JVM）统一 parse/normalize/validate/migrate；配置层 53 断言
- **P2 同步内核**：`BridgePusher` 认端口+聚合推送；无密钥按字段推送；冲突基线 / pending 重试
- **P3 配置页**：状态条 7 状态、模型 chips + 管理、真实推送结果、冲突条、暂停态屏蔽
- **P4 导入导出**：导出默认脱敏、含密钥二次确认、导入走 codec 校验
- 附带：`scripts/ime.sh`（ADBKeyboard）、契约脚本端口身份判据与转义字段归一化

**0.9.0 调用量统计**：真实模型客户端计数（`qm.i/j/b` 三方、`um.h` 官方）；
主对话/子调用分开；账本落小爱 `files/xiaoi_usage.log`（跨进程追加、7 天）；
`GET /stats?days=N` + MCP `xiaoai_stats`；主页「今日调用 · 三方/官方」。

**0.10.0 工程化收敛（P0~P3b）**：

- **P0 地基**：`build.sh` 改为 aapt2 → R → javac；22 处 `getIdentifier` 全部走 `R.*`；
  删死布局/死 drawable；manifest 显式 `targetSdk=26` 并与 Gradle 元数据对齐；
  新增 `scripts/check-consistency.sh`（版本/SDK/R/线程/HTTP/Toast/死资源），接入
  `check-all` 与 `pre-push`
- **P1 主题单源**：`ThemeManager`/`ThemeMode`/`Prefs`；主题只存 prefs，
  `config.json` 的 `themeMode` 忽略且不再写回；`BaseActivity.onResume` 比对并
  `recreate()`，被 `REORDER_TO_FRONT` 复用的旧页面也会立即换主题；切主题保留草稿
- **P1b 状态统一**：`AppStatus` 枚举 + `StatusResolver`（纯 JVM）；主页与配置页
  同一套标题/规则；`StatusCache` 以「端点+密钥+模型」指纹缓存上次校验结论
- **P2 数据通路**：`BridgeClient` 收敛端口发现/鉴权/MCP/logs/stats/island；
  `AppExecutors` 统一后台线程；修复 `pushAsync` 创建线程未 start 的 bug
- **P3a/P3b 设计系统**：数字砖改真实「今日三方/今日官方」；FAB 干净态可读；
  对话框补齐 values-night；删 Monet 统一品牌色；Toast → 应用内提示条；
  颜色令牌 32 → 19；新增 `dimens.xml` 与 `Widget.XiaoiPlus.PageTitle`

**0.11.0-beta.4 测试版（令牌轮换修复）**：

- `/config` 推送支持多令牌：先试新 token，失败后回退上一次成功推送的 token
- 写模块 prefs 改 `commit()`，保证轮换时镜像先落盘
- 真机验证：生成新令牌 → 保存 → 旧 token 两个端口 401、新 token 200
- JVM 单测 416 断言（新增 BridgePusher 轮换用例）

详见 `CHANGELOG.md` 0.11.0-beta.4。

**0.11.0-beta.3 测试版（局域网 MCP）**：

- 新增 `mcp.lan` 与设置页「允许局域网访问」开关：开启且 token 非空时监听 `0.0.0.0:8771~8775`
- 未开开关 / token 为空 / MCP 开关关闭时仍只绑 `127.0.0.1`；`/config`、`/island` 永远只收本机
- 开关热生效，保存后原地重绑端口，无需重启小爱；关闭即恢复仅本机
- 配置页显示局域网 IP + 本机 URL；复制连接信息 / Skill 按模式生成，不再强制写 `adb forward`
- `xiaoai_config` 增加 `lan` 字段；`config.json` round-trip 保留 `mcp.lan`
- 真机（USB adb）验证：局域网 health / initialize / ping / tools/list(8) / status / cli /
  logs / stats 全通；无 token 或错 token 401；局域网 `/config`、`/island` 403；
  关闭开关后局域网不可达，清空 token 自动回到 loopback

详见 `CHANGELOG.md` 0.11.0-beta.3 与 `docs/security.md`。

**0.11.0-beta.1 / beta.2 测试版**：

- 图标全套重绘：24dp 单色矢量 + 导航 `setImageTintList` + adaptive launcher；
  删除 `ic_root/ic_bridge/ic_marker/...` 等一图多义与死图标
- 主页回前台重新探测运行时；状态缓存 10 分钟 TTL；「已确认/未确认」证据行
- MCP 速览工具清单走 `Mcp.toolsList()` 单一来源（含 `xiaoai_stats`），端口显示实际值
- MCP 令牌默认掩码；导出/备份拆分；脱敏文件导入阻断
- 真机验收（USB adb）：主页/配置/日志/关于、深浅主题、暂停/恢复、备份对话框、
  MCP health/tools/stats 均通过；beta.2 修复了 beta.1 的三个问题

详见 `CHANGELOG.md` 0.11.0-beta.2 与 `docs/design-system.md`。

## 6. 已知限制（诚实清单）

1. **输出前缀（markerTemplate）已从 UI 隐藏**：hook `sm.b$c/d` 已跑通，
   但小爱多进程下同一条回复会被注入 2~5 次；`Hook.hookResponsePrefix` 调用点仍注释，
   配置页整张前缀卡已 `visibility=gone`，不再暴露“能开但不生效”的假功能。
   修复方向：Frida 定位跨进程 delta 路径后做单点注入，再恢复 UI。
2. **桥不回传 apiKey**（安全设计）：从运行时导入后，若文件里原本没有密钥，
   会保持 `enabled=false` 等你补填——避免用空 key 上线。
3. **配置页端点不可达/模型不存在**两个状态未在真机触发过（需改坏真实端点/模型）。
4. **深色/浅色**均验证过；输入框不再白底浅字。
5. 契约基线里 `text` 字段整体被脱敏（`"text":"T"`），结构断言由 jq 覆盖。
6. **官方调用量在真机没有触发过**：`um.h`（TaiyiLlmClient）的 hook 已装好，
   但小爱的文本对话当前走 OpenAI 兼容客户端（`qm.i/j`），`/stats` 的 official 为 0。
   要验证官方计数，需要在「未配置模型」的小爱上直接对话。
7. 计数口径是「模型客户端调用次数」：一次用户消息可能触发多轮 agent 工具循环，
   因此数字会大于消息条数；主对话只统计带 tools 或消息数 ≥6 的调用，子调用在
   `/stats` 的 `sub` 里单列。
8. `enabled=false` 现在会停掉 baseUrl/apiKey/model 覆盖，但小爱侧若有已持久化的
   模型配置，仍可能走原端点；停止重定向的彻底程度取决于小爱自身缓存。
9. **0.11.0-beta.3 仍存在的验收空白**：脱敏文件导入阻断只有单测、未在 SAF 选择器里走；
   日志失败四分类未真机触发（需关 MCP/清令牌）；launcher adaptive-icon 未单独截图；
   配置页「推送状态未知」仍可能需要手动点一次（P1）；装饰性 `↻`/`↧` 尚未全换矢量。
   局域网 MCP 已完成真机验证（health/tools/status/cli/logs/stats、401/403、开关热切换）。
10. **重构尚未完成的部分**（P3c/P4）：
   - 四个页面仍有约 100 处硬编码中文，尚未全部迁到 `strings.xml`
   - `ConfigActivity` 仍有 ~1600 行，未拆成 Page/ModelEditor/PushController
   - 组件仍是「style + 页面内联」，尚未抽成可复用 CompoundView
   - 页面骨架（AppBar 在滚动区 vs 固定工具条）尚未完全统一
   - 单 Activity 方案未采用，仍是 4 Activity + shell

## 7. 下一阶段建议

1. **0.9 M1-L1 语音动作引擎**（`docs/feature-candidates.md` Top1）：命令词 + ActionRegistry
   白名单 + 危险动作二次确认 + `POST /action`；开工前跑第 6 节待实测清单（变更性命令需授权）
2. 输出前缀单点注入（Frida 定位后；修好再恢复配置页卡片）
3. （可选）`/v1/models` 模型发现——独立规划，不与本轮混做
4. 收尾 P3c/P4（见 §6 第 10 条），然后发稳定版（例如 0.11.0 正式版）：
   `git tag v0.11.0 && git push origin v0.11.0`
   （beta.1~beta.3 已发布为 prerelease；正式版需去掉版本号里的 `-`）

## 8. 回滚

- 配置回滚：`adb push` 旧 `config.json`（建议每次改前 `adb pull` 备份到 `/tmp`）
- 代码回滚：`git revert <commit>`；模块卸载即删除私有目录（含密钥）
- 小爱恢复原生：配置里 `enabled=false`（保留配置）或卸载模块后重启小爱
