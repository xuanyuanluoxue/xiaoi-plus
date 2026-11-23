# xiaoi-plus MCP 工具参考

协议：JSON-RPC 2.0 over HTTP，单端点 `POST /mcp`，无会话。
鉴权：`Authorization: Bearer <token>`。

**入站画像**：`POST /mcp?profile=readonly` 只暴露
`xiaoai_status` / `device.info` / `app.open` / `alarm.set` / `media.control` /
`notification.send`，其余工具调用返回 `FORBIDDEN_TOOL`。
双向枢纽写进小爱 `mcp_servers.json` 的 url 就带这个参数，防止「小爱调我们的 submit
→ 我们再调小爱」的自递归；外部 Agent 用不带参数的 `/mcp` 拿到全部工具。

**0.16.0 起只有一个端口 `8771`**：App 进程不再单独监听，设备动作工具由 App 执行但
从同一个端点调用（共 24 个工具：桥 18 + 设备动作 6）。
App 未就绪时返回 `AGENT_UNAVAILABLE`，`/health` 的 `agent=ipc|down` 可判断；
失败带 `retryable:true`。

约束（全部来自 `Contract.java`）：请求体 ≤64 KB；`submit.text` ≤4000；
`ask.text` ≤8000；`notify.text` ≤2000；`todo.title` ≤200 / `content` ≤8192；
`logs.limit` 缺省 80 / 上限 300 / `0`=空；`stats.days` 上限 90；
`audit.limit` 1~200；确认 token 60 秒一次性；
`prompt.text` ≤8192；`memory.text` ≤4 KB / 文件 ≤512 KB / `section` ≤64 字符 /
`memory.limit` 缺省 50 / 上限 200。

## 协议方法

### initialize

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-03-26",
    "capabilities": {},
    "clientInfo": {"name": "my-agent", "version": "1.0"}
  }
}
```

返回：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-03-26",
    "capabilities": {"tools": {"listChanged": false}},
    "serverInfo": {"name": "xiaoi-plus", "version": "0.12.0-beta.1"},
    "instructions": "小爱同学+ 统一 MCP 端点（8771）：24 个工具都从这里调用..."
  }
}
```

`serverInfo.version` 是当前 APK 版本（0.16.0 起是 `0.16.0`）。

### tools/list

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
```

### tools/call

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call",
 "params":{"name":"xiaoai_status","arguments":{}}}
```

返回结构：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{"type": "text", "text": "facade=true cli=true port=8771 version=0.12.0-beta.1"}],
    "isError": false
  }
}
```

客户端必须检查 `result.isError`，文本在 `result.content[0].text`。

## 工具

### xiaoai_status

参数：无。

返回示例：

```text
facade=true cli=true port=8771 version=0.16.2 hooks=ok compat=whitelist xiaoai=8.2.70.8421 rewrite=hit
```

- `facade=false` 或 `cli=false`：小爱进程未就绪。
- `port=0`：没有数据面 leader（MCP 关闭或正在切换）。
- `hooks=ok|partial|none`：本进程实际挂上了几个关键 hook（配置改写 / provider 标识 /
  视觉声明 / 能力桥）。`ok` 才算挂全；`partial` 说明部分类没找到；`none` 说明
  一个都没挂上。
- `compat=whitelist|probe|none`：启用依据。`whitelist` 是版本在已验证白名单；
  `probe` 是早期加载读不到版本而放行；`none` 是版本不在白名单、已主动 no-op。
- `xiaoai=<版本>`：设备上小爱实际的 versionName。判断「要不要适配新版本」的唯一依据。
- `rewrite=hit|none|?`：**改写是否真的生效**。`hit` 小爱读过被覆盖的 getter；
  `none` 已经发生过模型调用但 getter 一次没被读，说明该版本不吃这套 hook；
  `?` 还没发生调用，暂时无法判断。
- `apptools=ok methods=N` / `apptools=none`：小爱进程内 AppTool 通道是否可用，
  `N` 是 `UIAgentServiceForOSBot` 声明的方法总数（含 binder 样板，非业务方法数）。
  `none` 表示拿不到该类，`xiaoai_nlp` 会返回 `APPTOOL_UNAVAILABLE`。
- `clawdb=ok|none`：加密记忆库（`claw_memory.db`）通道。`ok` 表示 AndroidKeyStore 里的
  主密钥能解出口令（`xiaoai_memory op=stats/live` 可用）；`none` 只影响这两个 op。

排查顺序：`hooks=none` + `compat=none` → 版本没进白名单；`hooks=ok` + `rewrite=none`
→ 装上了但该版本不读这些 getter（不兼容）；`hooks=ok` + `rewrite=hit` 但回复不对 →
问题在配置（模型/端点/密钥），不在 hook。

**「装上了」不等于「生效了」**：`hooks=ok` 只说明 hook 挂成功，`rewrite=hit` 才是证据。

旧版本：≤0.16.0 只有前四个字段；0.16.1 有 `hooks`/`compat`/`xiaoai`；
`rewrite` 从 0.16.2 起，`apptools` 从 0.18.0 起。

### xiaoai_nlp

把小爱自己的语义解析结果以 JSON 返回（小爱进程内 `nlp_query`，**不经过对话模型**）。
返回的是「这句话会触发什么」的执行计划，不是槽位抽取：

```json
{"type":"other",
 "instruction_types":["Template.ResultOperationInfo","System.CheckScreenUnlocked",
   "UIController.Navigate","Template.Query","Application.Operate",
   "SpeechSynthesizer.Speak","Template.Toast"],
 "instruction_count":7}
