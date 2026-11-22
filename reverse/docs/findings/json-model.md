# findings: kotlinx JSON 模型的混淆形态

> 证据级别：实测（真机 hook 日志）+ 静态（dexdump 构造器）｜版本 8.2.70.8421
> 起因：P2 提示词注入「日志说成功、模型没反应」，最后定位到这里。

## 1. 现象

```
content 诊断: null=false class=t92.r scalar=true map=false list=false
字符串元素构造器: t92.r (3:Object,boolean,f,) (5:Object,boolean,f,int,DefaultConstructorMarker,)
注入后 system 长度 123（block=123 替换=true）
提示词注入已生效（system-message）
→ 但回复仍是小爱原人格
```

第一次失败更早：用 `s92.b#d`（历史文档里的 kotlinx `Json` 单例）解析字符串，
直接 `NoSuchFieldException` —— **字段名在新版本变了**。

## 2. 真实结构

| 角色 | 运行时类 | 关键结构 |
|---|---|---|
| 对象 | `kotlinx.serialization.json.JsonObject`（名字保留） | 实现 `Map`；字段 `Companion:Companion`、`a:Map`；构造器 `(Companion, Map)` |
| 字符串 | `t92.r`（R8 改名，原名 `JsonPrimitive`） | 构造器 `(Object, boolean, <kind>)` |
| 数组 | `kotlinx.serialization.json.JsonArray` | 构造器 `(Companion, List)` |

## 3. 正确的重建方式

不要查类名，用**样本对象自己的构造器**：

```java
// 1) 复制 Map（JsonObject 实现了 Map，可直接 entrySet）
Map<String,Object> copy = new LinkedHashMap<>();
for (Map.Entry<?,?> e : ((Map<?,?>) jsonObject).entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());

// 2) 造字符串元素：找 (Object, boolean, ...) 构造器，额外参数从样本字段取
Object prim = newStringLike(oldPrimitive, "新文本");

// 3) 造对象：找 (Companion, Map)，Companion 从静态字段取
Object rebuilt = constructLike(sample, copy);
```

`constructLike` 的匹配顺序（缺一不可）：

1. 单参数 `(Map)` / `(List)` —— 常规 Kotlin 类；
2. `(Companion, Map/List)` —— kotlinx 的对象/数组；
3. 字符串元素：`(String)` → `(String, boolean)` → `(Object, boolean, …)`。

## 4. 踩坑清单

- ❌ 用 `Class.forName("kotlinx.serialization.json.JsonPrimitive")` —— 被改名；
- ❌ 用 `parseToJsonElement` 重新解析 —— 需要一个 Json 单例，而单例的持有类/字段名会变；
- ❌ 只按单参数构造器重建 —— JsonObject 是 `(Companion, Map)`，会静默失败；
- ❌ 重建失败时仍然 `setResult` —— 我们一开始就是「计数成功但内容没变」。

## 5. 仍未解决

即使重建成功（`注入后长度` 已变成我们的 123 字符），**模型回复仍未改变**。
说明这个构造方法的返回值可能不是最终发送的对象。下一步改注入点：

```
streamCompletion(messages, tools, config, continuation)
  → 在 before 阶段替换 param.args[0] 为改好的消息列表
```

验证方法：替换后问「你是谁」，回复应自称「小落」；若仍不变，再找序列化点。

## 6. 复现命令

```bash
# 运行时形状（需模块 debug 日志）
bash reverse/scripts/runtime-logs.sh --grep 请求形状
bash reverse/scripts/runtime-logs.sh --grep 字符串元素构造器

# 静态：确认构造器形状
python3 reverse/scripts/scan.py classes --ctor-shape object-bool
```
