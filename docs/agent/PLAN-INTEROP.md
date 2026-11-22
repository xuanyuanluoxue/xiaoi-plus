# 双向接入（Interop）规划：我们调小爱 + 小爱调我们

> 状态：**规划稿**（未实现，不含代码改动）　日期：2026-11-22
> 证据标记：**[实测]** 真机/代码可验证　**[静态]** 反编译/资产/Manifest 可读　**[推测]** 未验证的判断
> 关联：[CAPABILITIES.md](CAPABILITIES.md)、[OSBOT-PROTOCOL.md](OSBOT-PROTOCOL.md)、
> [../../reverse/docs/08-internal-api.md](../../reverse/docs/08-internal-api.md)、
> [../../reverse/docs/03-agent-runtime.md](../../reverse/docs/03-agent-runtime.md)
> 前提：小爱私有资产 `assets/mcp/README.md` 是本节「通道 C」的唯一官方说明（[静态]，8.2.70.8421）

## 1. 一句话结论

**把 8771 做成唯一双向枢纽**：小爱通过「个人 MCP 服务」反向连上我们的 8771（零签名、零 hook 漂移、天然落审计）；
我们通过**进程内直取 `UIAgentServiceForOSBot` 的 18 个方法**把「小爱的能力」变成 8771 工具（方法名稳定、无 IPC、无权限）；
`ExternalAgentService` 只在需要**流式会话/思考/TTS 事件**时进程内直连（P1），用来替掉会漂移的 facade/CLI 黑盒；
`apptoolsdk` 与 `A2A` 仅做验证性 spike，不进主线。

一句话优先级：**8771 反向接入(P0) > 18 方法内取(P0/P1) > ExternalAgentService 流式(P1) > apptoolsdk(P1 验证) > ASR/TTS/A2A(P2)**。

## 2. 功能点表

方向：**IN** = 小爱调我们；**OUT** = 我们调小爱；**BOTH** = 双向同一平面。
成本：S ≈ ≤1 人日、M ≈ 2~4 人日、L ≈ ≥1 周（含真机反复）。价值 1~5。

