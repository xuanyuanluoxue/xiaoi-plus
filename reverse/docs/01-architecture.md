# 01 架构：全景、进程与组件

> 证据级别：实测（进程/日志/请求形状）+ 静态（Manifest / 全量 dex / assets）｜适用版本：8.2.70.8421
>
> 本篇的图都能直接贴进 GitHub/Typora 渲染（Mermaid）。实线=已观测，虚线=推测或未验证。

## 1. 全景图（分层）

```mermaid
flowchart TB

  subgraph ENTRY["入口层"]
    E1["语音唤起<br/>voice_trigger 进程"]
    E2["主界面 / 悬浮窗<br/>osbot.ui"]
    E3["外部 Agent<br/>ExternalAgentService v2"]
    E4["CLI 调用方<br/>CliCommandService"]
    E5["个人 MCP 服务<br/>mcp_servers.json"]
    E6["headless START<br/>SidekickOsbotStartReceiver"]
  end

  subgraph ORCH["编排层 com.aios.osbot（8145 类）"]
    O1["AgentManager / AgentRegistry<br/>assets/agents/*/config.json（19 个）"]
    O2["osbot.main 主 Agent<br/>mode=main · depth≤3 · iter≤30 · allow=*"]
    O3["子 Agent<br/>taiyi / chat / group_chat / notes×7 / type×5 / camera / watch"]
    O4["意图路由 router.semantic<br/>280+ 类"]
    O5["会话与上下文<br/>SessionIsolation · ContextAccessSnapshot"]
  end

  subgraph CAP["能力层"]
    C1["ToolRegistry<br/>481 个工具 · 12 分组 · 42 前缀"]
    C2["两级检索<br/>tool_search → call_tool"]
    C3["ToolExecutor<br/>权限 · 写路径安全 · 风险工具 · 执行轨迹"]
    C4["内置工具<br/>文件 / CLI / 浏览器 / 设备 / 跨设备 / 日历"]
    C5["App 工具 UIAgentServiceForOSBot<br/>18 个：nlpQuery · memory · 文生图 · 翻译 · 媒体 · 屏幕"]
    C6["第三方 App 工具<br/>apptoolsdk AppToolProviderService"]
    C7["MCP 网关<br/>mcp 工具 + reload_mcp_config"]
    C8["A2A 远端技能<br/>a2a.index · a2a.discovery"]
    C9["skill 知识库<br/>SKILL.md · skill 工具 · reload_skills"]
  end

  subgraph MODEL["模型层"]
    M1["sm.a 配置对象<br/>getBaseUrl · getApiKey · getModel · getModelProviderId"]
    M2["请求构造<br/>buildOpenAIRequest · buildResponsesRequest<br/>(messages, tools, config) → JsonObject"]
    M3["qm.i chat · qm.j Responses<br/>qm.b Anthropic · um.h 官方"]
  end

  subgraph MEM["记忆层 ClawMemory（208 类）"]
    R1["memory_write / memory_read / memory_forget<br/>memory_search（向量 + 关键词）"]
    R2["注入策略：只进「记忆大纲」"]
    R3["MEMORY.md · outline.md · daily/ · facts/"]
    R4["云端同步 MiclawSyncService"]
  end

  subgraph OBS["可观测与治理"]
    G1["ToolExecTrace 执行轨迹"]
    G2["risk_tool_config.json<br/>短信 / 电话 / 邮件 强制确认"]
    G3["security.guardrail 扫描（47 类）"]
  end

  E1 --> O4
  E2 --> O2
  E3 --> O5
  E4 --> C2
  E5 --> C7
  E6 --> O2

  O4 --> O1
  O1 --> O2
  O1 --> O3
  O2 --> O5
  O3 --> O5

  O5 --> C1
  C1 --> C2
  C2 --> C3
  C3 --> C4
  C3 --> C5
  C3 --> C6
  C3 --> C7
  C3 --> C8
  C3 --> C9

  C3 --> G1
  C3 --> G2
  C3 --> G3

  O5 --> R1
  R1 --> R2
  R2 --> M2
  R1 --> R3
  R3 --> R4

  M1 --> M2
  M2 --> M3
  O5 --> M2
```

## 2. 进程模型与三条对外通道

小爱不是单进程应用。在 M332BF / Android 17 上观察到 4 个进程（同一 APK、同一 uid）：

