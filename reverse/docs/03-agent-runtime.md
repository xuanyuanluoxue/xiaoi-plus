# 03 Agent 运行时：facade / CLI / 意图

> 证据级别：实测（hook 日志）+ 静态（类名）｜适用版本：8.2.70.8421

## 1. 一句话

小爱的「Agent 入口」有两层：

1. **官方外部通道**（推荐了解）：5 个导出的 AIDL 服务 —— `ExternalAgentService`
   （开/关会话、提交一轮、流式回调）、`CliCommandService`（执行 CLI）等，
   权限 `com.aios.osbot.permission.EXTERNAL_AGENT`（signature|privileged）。
   → 详见 [08-internal-api.md](08-internal-api.md)
2. **进程内 facade**（我们目前在用的）：`OsbotFacade` 提供 facade/CLI，
   只有具备它的进程才能接管我们的 8771 数据面。这一层是**我们**的用法，不是小爱官方给第三方的 API。

## 2. 已知组成

| 组成 | 证据 | 说明 |
|---|---|---|
| `com.aios.osbot.external.ExternalAgentService` | Manifest（exported + 权限） | 官方外部 Agent 入口，interface_version=2 |
| `com.aios.osbot.external.CliCommandService` | Manifest（exported + 权限） | CLI 执行（含流式与上传流） |
| `com.aios.osbot.facade.OsbotFacadeImpl` | 静态类名（未混淆） | 能力桥 hook 的构造器 |
| CLI 绑定 | 实测日志 `bindCli: 拿不到 Context` | 进程启动早期拿不到 Context，随后成功 |
| facade 就绪 | `/health` 的 `facade=true` | 只有部分进程有 |
| leader 约束 | 实测 `本进程没有 facade/CLI，不接管数据面` | 没 facade 的进程主动让位 |

`OsbotFacadeImpl` 的类名**没有被混淆**，这类「平台级 SDK 名」通常保持原样，
是跨版本最稳的锚点之一。

## 3. 进程角色（与 01 呼应）

```
:core          没有 facade → 只做后台，不接管 8771
main           有 facade   → 通常成为 leader，承载 8771
voice_trigger  唤起时出现，是否有 facade 取决于版本
```

我们的判断逻辑（`BridgeLifecycle`）：先看 facade，再看 CLI；两者都没有就等 30 秒。

## 4. 意图 / 技能分发（已澄清大半）

| 问题 | 答案 | 证据 |
|---|---|---|
| 一句话怎么变成动作 | 主 Agent 拿到消息 → 模型决定调用哪个工具 → `ToolExecutor.execute` | `ToolRegistry` / `ToolExecutor` 静态 + 运行时工具采样 |
| 工具怎么注册 | `ToolRegistry.register` / `registerSystem`，按 Agent 的 allow/deny 过滤 | dexdump |
| 工具太多怎么办 | 常驻 20 个 + `tool_search` 检索 + `call_tool` 调用 | 运行时采样 |
| 本地 NLU | 有：`nlp_query` 直接返回语义解析 JSON | `assets/app_tools.json` |
| 技能怎么注册 | 文件驱动：`assets/agents/*/` + `skills/*/SKILL.md`，可 `reload_*` | assets + 工具清单 |
| 服务端下发 | 存在（A2A 索引 + 记忆云端），客户端只看得到缓存 | `a2a/index` 包 |

意图路由包：`com.aios.osbot.router`（284 类，含 `router/semantic` 15 类、`router/db` 10 类）。

## 5. 与我们模块的关系

| 我们的能力 | 走的路径 |
|---|---|
| `xiaoai_submit`（让小爱执行一句话） | facade / CLI |
| `xiaoai_cli`（原始 CLI 透传） | facade |
| 设备动作（打开 App、闹钟等） | **不经过小爱**，由我们 App 进程执行，经 8771 暴露 |

这三者要分清：前两个是「借用小爱的 Agent」，第三个是「我们自己的执行器」。
外部 Agent 看到的是同一个 8771，但底层归属不同（`工具归属` 表见 `ToolOwnership`）。

## 6. A2A：小爱的 Agent 协议层（静态发现）

在 `com.aios.osbot.a2a` 下有一整套 **Agent-to-Agent** 协议模型，说明小爱不只是
「一个助手」，它自己也在做多 Agent 编排：

| 包 | 类（节选） | 含义 |
|---|---|---|
| `a2a/model` | `A2aAgentSkill`、`RemoteSkillDetail` | 远端 Agent 的技能描述与详情 |
| `a2a/discovery` | `A2aSkillDetailCache`、`a2a.discovery.a`（38 方法） | 技能发现与缓存 |
| `a2a/index` | `A2aAgentIndex`、`A2aAgentIndexManager`、`A2aIndexEntry`、`A2aIndexSource`、`A2aIndexRender` | Agent 索引：来源、条目、渲染 |

要点（静态推断）：

- 技能是**可发现、可缓存、可渲染**的对象，不是硬编码 if-else；
- `A2aIndexSourceEnvelope` 说明索引来自外部来源（服务端或其它 App）；
- `A2aIndexRender` 说明技能最终要渲染进提示词/结果（与 30KB system 可能相关）。

> 这解释了为什么 30KB system 会变：其中的技能索引是按发现结果动态拼的。
> 具体拼装点与格式仍未定位（见 02 第 9 节）。

## 7. AppTool SDK：把 App 能力变成小爱工具（静态发现）

`com.aios.apptoolsdk` 是**给第三方 App 用的工具提供方 SDK**：

| 类 | 作用 |
|---|---|
| `AppToolProviderService` | Service，App 侧实现后向小爱暴露工具 |
| `Tool` / `ToolArgs` / `ToolDescription(s)` | 工具模型：名字、参数、描述 |
| `IAppToolProvider`（AIDL） | 跨进程调用接口 |
| `IPendingIntentJumpService` | 工具执行后跳转回 App（待确认） |

对我们的意义：

- 小爱支持「App 提供工具 → 小爱调用」这一模式，机制是 **AIDL + Service**；
- 我们的模块走的是另一条路（8771 MCP，主动让小爱执行），两者不冲突；
- 若要「把小爱工具链暴露给外部 Agent」，MCP 更直接；若要「让小爱调用我们的能力」，
  未来可以考虑实现这套 SDK（但需要注册/白名单，未验证）。

## 8. 已知未知


| 项目 | 状态 | 验证方法 |
|---|---|---|
| facade 暴露的方法清单 | 未知 | 反射 dump `OsbotFacadeImpl` 的 public 方法 |
| 是否有第二个 Agent 入口（非 facade） | 推测没有 | 搜 `submit`/`ask` 类方法 |
| 意图分发是否在本地 | 未知 | 断网后看本地技能是否仍生效 |
| 技能执行是否可被外部触发 | 部分（CLI 可） | 用 `xiaoai_cli` 逐个试 |