| 编号 | 方向 | 名称 | 用户故事 | 依赖（类/方法/权限） | 实现要点 | 价值 | 成本 | 风险 | 验收标准 |
|---|---|---|---|---|---|---|---|---|---|
| I0 | IN | MCP 兼容层 | 小爱能把 8771 当成一个正常 MCP 服务连上，不掉线、不报协议错 | `Mcp.dispatch`（已判 `id==null` 返回 202）、`initializeResult`、`Bridge` 的 8771 监听 [实测] | 补齐 Streamable HTTP 语义：`Accept: application/json, text/event-stream` 时仍回 `application/json`；兼容 `2025-06-18` 客户端的 version 协商（回我们自己支持的版本并接受降级）；`DELETE`/`GET` 返回 405 而不是 404；`tools/list` 结果稳定排序 | 4 | S | 协议版本不匹配 → 小爱侧「连接失败」且无细节 | 小爱 `lscmd` 里出现 `xiaoi_*` 工具清单；连续 10 次调用无重连 |
| I1 | IN | 8771 反向注册（个人 MCP） | 我对小爱说「用 plus 查一下手机状态/打开某个 App」，它直接调我们的工具 | `mcp/mcp_servers.json`（字段 `name/url/enabled/headers/tool_prefix/timeout_seconds`）[静态]；`reload_mcp_config`[静态]；本机 `http://127.0.0.1:<port>/mcp` [实测] | 由 voiceassist 进程内**读-改-写**配置：先读快照→按 `name` 合并→备份→写→调 `reload_mcp_config`→用 `lscmd` 校验；`url` 取运行时**实际端口**（8771~8775 会漂移 [实测]）；`tool_prefix:false`（工具名已带 `xiaoai_` 前缀，避免 `xiaoi-plus__xiaoai_*` 双重前缀）；App 内一个总开关 + 状态页显示「小爱已连接/工具数」 | 5 | M | 端口漂移导致 URL 失效；小爱升级改字段/覆盖配置 | 关闭开关后配置项被移除且 `lscmd` 里工具消失；开关打开 30s 内工具可用 |
| I2 | IN | 入站令牌与工具画像（profile） | 我能决定「小爱最多能调我哪些工具」，且这个令牌和外部 Agent 的令牌互不通用 | 现有 Bearer 校验 [实测]；`Mcp` 工具表（17 个）[实测] | 新增**第二个 token 槽**：`inboundToken` → 只映射到白名单工具集；`inbound` token 强制仅 loopback（LAN 开关打开也不放行）；工具画像默认 `readonly`：`xiaoai_status`、`device.info`、`app.open`、`alarm.set`、`media.control`；显式**排除** `xiaoai_submit/ask/cli/config/switch_model/logs/stats/prompt/memory/confirm`（递归与自改配置、代签） | 5 | S | 误配把 `confirm`/`cli` 暴露给小爱 → 自审批、递归 | 用 inbound token 调 `xiaoai_submit` 返回 `FORBIDDEN_TOOL`；用外部 token 调不到 inbound 专属工具 |
| I3 | IN | 入站危险动作的用户确认 | 小爱想让我做有副作用的动作时，最终确认权在我，不在小爱 | 无（沿用 60s confirm token）[实测]；超级岛 `xiaoai_notify` [实测] | inbound 画像**不含 confirm**；危险级工具返回 `NEED_USER_CONFIRM` + 岛提醒 + 一次性 token **只回给 PC Agent/用户**，小爱拿不到可消费的 token；确认页面显示「调用方：小爱」 | 4 | S | 小爱把 `NEED_USER_CONFIRM` 当成失败重试 → 打扰 | 入站调用 `notification.send` 必须产生一条用户可见提醒；无 token 时永不执行 |
| I4a | OUT | 小爱 NLU / 屏幕理解（18 方法首批） | 「小爱怎么理解这句话」「现在屏幕上是啥」——外部 Agent 直接拿到结构化结果 | `com.xiaomi.voiceassistant.UIAgentServiceForOSBot.nlpQuery/getScreenContext/getCurrentPackage` [静态] | 在 voiceassist 进程 hook 该 Service 的 `onCreate` 捕获实例 → 反射调方法（返回 `String` JSON）[推测返回类型]；只允许在 8771 host 执行（App 进程 uid 不对 [实测]）；`getScreenContext` 加开关与脱敏（不落盘） | 5 | M | 方法被混淆/签名变动；截图隐私 | `xiaoai_nlp("明天八点叫我")` 返回含 alarm 语义的 JSON；`xiaoai_screen` 返回前台包名 |
| I4b | OUT | 小爱能力余量（知识库/记忆/翻译/图像/媒体） | 外部 Agent 不必自带 5 套 key，直接用手机上的小爱能力 | 同 I4a：`searchMiKnowledge/readMemory/writeMemory/translateText/textToImage/searchMedia/playMedia` [静态] | 一个通用 `xiaoai_apptool(method,args)` 做过渡（带方法白名单），稳定后再拆成独立工具；`writeMemory` 默认关；`readMemory` 复用官方记忆，不另造一份 | 4 | M | 返回结构未知、参数拼装错误率高 | 每个方法至少 1 条真机用例通过；错误码区分 `UNSUPPORTED_METHOD`/`APPTOOL_FAILED` |
| I5 | OUT | ExternalAgentService 进程内会话 | 我要「流式」对话：一边生成一边看到文本/思考/工具事件，而不是等 CLI 黑盒吐完 | `com.aios.osbot.external.ExternalAgentService` 的 `openSession/closeSession/submit/isFloatWindowVisible` + `IExternalAgentCallback`（6 个回调）[静态] | hook `onCreate` 捕获 Service 实例；callback 用 **`Proxy.newProxyInstance`（接口级动态代理，同进程不走 Binder）**[推测可行]；对外暴露 `xiaoai_chat_stream`（MCP progress/分块返回）；与 `xiaoai_ask` 并存，作为其「结构化升级版」 | 4 | M | 权限/绑定语义变化；协程线程上下文（可能在 Main） | 收到 ≥3 类回调（text/reasoning/tool）；`onComplete` 文本与 `xiaoai_ask` 一致；无 ANR |
| I6 | OUT | CliCommandService 进程内直连 | 不再依赖 `OsbotFacadeImpl` 这种会漂移的类名，CLI 通道更稳 | `ICliCommandService.executeCommand/executeStreaming` [静态] | 与 I5 同一 hook 点，优先于 facade；保留 facade 作 fallback；`executeStreamingWithFd` 暂不用（上传流无场景） | 3 | S | 双通道并存导致行为不一致 | `xiaoai_cli` 在 facade=false 的进程也能用（或明确返回不可用原因） |
| I7 | IN | apptoolsdk 原生工具面（spike） | 小爱把「打开 App / 查电量」当成它自己的原生工具，体验比 MCP 更顺 | `com.aios.apptoolsdk.AppToolProviderService` / `IAppToolProvider` / `AppTool` / `AnnotatedToolService` [静态]；`BIND_APP_TOOL` = signature [静态] | **先纠正一个误判**：`BIND_APP_TOOL` 约束的是**调用方**（小爱，系统签名，天然持有），不是提供方；所以「签名」不是拦路虎。真正的未知是**发现机制**（intent-filter 是否足够 / 是否需系统白名单）。做一个只含 2 个只读工具的探针 App 验证 | 4 | M | 若需白名单，此路不通且无受害者级绕法 | 小爱工具列表里出现探针工具并可调用；失败则记录「发现机制需白名单」并关闭该线 |
| I8 | BOTH | MCP 双向桥（同一端点既 server 又 client） | 小爱配置里的其他 MCP（飞书/高德）我也能用，不必重复配 key | 小爱 `mcp` 工具 + `lscmd` [静态]；我方 `Mcp` [实测] | 8771 增加 `xiaoai_mcp_list` / `xiaoai_mcp_call(provider,tool,args)`，进程内走小爱的 MCP 网关；**出站结果标记 `untrusted_mcp`**，不得直接驱动特权动作；不把 8771 自己列进去（防自环） | 3 | M | 自环、凭据外泄、结果不可信 | 能列出 `feishu` 等 provider；调用返回带 `source=mcp:<provider>`；自环检测生效 |
| I9 | OUT | ASR / TTS 复用 | 语音备忘录、通知播报都用小爱的嗓子与识别，不再自建 | `ExternalAsrService`（`IAsrCallback`）[静态]；`OsbotRequest.Tts` 存在 [静态]；`set_tts_enabled` 工具 [静态] | TTS 先做（风险低）：`xiaoai_tts(text)` → 进程内 Tts 请求/系统 TTS 兜底；ASR 后做：`xiaoai_asr(audio)`，需先摸清采样率/格式/是否要 `EXTERNAL_AGENT` 之外的前置 | 3 | M | ASR 协议未知、音频隐私 | TTS 能在免提/岛提醒后播报；ASR 对 10s 中文音频 WER 可接受 |
| I10 | OUT | A2A 跨设备 | 「让家里那台手机的小爱去做」——把任务派到另一台同账号设备 | `device_list` / `send_task_to_device` / `send_message(agent://…)` [静态]；`com.aios.osbot.a2a` [静态] | 只做**调用方**，不做技能索引伪造；返回远端结果原样透传 + 标注 `remote_device` | 2 | L | 双设备+同账号才可验证；隐私与越权面大 | 两台设备能拿到 `device_list`；一次派发任务成功（或明确记录不可用原因） |
| I11 | BOTH | 双向审计与信任分级 | 我能回答「这条动作是谁让我做的」，并能一键切断某一侧 | `xiaoai_audit` [实测]；`ToolOwnership` [实测] | 每次调用记录 `caller=local-agent|xiaoi-inbound|pc-agent`、`profile`、`token-id`（不记 token 明文）、`untrusted` 标记；入站调用单独计数并在 App「接入」页可见；两侧各有独立 kill switch | 4 | S | 审计里混入正文/隐私 | 能按 caller 过滤出「小爱调我的 20 条」；关闭入站后立即 403 |

