# MCP 工具面扩展规划（PLAN-MCP-SURFACE）

> 状态：**规划，未实现**。上游证据：[reverse/docs/08-internal-api.md](../../reverse/docs/08-internal-api.md)、
> [04-toolchain.md](../../reverse/docs/04-toolchain.md)、[05-skills.md](../../reverse/docs/05-skills.md)、
> [06-memory.md](../../reverse/docs/06-memory.md)；现状口径：[CAPABILITIES.md](CAPABILITIES.md)、[README](../../README.md)。
> 范围：只规划 8771 暴露给外部 Agent 的工具面，不改代码、不新增端口、不做反向注册。

## 1. 一句话结论与现状

**结论：先加一个探针把「通道 A（18 个进程内 App 工具）」打通，再只挑 5~8 个外部 Agent 拿不到的小爱独有能力做成多模工具；481 个工具直通明确不做。**
判断依据：通道 A 的方法名是稳定英文 camelCase、无 IPC、无权限（静态，08 §2/§5），但「反射入口是否稳定」至今**未验证**（08 §7），
所以第一个交付物不是功能，而是「能证明入口可用」的最小探针 + 首个只读工具。

| 现状事实 | 级别 | 出处 |
|---|---|---|
| 对外共 **17 个**工具 = 桥 11 + 设备动作 6，按 `ToolOwnership` 分流 | 静态 | `domain/.../core/mcp/ToolOwnership.java`、`application/.../Mcp.java#toolsList` |
| 工具顺序被单测逐字断言（加工具必须同步改测试与 `initialize.instructions` 的「17 个」） | 实测 | `tests/.../McpTest.java:179` |
| 小爱一次对话声明 **20 个**工具，静态清单 **481** 个（42 前缀 / 12 分组），其余靠 `tool_search` + `call_tool` | 实测+静态 | 04 §2/§3 |
| 通道 A 有 **18 个**方法，`class` 固定 `com.xiaomi.voiceassistant.UIAgentServiceForOSBot` | 静态 | `assets/app_tools.json`、`reverse/data/tool-catalog.md:765` |
| 5 个导出 AIDL 服务，权限 `EXTERNAL_AGENT` = signature\|privileged（第三方 App 拿不到，我们在进程内） | 静态 | 08 §3/§5，Manifest |
| 记忆三件套 `memory_read/write/forget` + `memory_search`（向量+关键词），默认只注入大纲 | 实测 | 06 §1 |
| 现有 `xiaoai_todo_create` 走的是**小米笔记的 AppToolProvider**（`IAppToolProvider#handleRequest`），**不是**通道 A；代码在 `platform/.../agent/AppToolClient.java`，真机结果未记录 | 静态 | `platform/.../agent/AppToolClient.java` |

三条设计原则：

1. **先探针后功能**。通道 A 的实例获取方式、方法签名、参数编码都未验证；用 `xiaoai_status` 暴露就绪度，失败只降级不崩。
2. **一个工具多副面孔**。MCP 客户端会把全部 schema 塞进上下文，宁可 `mode=` 多态，也不要 12 个单功能工具；本轮新增 ≤8 个（17 → 25 上限），P0 只加 1 个，F8 收益不足就直接砍。
3. **隐私与副作用默认关**。屏幕、收藏、播放、写记忆都必须在 App 内显式开启；执行留审计但**不留正文**。

## 2. 功能点表

价值 1–5；成本 S（≤1 天）/M（2–4 天）/L（>1 周）。「依赖内部 API」中的参数名取自 08 §2 与 `tool-catalog.md`，
**类型与必填性待验证**（见 §5-V2）。

