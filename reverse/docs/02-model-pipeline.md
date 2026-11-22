# 02 模型链路

> 证据级别：静态（dexdump 签名）+ 实测（真机日志/请求形状）｜适用版本：8.2.70.8421

## 1. 一句话

小爱把「配置对象」和「请求构造」分开了：配置由 `sm.a` 提供四个 getter，请求由
`qm.*` 的 `build*Request` 装配成 kotlinx `JsonObject`，再由具体客户端
（`qm.i` / `qm.j` / `qm.b` / `um.h`）发出去。

## 2. 配置对象

`sm.a` 的 getter（我们已经在 hook）：

```
getBaseUrl()          -> 端点
getApiKey()           -> 密钥
getModel()            -> 模型名
getModelProviderId()  -> provider 标识（对应请求头 X-Model-Provider-Id）
```

实测：第三方端点生效时，日志里会出现 `X-Model-Provider-Id=xiaoi-plus`，说明小爱
**本来就会**把这个 getter 的值写进请求头——这是最"原生"的非文本渠道。

## 3. 四个客户端

| 类 | 协议 | 归类 | 证据 |
|---|---|---|---|
| `qm.i` | chat/completions | 三方 | `streamCompletion` + `buildOpenAIRequest` |
| `qm.j` | Responses API | 三方 | `streamCompletion` + `buildResponsesRequest` |
| `qm.b` | Anthropic Messages | 三方 | `streamCompletion` |
| `um.h` | 通用官方（Taiyi） | 官方 | `streamCompletion` |

调用链观察：`InstrumentedLlmClient → DynLlmClient → 具体客户端`，所以统计只挂具体客户端的
`streamCompletion`，否则同一次调用会记两次。

## 4. 请求构造（静态签名）

```
Lqm/i; buildOpenAIRequest$llm_client
      (Ljava/util/List; Ljava/util/List; Lsm/a;)
      -> Lkotlinx/serialization/json/JsonObject;
Lqm/j; buildResponsesRequest$llm_client
      (Ljava/util/List; Ljava/util/List; Lsm/a;)
      -> Lkotlinx/serialization/json/JsonObject;
```

参数含义：`messages`、`tools`、`config`。**第二个参数就是工具链**——这是后续剖析
function calling 的入口（见 `04-toolchain.md`）。

`$llm_client` 后缀说明 Kotlin 生成了重载：只挂「名字含 `buildOpenAIRequest` 的第一个」
会挂错方法（本次实测：只挂第一个时注入计数不涨，全部挂上后才涨）。

## 5. 请求体形状（实测）

从真机 hook 打出来的「形状」日志（不含正文）：

```
请求形状: 条数=53 roles=system,user,user,assistant,user,assistant,...
          长度=29870,3414,83,2,83,2,...
```

结论：

1. 第一条是 `system`，长度约 **30 KB**（小爱的人格 + 能力描述 + 工具说明）；
2. 之后是 `user/assistant` 交替，单条 83–136 字符左右；
3. 全部是 **`kotlinx.serialization.json.JsonObject`**，不是领域对象。

这条 30KB 的 system 就是「改人格」要动的东西——也是为什么只追加一句
"你叫小落" 往往无效：原文的人格描述太强。

### 5.1 `tools` 参数的真实形状（实测）

```
工具采样: qm.i messages=26 tools=java.util.ArrayList size=20
  agent, background_task, cli, cloud_sandbox, get_current_time, load_message,
  mcp, miot_ask_assistant, personaldata_search, read_file, remote_file,
  search_history, skill, url_fetch, var, web_search, write_file,
  memory_write, memory_read, memory_forget
```

- 类型是 `ArrayList<ToolDefinition>`，`ToolDefinition(name, description, parameters: JsonObject)`；
- 一次普通对话声明 **20 个**，其余 461 个靠 `tool_search` / `call_tool` 按需取；
- 所以「小爱是否启用 function calling」的答案是**是**。

详见 [04-toolchain.md](04-toolchain.md)。

## 6. JSON 模型的混淆形态（重要）

这是 P2 注入最初的失败点，记下来避免重复踩：

| 角色 | 混淆形态 | 结构 |
|---|---|---|
| 对象 | `JsonObject` | 实现 `Map`；字段 `Companion` + `a:Map`；构造器可能是 `(Map)` 或 `(Companion, Map)`（随混淆变化） |
| 字符串 | `t92.r`（原名 JsonPrimitive） | 构造器 `(Object, boolean, <kind>)` |
| 数组 | `JsonArray` | 构造器 `(Companion, List)` |

含义：

- **不能按类名 `kotlinx.serialization.json.JsonPrimitive` 去找**（被 R8 改名）；
- 但 `JsonObject` **实现了 `Map` 接口**，可以用 `instanceof Map` 安全识别；
- 重建时用「样本对象自己的构造器」而不是硬编码签名；
- 8.2.70 里旧的 `s92.b#d`（Json 单例）已经失效——**不要再用它解析字符串**。

## 7. 改写点评估（实测：A 方案无效）

| 方案 | 位置 | 现状 |
|---|---|---|
| A | `build*Request` 返回后 setResult | 已实现；`消息重建: ok=true`、`注入后 system 长度 123`，但**回复完全不变** → 返回值不是最终发送对象 |
| B | `streamCompletion` 入参 `args[0]`（`ArrayList<JsonObject>`） | 未实现；最接近网络层，优先级最高 |
| C | HTTP body 序列化处 | 未定位 |
| D | **走官方通道改人格** | `profile.json` / `update_custom_prompt` / `reload_prompt`，见 [08-internal-api.md](08-internal-api.md) |

A 方案的证据链（同一次请求）：

```
模型调用: 三方 qm.i model=deepseek-v4.1-flash
工具采样: qm.i … size=20
请求形状: 条数=27 roles=system,user,user,assistant,...
消息重建: ok=true ctors=kotlinx.serialization.json.JsonObject (1:Map,)
注入后 system 长度 123（block=123 替换=true）
提示词注入已生效（system-message）
→ 回复仍是「我是超级小爱…」
```

结论：**对象造出来了，但不是被发出的那一个**。下一步要么改 `streamCompletion` 的入参，
要么放弃注入、改用 D。

## 8. 验收标准（人格）

把 system 里的「超级小爱 / 小爱同学」定向替换成「小落」后：

- 问「你是谁」→ 回复自称「小落」；
- 问「设个闹钟」→ 能力仍然可用（确认没有破坏原生工具描述）；
- 连续三轮 → 注入标记只有一份（不累积）。

## 9. 已知未知

| 项目 | 状态 |
|---|---|
| `tools` 参数的真实结构 | **已确认**：`ArrayList<ToolDefinition>`，20 个 |
| 是否启用 function calling | **已确认**：是 |
| 30KB system 的生成来源 | 推测：`profile.json` + `prompt.md` + 记忆大纲 + 工具选择规则拼接 |
| 请求是否流式、如何拼装 | 未观察（hook 在调用前） |
| A 方案为何无效 | 未定位（返回值非最终对象） |
