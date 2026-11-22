# 07 混淆漂移与重定位配方

> 目的：小爱每次升级都会重命名，本文记录「哪些名字变了、怎么在 10 分钟内找回来」。

## 1. 已观测的漂移

| 版本 | 对象 | 结论 |
|---|---|---|
| 8.2.65.4714 | `sm.a` 配置、`sm.f` 视觉、`qm.*` 客户端 | 基线 |
| 8.2.70.8421 | 同上 | **这些没变**，只是白名单没加 |
| 8.2.70.8421 | `s92.b#d`（kotlinx Json 单例） | **失效**：字段名变了，解析回退为 null |
| 8.2.70.8421 | `JsonPrimitive` | 变成 `t92.r`，构造器 `(Object, boolean, <kind>)` |
| 8.2.70.8421 | `JsonObject` | 名字保留，但构造器是 `(Companion, Map)` |

教训：**类名保留 ≠ 字段/构造器保留**。定位要用结构，不要用名字。

## 2. 重定位配方

工具首选 `reverse/scripts/dexindex.py`（全量 dex 建索引，毫秒级查询），
`scan.py` 只用于只有 dexdump 文本时的兜底。

### 2.0 稳定锚点（本轮实测）

| 名字 | 是否被混淆 | 说明 |
|---|---|---|
| `com.aios.osbot.*`、`com.aios.apptoolsdk.*` | **否**（大量保留） | 平台级 SDK 名，跨版本最稳 |
| `com.aios.osbot.tools.Tool` 的 `getName` / `getParameters` | 否 | 工具接口 |
| `OsbotFacadeImpl` | 否 | 我们正在用的进程内入口 |
| `qm.i` / `qm.j` / `qm.b` / `um.h` | 短名，会漂移 | 模型客户端 |
| `sm.a` 的四个 getter | 否 | 配置 |
| `t92.r`（JsonPrimitive） | 会漂移 | 只按构造器形状识别 |

### 2.1 找配置对象

特征：一个类提供三个返回 `String` 的 getter（端点/密钥/模型）+ 一个 provider 标识。

```bash
python3 reverse/scripts/scan.py classes --contains getBaseUrl
python3 reverse/scripts/scan.py classes --contains getModelProviderId
```

### 2.2 找请求构造

```bash
python3 reverse/scripts/scan.py classes --contains buildOpenAIRequest
python3 reverse/scripts/scan.py classes --contains buildResponsesRequest
```

确认返回类型是 `kotlinx/serialization/json/JsonObject`，参数是 `(List, List, sm.a)`。

### 2.3 找客户端

```bash
python3 reverse/scripts/scan.py classes --contains streamCompletion
```

四个类里，`um.h` 是官方，其余是三方（由调用量统计的归属推出来）。

### 2.4 找 JSON 元素（改请求体时必须）

```bash
# 对象：实现了 Map 的 JsonElement
python3 reverse/scripts/scan.py classes --extends kotlinx/serialization/json/JsonElement --implements java/util/Map
# 字符串：构造器含 (Object, boolean, ...) 的类
python3 reverse/scripts/scan.py ctors --shape object-bool
```

运行期更简单：**用返回对象自己的 `getClass()` 和构造器重建**，完全不查名字
（我们在 `Hook.constructLike` / `newStringLike` 就是这么做的）。

## 3. 升级后的最小验证清单

```
1. /health 的 facade/cli 是否仍为 true
2. 日志里「配置 hook 完成 / provider 标识 hook 完成 / 各客户端 hook 完成」
3. xiaoai_submit 一次，看 stats.third 是否 +1（证明请求确实走了三方）
4. 看 xiaoai_prompt 的 stats.failures 是否增加（提示词链路）
5. `dexindex.py pkgs --prefix Lcom/aios/osbot/` 看包结构是否还在
6. 有问题就跑 dexindex.py / scan.py 重定位，更新本表
```

## 4. 反模式

- ❌ 用 `s92.b` 之类的短名硬编码（8.2.70 已证明会失效）
- ❌ 只按名字挂第一个重载（`$llm_client`）
- ❌ 拿 `dexdump` 文本直接 grep（UTF-16 编码）
- ❌ 把「hook 抛异常被 catch」当成「没走到」——要加计数