```

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `text` | string | 是 | 要解析的一句话（≤500 字符） |

- `instruction_types` 即小爱的能力分组：`Application.Operate`（操作 App）、
  `Template.Query`（检索/问答）、`UIController.Navigate`（界面跳转）、
  `SpeechSynthesizer.Speak` 等。外部 Agent 可据此判断「小爱能不能直接执行、
  属于哪类」，再决定走 `xiaoai_submit` 还是设备动作工具。
- **只适合本机解析的语句**（打开 App、设闹钟、记事等，毫秒级返回）。
  音乐/天气这类要联网的语句在小爱内部**不返回**：8 秒后回
  `{"ok":false,"error":"CALL_TIMEOUT",...}`，且那条 binder 线程仍占着，
  之后一段时间再调回 `{"ok":false,"error":"NLP_BUSY",...}`。
  这类语句请直接用 `xiaoai_submit`。
- 错误码：`APPTOOL_UNAVAILABLE`（AppTool 通道不可用）、`CALL_TIMEOUT`、
  `NLP_BUSY`、`CALL_FAILED`。不会抛异常，也不回空对象。
- `nlp_query` 独占自己的线程池，卡住不影响 `xiaoai_memory` / `xiaoai_todo_create`。

### xiaoai_submit

让小爱执行一句话任务。默认返回回复文本；`structured:true` 时返回
`{"ok":true,"text":"...","sessionId":"..."}`，便于多轮复用。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `text` | string | 是 | 要说给小爱的话（≤4000 字符） |
| `sessionId` | string | 否 | 复用已有会话以保留上下文；不传则自动 `session.open` |
| `structured` | boolean | 否 | true 返回 JSON（含 sessionId）；默认 false 只返回文本 |

```json
{"name":"xiaoai_submit","arguments":{"text":"把蓝牙关掉"}}
```

```json
{"name":"xiaoai_submit","arguments":{
  "text":"刚才那个再确认一下",
  "sessionId":"<sessionId>"
}}
```

```json
{"name":"xiaoai_submit","arguments":{"text":"记住 42","structured":true}}
```

可能耗时较长（小爱内部多轮工具调用），客户端超时建议 ≥120 秒。

失败文本：

- `missing argument: text`
- `text too long (max 4000)`
- `session.open failed: ...`
- `ERR cli service not ready`
- `unknown argument: xxx（可用：text, sessionId, structured）`
- 其他 `isError=true` 文本

> 需要稳定拿 `sessionId` 时也可以用 App 侧的 `xiaoai_ask`（它一直返回 JSON）。

### xiaoai_todo_create

确定性创建一张小米笔记待办卡片，直接调用 Notes 的 `todo_create` AppTool，不经过 LLM。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | 是 | 待办清单标题 |
| `content` | string | 否 | 每行 `- [ ] 文本`；留空自动生成 `- [ ] <title>` |

```json
{"name":"xiaoai_todo_create","arguments":{"title":"买牛奶"}}
```

```json
{"name":"xiaoai_todo_create","arguments":{
  "title":"购物","content":"- [ ] 牛奶\n- [ ] 鸡蛋"}}