| 进程 | 作用（观察） | 典型日志 |
|---|---|---|
| `com.miui.voiceassist` | 主进程；**8771 leader** 通常在这里 | `已获得数据面 leader 锁` |
| `com.miui.voiceassist:core` | 核心服务；没有 facade/CLI | `本进程没有 facade/CLI，不接管数据面` |
| `com.miui.voiceassist:voice_trigger` | 语音唤起 | 唤起后进程才出现 |
| `com.miui.voiceassist:provider` | 内容 Provider 侧（历史日志见过） | — |

```mermaid
flowchart LR

  subgraph APP["com.miui.voiceassist（同一 APK · 多进程）"]
    P1["主进程<br/>facade + CLI · 8771 leader"]
    P2[":core<br/>后台 / 无 facade"]
    P3[":voice_trigger<br/>语音唤起"]
    P4[":provider<br/>内容 Provider"]
  end

  subgraph OUT["对外通道（详见 08）"]
    CH1["① 进程内 App 工具<br/>UIAgentServiceForOSBot<br/>18 个方法 · 无 IPC"]
    CH2["② 导出 AIDL<br/>权限 EXTERNAL_AGENT<br/>signature | privileged"]
    CH3["③ MCP 客户端<br/>小爱去连外部服务<br/>完全开放"]
  end

  EXT1["外部 Agent / App"] -.->|"需要系统签名"| CH2
  CH2 --> P1
  CH3 --> P1
  P1 --> CH1
  P2 --> CH3
  P3 --> P1
  P4 --> P1
```

要点：

- **模块要在每个进程都生效**，否则会出现「主进程看到改写、`:core` 没改写」的假象。
  这也是日志必须跨进程合并的原因（见 `docs/refactor/LOGGING-1.md`）。
- 只有具备 **facade/CLI** 的进程才允许接管数据面；`:core` 会主动让位，30 秒后才允许接管。

## 3. 代码地图（全量 dex 索引实测）

32 个 dex、341 MB、15.2 万个类。小爱自己的框架叫 **osbot**（OS Bot），
`com.aios.*` 共 8488 个类：

| 包 | 类数 | 作用 |
|---|---|---|
| `com.aios.osbot.tools` | 2952 | 工具框架 + 全部本地工具 |
| `com.aios.osbot.ui` | 1228 | 界面 |
| `com.aios.osbot.facade` | 952 | 对外 facade（我们 hook 的入口） |
| `com.aios.osbot.im` | 582 | 好友聊天 |
| `com.aios.osbot.data` | 357 | 数据层 |
| `com.aios.osbot.router` | 284 | 意图路由（`semantic` 子包） |
| `com.aios.osbot.cli` | 226 | CLI 协议（编解码/事件契约） |
| `com.aios.osbot.memory` | 208 | 记忆（`claw` 203 + `topic` 5） |
| `com.aios.osbot.a2a` | 151 | Agent-to-Agent 协议 |
| `com.aios.osbot.voice` | 142 | 语音 |
| `com.aios.osbot.external` | 134 | 导出给外部 Agent 的 AIDL 服务 |
| `com.aios.osbot.event` | 98 | 事件/定时/短信 |
| `com.aios.osbot.mcp` | 39 | MCP 客户端 |
| `com.aios.apptoolsdk` | 101 | 第三方 App 提供工具的 SDK |
| `com.aios.tools` | 203 | 工具核心抽象 |

> 生成这张表：`python3 reverse/scripts/dexindex.py pkgs --prefix Lcom/aios/osbot/ --depth 4`

## 4. 组件（静态）

从 `AndroidManifest.xml`（aapt2 xmltree）能确认的结构：

- **Activity**：助手主界面、设置页（`超级小爱` 设置）
- **Service**：语音/助手常驻服务；`OsbotFacade` 相关服务
- **导出的 AIDL 服务**（5 个，`EXTERNAL_AGENT` 权限）：`ExternalAgentService`、
  `CliCommandService`、`PendingIntentJumpService`、`ExternalAsrService`、`OSbotMessengerService`
  → 详见 [08-internal-api.md](08-internal-api.md)
- **Receiver**：开机、语音唤醒、`headless.action.START`（无头模式）
- **Provider**：内容、文件、记忆（`MemoryOperatorProvider`）

> 组件全量清单：`reverse/scripts/report.sh`。