## 3. 优先级、依赖与落地顺序

**P0（先把闭环跑通，全部不需要系统签名）**

1. **I0 + I1 + I2** —— 一条链，缺一不可：兼容层 → 配置写入 → 画像/令牌。
   闭环验收：对小爱说「用 plus 看看手机状态」，它调 `xiaoai_status`/`device.info` 成功。
2. **I11（最小版）** —— 只加 `caller` 字段与入站 kill switch，跟 P0 一起进，不然事后补审计会漏数据。
3. **I4a** —— `xiaoai_nlp` 单独先上（外部 Agent 最想要、失败也不伤主流程）。

**P1（增强，仍不需要签名）**

4. **I5**（流式会话）→ 顺带把 **I6** 一起做（同一 hook 点，边际成本低）。
5. **I4b** —— 分批：知识库/翻译 → 记忆（默认关）→ 图像/媒体。
6. **I3** —— 入站危险动作确认（依赖 I2 画像先落地）。
7. **I7** —— apptoolsdk 探针（**先验证发现机制，再决定投不投入**；结论可能直接砍掉此线）。
8. **I8** —— MCP 反向代理（有真实需求再做，价值取决于用户是否配了别的 MCP）。

**P2（价值/成本不划算，观察）**

9. **I9**（TTS 可提前到 P1，若播报需求明确）、**I10**（A2A 需要第二台设备 + 同账号，验证成本高）。

依赖图（→ 表示依赖）：

```
I0 ── I1 ── I2 ── I3
      │      └── I11
      └── I4a ── I4b
I5 ── I6
I7（独立）   I8（依赖 I1）   I9/I10（独立）
```

## 4. 明确「不做」与理由