| # | 功能（新/增） | 用户故事 | 依赖内部 API | 实现要点 | 价值 | 成本 | 风险 | 真机验收标准 |
|---|---|---|---|---|---|---|---|---|
| F1 | `xiaoai_status` 加 `apptools=` 探针（**增强**） | 作为接入方，我想知道「通道 A 现在能不能用」，而不是等某个工具超时才发现 | `UIAgentServiceForOSBot` 类加载 + `getDeclaredMethods()`；不调业务方法 | `platform` 新增 `XiaoiAppToolClient.probe()`：反射列方法名/参数类型，与 `app_tools.json` 的 18 项比对；结果 `apptools=ok methods=18/18 instance=ok/none` 由 `Mcp.RuntimePort` 新增的 `appToolSummary()` 输出，与 `xiaoai_status` 现有 `hookSummary()` 并列；只打名字不打返回值 | 4 | S | 低 | `xiaoai_status` 出现 `apptools=ok methods=18/18`；人为把类名指到不存在的类，回 `apptools=none` 且其余工具照常 |
| F2 | `xiaoai_nlp`（**新增**） | 把一句话交给小爱自己的语义引擎，拿到结构化意图（闹钟/音乐/天气/待办），而不是我再写一套 NLU | `UIAgentServiceForOSBot#nlpQuery(query)`（1 参，返回 JSON，含 `render_token`） | `application/Mcp`：新端口 `AppToolPort.nlp(String)` + schema/dispatch；`platform/Bridge` 实现；新增 `XiaoiAppToolClient`（后台线程 + 3s 超时 + 32KB 截断 `truncated:true`），照抄 `AppToolClient` 的线程/超时纪律；`ToolOwnership` 记 BRIDGE | 5 | M | 中 | `{"text":"明天早上八点叫我起床"}` 回 JSON 含 `alarm`/`time` 组且 `render_token` 非空，<3s；`{"text":"asdfgh"}` 回 `ok:false` 或空组（写进 description），不抛异常；反射失败回 `APPTOOL_UNAVAILABLE` |
| F3 | `xiaoai_memory` 加 `op=search`（**增强**） | 让外部 Agent 用**官方**记忆检索（向量+关键词），而不是只能按小节读明文文件 | `readMemory(keywords, page, page_size)` | 保留 `get/append/replace` 语义不变；新增 `op=search` + `q`/`limit`，经新端口 `AppToolPort` 转发通道 A（`MemoryPort` 继续只管本地文件）；结果与文件 `get` 明确区分（description 写「search=官方索引，get=本地文件」）；不碰 `memory_forget` | 5 | M | 中 | `{"op":"search","q":"用户确知存在的片段"}` 命中 ≥1 条且正文含该片段；调用前后 `MEMORY.md` mtime 不变；缺 `q` 回参数错误；写开关关闭不影响 search |
| F4 | `xiaoai_screen`（**新增**，`mode=package/context`） | 「帮我看看屏幕上这页是什么」——不用 root/MediaProjection 就能拿到前台包名与页面文本 | `getCurrentPackage()`；`getScreenContext()`（截图 + 前台应用 + `doc_uri`/`web_text`） | 两个方法合成一个多模工具；**只返回文本字段**（截图路径对远程 Agent 不可达，仅回 `screenshotPath` 并在 description 标注「仅手机本地可读」）；新增 `AppConfig.screenReadEnabled` 默认 false、只能 App 内开；每次调用写审计（工具/时间/前台包/字节数，无正文） | 4 | M | 高（隐私） | 微信聊天页：`mode=package` 回 `com.tencent.mm`；`mode=context` 回 `web_text`/`doc_uri`/`unsupported_screen` 之一；开关关时回 `SCREEN_DISABLED` 且审计里无新记录 |
| F5 | `xiaoai_catalog`（**新增**，`kind=tools/agents/skills`） | 作为外部 Agent，我先查「小爱到底会什么」，再决定 `xiaoai_submit` 怎么说，而不是盲猜 | `assets/tool_enriched.json`（481）、`assets/agents/*/config.json`（19~20，待对账）、`skills/*/SKILL.md` 索引 | 纯读，不走反射：`platform` 用 `context.getAssets().open()` 解析，首次解析后缓存精简表（name/group/summary≤80 字）；检索逻辑放 `domain`（可单测）做子串/分组过滤 + `limit`；assets 缺失回 `CATALOG_UNAVAILABLE` | 4 | M | 低 | `{"kind":"tools","q":"闹钟","limit":5}` ≤5 条且每条有 name+group；`kind=agents` 与 `assets/agents/*` 实际目录数一致（05 记 19、`tool-catalog.md` 列 20，先对账）；`kind=skills` ≥3 个顶层技能；超大 `limit` 被夹并回 `clamped:true` |
| F6 | `xiaoai_knowledge`（**新增**） | 查小米官方知识（3C 参数、客服口径），避免让模型编参数 | `searchMiKnowledge(query, scene, query_intent)` | 同 F2 的端口与客户端；`scene`/`query_intent` 设默认值并允许缺省；离线/未登录要**快速失败**（5s 上限），不能挂住 8771 的请求线程 | 3 | S | 低 | `{"query":"小米15 电池容量"}` 回非空文本/结构化字段；飞行模式下 5s 内回结构化错误；不传 `query` 回参数错误 |
| F7 | `xiaoai_media`（**新增**，`action=search/play`） | 「放点周杰伦」——先搜再播，搜索不产生副作用 | `searchMedia`（10 可选参）、`playMedia`（8 可选参，需 search 返回的 id） | 一个工具两动作；`action=search` 只读、默认；`action=play` 必须带确认（复用 `xiaoai_confirm` 的 60s token 机制）且受 `AppConfig.mediaPlayEnabled`（默认 false）双重约束；只透传已文档化的参数名，其余丢弃并回 `unknown argument` | 3 | M | 中 | `action=search,q=周杰伦` 回 ≥1 条含 `id`/`title`；`action=play` 无确认回 `CONFIRM_REQUIRED`；确认后媒体会话变化（可判）；search 前后当前播放项不变 |
| F8 | `xiaoai_favorite`（**新增**，`type=list/detail/trigger`） | 把当前屏幕的图文/链接收藏起来，或翻已收藏内容 | `favorite(type, pageNumber, pageSize, keywords, favoriteId)` | `list/detail` 只读，`trigger` 会截屏+解析 → 必须确认 + 受 `AppConfig.favoriteEnabled`（默认 false）；`type` 取值以实测为准（见 V8），未知值回 `INVALID_ARGUMENT` 而非猜测 | 2 | L | 高（截屏+写用户数据） | `type=list` 回条目（可为空）；`type=trigger` 无确认回 `CONFIRM_REQUIRED`，确认后 `list` 数量 +1；`type=detail` 缺 `favoriteId` 回参数错误 |
| F9 | `xiaoai_card`（**新增**） | 把 F2 解析出的意图渲染成小爱原生卡片，给用户看结果而不是一段 JSON | `showInstruction(token)`（token 必须来自 `nlpQuery` 的 `render_token`） | 只接受 `nlpQuery` 产生的 token，不接受任意字符串；token 失效/过期回结构化错误；无副作用、无隐私，成本最低，放 F2 之后顺手做 | 2 | S | 低 | 把 F2 的 `render_token` 传入后屏幕上出现对应原生卡片（肉眼看/截屏判定）；乱填 token 回 `INVALID_TOKEN` 不崩 |
| F10 | `xiaoai_apptool`（**新增**，白名单） | 用同一套方式确定性调用其他 App 的 AppTool（先只放便签），而不是每个 App 写一个专用工具 | `com.aios.apptoolsdk.aidl.IAppToolProvider#handleRequest(String)`（`AppToolClient` 已实现，真机结果未记录） | 复用 `AppToolClient` 的 bind/超时/并发上限；新增**包名+工具名白名单**（初始仅 `com.miui.notes`），非白名单回 `PACKAGE_NOT_ALLOWED`；请求体走 `AppToolProtocol` 风格的结构化构造，不拼字符串 | 4 | M | 中 | `{"pkg":"com.miui.notes","tool":"todo_create",...}` 与现有 `xiaoai_todo_create` 产出同一张卡片；`pkg=com.android.settings` 回 `PACKAGE_NOT_ALLOWED`；Notes 未安装回 `BIND_REJECTED` |

