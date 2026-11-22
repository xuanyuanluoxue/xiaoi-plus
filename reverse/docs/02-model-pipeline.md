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
2. 之后是 `user/assistant` 交替，单条 83 字符左右；
3. 全部是 **`kotlinx.serialization.json.JsonObject`**，不是领域对象。

这条 30KB 的 system 就是「改人格」要动的东西——也是为什么只追加一句
"你叫小落" 往往无效：原文的人格描述太强。

## 6. JSON 模型的混淆形态（重要）

这是 P2 注入最初的失败点，记下来避免重复踩：

| 角色 | 混淆形态 | 结构 |
|---|---|---|
| 对象 | `JsonObject` | 实现 `Map`；字段 `Companion` + `a:Map`；构造器 `(Companion, Map)` |
| 字符串 | `t92.r`（原名 JsonPrimitive） | 构造器 `(Object, boolean, <kind>)` |
| 数组 | `JsonArray` | 构造器 `(Companion, List)` |

含义：

- **不能按类名 `kotlinx.serialization.json.JsonPrimitive` 去找**（被 R8 改名）；
- 但 `JsonObject` **实现了 `Map` 接口**，可以用 `instanceof Map` 安全识别；
- 重建时用「样本对象自己的构造器」而不是硬编码签名；
- 8.2.70 里旧的 `s92.b#d`（Json 单例）已经失效——**不要再用它解析字符串**。

## 7. 改写点评估（进行中）

| 方案 | 位置 | 现状 |
|---|---|---|
| A | `build*Request` 返回后 setResult | 已实现；实测注入计数会涨，但**回复未变**——疑似返回值不是最终发送对象 |
| B | `streamCompletion` 入参 `args[0]`（messages 列表） | 未实现；最接近网络层，优先级最高 |
| C | HTTP body 序列化处 | 未定位（未见 okhttp 字符串，可能自研/其它栈） |

下一步（按顺序）：

1. 看 `消息重建: ok=?` 日志，确认 A 方案里 JsonObject 是否真的重建成功；
2. 若重建成功但回复仍不变 → 判定 A 无效，改做 B：在 `streamCompletion` 前把
   `args[0]` 换成改好的列表；
3. 若 B 仍无效 → 找序列化点（C）。

## 8. 验收标准（人格）

把 system 里的「超级小爱 / 小爱同学」定向替换成「小落」后：

- 问「你是谁」→ 回复自称「小落」；
- 问「设个闹钟」→ 能力仍然可用（确认没有破坏原生工具描述）；
- 连续三轮 → 注入标记只有一份（不累积）。

## 9. 已知未知

| 项目 | 状态 |
|---|---|
| `tools` 参数的真实结构 | 未知，待 dump 全量 dex |
| 是否启用 function calling | 未知（第二参数存在，但可能为空数组） |
| 30KB system 的生成来源 | 推测：服务端下发 + 本地模板拼接；未验证 |
| 请求是否流式、如何拼装 | 未观察（hook 在调用前） |