## 5. 控制面（我们模块建的，不是小爱原生）

小爱原生没有「外部控制面」。我们模块在 App 与各小爱进程之间建立了一条 Binder 通道：

```
App 进程（plus.xiaoi.module）
  BridgeControlService  ◀── C2S_REGISTER / PULL_CONFIG / REPORT_STATE / APPEND_LOGS / PULL_LOGS
        │
        ├── S2C_RELOAD / SHOW_ISLAND / FLUSH_LOGS / PUSH_LOGS ──▶ 各小爱进程
        └── 注册表：pid / process / port / cfgRev / callback
```

作用：

- 配置热更新（App 写 `config.json` → prefs 镜像 → 广播 reload）；
- 日志汇总（各进程 push 增量 → App 合并视图）；
- 设备动作转发（App 进程执行，经 8771 暴露给外部）。

**注意**：这条通道是我们的，不是小爱的。分析小爱原生架构时不要把它算进去。

## 6. 我们模块的落点

| 落点 | 位置 | 作用 |
|---|---|---|
| 配置 getter | `sm.a.getBaseUrl/getApiKey/getModel/getModelProviderId` | 模型重定向 |
| 视觉声明 | `sm.f.getSupportsVision` | 让第三方模型收到图片 |
| 请求构造 | `qm.i.buildOpenAIRequest*` / `qm.j.buildResponsesRequest*` | 提示词注入（P2，实测无效） |
| 客户端调用 | `qm.i` / `qm.j` / `qm.b` / `um.h` 的 `streamCompletion` | 调用量统计 + 工具形状采样；**未来注入点候选** |
| 能力桥 | `OsbotFacadeImpl` 构造器 | CLI / facade |
| 响应前缀 | `sm.b$c` / `sm.b$d` 构造器 | 已禁用（多进程重复注入） |
| JSON 重建 | `platform/JsonNodes` | 按样本构造器重建 JsonObject/JsonPrimitive |

## 7. 一次对话的调用时序（实测形状）

```mermaid
sequenceDiagram
  autonumber
  participant U as 用户
  participant A as 主 Agent osbot.main
  participant S as ToolRegistry
  participant X as ToolExecutor
  participant T as 具体工具
  participant L as 客户端 qm.i
  participant R as 模型（三方/官方）

  U->>A: 一句话（语音或文本）
  A->>A: 组装上下文<br/>system≈30KB + 历史 + 记忆大纲
  A->>S: getToolDefinitions()
  S-->>A: 20 个常驻工具<br/>ToolDefinition(name, desc, schema)
  A->>L: streamCompletion(messages, tools, sm.a)
  L->>R: POST 请求体<br/>messages + tools schema
  R-->>L: 流式 delta
  L-->>A: 文本 / tool_call
  alt 命中工具
    A->>S: tool_search(关键词) 检索其余 461 个
    S-->>A: 候选工具
    A->>X: execute(name, args, ctx)
    X->>X: 权限 / 写路径 / 风险确认
    X->>T: Tool.execute(JsonObject, ExecutionContext)
    T-->>X: ToolResult
    X-->>A: 结果 + 执行轨迹
    A->>L: 回填结果，继续
  else 纯文本
    A-->>U: 直接回复
  end
```

实测锚点：`工具采样: qm.i messages=26 tools=java.util.ArrayList size=20`，
元素是 `ToolDefinition`，名字见 [04-toolchain.md](04-toolchain.md)。

## 8. 工具的两级检索与门禁

```mermaid
flowchart TD
  Q["用户意图"] --> D{"模型判断<br/>能不能直接答"}
  D -->|能| ANS["直接回复"]
  D -->|需要动作| PRE["20 个常驻工具<br/>agent · cli · mcp · skill · read/write_file<br/>web_search · url_fetch · background_task<br/>memory_write/read/forget · miot_ask_assistant …"]

  PRE -->|不够用| SEARCH["tool_search<br/>BM25 + 向量（tool_enriched.json 语料）"]
  SEARCH --> CAND["候选工具<br/>481 池中筛选"]
  CAND --> CALL["call_tool / 直接调用"]
  PRE --> CALL

  CALL --> EXEC["ToolExecutor.execute"]
  EXEC --> CHK{"门禁"}
  CHK -->|"allowlist / denylist"| DENY["拒绝并说明"]
  CHK -->|"写路径不安全"| DENY
  CHK -->|"风险类：短信/电话/邮件"| CONFIRM["risk_tool_config<br/>强制用户确认"]
  CHK -->|通过| RUN["执行 → ToolResult → 回填模型"]

  CONFIRM -->|确认| RUN
  CONFIRM -->|拒绝| DENY
```

