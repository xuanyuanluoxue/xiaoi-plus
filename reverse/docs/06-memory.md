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

加密库（`claw_memory*.db` / `memory_data_*.db`）的密钥链与表结构见 §7。

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

## 7. 两个加密库（已解，静态 + 实测）

小爱有两套互不相干的本地库，都用自带 `net.zetetic` SQLCipher，但**密钥链完全不同**。

### 7.1 `databases/claw_memory.db` — 对话抽取（`MEMORY.md` 的来源）

```
SharedPreferences(claw_memory_db_security)
├── db_enc_passphrase_v1 = base64(AES-256-GCM 密文)
└── db_enc_iv_v1         = base64(IV)
        ▼ AndroidKeyStore 别名 claw_memory_db_master_key（AES-256-GCM，无需用户认证）
     32 字节随机密钥 → 小写 hex（64 字符）= SQLCipher 口令
```

表（Room v11）：

| 表 | 主要列 | 作用 |
|---|---|---|
| `conversation` | `session_id` / `query` / `answer` / `timestamp` / `agent_id` / `memory_extracted` | 全部会话问答原文 |
| `memory_meta` | `mem_key` / `mem_value` / `mem_type` / `mem_filename` / `mem_dir` / `mem_source` / `mem_extractor` / `timestamp` / `status` | 抽取出的记忆条目，`MEMORY.md` 就是它的渲染 |
| `memory_meta_new` | 同上（无 `mem_extractor`） | 迁移中的新表 |
| `memory_file_sync_state` | `file_key` / `content_hash` / `file_mtime` / `file_size` | 落盘文件的同步状态（改写 `MEMORY.md` 前后比对） |

工作进程（`com/aios/osbot/memory/claw/worker/`）：`SessionWorker` / `FlowWorker` /
`TopicWorker` + `scheduler/photo/*`。实测：对小爱说一句后 **10 秒内**
`memory_meta` 多出一条（`mem_extractor=conv_worker`），`MEMORY.md` 同时被改写。

> 本项目已在 `platform/agent/ClawMemoryDb` 里实现只读访问（`xiaoai_memory op=stats/live`）。

### 7.2 `databases/memory_data_<uid>.db` — 官方「记忆」（云端的本地副本）

```
MMKV files/mmkv/default → KEY_STORE_PASSWORD
        ▼
files/MemoryKeyStore.jceks（KeyStore.getDefaultType()，别名 MemoryKey，AES）
        ▼ SecretKey.getEncoded() 的 32 字节
SQLCipher（Room 的 SupportOpenHelperFactory(byte[])）
```

表（`com.xiaomi.voiceassistant.database.memory.MemoryDbHelper`）：

| 表 | 主要列 | 作用 |
|---|---|---|
| `memory_public_table` | `id` / `maskuid` / `maskDeviceId` / `entityId` / `name` / `nickName` / `relationship` / `category` / `secondCategory` / `meta` / `syncStatus` | 人物/实体档案 |
| `memory_private_table` | `id` / `maskuid` / `entityId` / `category` / `secondCategory` / `meta` | 隐私条目（号码、地址…） |
| `memory_relation_table` | `entityId` / `name` / `relationship` / `meta` / `syncStatus` | 实体关系 |
| `image_table` | `id` / `entityId` / `privacyId` / `base64RawData` | 记忆配图（base64 存库） |
| `collect_data_table` | `id` / `favoriteData` / `syncStatus DEFAULT 1` | 收藏数据（迁移脚本里建的） |

`syncStatus`（静态 + 实测）：

| 值 | 含义 | 证据 |
|---|---|---|
| `1` | **已同步**（云上已有） | 用 `write_memory` 写一条后，`memory_public_table` 从 8 → 9 且新行 `syncStatus=1`；`collect_data_table` 42 行全为 1 |
| `2` | **待同步** | `changeDbStatusToNeedSync()` 置 2，日志原文「修改状态为待同步」 |
| `NULL` | 该行不参与同步 | `memory_relation_table` 的唯一一行是 NULL |
| `0` | 未观测到 | — |

实测当前设备：`memory_public_table` 9 / `memory_relation_table` 1 / `memory_private_table` 2 /
`collect_data_table` 42 / `image_table` 0，**没有待同步行**——因为 `write_memory` 直接写云端，
本地这条是回填的副本。

`read_memory` / `write_memory`（通道 A）落的就是这套模型：`read_memory` 返回的
`entityMemories` / `privacyMemories` / `generalMemories` 与上面三张表一一对应。

## 8. 联网同步：云端记忆 API（静态，未主动调用）

### 8.1 端点

```
POST https://api.open.xiaomixiaoai.com/rag/open/user/memory/common?method=<action>
```

`<action>` 取自 14 个常量（`classes3.dex → Lfg0/p;`，Retrofit 接口）：

