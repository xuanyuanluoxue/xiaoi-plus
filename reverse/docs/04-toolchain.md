# 04 工具链与函数调用

> 证据级别：静态（assets/tool_enriched.json + dexdump）+ 实测（真机请求采样）｜版本：8.2.70.8421

## 1. 一句话结论

小爱有一整套**函数调用工具链**：静态清单 481 个工具、42 个服务前缀、12 个分组；
真机一次普通对话里，模型实际收到 **20 个工具**（`preload_tools` + 记忆三件套）。
完整清单由脚本生成：[`../data/tool-catalog.md`](../data/tool-catalog.md)。

## 2. 实测：一次请求里声明了哪些工具

在 `qm.i.streamCompletion(messages, tools, config, cont)` 的 `before` 阶段采样
（只打名字，不打 schema）：

```
工具采样: qm.i messages=26 tools=java.util.ArrayList size=20
  agent, background_task, cli, cloud_sandbox, get_current_time,
  load_message, mcp, miot_ask_assistant, personaldata_search,
  read_file, remote_file, search_history, skill, url_fetch, var,
  web_search, write_file, memory_write, memory_read, memory_forget
```

这 20 个 = `assets/agents/osbot.main/config.json` 的 `preload_tools`（17 个）
\+ `memory_write` / `memory_read` / `memory_forget`（记忆三件套）。

要点：

- `tools` 参数是 `ArrayList<ToolDefinition>`，`ToolDefinition(name, description, parameters: JsonObject)`，
  **不是** kotlinx `JsonObject` 数组。序列化成 OpenAI function schema 发生在更下层。
- 常驻工具只有 20 个，其余 461 个按需通过 `tool_search` / `call_tool` 检索后调用。
  这就是「工具太多装不下 → 两级检索」的设计。

## 3. 工具清单的构成（481 个）

`assets/tool_enriched.json` 的字段：

```
name, group, description, parameters(schema), bm25_text, embedding_text, searchHint, tags, scope
```

`bm25_text` / `embedding_text` 就是 `ToolRegistry.searchTools` 用的检索语料（关键词打分 + 向量）。

### 3.1 分组

| 分组 | 数量 | 含义 |
|---|---|---|
| CORE | 342 | 通用核心（设备控制、系统 App、跨设备） |
| TRAVEL | 53 | 出行（机票/酒店/火车/打车/公交，来自 `RideClawMcp`） |
| UTILITY | 31 | 工具类（文件、时间、后台任务、RN UI） |
| FILE | 13 | 文件读写编辑 |
| CONNECTIVITY | 12 | WiFi、短信、联系人、定位 |
| BROWSER | 9 | 后台无头浏览器 + 联网搜索 |
| MEDIA | 8 | 播放控制、截图、TTS |
| HISTORY | 5 | 历史消息 + 记忆 |
| RN | 4 | 生成/保存/预览 React Native 小程序 |
| PHONE | 2 | 通话记录 |
| SANDBOX | 1 | `adb_bash`（Harbor Docker 容器） |
| EXTERNAL | 1 | 米家（`miot_ask_assistant`） |

### 3.2 服务前缀（42 个，Top 12）

| 前缀 | 数量 | 归属 |
|---|---|---|
| `(顶层)` | 118 | osbot 原生工具 |
| `RideClawMcp` | 53 | 出行（携程类 MCP） |
| `aicr` | 43 | 相册/文档智能（相册建册、图片搜索） |
| `notes` | 28 | 便签 |
| `mediaeditor` | 26 | 媒体编辑 |
| `account` | 21 | 小米账号 |
| `gallery` | 21 | 相册 |
| `email` | 18 | 邮件 |
| `voiceassist` | 15 | 小爱自身能力 |
| `securitycenter` | 13 | 安全中心 |
| `calendar` | 9 | 日历 |
| `contacts` | 9 | 联系人 |

完整列表与每个工具的说明见 [`../data/tool-catalog.md`](../data/tool-catalog.md)。