```

成功时 `content[0].text` 原样返回 Notes 的结果文本（通常是含 `noteId` 的 JSON）。
失败时 `isError=true`，`text` 是结构化错误：

```json
{"ok":false,"error":"CALL_TIMEOUT","message":"...","hint":"可改用 xiaoai_submit 兜底..."}
```

常见 `error`：`BIND_REJECTED` / `BIND_TIMEOUT` / `CALL_TIMEOUT` / `CALL_FAILED` /
`PROVIDER_ERROR` / `INVALID_RESPONSE`。
不要用 `xiaoai_submit` 替代确定性路径；只有失败时才按 `hint` 兜底。

### xiaoai_config

参数：无。返回当前生效配置（JSON 字符串，放在 `content[0].text`）：

```json
{
  "model": "deepseek-v4.1-flash",
  "baseUrl": "https://api.example.com/v1",
  "enabled": true,
  "marker": true,
  "markerTemplate": "",
  "bridge": true,
  "lan": false,
  "noAuth": false,
  "weakToken": false,
  "authMode": "token",
  "apiKeySet": true,
  "tokenSet": true,
  "port": 8771,
  "version": "0.16.0"
}
```

不会返回 apiKey/token 明文。`authMode`：`off`（免鉴权）/ `weak`（任意非空密码）/
`token`（默认，局域网要求 32 位十六进制）。`noAuth` / `weakToken` 是原始开关。

### xiaoai_switch_model

热切换模型，只能改 `model` / `marker`。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `model` | string | 是 | 已有端点上的模型 id |
| `marker` | boolean | 否 | 回复开头是否带 `[model=...]` |

```json
{"name":"xiaoai_switch_model",
 "arguments":{"model":"deepseek-v4.1-flash","marker":true}}
```

成功：

```json
{"ok":true,"model":"deepseek-v4.1-flash","lastGoodModel":"deepseek-v4.1-flash",
 "baseUrl":"https://...","apiKeySet":true,"changed":true}
