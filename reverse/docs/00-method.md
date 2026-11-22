# 00 方法论

## 1. 三条证据线

| 线 | 手段 | 能回答什么 | 局限 |
|---|---|---|---|
| 静态 | `dexdump` / `aapt2` / 资源树 | 类结构、方法签名、字符串、组件清单 | 混淆名会漂移；看不懂运行时值 |
| 动态 | `logcat` + 我们的 hook 日志 + 8771 MCP | 谁被调用、参数形状、时序 | 只能观察被 hook 的点 |
| 文件 | `/data/data/com.miui.voiceassist/` | 配置、记忆、技能缓存 | 加密库读不了 |

三者必须交叉：静态给「在哪」，动态给「有没有跑」，文件给「跑完留下什么」。

## 2. 定位策略：按结构，不按名字

小爱每个版本都会重新混淆。硬编码 `qm.i` 这种名字的代价是：升级即失效（8.2.70 的
`sm.a` 还在，但 Json 单例已经从 `s92.b#d` 变成了别的形状）。

稳定的定位特征，按可靠性排序：

1. **方法名**（Kotlin/序列化/HTTP 相关名字常被保留）
   - `buildOpenAIRequest`、`buildResponsesRequest`、`streamCompletion`、`getSupportsVision`
2. **签名组合**
   - `(List, List, sm.a) -> kotlinx.serialization.json.JsonObject`
   - `streamCompletion(messages, tools, config, continuation)`
3. **字符串常量**
   - `api_key`、`base_url`、`X-Model-Provider-Id`、`instructions`
4. **结构形状**
   - 「持有 `Map` 字段并实现 `Map` 的类」= JsonObject
   - 「构造器 `(Object, boolean, <kind>)`」= JsonPrimitive

`scripts/scan.py` 就是按这四类特征搜索的。

## 3. 动态取证的做法

优先用**我们自己的模块**当探针，而不是上 Frida：

- 已经有 4 个客户端的 `streamCompletion` hook（统计用），可以在不改产品的分支上临时
  加日志，拿到真实请求形状；
- `xiaoai_logs` 是跨进程合并视图，一次调用能看到三个进程的同一时刻；
- 敏感内容只打**长度/计数/布尔**，不打正文（这条在 P2 踩过坑）。

## 4. 产出规范

每篇文档必须包含：

1. **结论表**：一句话结论 + 证据级别 + 适用版本；
2. **原始证据**：命令与输出片段（脱敏）；
3. **重定位方法**：换版本后怎么重新找到它；
4. **已知未知**：明确列出没搞清的部分与验证办法。

## 5. 一次分析的典型顺序

```
pull-apk.sh          # 确认版本与 codePath（运行中的那份）
dump-dex.sh          # 补齐 dump（增量）
scan.py classes ...  # 先按方法名找入口
scan.py strings ...  # 再看字符串确认语义
runtime-logs.sh      # 真机触发一次，看是否被调用
写 docs/findings/xx.md
```

## 6. 反模式

- ❌ 拿出厂 APK 当运行版本（`/product/priv-app/...` 常年不更新）
- ❌ 用 `dexdump` 输出直接 `grep`（PowerShell 重定向写 UTF-16，要按 `utf-16` 读）
- ❌ 只挂同名方法里的第一个（Kotlin 会生成 `xxx$llm_client` 之类的重载）
- ❌ 把「hook 抛异常被 catch」当成「没走到」