## 3. 排序与依赖

```text
F1（探针，S）──┬── F2（nlp，M）──┬── F3（memory search，M）
                │                  └── F9（card，S）
                ├── F4（screen，M）──（共享：screenReadEnabled + 审计）
                ├── F6（knowledge，S）
                ├── F7（media，M）
                └── F8（favorite，L）──（共享：确认 token + 隐私开关）
F5（catalog，M，纯 assets）  独立，可与 F1 并行
F10（apptool 白名单，M）     独立，复用 todo_create 的 bind 路径
```

- **P0（本轮必做）**：F1 + F2 + F3。理由：F1 是所有通道 A 功能的前置验证，失败就应停在 F1 而不是继续堆功能；
  F2 证明「参数拼装 + JSON 返回 + 超时」全链路；F3 是用户感知最强的一条（记忆跨会话），且只用只读方法。
- **P1（F1 通过后）**：F5（零反射、低成本、立刻提升路由质量）→ F4（价值最高但隐私最重，必须有开关与审计）→ F6。
- **P2（按需）**：F7 / F8 / F9 / F10。F8 成本 L 且收益最低，除非用户明确要「收藏屏幕内容」，否则不启动。
- **串行约束**：F3 依赖 F2 的 `AppToolPort`；F9 依赖 F2 的 `render_token`；F4/F8 必须一起把隐私开关与审计做完，不允许先上一个裸工具。
- **每个功能的固定改动面**：`application/Mcp.java`（schema + dispatch + `unexpectedArg`）→ `Mcp.Hosts` 新端口（新方法给 `default` 实现，测试里的 FakeHost 不必逐个补） →
  `platform/Bridge.java` 适配 → `ToolOwnership` → `Contract` 上限 → `McpTest` 工具顺序断言 + `initialize.instructions` 计数 → README/CAPABILITIES。