```

`lastGoodModel` 是本次落盘的模型名，便于调用方判断是否需要回滚
（自动「连续 3 次上游失败回滚」尚未实现，留到后续版本）。

拒绝：

- 传 `baseUrl` / `apiKey` / `bridge` / `lan` / `token` / `enabled`：
  `端点和密钥只能在手机 App 设置；xiaoai_switch_model 只接受 model / marker（xxx 已拒绝）`
- 其他未知参数：`unsupported argument: xxx`
- 当前没有端点：`missing argument: baseUrl（当前没有可用端点，请在手机 App 设置）`

### xiaoai_island

手动弹一次超级岛提示。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `text` | string | 否 | 提示文案，默认「小爱同学plus」 |
| `force` | boolean | 否 | 忽略单次限制，默认 true |

> deprecated：新代码请用 `xiaoai_notify`（`channel=island`）。

```json
{"name":"xiaoai_island","arguments":{"text":"任务完成"}}
```

返回：`{"ok":true,"alreadyShown":false,"text":"任务完成"}`

### xiaoai_logs

读模块运行日志（**合并视图**：App 汇总全部小爱进程，含 `:core` / `:voice_trigger`）。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `limit` | integer | 否 | 尾部条数：缺省 80；`0` 返回空数组；上限 300 |
| `since` | integer | 否 | 只返回 `seq` 大于该值的日志（游标用返回里的 `lastSeq`） |

```json
{"name":"xiaoai_logs","arguments":{"limit":20,"since":0}}
```

返回 JSON 数组文本，每项包含 `seq`、`t`（本地 `HH:mm:ss.SSS`）、`level`、`msg`，并追加 `tag`（来源）、`ts`（epoch 毫秒）、`pid`（写入进程）、`proc`（进程名）。旧字段名与顺序不变。

顶层还带：

| 字段 | 含义 |
|---|---|
| `count` | 本次返回条数 |
| `lastSeq` | 下一次 `since` 用这个值 |
| `scope` | `merged` = App 侧跨进程合并；`local` = 控制面不可达，回落到 8771 进程自己的缓冲 |

注意：

- `scope=merged` 的 `seq` 由 App 分配，是**跨进程**游标；`scope=local` 是本进程序号。两者不能混用——看到 `lastSeq` 比手里的 `since` 小，说明 App 重启过，把 `since` 归零重新取。
- 合并视图按事件时间排序（各进程推送节奏不同，到达顺序不等于发生顺序）。
- 同一 `(pid, seq)` 重复推送会被去重；来源进程重启后序号从 1 重来，按时间识别为新日志，不会误丢。
- 合并缓冲是 600 条（三个进程共享），比单个进程的 300 条更容易被冲掉；要留证据请同时抓 logcat。
- 控制面断开时自动回落 `scope=local`，此时只能看到 8771 leader 进程的日志。

### xiaoai_stats

调用量统计。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `days` | integer | 否 | 1~90，缺省 7；超过 90 夹到 90 并回 `clamped:true` |

```json
{"name":"xiaoai_stats","arguments":{"days":1}}
```

返回按天聚合的三方/官方调用次数，子调用单独在 `sub`。

### xiaoai_prompt

读用户自己的系统提示词配置与注入状态。参数：无。

```json
{"name":"xiaoai_prompt","arguments":{}}
```

返回 JSON 文本（`content[0].text`）：

```json
{
  "enabled": false,
  "mode": "append",
  "text": "",
  "textLength": 0,
  "memoryInject": false,
  "memoryInjectLines": 20,
  "memoryWriteEnabled": false,
  "marker": "<!-- xiaoi-plus:prompt v1 -->",
  "stats": {
    "injections": 0,
    "skips": 3,
    "failures": 0,
    "lastAt": 0,
    "lastNote": "no-target"
  }
}
```

> 注意：`text` 是用户自己的提示词正文。任何持有令牌的客户端都能读到它；
> 日志与导出包里不包含正文。

- **默认全关**：`enabled=false` 时不会改小爱请求，`stats` 也不会增长。
- `stats` 只有次数与命中位置（`instructions` / `system-message` / `system-inserted`），
  **不含对话或提示词正文的历史**。
- 修改提示词只能在手机 App「小爱 › 系统提示词」里做；远程只能读。

### xiaoai_memory

读写小爱记忆。三条通道：

| 通道 | op | 数据源 | 说明 |
|---|---|---|---|
| 本地文件 | `get` / `append` / `replace` | `files/memory/MEMORY.md` | 明文 Markdown，按 `#`/`##` 分节 |
| 官方记忆 | `official` / `official_write` | 小爱官方记忆服务（`read_memory`/`write_memory`） | 云端口径，实体/隐私/个人标签 |
| 加密记忆库 | `stats` / `live` | `databases/claw_memory.db`（SQLCipher） | 对话抽取出的条目（`memory_meta`），带来源与抽取器 |
| 官方本地库 | `sync` | `databases/memory_data_<uid>.db`（SQLCipher，密钥在小爱 JCEKS 里） | 各表条数、`syncStatus` 分布、待同步行 |

加密库的口令由小爱自己的 AndroidKeyStore 条目解开，**只能在 voiceassist 进程内读**
（模块已在该进程）；读不到时返回 `CLAWDB_*` 结构化错误，不影响其它通道。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `op` | string | 否 | `get`（默认）/ `official` / `stats` / `live` / `sync` / `append` / `replace` / `official_write` |
| `section` | string | append/replace 必填 | 小节路径，例如 `长期记忆/重要事实`；不存在时新建 |
| `text` | string | append/replace 必填 | 内容，多行按条目拆分；≤4 KB |
| `limit` | integer | 否 | `get` 每小节条数（缺省 50，上限 200）；`live` 条数（缺省 20，上限 200）；`sync` 每表待同步行（缺省 20，上限 100） |
| `q` | string | 见 op | `official` 关键词；`official_write` 的自然语言内容（≤500 字）；`live` 的匹配词（正文与键） |
| `since` | integer | 否 | `live` 起始时间（epoch 毫秒），缺省 0=不限 |
| `page` | integer | 否 | `official` 页码，缺省 1 |
| `pageSize` | integer | 否 | `official` 每页条数，缺省 10，上限 50 |