| 不做 | 理由 |
|---|---|
| 重打包/改签名小爱、伪造 `EXTERNAL_AGENT` / `BIND_APP_TOOL` 签名权限 | 违反项目原则（不分发、不修改小爱）；签名权限无法在不改系统的情况下获得；进程内直取已覆盖同样能力 |
| 从**模块 App 进程**跨 uid `bindService` 通道 B | `EXTERNAL_AGENT` 是 signature\|privileged [静态]，App 不是系统签名；已有实测旁证：facade/CLI 只在 voiceassist 进程可用 [实测]。只能在 8771 host 内做 |
| inbound 暴露 `xiaoai_submit` / `xiaoai_ask` / `xiaoai_cli` | 小爱调「让小爱执行」＝自递归，可能烧 token/死循环；且绕过画像审计 |
| 让小爱消费 `xiaoai_confirm` 的 token | 等于让被授权的执行方自己批准自己；与 CAPABILITIES A18「不代签」原则冲突 |
| 依赖 MCP 返回值驱动小爱的敏感动作（读短信/通讯录后再发出去） | 小爱对个人 MCP 返回有明确「不可信」规则 [静态]，产品上必被拦；绕它＝对抗平台安全策略 |
| 往系统里塞 apptoolsdk 白名单 / 改系统设置绕过发现机制 | 属于系统级 hack，收益不确定、升级即碎；I7 只做「合规声明式注册」验证 |
| 伪造/注入 A2A 技能索引与服务端下发 | 涉及账号态与云端协议，不可控；只做 A2A 的调用方 |
| 为「小爱调我们」而把 8771 常驻 `0.0.0.0` | 同机 loopback 已足够；扩大 LAN 暴露面没有收益 |
| 自建 ASR/TTS/唤醒词引擎 | 与 PLAN.md §2.2 非目标一致；只复用 |

## 5. 待验证清单（区分有 root / 无签名条件）

> 全部可在「有 root + LSPosed，无小米签名」下完成；标注 ⚠ 的需要第二台设备或改系统。

| # | 待验证 | 方法 | 证据级别 | 前置 |
|---|---|---|---|---|
| V1 | 8771 能否被小爱 MCP 客户端连上（cleartext loopback、`Accept`、协议版本） | 写 `mcp_servers.json` + `reload_mcp_config` + `lscmd`，看连接状态与错误 | [推测]→实测 | root（读写私有目录） |
| V2 | `mcp_servers.json` 真实结构（是否还有 `servers[]` 之外的键、小爱是否会重写） | 写前/写后/重启后三次快照 diff | [静态]→实测 | root |
| V3 | 8771~8775 端口漂移对 `url` 的影响 | 占用 8771 后观察实际端口与配置失配表现 | [实测] | 无 |
| V4 | 小爱侧工具命名（`tool_prefix` 实际效果）与调用能否被我们审计到 | 调一次，看 8771 日志里的 `caller`/工具名 | [推测]→实测 | V1 |
| V5 | `UIAgentServiceForOSBot` 能否在进程内 hook 到实例；18 方法的真实签名/返回类型/线程 | hook `onCreate` + dump 实例 public 方法 + 单方法探针 | [静态]→实测 | root+LSPosed |
| V6 | `ExternalAgentService` 实例捕获 + 同进程 `Proxy` callback 是否成立（不需 Binder） | hook 后跑一次 `submit`，看 6 类回调 | [推测] | 同上 |
| V7 | apptoolsdk 的**发现机制**：intent-filter 是否足够 / 是否需系统白名单 | 装 2 工具探针 APK，看小爱「设置→应用工具/MCP」是否出现 | [推测] | 无签名可测；⚠ 可能需要系统白名单 |
| V8 | `ExternalAsrService` 协议（采样率/编码/回调时序） | 反编译 AIDL 常量 + 送 3 段音频 | [静态]→实测 | root |
| V9 | 小爱对 MCP 工具结果的呈现方式（是否念给用户、是否截断、是否当不可信） | 让工具返回 4KB 文本/可疑指令，观察 | [推测] | V1 |
| V10 | 双 token + 画像在现有代码里的最小改动面（`Mcp`/`Bridge`/`Config`） | 只读代码评估，不改 | [实测] | 无 |
| V11 | ⚠ A2A `device_list` / `send_task_to_device` 在本账号可用性 | 两台同账号设备，各跑一次 | [静态]→实测 | 第二台设备 |

## 6. 与现有文档的衔接

- 本文件的 I2/I11 落地后，需同步 [CAPABILITIES.md](CAPABILITIES.md) 的「C 桥」分组（新增方向列：外部 Agent / 小爱 / 两者）。
- I4a/I4b 的命名沿用 `xiaoai_*`，与 `reverse/docs/08` §6 的映射建议保持一致，避免两套命名。
- I5/I6 属于「通道 B 的进程内用法」，实现后应回写 [OSBOT-PROTOCOL.md](OSBOT-PROTOCOL.md) 的「还缺什么」清单。
