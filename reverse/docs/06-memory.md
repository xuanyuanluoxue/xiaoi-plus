# 06 记忆系统

> 证据级别：实测（文件结构 + 工具实测）+ 静态（assets/包结构）｜版本：8.2.70.8421
>
> 结论先行：小爱的记忆是**云端为主 + 本地缓存**的「ClawMemory」子系统，
> 不是我们之前以为的「一个 MEMORY.md 文件」。

## 1. 三件套工具（运行时实测存在）

| 工具 | 分组 | 作用（取自工具描述） |
|---|---|---|
| `memory_write` | HISTORY | 写入记忆，`content` 为 Markdown（`# 类型 → ## 维度 → - 内容`），一轮对话只允许调用一次 |
| `memory_read` | HISTORY | 按类型/维度/日期批量精确读取；`memType` / `memKey` / `date` 至少给一个 |
| `memory_forget` | HISTORY | 删除记忆（`tool_enriched.json` 里未收录，运行时才出现） |
| `memory_search` | HISTORY | 语义检索（**向量 + 关键词**混合），工具描述原文：「系统**只**自动注入 [记忆大纲]（类型/维度索引，不含条目内容）」 |

最后一条极其重要：**小爱默认只把「记忆大纲」放进上下文，条目不自动进**。
这解释了为什么它的 system 只有 30 KB 而不是几十 MB。

## 2. 包结构（静态）

```
com/aios/osbot/memory/       208 个类
├── claw/                    203   ← ClawMemory 主体
└── topic/                     5   ← 记忆主题化（topic 合并/排序）
```

Manifest 里的记忆相关组件：

| 组件 | 权限 | 作用 |
|---|---|---|
| `com.aios.osbot.memory.claw.provider.MemoryOperatorProvider` | `com.aios.osbot.permission.OPERATOR_CLAW_MEMORY`（signature\|privileged） | 跨进程记忆操作 Provider |
| `com.aios.osbot.memory.claw.broadcast.MemoryBroadcastReceiver` | `com.xiaomi.aicr.permission.DEVICE_DATA_MEMORY` | 接收 `com.xiaomi.aicr.aimemory.ACTION_DEVICE_CLEAR_MEMORIES`（清空记忆） |
| `com.aios.osbot.sync.MiclawSyncProvider` / `MiclawSyncService` | `BIND_SYNC_ADAPTER` | 记忆同步（Miclaw = 记忆云） |

## 3. 本地文件（实测）

```
/data/data/com.miui.voiceassist/files/memory/
├── MEMORY.md            15 KB   明文，按 # / ## 分节
├── outline.md          650 B    每条记忆一行摘要（= 工具说的「记忆大纲」）
├── sandbox_research.md 2 KB
├── daily/                      按天记录
└── facts/                      事实条目
```

加密库（`claw_memory*.db` / `memory_data_*.db`）在别处，本子项目只做只读分析、不碰。

`MEMORY.md` 的分节（脱敏，只留结构）：

```
# 用户画像
  ## 基本信息 / 健康状况 / 兴趣爱好 / 生活习惯 / 技能经验 / 工具与环境
# 长期记忆
  ## 社交关系 / 工作项目 / 长期规划 / 重要事实
# AI助手身份
  ## 说话风格 / Terminal 开发 / …
```

`outline.md` 是扁平视图：`## 小节` + `- [小节] 摘要`，与小爱自己维护的索引对应。

## 4. 记忆生成流水线（assets/prompts）

`assets/memory-topic/`（16 个提示词）与 `assets/prompts/clawmemory/`（多组）揭示了管线：

| 提示词 | 阶段 |
|---|---|
| `prompt_memory_extraction_gate.txt` | 抽取前的**门控**（判断这句话值不值得记） |
| `prompt_memory_extract.txt` | 抽取（17 KB，最长的一份） |
| `memory_enhance_extract.txt` | 增强/补全 |
| `prompt_query_gate.txt` | 查询前的门控（判断要不要检索记忆） |
| `dreamer_clean.txt` / `dreamer_consolidate_*.txt` | 「记忆梦」：离线清理、合并、蒸馏 |
| `compress_memory_search.txt` / `compress_files_combined.txt` | 注入前压缩 |
| `memory-topic/*`（16 个） | 主题化：扫描、排序、合并、冲突解决、时间归一化 |

`assets/memory-topic/prompt.md`（8.8 KB）是主题化主提示词。

结论：记忆不是「写一条就用一条」，而是有
**门控 → 抽取 → 增强 → 主题化 → 压缩 → 注入**的完整链路，
且带一个类似「睡眠整理」的离线过程（`dailydream_*`）。

## 5. 我们自己的实现（P2）与官方的关系

| 维度 | 官方（通道 A） | 我们的 `MemoryService` |
|---|---|---|
| 存哪 | 云端 + 本地加密库 + `MEMORY.md` | 只写 `MEMORY.md` |
| 检索 | 向量 + 关键词 | 无（纯文件读） |
| 注入 | 只注入大纲 | 把 outline 前 N 行塞进 system（P2-5） |
| 删除 | `memory_forget` | **无删除入口**（保守设计） |

建议（未实施）：优先转调官方 `writeMemory` / `readMemory`，把 `MEMORY.md` 写入降级为
「兜底/离线」。这样不依赖提示词注入是否生效，也避免与官方流水线抢写。

## 6. 风险

| 风险 | 说明 | 对策 |
|---|---|---|
| 云端覆盖 | 记忆真相在云端，本地写会被回写 | 只追加，或改用官方工具 |
| 指令注入 | 记忆正文含指令性文本 | 注入时包围栏 + 免责句 |
| 编码 | 必须 UTF-8 无 BOM | 固定 UTF-8 无 BOM |
| 并发 | 小爱自己也写 | 备份 + 临时文件 + 原子 rename |
| 隐私 | 记忆是最敏感的数据 | 正文不进日志、不进导出包 |

## 7. 已知未知

| 项目 | 状态 | 验证方法 |
|---|---|---|
| 云端记忆的真正存储与同步协议 | 不可见 | 只能观察 `MiclawSyncService` 的调用 |
| `outline.md` 由谁生成、何时重建 | 未知 | 改 MEMORY.md 后看 mtime |
| `daily/` `facts/` 与 MEMORY.md 的关系 | 未知 | 逐日对比 |
| `memory_forget` 的作用范围（本地/云端） | 未知 | 谨慎试删一条测试数据 |
| 加密库表结构 | 不研究（边界） | — |