```json
{"name":"xiaoai_memory","arguments":{"op":"get","limit":50}}
{"name":"xiaoai_memory","arguments":{"op":"official","q":"羽毛球","page":1,"pageSize":10}}
{"name":"xiaoai_memory","arguments":{"op":"official_write","q":"我每周三晚上打羽毛球"}}
{"name":"xiaoai_memory","arguments":{"op":"stats"}}
{"name":"xiaoai_memory","arguments":{"op":"live","q":"跑步","limit":5}}
{"name":"xiaoai_memory","arguments":{"op":"live","since":1795415000000}}
{"name":"xiaoai_memory","arguments":{"op":"sync","limit":10}}
{"name":"xiaoai_memory","arguments":{
  "op":"append","section":"长期记忆/重要事实","text":"项目代号 xiaoi-plus"}}
{"name":"xiaoai_memory","arguments":{
  "op":"replace","section":"长期记忆/待办","text":"- 检查日志"}}
```

`op=stats` 返回（不含正文）：

```json
{"ok":true,"db":"claw_memory.db",
 "tables":[{"t":"conversation","n":"292","lo":"1793540904600","hi":"1795415668718"},
           {"t":"memory_meta","n":"207","lo":"1793541156000","hi":"1795415674000"},
           {"t":"memory_file_sync_state","n":"17","lo":"1795415587356","hi":"1795415676871"}],
 "types":[{"t":"每日记忆","n":"154"},{"t":"用户画像","n":"18"},{"t":"长期记忆","n":"12"}]}
```

`op=live` 返回条目（`mem_value` 超过 300 字截断并带 `"truncated":true`）：

```json
{"ok":true,"count":1,"items":[{"id":"1145","mem_key":"生活习惯",
 "mem_value":"每天早上七点跑步","mem_type":"用户画像","mem_filename":"MEMORY.md",
 "mem_source":"conversation","mem_extractor":"conv_worker",
 "timestamp":"1795415672000","date":"2026-11-23 14:34:32","status":"1"}]}
```

- 实测：对小爱说一句后 **10 秒内**就能在 `op=live` 查到 `conv_worker` 抽出的条目
  （同一次抽取也会改写 `MEMORY.md`，所以 `get` 差不多同步，但 `live` 多了
  `mem_type` / `mem_source` / `mem_extractor` / 时间 / 状态，并能按词与时间过滤）。
- `op=live` 只读 `memory_meta`，**不暴露 `conversation` 原文**；官方写入（`official_write`）
  落在官方记忆服务里，用 `op=official` 查，不在这个库里。
- `op=stats` / `op=live` 是只读，不受「允许写记忆」与局域网写开关约束。

`op=sync` 返回官方记忆库（`memory_data_<uid>.db`）的同步状态，只回身份字段，不回 `meta` 正文：

```json
{"ok":true,
 "tables":[{"table":"collect_data_table","rows":42,"sync":[{"s":"1","n":"42"}]},
           {"table":"memory_public_table","rows":9,"sync":[{"s":"1","n":"9"}]},
           {"table":"memory_relation_table","rows":1,"sync":[{"s":null,"n":"1"}]},
           {"table":"memory_private_table","rows":2},
           {"table":"image_table","rows":0}],
 "pendingStatus":2,"pendingCount":0,"pending":[]}
```

`syncStatus`：`1`=已同步（云上已有，实测写入后即 1）、`2`=待同步、`NULL`=不参与同步。
`pending` 里每条是 `{table,id,name,nickName,relationship,category,syncStatus,updateTime}`，
最多 100 条。读法：进程内调小爱自己的 `MemoryDbHelper.createDb(Context)`，不自己解密钥；
失败返回 `OFFICIALDB_UNAVAILABLE`（该库只在 voiceassist 进程内可开）。

写类操作（`append` / `replace` / `official_write`）有两道闸：

1. 手机 App 内打开「允许写记忆」（默认关）；
2. **局域网来源**还必须带请求头 `X-Xiaoi-Write: <同一个 Bearer token>`（二次鉴权），
   否则返回 `DENIED_LAN`。只读操作（`get` / `official`）不受影响。

`official_write` 拒绝删除类指令（`忘掉` / `删除` / `清空` / `forget`）——本模块不提供删除入口。

