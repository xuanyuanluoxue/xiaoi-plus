# 04 工具链与函数调用（持续剖析）

> 证据级别：静态（签名）+ 实测（请求形状）｜状态：**进行中**

## 1. 为什么先看这里

本项目的目的不是「让小爱变强」，而是**把它的工具链接出来给外部 Agent 用**。
所以第一优先级是搞清：小爱自己有没有 function calling、声明了哪些工具、
参数 schema 长什么样。任何发现都应该优先落成 MCP 工具。

## 2. 已知事实

1. 请求构造的**第二个参数是 `tools`**：

   ```
   buildOpenAIRequest$llm_client(List messages, List tools, sm.a config) -> JsonObject
   ```

2. 真机请求形状里观察到的顶层字段（chat 链路）：
   - `messages`（53 条，`system` 30KB + 对话往返）
   - `tools` 是否存在、内容是什么：**尚未确认**

3. 我们模块已经能把设备动作以 MCP 工具形式暴露（17 个工具，桥 11 + 动作 6），
   这条路与「小爱自己的 tools」是两条独立的线。

## 3. 要回答的问题

| # | 问题 | 方法 |
|---|---|---|
| Q1 | `tools` 在真实请求里是空数组还是带 schema？ | hook 打印 `sizeOf(args,1)` + 顶层 key |
| Q2 | 小爱是否解析模型返回的 `tool_calls`？ | 搜 `tool_calls` 字符串；看响应处理 |
| Q3 | 工具的 schema 从哪来（本地常量/服务端）？ | 找包含 `"type":"function"` 的字符串 |
| Q4 | 有没有本地工具注册表（类名→handler）？ | 扫 `Map<String, ...>` 静态字段 + 反射注册 |
| Q5 | 工具调用是否走 facade/CLI？ | 触发一次工具调用看调用栈 |

## 4. 扫描配方

```bash
# Q1/Q2：字符串面
python3 reverse/scripts/scan.py strings --grep tool_calls
python3 reverse/scripts/scan.py strings --grep '"type":"function"'

# Q3/Q4：结构面
python3 reverse/scripts/scan.py classes --contains ToolRegistry
python3 reverse/scripts/scan.py classes --field-type java/util/Map --name-hint Tool
```

## 5. 产出原则

- 每找到一个工具定义，记录：名字、参数、归属（小爱本地 / 服务端 / 我们的）、
  触发条件、是否需要确认。
- **能通过 MCP 暴露的，优先做 MCP 工具**，不要塞进提示词让模型自己想办法。
- 需要二次确认的危险动作，走现有 `xiaoai_confirm` 流程，不要新造一套。

## 6. 当前结论（静态扫描已落地）

`scan.py strings` 的实测命中：

| 字符串 | 命中 | 意义 |
|---|---|---|
| `function_call` | 11（classes27） | 实现里有函数调用分支 |
| `tool_calls` | 5（classes15×1、classes23×4） | 解析模型返回的工具调用 |
| `parameters` | 5 | 工具参数 schema |
| `tools` | 153 | 工具列表广泛使用 |
| `tool_choice` | 0 | 可能不支持强制指定 |

配套的静态类（`com.aios.apptoolsdk`）：

```
AppToolProviderService     第三方 App 提供工具的 Service
Tool / ToolArgs / ToolDescription(s)   工具模型
IAppToolProvider (AIDL)    跨进程接口
IPendingIntentJumpService  执行后的跳转接口
```

结论：**小爱有自己的 function calling loop**（声明工具 → 模型返回 tool_calls →
本地执行 → 回填），并且对外开放了 App 侧 SDK。

两件仍未确认：

1. 真实请求里的 `tools` 是空数组还是带 schema（需要一次运行时采样）；
2. 这套 loop 能否被外部触发（通过 facade/CLI），还是只服务于小爱自己的意图。

## 7. 下一步（按性价比）

1. **运行时采样 `tools` 长度**：在现有 `recordLlmCall` 打印 `sizeOf(args,1)`，
   一次对话即可判定「有没有声明工具」；
2. 若 > 0：打印 `tools` 内元素的**键名**（不含正文），确认 schema 形状；
3. 找 `tool_calls` 的消费方：按字符串定位类，看分发逻辑；
4. 若可外部触发：优先做 MCP 工具（`xiaoai_tools_list` / `xiaoai_tool_call`）。

> 待办：`runtime-logs.sh` 增加 tools 长度采集；`scan.py` 增加 function-schema 规则。