## 4. 值得单独记录的几个工具

| 工具 | 为什么重要 |
|---|---|
| `nlp_query`（App 工具） | 直接返回小爱语义解析 JSON，等于免费 NLU |
| `update_custom_prompt` | **改 Agent 全局行为设定**；主 Agent 的 `tools_denylist` 里禁用了它 |
| `reload_prompt` / `reload_agents` / `reload_skills` / `reload_mcp_config` | 运行时可重载配置，说明这些都是**文件驱动**的 |
| `skill` | 技能知识库：`load` / `list` / `search`，读 `skills/*/SKILL.md` |
| `tool_search` / `call_tool` | 两级工具检索入口 |
| `agent` / `start_agent` / `send_message` | 多 Agent 编排（depth ≤ 3） |
| `adb_bash` / `cli` / `cloud_sandbox` | 沙箱执行（Harbor Docker + adb reverse） |
| `memory_read` / `memory_write` / `memory_forget` | 记忆读写（见 06） |
| `mcp` | MCP 网关，先 `help <provider>` 再调 |

`update_custom_prompt` 的存在意味着：**改人格有官方入口**，不必靠 hook 改 system。
但主 Agent 把它放进了 denylist，所以只能由子 Agent 或外部通道调用——这一点待验证。

## 5. 工具定义的结构（静态）

```java
// com.aios.osbot.tools.Tool
String getName();
String getDescription();
String getSummary();
String getCategory();
String getSearchHint();
String getRejects();             // 排除条件（不该用它的场景）
List<String> getTags();
List<String> getScope();
JsonObject getParameters();      // JSON Schema
long getTimeoutMs();
boolean isStoppable();
Object execute(JsonObject args, ExecutionContext ctx, Continuation cont);
```

注册与检索：

```
ToolRegistry.register(Tool) / registerSystem(Tool)
ToolRegistry.get(name) / getAll() / getToolDefinitions() / listAllToolDefinitions()
ToolRegistry.searchTools(keywords, limit)      // BM25 风格打分
ToolRegistry.addRegistrationDenylist(...)      // 按 Agent 屏蔽
ToolConfigManager                             // 分组开关（exp group）
```

`ToolExecutor.execute(name, args, ctx, cont)` 负责：权限检查（`ToolPermissionChecker`）、
写路径安全（`checkWritePathSecurity`）、风险工具（`sj.f riskToolManager`）、执行轨迹（`recordExecTrace`）。

## 6. 工具提示语料从哪来

```
ToolHintsLoader.init(context)
  ASSETS_PATH        → assets/…（tool hints）
  GENERATED_FILE_NAME→ tool_hints_generated.json
  日志原文："cli mappings from tool_enriched.json + tool_hints_generated.json(asset=…)"
```

含义：工具的可检索语料是**构建期生成**的（`tool_enriched.json`），运行期可增量生成
（`tool_hints_generated.json`），所以工具名与描述能在不升级 APK 的情况下更新一部分。

## 7. 与外部 Agent 的关系

`com.aios.apptoolsdk` 提供了反向 SDK：第三方 App 可以用 AIDL 把小爱没装的能力
接进来（`AppToolProviderService` / `IAppToolProvider` / `AppTool` / `ToolArgs`）。
详见 [08-internal-api.md](08-internal-api.md)。

## 8. 本轮已验证 / 未验证

| 结论 | 级别 |
|---|---|
| 一次对话声明 20 个工具，名字如上 | 实测 |
| 481 个工具、42 前缀、12 分组 | 静态（assets，构建期生成） |
| 工具 schema 是 JSON Schema | 静态（`parameters` 字段） |
| `tool_search` 只检索、`call_tool` 才执行 | 静态（工具描述） |
| 20 个之外的工具真的能按需检索到 | 未验证（需真机触发一次 `tool_search`） |
| `update_custom_prompt` 是否可被外部调用 | 未验证 |