## 4. 明确不做

| 不做项 | 理由 |
|---|---|
| 全量 481 工具直通（`ToolExecutor.execute(name, args, ctx, cont)` / `call_tool` 任意 name） | 一个入口等于把 `adb_bash`、`cli`、`cloud_sandbox`、`write_file`、短信、联系人全部交给外部 Agent；CAPABILITIES B20/B28 已明确不做，直通会绕过这条边界。能力扩展只能「单点封装、逐条评审」 |
| 流式对话（`IExternalAgentService#submit` + `onTextDelta/onReasoningDelta` 回调） | MCP over HTTP 是单响应；要做流就得引入 SSE/分块，成本 L，而现有 `xiaoai_ask` 已覆盖 90% 场景。等真的有长任务需求再评估 |
| `ExternalAsrService` / TTS 音频 | 8771 请求体上限 64KB（README），音频是二进制双向流，通道不匹配 |
| `PendingIntentJumpService` / `OSbotMessengerService` | 需要 signature\|privileged 才有意义的跳转/事件通道，对远程 Agent 无收益；我们进程内直调也绕不过宿主 App 的 pendingIntent 归属 |
| 远程 `update_custom_prompt` / 改 `profile.json`（官方人格通道） | 主 Agent 的 `tools_denylist` 已禁用（04 §4）；效果未验证（05 §6）；且与「配置只能在手机 App 改」的产品边界冲突。留在 §5 待验证，不做工具 |
| `memory_forget` / 删除 / 清空记忆 | 破坏性不可逆，现有 `xiaoai_memory` 明确无删除入口（P2 §2）；删除权限只留给小爱自己 |
| `textToImage` / `imageToText` | 返回值是设备本地文件路径，MCP 文本通道交付不了图片；改 base64 会撑爆响应且与 64KB 请求上限不对称。图片理解已有 A3（vision hook）覆盖 |
| `translateText` / `translateImage` | 外部 Agent 自带翻译，小爱翻译无独占优势（价值 1–2）；图片翻译还卡在「远程无法提供 content:// 入参」 |
| `settingsAccess`（仅 `dialect`）/ `exitXiaoAi` | 价值 1：一个改方言、一个关 UI，副作用大于收益 |
| 相册/邮件/账号类工具（`aicr` 43、`gallery` 21、`email` 18、`account` 21） | 隐私与账号风控极高，且大量返回二进制；不在「只在自己设备上做助手」的最小闭环里 |
| 反向注册（写 `mcp_servers.json` + `reload_mcp_config`，让小爱调我们） | 方向与本项目相反（我们是被调用方），且受小爱 MCP 三段安全规则约束（08 §4）；不是本轮工具面扩展 |
| 新增独立端口 / 再开一个 HTTP 端点 | 0.16.0 起单端口是硬约束（README、`ToolOwnership` 注释），新能力一律并入 8771 |