`get` 返回：

```json
{"ok":true,"code":"OK","exists":true,"file":"MEMORY.md","bytes":15360,"items":42,
 "sections":[{"level":1,"title":"用户画像","count":12,"items":["- ..."]}]}
```

某个小节超过 `limit` 时该项带 `"truncated":true` 与真实 `count`。

写操作返回：

```json
{"ok":true,"code":"APPENDED","op":"append","section":"长期记忆/重要事实",
 "file":"MEMORY.md","bytes":15400,"items":43,"backup":"MEMORY.md.bak-20261121143000123"}
```

- **写操作默认关闭**：`memoryWriteEnabled=false` 时返回 `isError:true`，
  文案提示去 App 打开；不会创建或修改任何文件。
- 写前自动备份到同目录 `MEMORY.md.bak-<时间戳>`（保留 5 份），临时文件 + rename 原子替换。
- 常见错误码：`TEXT_TOO_LARGE` / `FILE_TOO_LARGE` / `MISSING_SECTION` /
  `MISSING_TEXT` / `SECTION_TOO_LONG` / `WRITE_FAILED` / `NO_CONTEXT`。
- **不提供删除或清空**；`outline.md` 只读。

### xiaoai_apptools / xiaoai_apptool

系统应用工具（AppTool）的发现与调用。设备上共 17 个 provider / 112 个工具
（日历、闹钟、录音、相册、天气、笔记、电话…），我们只暴露白名单里的组合。

```json
{"name":"xiaoai_apptools","arguments":{"app":"alarm"}}
{"name":"xiaoai_apptool","arguments":{"app":"alarm","tool":"deskclock_query_alarms"}}
{"name":"xiaoai_apptool","arguments":{"app":"alarm","tool":"deskclock_add_alarm",
  "confirm":true,"args":{"hour":7,"minutes":0}}}
```

`xiaoai_apptools` 返回：

```json
{"ok":true,"count":9,"apps":[{"app":"alarm","pkg":"com.android.deskclock",
 "tools":[{"tool":"deskclock_query_alarms","risk":"read","params":"-"},
          {"tool":"deskclock_add_alarm","risk":"write","params":"hour*, minutes*, ..."}]}]}
```

| app 别名 | 包 | 代表工具 |
|---|---|---|
| `alarm` | com.android.deskclock | `deskclock_query_alarms` / `deskclock_add_alarm` |
| `calendar` | com.android.calendar | `query_events` / `create_event` |
| `notes` | com.miui.notes | `note_search` / `note_create` / `todo_create` |
| `recorder` | com.android.soundrecorder | `search_recordings` / `get_transcription` |
| `gallery` | com.miui.gallery | `gallery_search` / `gallery_get_photo_info` |
| `weather` | com.miui.weather2 | `weather_get_city_weather` |
| `phone` | com.android.phone | `phone_get_caller_info` / `phone_settings` |
| `milink` / `cleaner` / `wallet` / `launcher` / `aicr` | 对应系统包 | 互联、清理、记账、桌面、AI 事件 |

- `risk=read` 直接放行；`risk=write` 必须 `confirm:true`，否则回 `NEEDS_CONFIRM`。
- 不在白名单 → `UNKNOWN_APPTOOL`（外呼、发文件、提交反馈、清理等刻意不收录）。
- 返回值是各 App 自己的 JSON（可能是数组或纯文本），原样透传；只有我们自己的
  结构化错误才带 `isError:true`。

### xiaoai_alarms / xiaoai_events / xiaoai_transcript / xiaoai_weather

四个高频只读封装，参数已经拼好，等价于 `xiaoai_apptool`：

| 工具 | 参数 | 等价调用 |
|---|---|---|
| `xiaoai_alarms` | 无 | `alarm / deskclock_query_alarms` |
| `xiaoai_events` | `start`、`end`（必填）、`keyword`、`maxResults` | `calendar / query_events` |
| `xiaoai_transcript` | `db_id`（取某条转录）或 `q` + `limit`（搜录音） | `recorder / get_transcription` 或 `search_recordings` |
| `xiaoai_weather` | `city`（必填）、`alert`（true=预警） | `weather / weather_get_city_alert` 或 `weather_get_city_weather` |