| action | 请求体 | 作用 |
|---|---|---|
| `getByPage` | `GetByPageRequestBody` | 分页拉取记忆 |
| `extractMemory` | `ExtractMemoryRequestBody` | 云端抽取（`sessionQa[]` + `source` + `needSave` / `needVerify`） |
| `insertMemory` | `hg2.g0`（JSON） | 新建 |
| `updateMemory` / `upsertFavoriteData` | JSON | 更新 |
| `deleteMemory` | `MemoryDeleteRequestBody{category, ids[]}` | 删除单条 |
| `deleteAllSyncData` | JSON | 清空同步数据 |
| `syncMemory` | `MemorySyncBean{deleteAll, existingMemoryIds[], modifyMemories}` | **本地→云端批量同步** |
| `syncCloudMemory` | JSON | 云端→本地（下拉） |
| `syncHistoryUserData` | JSON | 历史用户数据同步 |
| `syncFavoriteData` / `deleteFavoriteData` | `FavoriteDataSyncBean` | 收藏同步 |
| `legalityCheck` | `LegalityCheckBean{meta, nickName}` | 写入前合法性/重名校验 |
| `syncFestivalInfo` / `syncFestivalInfoCallback` | 无 / JSON | 节日信息同步 |

响应统一是 `CommonMemoryResponse{code, msg, data{aboutme, memories, ...}}`，
`code=200` 成功；`3001` / `30041` / `30042` 对应「多个同名同关系的人」「已存在同名同关系」
「已存在关系为本人」这类业务错误（`MemoryRepo` 里带中文常量）。

### 8.2 鉴权

请求头（`ah1.n0.getHeader()`）：

| 头 | 来源 |
|---|---|
| `rid` | 每次随机（`APIUtils.randomRequestId(false)`） |
| `userid` | `baselibrary.utils.g.getCurUserId()` |
| `appid` | `r31.a.getAccountClientId()` |
| `deviceid` | `d91.e.getDeviceId()` |
| `Cookie` | `"<appid>_serviceToken=<token>;userId=<uid>"`（`r31.c$b.getCookieFromToken`） |

即 **MiPassport 的 serviceToken**，走 Cookie 而不是 Bearer。token 由 Passport SDK 维护，
过期由 `AccountAuthenticator` 续。

### 8.3 同步方向与触发

```
                    写路径
write_memory / 语音写入 ──► 本地 memory_data_*.db（syncStatus=2 待同步）
                                    │  (changeDbStatusToNeedSync)
                                    ▼
                          gh1.p1 同步器（MemorySyncHelper）
                            ├ getAllModifiedData / getModifiedCollectData
                            ├ syncToServer  → POST syncMemory（分页、existingMemoryIds 去重）
                            ├ handleFailData → 失败的置回待同步 / 记失败
                            └ doSyncIfNeed  ← onNetworkConnectivityChanged（网络恢复时补传）
                    读路径
read_memory ──► getByPage / syncCloudMemory（云端） + 本地 memory_data 库
```

- 同步是**网络恢复 + 本地标脏**驱动的，没有固定心跳；
- 一次 `syncMemory` 带 `existingMemoryIds`（云端已有的 id）与 `modifyMemories`（增量），
  即「差量上传」，服务器按 id 决定 insert/update；
- `extractMemory` 是另一条线：把 `sessionQa[]` 交给云端抽记忆，返回本地再落库。

### 8.4 与我们 MCP 的关系

| 我们的 op | 数据源 | 与同步的关系 |
|---|---|---|
| `official` / `official_write` | 通道 A `read_memory` / `write_memory` | 直接走云端（写入即云上可见，实测约 8 s 抽取延迟） |
| `get` / `append` / `replace` | `MEMORY.md` | 本地渲染，**不参与云端同步** |
| `stats` / `live` | `claw_memory.db` | 对话抽取，**不参与云端同步**（另一套管线） |

结论：**`claw_memory.db` 目前只在本地，云同步只覆盖 `memory_data_*.db` 这套官方记忆。**

判断「这条记忆有没有上云」已经落地为 `xiaoai_memory op=sync`：进程内反射
`MemoryDbHelper.createDb(Context)` 拿小爱自己的 Room 实例（不用我们解 JCEKS 密钥），
读各表条数、`syncStatus` 分布与 `syncStatus=2` 的行（只回 id/name/关系/时间，不回 `meta`）。

## 9. 已知未知

| 项目 | 状态 | 验证方法 |
|---|---|---|
| `syncStatus` 0/1 的精确语义 | 未确认（2=待同步已确认） | 读一条刚写入的官方记忆再看同步前后取值 |
| `claw_memory.db` 是否也会上云 | 静态看没有对应调用 | 改一条 `memory_meta` 后看云端 `getByPage` 是否出现 |
| `MiclawSyncService` / `MiclawSyncProvider` 的独立通道 | 只看到 Manifest 声明 | 抓 binder 调用或看日志 |
| 云端 `getByPage` 的分页与排序 | 未调用过 | 用 Cookie 直接打一次 API |
| `outline.md` 的重建时机 | 部分已知（抽取时同写） | 改 `MEMORY.md` 后看是否被覆盖 |
