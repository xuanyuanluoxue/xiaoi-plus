# 05 技能（skill）清单与触发

> 证据级别：静态（未完成）+ 推测｜状态：**待剖析**

## 1. 先定义「技能」

在小爱语境里，用户说「设个明早七点的闹钟」，走的不是大模型推理，而是**本地技能**：
意图识别 → 参数抽取 → 本地执行。这类能力通常满足：

- 断网可用（至少部分）；
- 响应极快（毫秒级，不经过模型）；
- 有固定话术模板。

我们观察到：问「17 乘以 23」会走模型（三方调用计数 +1），问「现在几点」常常不走，
这支持「本地技能 + 模型兜底」的双层结构（推测）。

## 2. 可能的技能来源

| 来源 | 位置 | 说明 |
|---|---|---|
| APK assets | `assets/` | 部分技能配置/话术模板 |
| 数据目录 | `/data/data/com.miui.voiceassist/files/` | 下发的技能包/缓存 |
| SharedPreferences | `shared_prefs/` | 技能开关 |
| 服务端 | — | 不可见，只能观察行为 |

> TODO：`report.sh` 加上 assets 清单与 `files/` 目录树（脱敏，只列结构不列内容）。

## 3. 枚举计划

1. `aapt2 dump resources` 找 `skill` / `intent` / `command` 相关资源名；
2. `scan.py strings --grep skill` 找类名/JSON 键；
3. 真机断网测试：同一句话断网前后是否都能答，区分本地/云端；
4. 用 `xiaoai_cli` 逐个试已知命令，记录可用性与参数。

## 4. 我们关心的部分

不是「复刻技能」，而是：

- **哪些技能可以被外部 Agent 直接调用**（通过 CLI / MCP）；
- 哪些技能需要用户确认（安全边界）；
- 技能调用是否产生模型调用（影响计费口径）。

## 4. 静态发现：技能是 A2A 对象（不是 if-else）

`com.aios.osbot.a2a` 下的类说明技能体系是**协议化**的：

| 类 | 作用 |
|---|---|
| `A2aAgentSkill` | 一个 Agent 技能：id、名称、描述、输入输出（28 个方法） |
| `A2aSkillDetailCache` | 技能详情缓存 |
| `RemoteSkillDetail` | 远端下发的技能详情 |
| `A2aAgentIndex` / `A2aAgentIndexManager` | Agent 索引：注册、查询、缓存 |
| `A2aIndexEntry` / `A2aIndexSource` / `A2aIndexSourceEnvelope` | 索引条目与来源信封 |
| `A2aIndexRender` | 把索引渲染成提示词/结果的一部分 |
| `a2a.discovery.a`（38 方法） | 发现流程（协程） |

由此可以推断（**推测**，待验证）：

1. 技能来自**多个来源**（本地 + 远端），有索引与缓存；
2. 索引会被渲染进请求（很可能就是 30KB system 的一部分）；
3. 技能的启用/停用可能是一个来源过滤，而不是代码分支。

## 5. 枚举计划

1. `scan.py classes --class-contains com/aios/osbot/a2a` 全量列表（已做，见 findings）；
2. 找索引的**持久化位置**：`/data/data/.../files` 或 `databases`，看有无 json/db；
3. 断网对比：同一句话断网前后是否都能答，区分本地/远端技能；
4. `A2aIndexRender` 的方法签名→渲染格式（是否就是 system 里的技能段）。

## 6. 已知未知


| 项目 | 状态 |
|---|---|
| 本地技能总数 | 未知 |
| 是否存在技能 manifest | 未知 |
| 技能是否可被 `xiaoai_cli` 触发 | 部分（CLI 能力有限） |
| 服务端技能列表 | 不可见 |

> 结论暂缺。等 `assets` 与 `files/` 清单出来后再补本节。