```json
{"name":"xiaoai_events","arguments":{"start":"2026-11-23","end":"2026-11-30"}}
{"name":"xiaoai_transcript","arguments":{"q":"会议","limit":5}}
{"name":"xiaoai_weather","arguments":{"city":"广州"}}
```

实测：`xiaoai_alarms` 返回设备真实闹钟（含 `daysOfWeekDesc`），
`xiaoai_transcript` 返回 55 条录音列表，`xiaoai_weather` 返回实时温度与分钟级降水。

### xiaoai_cli

原始 CLI 透传，调试用。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `cmd` | string | 是 | JSON 命令串 |

```json
{"name":"xiaoai_cli",
 "arguments":{"cmd":"{\"id\":\"1\",\"method\":\"auth.status\"}"}}
```

返回小爱 CLI 的原始响应文本。

## App 侧工具（设备动作，经控制面 Binder 从 8771 转发）

这 6 个工具由 App 进程执行；详细 schema 与动作清单见 `skill/xiaoi-agent/references/actions.md`。
调用方看到的都是 8771 的同一个 tools/call。

| 工具 | 参数 | 说明 |
|---|---|---|
| `xiaoai_capabilities` | `includeUnavailable`（默认 true） | 列出设备动作、schema、风险、可用性 |
| `xiaoai_action` | `action`（必填）+ 动作参数 | 执行动作；`risk=confirm` 首次返回 `NEEDS_CONFIRMATION` + `confirmToken` |
| `xiaoai_confirm` | `confirmToken`、`approve` | 批准/拒绝；token 一次性、60 秒过期；已用 → `CONFLICT`，过期 → `CONFIRMATION_EXPIRED`，拒绝后复用 → `CONFIRMATION_DENIED` |
| `xiaoai_audit` | `limit`（1~200，默认 50） | 审计摘要，含 `requestId`（未传时由服务端生成并回显在动作结果里） |
| `xiaoai_notify` | `text`（≤2000）、`channel`、`force` | 超级岛优先，失败自动降级普通通知；只提醒，不能批准危险动作 |
| `xiaoai_ask` | `text`（≤8000）、`sessionId`、`context`（≤8KB）、`timeoutMs`（≤180000） | 代理小爱，返回 `{ok,text,sessionId,via,timeoutMs}` |

```json
{"name":"xiaoai_ask","arguments":{"text":"记住 42"}}
{"name":"xiaoai_notify","arguments":{"text":"构建完成","channel":"island"}}
{"name":"xiaoai_action","arguments":{"action":"device.info","args":{},"dryRun":true}}
```

## 其他 HTTP 端点

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/health` | Bearer | `ok facade=<b> port=<n> auth=<mode> tools=<n> agent=ipc|down version=<v>` |
| GET | `/logs?limit=&since=` | Bearer | 日志 JSON；`limit=0` 返回空数组 |
| GET | `/stats?days=` | Bearer | 调用量 JSON；超 90 回 `clamped:true` |
| GET | `/cli?cmd=` | Bearer | 原始 CLI |
| POST | `/mcp` | Bearer | MCP JSON-RPC |
| OPTIONS | 任意 | 无 | `204` 预检（CORS），所有响应带 `Access-Control-Allow-*` |
| POST | `/island` | 仅 loopback，无 token | 弹岛 |
| POST | `/config` | 无 | **固定 410 Gone** |

请求体 >64 KB 一律 `413 payload too large (max 65536 bytes)`。

## 安全说明

- 外部 Agent 只应连接 App 显示的端口，不要扫描 8771~8779 逐个发 token。
- 局域网必须使用 32 位十六进制 token；短 token 自动回退仅本机。
  （App 里可显式打开「允许弱密码」/「免鉴权」，属于测试便利选项，默认关闭。）
- token 轮换后旧客户端立即 401。
- 远程写配置的能力被刻意限制为 `model` / `marker`；端点和密钥只能在手机 App 内改。
- 不要把 `apiKey` / `mcp.token` 写入日志、提交或分享文件。
- **CORS 是刻意开放的**：浏览器面板可直接调用。但「局域网 + 免鉴权 + 浏览器」同时成立时，
  任何网页都能驱动设备（`xiaoai_action`），见 `docs/security.md §3.1.1`。