## 5. 待验证清单

| # | 待验证 | 影响 | 验证办法（不依赖本轮改代码） |
|---|---|---|---|
| V1 | `UIAgentServiceForOSBot` 的反射入口：类是否在、实例怎么拿（静态字段 / Service 单例 / Hilt 图）、方法签名与参数类型 | F1–F4/F6–F9 全部 | 静态：`dexindex.py cls Lcom/xiaomi/voiceassistant/UIAgentServiceForOSBot;` 看 `<init>` 与字段；运行：F1 探针只打方法名+参数类型，与 `app_tools.json` 18 项逐一比对 |
| V2 | 参数怎么传：`apptool` 是把参数展开成方法实参，还是传一个 JSON 字符串 | F2/F3/F6/F7/F8 | 先只做 `nlpQuery`：分别试「单 String 实参」与「JSON 字符串实参」，看哪个返回结构化 JSON |
| V3 | 通道 A 在哪些进程可用（main / :core / voice_trigger） | 桥的 leader 可能不在有实例的进程 | 探针日志带 pid+进程名；与 `xiaoai_logs` 的 `proc` 字段对照；必要时把「有 apptools 实例」加进 leader 选举条件 |
| V4 | `nlpQuery` 返回体是否总带 `render_token`、无意图时的形状 | F2/F9 的错误语义 | 真机跑 10 句：有意图/无意图/英文/长句，记录字段名（不记正文） |
| V5 | `readMemory` 与 `memory_search` 是否同一套索引；`page/page_size` 语义 | F3 的分页与上限 | 用已知记忆做 3 次不同 `page_size`，比对返回条数与 `total` |
| V6 | `getScreenContext` 的字段全集、是否依赖小爱在前台/悬浮窗、截屏权限从哪来 | F4 可用性与隐私边界 | 微信/浏览器/PDF 三种屏幕各一次，记录字段名与 `screen_type`；小爱退到后台再试一次 |
| V7 | `searchMiKnowledge` 是否要登录态/网络、`scene`/`query_intent` 的合法取值 | F6 | 断网/未登录各试一次；`scene` 传空与传 `phone` 对比 |
| V8 | `favorite` 的 `type` 合法取值，`trigger` 是否必须在前台/需要 render_token | F8 | 只做只读 `list` 与非法 `type` 探测，不触发收藏 |
| V9 | `searchMedia` 返回的 id 能否直接喂给 `playMedia`，必填参数是哪几个 | F7 | 搜一次、取第一条 id 播放 3 秒后暂停 |
| V10 | 481 工具与 Agent 清单在运行期 APK 内的路径/数量是否与 `extract-assets.sh` 一致（05 记 19、catalog 列 20） | F5 | 探针列 `assets.list("")` 与 `assets.list("agents")`，与 `reverse/data/tool-catalog.md` 对账 |
| V11 | 单次返回体积：`nlpQuery`/`getScreenContext` 的 JSON 常见大小 | 是否触发客户端截断 | 探针只记字节数；>32KB 的场景改用 `fields=` 裁剪 |
| V12 | 8.2.70.8421 之外版本的方法名稳定性 | 兼容性矩阵 | 每个新适配版本跑一次 F1 探针，`methods=18/18` 才允许开通道 A 工具 |