## 9. 记忆链路（官方流水线，推测部分已标注）

```mermaid
flowchart LR
  M0["一轮对话"] --> GATE{"抽取门控<br/>prompt_memory_extraction_gate"}
  GATE -->|"值得记"| EXT["抽取<br/>prompt_memory_extract"]
  GATE -->|"不值得"| SKIP["丢弃"]
  EXT --> ENH["增强<br/>memory_enhance_extract"]
  ENH --> TOPIC["主题化 16 个提示词<br/>扫描 / 排序 / 合并 / 冲突 / 时间归一"]
  TOPIC --> STORE[("ClawMemory<br/>向量库 + 加密库")]
  STORE --> SYNC["MiclawSyncService<br/>云端同步"]

  STORE --> OUTLINE["outline.md<br/>记忆大纲"]
  OUTLINE --> INJECT["按需注入 system<br/>只给大纲，不给条目"]
  INJECT --> LLM["模型"]

  STORE -.->|"按需检索"| SEARCH["memory_search<br/>向量 + 关键词"]
  SEARCH --> LLM
  FILE[("MEMORY.md 明文<br/>daily/ · facts/")] -.->|"模块同 uid 直读"| MOD["我们的模块"]
```

> 门控 → 抽取 → 增强 → 主题化这条链是**从提示词文件推断**的（`assets/memory-topic/`、
> `assets/prompts/clawmemory/`），具体调用顺序未在运行时验证，详见 [06-memory.md](06-memory.md)。

## 10. 关键对象对照

| 层 | 关键类 / 文件 | 职责 | 证据 |
|---|---|---|---|
| 编排 | `assets/agents/*/config.json` | 19 个 Agent：mode、depth、迭代数、工具白/黑名单 | 静态 |
| 编排 | `com.aios.osbot.tools.Tool` | 工具接口：`getName` / `getParameters` / `execute` | 静态 |
| 能力 | `ToolRegistry` | 注册、`searchTools`、`getToolDefinitions`、denylist | 静态 |
| 能力 | `ToolExecutor` | 权限、写路径安全、风险工具、执行轨迹 | 静态 |
| 能力 | `assets/tool_enriched.json` | 481 个工具的 schema + 检索语料 | 静态 |
| 能力 | `UIAgentServiceForOSBot` | 18 个进程内 App 工具 | 静态 + 清单 |
| 模型 | `sm.a` | 端点 / 密钥 / 模型 / provider 标识 | 静态 + 实测 |
| 模型 | `qm.i` / `qm.j` / `qm.b` / `um.h` | 四种协议客户端 | 静态 + 实测 |
| 模型 | `ToolDefinition` | 真实 tools 元素类型（ArrayList，20 个） | **实测** |
| 记忆 | `memory_write/read/forget` + ClawMemory | 写入、批量读、语义检索 | 静态 + 实测 |
| 出口 | 5 个导出 AIDL 服务 | 外部 Agent 接入 | 静态（Manifest） |

## 11. 已知未知

| 项目 | 状态 | 验证方法 |
|---|---|---|
| 各进程的精确职责边界 | 部分 | 分别停用 / 唤起，看日志与端口 |
| `provider` 进程是否参与模型调用 | 未知 | 让它单独接一次请求 |
| 意图路由（`router.semantic`）的打分机制 | 未知 | 断点/日志看 `searchTools` 入参 |
| A2A 远端技能是否真能拉取 | 未知 | 两台同账号设备，看日志 |
| 记忆流水线的真实调用顺序 | 推测 | 逐段断点或日志 |
| 是否有独立的后台推理进程 | 推测没有 | 抓 `ps` 全量 + 网络连接归属 |

## 12. 版本适配注意

- 进程名里带 `:` 的是同一 APK 的私有进程，不是独立应用；
- `versionName` 必须在**运行中的进程**里读（`PackageManager`），出厂包常年不更新；
- 升级后必须**重启小爱**才加载新模块代码（本次踩过：桥还是旧逻辑）。
