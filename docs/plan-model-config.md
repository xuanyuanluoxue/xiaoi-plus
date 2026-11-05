# 规划：模型选择与配置（model & config）

> 状态：**已执行（P0~P4 完成；D1~D5 按推荐采纳）**
> 适用版本：0.8.1 之后的下一轮迭代
> 关联文件：`Config` / `ConfigRepository` / `ConfigSync` / `AppConfig` / `ModelValidator` /
> `ConfigActivity` / `Bridge` / `Mcp` / `Hook` / `activity_config.xml`
> 本文只描述**要做什么、为什么、怎么验收**，不含实现代码。

---

## 0. 一句话结论

现在的问题不是「少了一个按钮」，而是**同一份配置有三套模型、六份字段清单、四种"生效"语义**，
任何一次改动都只能靠装机试错来确认。所以本轮的正确顺序是：
**先统一数据层与同步内核（可离线测），再重排 UI（一次装机），最后才讨论体验细节。**

---

## 1. 目标与非目标

### 1.1 用户视角要解决的三个任务

| 编号 | 用户任务 | 现在的实际体验 | 验收口径 |
|---|---|---|---|
| J1 | **能选模型** | 能选/能加/能删，但"删除"藏在"添加"对话框里；删服务商没有入口 | 从配置页 3 次点击内完成"选中某个模型并保存" |
| J2 | **能改配置** | 端点/密钥/前缀/MCP 都能改，但改完不知道有没有推到小爱（`save()` 无条件 toast「已保存并推送」） | 保存后 3 秒内，页面状态条客观显示推送结果（成功/失败/待重试） |
| J3 | **能知道是否生效** | 主页显示「已激活」但依赖 MCP 开启；配置页「模型状态」只证明"端点可达"，不证明"小爱在用" | 一个常驻状态条同时回答：配置是否有效 / 端点是否可达 / 小爱侧是否在用 |

J3 是这一轮真正的新东西。J1/J2 是"把已经做了一半的补齐并去掉陷阱"。

### 1.2 非目标（明确不做，防止范围蔓延）

1. **不做多模型并发路由 / 自动 failover / 按 token 计费统计**。真实需求是"换一个模型试试"。
2. **不做 OAuth、登录换 key、额度查询**。仍然是 BYOK 明文 key。
3. **不做非 OpenAI 兼容协议适配**（Anthropic Messages、Gemini 原生等）。`ModelValidator` 只认 `/chat/completions`。
4. **不做云端同步、配置分享平台、自动更新**（违反"无网络上报"约束）。
5. **不引入任何第三方运行时依赖、不引入 Gradle、不引入 AppCompat/Compose**。仍用 `build.sh` + 手写 XML View。
6. **不在本轮修复"响应前缀多进程重复注入"**。该功能维持禁用，配置页如何处理见决策 D5。
7. **不重做四页导航与设计令牌**。只在 `activity_config.xml` 现有令牌内重排。

---

## 2. 现状审计

### 2.1 数据流（真实运行路径）

```
┌──────────────────────── App 进程（plus.xiaoi.module） ───────────────────────┐
│                                                                             │
│  ConfigActivity ──load()──► ConfigRepository ◄──FileObserver── ConfigSync    │
│       │  save()                │  │                              │          │
│       │                        │  └─ write() ──► config.json（事实源）       │
│       │                        │                    ▲                        │
│       │                        └─ writePrefs() ──► 模块 prefs              │
│       │                                             xiaoi_plus.xml          │
│       └──────────── pushAsync() ──► POST 127.0.0.1:8771..8775 /config       │
│                                        （首个 200 即 return）                │
└─────────────────────────────────────┼───────────────────────────────────────┘
                                      │  loopback HTTP
┌───────────────── 小爱进程 × N（主进程 / :core / :voice_trigger …） ───────────┐
│  Bridge（每个进程各起一个 server，各自占 8771~8775 之一）                     │
│    handleConfig() → Mcp.mergeConfig(body, HOST.config())                     │
│                   → writeTargetPrefs()（写小爱 prefs）                        │
│                   → Hook.applyConfig()（仅本进程内存生效）                    │
│  Hook：Config.current() 每 800ms 比对                             │
│        /data/data/com.miui.voiceassist/shared_prefs/xiaoi_plus.xml mtime      │
└──────────────────────────────────────────────────────────────────────────────┘
```

可信度标注：
- `config.json` 位置 `getExternalFilesDir(null)/config.json` = `/sdcard/Android/data/plus.xiaoi.module/files/config.json`。
  **注意**：Android 11+ 第三方文件管理器通常打不开 `Android/data`，README 的"免 root 可编辑"实际只有 `adb pull/push`
  和部分系统文件管理器成立。这个落差要在文档里改口（见 §8 P0）。
- `ConfigSync.start()` 只在 `ConfigActivity` 与 `HomeActivity` 被调用（`AndroidManifest.xml` 无自定义 Application），
  所以"App 进程活着"等价于"用户打开过主页或配置页之后"。
- 推送是**广播猜测**：`ConfigRepository.pushAsync()` 端口 8771→8775 顺序尝试，**第一个返回 200 就 return**。
  因此只有一个小爱进程执行 `Hook.applyConfig()`；其余进程靠"读 prefs 文件 mtime"在 800ms 内跟上。

### 2.2 三套配置模型（结构性问题的根源）

| 模型 | 位置 | 字段集 | 谁写 | 谁读 |
|---|---|---|---|---|
| `AppConfig` | `config.json`（含 providers 两级结构） | 12 个（含 providers/models） | 配置页、`ConfigSync`、`importFromRuntimeQuiet` | 配置页、`ConfigSync` |
| `Config` | 模块 prefs + 小爱 prefs（扁平） | 8 个 | `ConfigRepository.writePrefs`、`Bridge.writeTargetPrefs`、`SettingsActivity` | `Hook`、`Bridge`、`Mcp.Host.config()` |
| `Mcp.Snapshot` | 运行时内存 | 8 个 | `mergeConfig`、`switch_model` | `Mcp`、`Bridge` |

三套之间靠**手写字段搬运**连接，而搬运点有 **6 处各写一份清单**：

1. `ConfigRepository.writePrefs()` —— 10 个键
2. `ConfigRepository.pushAsync()` —— 9 个字段（HTTP body）
3. `Bridge.writeTargetPrefs()` —— **7 个键**
4. `Mcp.mergeConfig()` —— 9 个字段
5. `Config.forApp()` / `Config.forTarget()` / `Config.parsePrefs()` —— 读侧 8 个键
6. `ConfigActivity.collect()` / `render()` —— UI 侧 6 个字段

**这不是"代码不够优雅"，而是已经产生了真实缺陷**：`Bridge.writeTargetPrefs()` 没有写 `Config.KEY_MARKER_TPL`（第 592–600 行）。
后果：推送后当前进程的 `Hook` 内存里有模板（走 `applyConfig`），但小爱 prefs 里没有；
小爱任一进程重启后 `Config.forTarget()` 读到空模板 → **前缀静默失效，且找不到原因**。
同理，`SettingsActivity.save()`（第 463–469 行）也写 7 个键，是第三份不一致的清单（该类目前是死代码，见 R10）。

### 2.3 已实现 / 半实现 / 缺失

**已实现（可信）**

- `config.json` 原子写（tmp + fsync + rename）：`ConfigRepository.write()`
- 显式 `enabled` 全链路（文件 / prefs / `/config` / `mergeConfig` / `xiaoai_config`）
- "无密钥不推送"硬保护：`ConfigSync.applyIfChanged()` 第 126–129 行
- `ModelValidator` 真实探针 + 7 种失败分类（**未提交，工作区状态**）
- `POST /config` 合并语义（缺失字段保持当前值）：`Mcp.mergeConfig()`
- token 轮换自愈：`Bridge.configAuthorized()` 接受内存 token 或模块 prefs token
- 端口身份自证：`GET /health` 返回 `facade=`、`port=`；契约脚本据此发现端口
- JVM 单测 200 个断言（Json 62 / Http 33 / Mcp 80 / Logs 13 / ModelValidator 12）
- 真机契约：`scripts/contract-test.sh --save-baseline|--check`

**半实现（能跑但有洞）**

| 项 | 现状 | 洞 |
|---|---|---|
| 模型管理 | `pickModel()` / `addModel()` / `promptAddModel()` | 删除只能从"添加"对话框进；`pickModel()` 的 `setNeutralButton("管理（长按删除）", null)` 是**空按钮**，文案承诺了行为但不做事 |
| 服务商管理 | `pickProvider()` 末尾「＋ 添加服务商」 | 无删除、无重命名、无改端点入口（添加完端点就改不了，只能手改文件） |
| 模型校验 | `checkModelNow()` 页面打开/文件变更/保存/手动触发 | 无结果缓存、无全局并发限制；`reason` 只显示 `r.message` 原文；`enabled=false` 且 key 为空时也会发请求 |
| 保存反馈 | `save()` 无条件 toast「已保存并推送」 | `pushAsync()` 的结果被完全丢弃，失败也显示成功 |
| 外部文件变更 | `reloadFromFile()` 直接 `render()` | 用户未保存的输入（含刚敲的 key）被**静默丢弃** |
| 主题 | 主题 chip 直接写 prefs `Prefs.KEY_THEME` | ~~`config.json` 里还有 `themeMode`，`writePrefs()` 会互相覆盖，双源~~（已修：主题只存 prefs，config 字段忽略且不再写回） |
| 开关禁用态 | `applyEnabledState()` 设 `alpha=0.45` + `setEnabled(false)` | `LinearLayout.setEnabled(false)` 不拦截子 View 点击，视觉禁用但可点击 |
| 引导导入 | `bootstrapIfNeeded()` 从运行时补 model/baseUrl | 桥不回传 apiKey；而 `ConfigRepository.defaults()` **没有读** `KEY_API_KEY/KEY_BASE_URL/KEY_MODEL`，v1→v2 升级后 key 不在文件里 |

**缺失（零实现）**

- 没有配置 schema 迁移代码（`AppConfig.VERSION = 2` 只写不读：`ConfigRepository.parse()` 从不看 `version`）
- 没有 `normalize()`（悬空 `activeProviderId` 只在 `AppConfig.activeProvider()` 里"兜底到第一个"，文件永远是脏的）
- 没有"配置快照 diff"（导入/外部变更都不给用户看差异）
- 没有"生效状态"聚合（文件有效 = ? 端点可达 = ? 小爱在用 = ?）
- 没有密钥边界处理（`apiKey` 明文进 `/sdcard`；`apiKey` 与 `mcpToken` 可被写反，且无检测）
- 没有 FileObserver 失败时的兜底轮询
- 没有 `ConfigSync` 监听器的注销（`ConfigActivity` 注册匿名 Runnable 后未 `removeListener`，静态 List 持有 Activity）

### 2.4 结构性风险（只列会反复咬人的）

**R1 字段清单碎片化（最高优先级）**
见 §2.2。新增一个字段今天的正确做法是改 6 处，漏一处的症状是"当时好、重启后不对"，
这正是最难查的一类 bug。`enabled` 上线时漏了 `marker_tpl` 的同类问题**已经发生并留在代码里**。

**R2 推送是"猜端口"而不是"认端口"**
`pushAsync()` 只看 HTTP 200。8770 曾被其它服务占用（契约脚本因此改成 `/health` 自证），
但**生产路径没有跟着改**。多进程下又只有第一个 200 的进程真正 `applyConfig`，其余进程靠 800ms 内 prefs 兜底，
用户在两秒内说的话可能走的是旧配置。

**R3 "文件即事实"与"无密钥不推送"互相打脸**
`applyIfChanged()` 在 `apiKeyOfActive()` 为空时**整份跳过**。于是用户在文件里改
`mcp.enabled=true`、改 `markerTemplate`、或把 `enabled` 设为 false 暂停——**全部静默无效**，
因为这次推送被 key 保护整体拦掉了。README 承诺的"改完 1~2 秒自动生效"在这些场景下是假的。

**R4 "生效"没有唯一定义**
至少存在四个布尔：`AppConfig.enabled`（显式开关）、`ConfigRepository.validate()` 是否通过、
`pushAsync()` 是否 200、小爱侧 `xiaoai_config.enabled`。UI 分别在不同地方展示其中一个，
用户无法得到"现在我到底在用哪个模型"的确定答案。这也是主页 `HomeActivity` 要靠桥查询才能显示真实模型的原因。

**R5 冲突策略缺失**
外部编辑触发 `reloadFromFile()` → `cfg = repo.load(); render();`。若用户正在输入，输入被覆盖且没有提示。
文件优先是正确原则，但"优先"不等于"静默丢弃用户正在做的事"。

**R6 弱引用式 active id**
`AppConfig.activeProvider()` 找不到就返回 `providers.get(0)`，`activeModel()` 同理。
于是"文件里 activeProviderId 指向不存在的 provider"表现为：UI 显示 A、文件仍写着 B、保存时把 A 的 baseUrl 写进 prefs
但 `activeProviderId` 保持 B。**读时兜底 + 写时不修复 = 永久脏数据**。用户遇到的"两个 provider 并存/指向错误"
就是这个机制的直接产物。

**R7 升级路径会丢密钥**
`ConfigRepository.defaults()` 只从 prefs 取 `marker/mcpEnabled/mcpToken`（主题已迁出 config），
不取 `KEY_BASE_URL/KEY_MODEL/KEY_API_KEY`。v1 用户升级到 0.8.1 后：文件里 key 为空 → 引导导入拿不到 key（桥不回传）
→ `enabled` 被迫置 false → 用户在配置页看到"暂停 + 空密钥"，会以为配置丢了。
这是**已在生产路径上的数据可用性问题**，优先级高于任何 UI 改动。

**R8 测试覆盖与改动中心错位**
200 个断言全在纯 JVM 层（Json/Http/Mcp/Logs/Validator）。而这一轮改动的中心——
`AppConfig` 序列化、`ConfigRepository.validate()`、`ConfigSync` 状态机、prefs 字段集——
**一个断言都没有**，只能装机看。这就是本轮"改代码→构建→装机→看"的循环成本来源。

**R9 文档与实现漂移**
`docs/ui-architecture.md` §2 描述的是"端口（动态探测）""打开配置文件""分享（默认脱敏）"，
三者在实现里都不存在（`share()` 是**含密钥**导出，`exportJson(false)`）。旧文档会被后来的人当需求读。

**R10 v1 页面是死代码**
`SettingsActivity`（572 行）在 `AndroidManifest.xml` 里仍然注册，但没有任何入口引用。
它保留着第三份 prefs 写入逻辑，是"改错文件"的定时炸弹。

---

## 3. 数据模型与文件格式

### 3.1 结论：保留 provider → model 两级结构

理由：真实世界里 API key 跟服务商走、模型跟端点走；"换模型不换 key"是最高频操作。
扁平结构（v1）在用户有 2 个以上服务商时必然退化成一堆手工复制粘贴。
因此 **不推翻两级结构**，只补齐它的语义。

### 3.2 文件格式（v2，本轮不升版号，只定义清楚）

```json
{
  "version": 2,
  "enabled": true,
  "marker": false,
  "markerTemplate": "",
  "activeProviderId": "deepseek",
  "activeModelId": "deepseek-chat",
  "mcp": { "enabled": true, "token": "…" },
  "providers": [
    {
      "id": "deepseek",
      "name": "DeepSeek",
      "baseUrl": "https://api.deepseek.com/v1",
      "apiKey": "sk-…",
      "models": [
        { "id": "deepseek-chat", "name": "DeepSeek Chat" },
        { "id": "deepseek-reasoner", "name": "DeepSeek Reasoner" }
      ]
    }
  ]
}
```

### 3.3 schema 版本策略

| 情况 | 行为 |
|---|---|
| `version` 缺失 | 视为 2（0.8.x 已产出的文件） |
| `version == 2` | 正常读写 |
| `version > 2`（来自更新的版本） | **只读降级**：可展示、可导出，但禁止保存（保存会丢新字段）。文案：「该配置由更新版本生成，请升级模块后再改」 |
| `version < 2` | 走迁移（见 §3.6） |
| 未知顶层字段 | **原样保留**：`parse()` 保留原始 `JSONObject`，`toJson()` 以它为底稿覆写已知字段。这是"回滚到旧版本不丢数据"的唯一保障 |

升版规则：只有**破坏性**结构变化才升 `version`；加字段不升版（旧版本靠"保留未知字段"天然兼容）。

### 3.4 字段语义表

| 字段 | 类型 | 默认 | 语义（必须唯一、可判定） | 写者 | 读者 | 误用的后果 |
|---|---|---|---|---|---|---|
| `enabled` | bool | `false` | **重定向总开关**。false = 保留全部字段但小爱走原生模型 | 配置页 | `Config.enabled()`、`Hook` | 与"未配置"混淆 → 用户以为配置丢了 |
| `version` | int | `2` | 结构版本，只影响读写兼容 | `toJson` | `parse` | 不读 = 无法回滚 |
| `marker` | bool | `false` | 上一代"标记"遗留位；**本轮起不再驱动任何行为**（仅保留兼容，`Hook` 里已由 `markerTemplate` 取代） | 配置页 | 兼容读 | 两套开关并存 → 用户困惑（见 D5） |
| `markerTemplate` | string | `""` | 输出前缀模板，`{model}` 占位。空 = 不注入。**开关 = 是否存在模板** | 配置页 | `Hook.prefixGuard()`（当前禁用） | 开关与内容双源 |
| `mcp.enabled` | bool | `false` | MCP/桥开关；关闭时局域网模式也不生效 | 配置页 | `Bridge` | — |
| `mcp.lan` | bool | `false` | 是否允许局域网访问；需 `mcp.enabled` 且 token 非空才真正绑 `0.0.0.0` | 配置页 | `Bridge` | 误以为开了就能连 → 实际无 token 仍只绑 loopback |
| `mcp.token` | string | `""` | 桥 Bearer token。空 = 拒绝所有请求 | 配置页 / `switch_model` 保留 | `Bridge.authorized` | 填成 apiKey → 桥与模型同时失效 |
| `providers[].id` | string | — | **稳定标识，永不复用、永不改**。生成规则见 §3.5 | 配置页 | `activeProviderId` | 复用 id 会让旧引用指向新服务商 |
| `providers[].name` | string | `id` | 仅显示，可随时改 | 配置页 | UI | 用 name 当 id 会因重名互相覆盖 |
| `providers[].baseUrl` | string | `""` | OpenAI 兼容根（含或不含 `/v1` 都可，校验只要求 http(s)） | 配置页 | `Config.baseUrl()` | 与 model 拼接方式不一致 |
| `providers[].apiKey` | string | `""` | **只属于该服务商**。绝不跨服务商复用、绝不与 token 混用 | 配置页 | `Hook` 请求头 | 填成 token → 401 且难定位 |
| `providers[].models[]` | array | `[]` | 候选模型；`id` 原样作为请求 `model` | 配置页 | UI | — |
| `activeProviderId` | string | `""` | 必须存在（normalize 保证） | 配置页 / normalize | `activeProvider()` | 悬空 → 静默 fallback |
| `activeModelId` | string | `""` | 必须存在于 active provider | 配置页 / normalize | `activeModel()` | 悬空 → 静默 fallback |
| ~~`themeMode`~~ | — | — | **已废弃**：主题是 App 本地 UI 偏好，唯一来源是 prefs（`Prefs.KEY_THEME` / `ThemeManager`）。旧文件字段读时忽略、写时不再输出 | — | `ThemeManager` | 曾与 prefs 双源，改主题后被文件覆盖 |

### 3.5 provider / model 标识生成规则

- provider id：`slug(name)`（小写、非字母数字转 `-`、截断 24 字符），冲突时追加 `-2`、`-3`。
  不再使用 `"p" + System.currentTimeMillis()`（`ConfigActivity.addProvider()` 现状）——不可读，且手改文件时无法引用。
- model id：用户输入原样；UI 去重；不做 slug。
- **id 一旦写入永不变更**，重命名只改 `name`。迁移时若发现重复 id，保留第一个，其余追加 `-dup2`。

### 3.6 迁移策略

| 来源 | 触发 | 规则 |
|---|---|---|
| v1 prefs（`base_url`/`api_key`/`model`/`marker`/`bridge`/`token`） | `config.json` 不存在，且 prefs 里 `base_url` 与 `model` 非空 | 生成 `provider{id:"imported"}`，key 直接**从模块 prefs 取**（这一步现在缺失） |
| 0.8.1 的默认骨架（无 key） | `ConfigSync.bootstrapIfNeeded()` | 现存逻辑：从运行时补 model/baseUrl；**新增**：先尝试模块 prefs 的 `KEY_API_KEY` 回填 |
| 未来 `version > 2` | `parse()` | 只读降级，禁写 |
| 解析失败（JSON 坏） | `load()` | 不覆盖、不写默认值；返回内存默认值 + UI 顶部红条「配置文件损坏，已备份为 config.json.bak」。**现在 `catch` 后直接返回 `defaults()` 且不提示，等于静默假装成功** |

迁移必须在 `save()` 之前完成，且迁移结果先写 `.bak` 再写正式文件。

### 3.7 多 provider 并存的防呆（核心）

新增纯函数 `normalize(AppConfig) -> {cfg, List<Repair>}`，规则按顺序执行：

| # | 条件 | 处理 | 用户可见 |
|---|---|---|---|
| 1 | `providers` 为空 | 生成默认 `deepseek` 骨架（无 key，`enabled=false`） | 空态卡 |
| 2 | provider `id` 空/重复 | 补 slug / 追加后缀 | 「已修正 1 处」 |
| 3 | `baseUrl` 相同的 provider | 合并 models / 保留被 active 引用的 id | 「已合并重复服务商」 |
| 4 | `activeProviderId` 空/悬空 | 取第一个"有 key 且有 model"的 provider；都没有则取 `providers[0]` | 「已重新指向 X」 |
| 5 | `activeModelId` 空/不在 active provider | 取该 provider 第一个 model；无 model 则 `""` | 「已选择 X」 |
| 6 | provider 有 model 但 `baseUrl` 非法 | 不删，标 `needsFix`，保存时定位到字段 | 行内红字 |
| 7 | `apiKey` 与 `mcp.token` 完全相同 | **拒绝保存**，提示「密钥与 MCP 令牌不能相同」 | 阻断式错误 |
| 8 | `apiKey` 形如 32 位 hex 且 token 为空 | 只警告（疑似填反），不阻断 | 黄色提示 |

关键设计：
- `normalize` 是**纯函数、无 IO、无 Android 依赖**，进 `tests/run.sh`。
- 修复结果**立即写回文件**（repair 后 `mark` 为"已应用"），保证"读到的文件永远是自洽的"。
  这直接消灭 R6：不再依赖 `activeProvider()` 的兜底，兜底只作为最后一道保险。
- UI 首屏若 `repairs` 非空，显示一条可展开的「已自动修正 N 处配置 · 查看」。

### 3.8 密钥与 token 的存放边界

现状：`apiKey` 明文写在 `/sdcard/Android/data/plus.xiaoi.module/files/config.json`，
`mcp.token` 同文件，`security.md` 已声明风险。本轮**只做三件事**，不改存储位置（见 D1）：

1. 保存前检测"key == token"（§3.7 #7）与"key 形如 token"（#8）。
2. **导出默认脱敏**：`exportJson(mask)` 已支持，但当前 `btn_export` 传的是 `false`。改为默认 `true`，
   另给"导出（含密钥）"二次确认入口。
3. 日志断言：`Logs` 明确禁止出现 key/token（已有约定，补一条 JVM 断言 `Logs.dump()` 不含 `sk-`）。

---

## 4. UI 方案

### 4.1 信息架构：一张"状态条" + 四张卡

```
┌ 配置 ────────────────────────────────────────────┐
│ ┌ 状态条（常驻，随状态变色）────────────────────┐ │
│ │ ● 已生效 · deepseek-chat · 2 分钟前校验       │ │
│ │   小爱正在使用该模型（端口 8771 已确认）      │ │
│ └───────────────────────────────────────────────┘ │
│                                                   │
│ ① 启用第三方模型            [开关]                │
│                                                   │
│ ② 模型                                            │
│   服务商  [DeepSeek] [GLM] [＋]          管理 ›   │
│   模型    [chat] [reasoner] [＋]         管理 ›   │
│   密钥    [••••••••••]              [显示]        │
│   状态    可用 · 412ms                    ↻       │
│                                                   │
│ ③ MCP                                             │
│   [开关]  令牌 [••••] [生成]                      │
│   局域网 [开关]  同一 Wi-Fi 可直连                │
│   http://192.168.x.x:8771/mcp  [复制连接信息]     │
│                                                   │
│ ④ 高级                                            │
│   从运行中小爱导入 ›   导出（脱敏）›              │
│   导出（含密钥）›      从文件还原 ›               │
│   打开配置文件位置 ›                              │
│                                                   │
│ ⑤ 外观   [跟随系统] [浅色] [深色]                 │
└───────────────────────────────────────────────────┘
```

**为什么这样排**

1. **状态条在最上**：用户来这个页面的第一诉求是"到底生效没"（J3）。它必须不滚动就能看到，
   且颜色/文案由单一状态机决定，不允许页面别处再出现第二种"已激活/未启用"说法。
2. **模型卡第二**：最高频操作。服务商与模型放同一张卡的两行 chips，是因为它们总是一起被改
   （换模型经常连带换端点）；分两张卡会让"选模型"变成两次滚动。
3. **chips 而不是下拉对话框**：候选通常 2~4 个，chips 一步直达、状态可见（当前项高亮），
   也天然支持"＋"与"管理"。超过 4 个时 chips 显示前 3 + 「+N」，点开完整列表。
4. **"管理"从行尾进入，删除在列表里**：删除是破坏性操作，必须与"选择"分离（现状把删除塞进"添加"对话框，
   且 `pickModel()` 里那个 neutral 按钮点了没反应）。
5. **MCP 独立卡但留在本页**：它同属"配置"，但默认折叠（开关关时不显示 token/JSON 区），
   避免默认关闭的功能占据首屏注意力。删掉现在那段 `{\n "endpoint": … }` 伪 JSON 展示——
   它把常量当运行时事实展示（`port = 8771` 硬编码），会误导用户。
6. **高级在倒数第二**：导入/导出是救援通道，低频但必须有；"打开配置文件位置"补上文档承诺但实现缺失的入口。
7. **外观最后**：真正的最低频，且改动立即生效（不需要保存）。

### 4.2 状态机的唯一定义

```java
// 概念（不进代码，只做约定）
enum Effective {
  NOT_CONFIGURED,   // 无 provider 或无 model
  PAUSED,           // enabled=false，且字段齐全
  INVALID,          // enabled=true 但 validate 不通过（缺 key / baseUrl 非法）
  SAVED_NOT_PUSHED, // 文件/prefs 已写，但没找到任何 小爱桥
  PUSHED_UNVERIFIED,// 推送成功但未确认小爱侧在用（拿不到 xiaoai_config）
  ACTIVE,           // 桥返回 enabled=true 且 model 一致
  DRIFT             // 桥返回的 model ≠ 文件里的 model（运行时被 switch_model 改过）
}
```

派生规则（**唯一**，页面所有文案都从这里出）：

| 状态 | 主文案 | 副文案 | 颜色 |
|---|---|---|---|
| NOT_CONFIGURED | 未配置 | 选择服务商与模型后即可启用 | text-hint |
| PAUSED | 已暂停 | 配置已保留，打开上方开关即恢复 | warning |
| INVALID | 配置不完整 | 缺密钥 / 端点格式不对（定位到行） | error |
| SAVED_NOT_PUSHED | 已保存 · 未生效 | 小爱未运行，下次启动自动生效 | warning |
| PUSHED_UNVERIFIED | 已保存并推送 | 未能读取小爱侧状态（MCP 未开启时不减分） | primary |
| ACTIVE | 已生效 · `<model>` | 小爱正在使用该模型 | success |
| DRIFT | 小爱在用 `<x>`，与配置不一致 | [以配置为准（重新推送）] [以运行时为准（导入）] | warning |

`DRIFT` 是新增但必要的：它把用户遇到的"activeProviderId 指向错误 provider / 两个 provider 并存"
从"没有说法"变成"有明确出口的两个按钮"。

### 4.3 每个交互的状态机

**A. 选择服务商 / 模型（chips）**

```
idle ──点 chip──► selected(activeProviderId/ModelId 改) ──► dirty
                        │
                        └─若该 chip 无 model──► 空态提示「该服务商还没有模型，点＋添加」
idle ──点「＋」──► 添加表单 ──校验──► ok: append + 若原 active 为空则自动选中 ──► dirty
                              └── fail: 行内红字（不弹 toast）
```

要点：
- 选中 **不** 自动保存（保持现有 FAB 模型）；但切换后立刻触发**节流校验**，让"状态"行反映新模型。
- 从"有 key 的 provider"切到"没 key 的 provider"时，密钥行必须显示空并高亮，不允许沿用上一个的 key。

**B. 删除模型**

```
管理列表 ──长按/右侧删除──► 确认对话框（显示"将被删除：xxx"）
   ├─ 非 active ──► 删除 ──► dirty
   └─ active ──► 删除 ──► 同 provider 下一个成为 active
                    └─ provider 已空 ──► activeModelId="" ──► 状态条变 INVALID/NOT_CONFIGURED
```

**C. 删除 / 重命名服务商**（本轮新增）

```
管理列表 ──► 删除 ──► 若该 provider 被 active 引用：
                确认框文案「这是当前使用的服务商，删除后需要重新选择」
                ──► 删除并把 active 指向剩余第一个（无则空）
```

**D. 模型状态校验**

```
idle ──触发──► checking（seq++，显示「校验中…」）
     ├─ 结果 seq != 当前 ──► 丢弃（已有机制，保留）
     ├─ ok ──► 可用 · 412ms（绿色）
     └─ fail ──► 映射后的可读文案 + 建议（红/黄）
stale：key/baseUrl/model 任一变更 ──► 状态灰显「配置已改，点按重新校验」
```

失败文案映射（`ModelValidator.Result.code` → 用户话术）：

| code | 文案 | 建议动作 |
|---|---|---|
| `ok` | 可用 · `<ms>ms` | — |
| `auth` | 密钥无效或被拒（401/403） | 「检查密钥是否属于该服务商；确认没有把 MCP 令牌填进密钥」 |
| `model` | 该端点没有这个模型 | 「点「＋」添加正确的模型 ID，或换服务商」 |
| `endpoint` | 连不上端点 | 「检查地址拼写、是否需要 `/v1`、手机是否能访问该域名」 |
| `timeout` | 8 秒无响应 | 「网络不稳或端点限流，稍后重试」 |
| `http` | 端点返回 `<code>` | 「查看响应摘要；多为账号配额或参数不被支持」 |
| `empty` | 端点有响应但不是 OpenAI 兼容格式 | 「确认这是 `/chat/completions` 端点」 |

**E. 导入（从运行中小爱）** —— 必须变成"有 diff 的合并"

```
点击 ──► 读取桥 xiaoai_config
   ├─ 桥不可达 ──► 「小爱未运行 / MCP 未开启」+ [如何开启]
   ├─ 成功 ──► 预览对话框：列出将变更的字段（model、baseUrl、mcpToken）
   │            并明确「密钥不会从运行时读回，需你手动确认」
   │            ├─ 应用 ──► saveQuiet + 重新校验
   │            └─ 取消 ──► 不改
   └─ 运行时 enabled=false ──► 只提示，不改本地 enabled
```

**F. 保存**

```
dirty ──FAB──► collecting ──validate──► fail：定位到第一个错误行 + 不关闭 + FAB 保持高亮
                              └─ ok ──► 写 .bak ─► 原子写文件 ─► 写 prefs ─► 推送（见 §5.2）
                                            ├─ 全部成功 ──► 状态条 PUSHED_UNVERIFIED / ACTIVE
                                            ├─ 部分成功 ──► 「已推送到 N/M 个小爱进程」
                                            └─ 全部失败 ──► 「已保存到文件；小爱未收到，重启小爱或下次打开 App 时重试」
```

**G. 外部文件变更**

```
listening ──onEvent(300ms debounce)──► hash 比对
   ├─ 与基线相同 ──► 忽略
   ├─ 页面 !dirty ──► 静默重载 + 状态条闪一次「已从文件更新」
   └─ 页面 dirty ──► 顶部 banner：「配置文件已在外部修改」
                       ├─ 重新载入（丢弃我的编辑）
                       └─ 保留我的编辑（下次保存会覆盖文件）
                       （默认高亮"重新载入"，但**不自动执行**）
```

### 4.4 空态与错误态文案（统一口径）

| 位置 | 空态 | 错误态 |
|---|---|---|
| 服务商 chips | 「还没有服务商 · 点＋添加第一个」 | 缺 baseUrl：「端点未填写」 |
| 模型 chips | 「这个服务商还没有模型 · 点＋添加」 | 模型 ID 重复：「已存在同名模型」 |
| 密钥 | hint「`sk-…`（只存本机）」 | 「密钥不能为空」「密钥与 MCP 令牌不能相同」 |
| 状态条 | 未配置 | 见 §4.2 表 |
| 导入 | — | 「小爱未运行或 MCP 未开启」 |
| 文件损坏 | — | 「配置文件损坏，已备份为 config.json.bak」 |

文案原则：**主文案说现状，副文案说下一步动作**；禁止出现"失败"两个字后面没有下一句。

---

## 5. 同步与校验

### 5.1 优先级（不可协商）

```
config.json（文件）  >  模块 prefs（镜像）  >  小爱 prefs  >  运行时内存
```

运行时变更（`xiaoai_switch_model` / 直接改小爱 prefs）**永不自动回写文件**，
只能通过显式"导入"回流，且必须展示 diff。这条已有实现基础（`ConfigSync` 注释），本轮只是补上 diff 与冲突提示。

### 5.2 完整时序

| # | 场景 | 期望行为 | 现状差距 |
|---|---|---|---|
| 1 | App 在前台，外部改文件 | 300ms 去抖 → hash 比对 → validate → 写 prefs → 推送 → UI 刷新/冲突提示 | 缺冲突提示；无 key 时整份跳过 |
| 2 | App 在前台，页面内保存 | 写 `.bak` → 原子写 → prefs → 全端口推送 → 状态条更新 | 无 `.bak`；推送结果丢失 |
| 3 | App 被冻结/无观察者，外部改文件 | 下次启动时 `applyIfChanged()` 比对 hash 补应用 | 已有；但若 observer 在 FUSE 上失效则**永远不生效**（见 #6） |
| 4 | 小爱进程重启 | 从 prefs 读回；若文件 hash 与上次应用不同则重新推送 | 已有；但 `marker_tpl` 不在小爱 prefs → 前缀丢失（R1） |
| 5 | 运行时 `switch_model` | 只改小爱侧；文件不变 → UI 若前台收到监听则显示 DRIFT | 无 DRIFT 概念 |
| 6 | FileObserver 静默失效（部分 ROM 的 `/sdcard`） | 兜底：`ConfigActivity.onResume` 与 `HomeActivity.onResume` 各做一次 hash 比对；前台每 10s 一次（低开销） | 完全没有兜底 |

### 5.3 推送：从"猜端口"改为"认端口 + 聚合"

```
1. 对 8771..8775 逐个 GET /health（带 Bearer，超时 600ms）
2. 只接受 body 以 "ok facade=" 开头的响应（与 contract-test.sh 的判据一致），记为已知桥
3. 对每个已知桥 POST /config，收集 {port, code, body}
4. 结果分类：
   全成功        → toast/状态条「已推送到 N 个进程」
   部分成功      → 「已推送 N/M，其余进程将在 800ms 内从 prefs 生效」
   全失败        → 「已保存到文件；未找到运行中的小爱」
5. 若推送的字段包含 mcp.enabled=true 而桥尚未启动：无法推送，改为下次启动应用（写 pending 标记）
```

去掉"首个 200 即 return"。这一步是让"多进程 + 端口冲突"从"玄学"变成"可计数"。
同时 `Bridge.handleConfig()` 在响应里加 `port`（已有）与 `pid`，方便日志对账。

### 5.4 无密钥时的推送策略（拆掉 R3）

把 `applyIfChanged()` 的"整份跳过"改为**按字段分组守卫**：

| 字段组 | 无 apiKey 时 |
|---|---|
| `model` / `baseUrl` / `apiKey` / `enabled` | **不推送**（保持硬保护，避免用空 key 冲掉运行时） |
| `mcp.*` | **照常推送**（桥 token 与模型无关） |
| `marker` / `markerTemplate` | 照常推送（只影响提示词，不涉及上游鉴权） |
| 主题（`themeMode`） | 不属于 config.json，只存 App prefs，不推送 |

实现上只需在 `pushAsync` 前构造 payload 时按组过滤，并在状态条注明「模型未推送（缺密钥）」。

### 5.5 冲突判定

- 基线：页面 `onCreate` 时记录 `fileHash`。
- 外部变更事件到达时：`hash != baseline && dirty` → 冲突 banner。
- 保存时二次确认：写文件前再取一次 hash，若与 `baseline` 不同 → 提示「文件在此期间被外部修改」，给"覆盖/重载"。
- 不使用时间戳（`lastModified` 在 FUSE 上精度不可靠），只用内容 hash。

### 5.6 ModelValidator 的触发时机与节流

| 触发 | 是否执行 | 节流 |
|---|---|---|
| 页面打开（`render()` 后） | 仅当 `enabled==true` 且 key 非空 | 同 fingerprint 10 分钟内不重复 |
| 模型/服务商切换 | 是 | 同上（fingerprint 变化则立即） |
| 保存成功后 | 强制 | 忽略自动节流 |
| 用户点状态行 | 强制 | 无 |
| 外部文件变更并应用后 | 是 | 自动节流 |
| `enabled==false` 或 key 为空 | **不执行** | 显示「已暂停，未校验」 |

- fingerprint = `sha256(baseUrl + "\n" + model + "\n" + sha256(key))` 前 12 位，**不落明文**。
- 结果缓存在模块 prefs（`check_<fingerprint>` = `code|ms|ts`），跨页面复用；超过 24h 自动视为 stale。
- 全局并发上限 1（`AtomicBoolean`），避免连点 chips 打出一串请求。
- 自动校验**绝不**在后台重复（只在页面可见时）。

---

## 6. 验收标准（每条都能在真机上客观验证）

### A. 模型选择

- [ ] A1 全新安装（无 config.json）：打开配置页 3 秒内出现「未配置」状态条 + 默认服务商 chip，无崩溃、无空白卡。
- [ ] A2 在服务商 chips 点「＋」添加 `GLM` + 端点 → chips 出现 `GLM`，且 `config.json` 的 `providers` 长度 +1，`id` 为可读 slug（非 `p1730…`）。
- [ ] A3 在模型 chips 点「＋」添加两个模型 → 两个 chip 出现；重复 ID 被拒绝且**行内**提示，不弹 toast。
- [ ] A4 「管理」里删除当前激活模型 → active 自动落到同 provider 下一个；`config.json` 的 `activeModelId` 同步改变（`adb pull` 验证）。
- [ ] A5 删除唯一模型 → 状态条变「配置不完整」，保存被阻止并定位到模型行。
- [ ] A6 删除服务商（本轮新增）→ 确认框出现；删除后 active 指向剩余第一个，文件不再含该 provider。
- [ ] A7 保存后 `ConfigActivity` 不再出现任何「管理（长按删除）」类空按钮。

### B. 生效判定

- [ ] B1 小爱运行 + MCP 开启 + 已保存：状态条显示「已生效 · `<model>`」，与小爱实际回复所用模型一致。
- [ ] B2 关闭小爱（`am force-stop`）后保存：状态条显示「已保存 · 未生效 ｜ 小爱未运行」，**不再**显示「已保存并推送」。
- [ ] B3 重新打开小爱 5 秒内（App 在前台）：状态条自动从「未生效」变「已生效」（依赖 pending 重试或 onResume 检查）。
- [ ] B4 用 MCP `xiaoai_switch_model` 改成另一个模型 → App 前台时状态条变「小爱在用 X，与配置不一致」，两个出口按钮可用。
- [ ] B5 关闭 `enabled` 保存 → 状态条「已暂停」，小爱侧回到原生模型（日志无「配置已推送」以外的写入），且 `apiKey` 仍在 `config.json` 中。
- [ ] B6 状态行文案只有 §4.2 表中的 7 种，页面其他位置不出现第二种"已激活/未启用"措辞（人工核对）。

### C. 防呆与迁移

- [ ] C1 手工把 `activeProviderId` 改成 `nope` 后保存文件 → 1~2 秒内被 normalize 修复为真实存在的 id，并提示「已自动修正 1 处配置」。
- [ ] C2 手工制造两个 `baseUrl` 相同的 provider → 被合并，`active*` 仍指向有效项（`adb pull` 验证）。
- [ ] C3 把 `apiKey` 与 `mcp.token` 填成同一串 → 保存被拒绝，提示明确。
- [ ] C4 从 0.8.0（v1 prefs 有 key）升级安装 → `config.json` 里 `apiKey` 非空（R7 修复证明）。
- [ ] C5 把 `config.json` 改成非法 JSON → 页面显示「配置文件损坏，已备份为 config.json.bak」，原文件不被覆盖。
- [ ] C6 写入 `"version": 3` → 页面只读，保存被禁用并给出升级提示。

### D. 同步

- [ ] D1 App 在前台时用 `adb push` 改 `model` → 2 秒内推送成功，日志有 `config.json 变更已应用: model=<新值>`。
- [ ] D2 同上，但页面有未保存输入 → 出现冲突 banner，用户输入**不被**清空。
- [ ] D3 无 `apiKey` 时手工把 `mcp.enabled` 改 true → 桥照常启动（当前会整份跳过，此条是 R3 的回归证明）。
- [ ] D4 小爱三个进程都在跑时保存 → 状态条显示「已推送到 N 个进程」，`N` 与 `/health` 探测到的桥数量一致。
- [ ] D5 手工改小爱 prefs 的 `model`（模拟运行时变更） → 不写回文件；点导入后出现 diff 预览再应用。
- [ ] D6 前缀模板改动后 `am force-stop` 小爱再启动 → `adb shell cat /data/data/com.miui.voiceassist/shared_prefs/xiaoi_plus.xml`（root）
      中 `marker_tpl` 仍为改动后的值（R1 回归证明）。

### E. 性能 / 安全 / 回归

- [ ] E1 连续点 5 个模型 chip → 最多 1 个校验请求（全局并发上限 1 + 节流）。
- [ ] E2 打开配置页不产生任何校验请求（`enabled=false` 时）。
- [ ] E3 导出默认脱敏：`apiKey` 与 `token` 为 `***`；仅"导出（含密钥）"二次确认后才含明文。
- [ ] E4 `adb logcat | grep xiaoi+` 中不出现任何 `sk-` 前缀串或 32 位 token。
- [ ] E5 `bash tests/run.sh` 全绿，断言数 ≥ 240。
- [ ] E6 `scripts/check-all.sh --no-device` 通过；有设备时 `scripts/contract-test.sh --check` 无差异。
- [ ] E7 版本不在白名单时（`Hook.SUPPORTED_VERSIONS`）小爱不崩、日志一行「版本不在白名单」。

---

## 7. 测试策略

### 7.1 JVM 单测：把"只能在真机看"的部分搬到桌面

**前置重构（P1 的核心）**：把 `ConfigRepository` 里的纯逻辑抽成 `ConfigCodec`：

```
ConfigCodec（纯 JVM，无 android 依赖）
  parse(String json)                    → AppConfig（保留未知字段）
  toJson(AppConfig, JSONObject base)    → String（以 base 为底稿覆写已知字段）
  normalize(AppConfig)                  → Repairs
  validate(AppConfig)                   → 错误码 + 定位字段
  migrateFromPrefs(Map<String,String>)  → AppConfig
ConfigRepository 只剩：IO、原子写、prefs 镜像、推送
```

这样 `tests/run.sh` 的 javac 列表里加一个文件，就能覆盖本轮 80% 的风险。

**测试清单（按套件）**

| 套件 | 断言点（数量级） |
|---|---|
| `ConfigCodecTest`（新） | round-trip 逐字节稳定（黄金串）；未知字段保留；`version` 缺失/2/3 三态；providers 为空；models 为空；`\u0000`/中文/超长 baseUrl |
| `NormalizeTest`（新） | §3.7 的 8 条规则各 2 例（触发/不触发）；重复 id；相同 baseUrl 合并；修复幂等（normalize 两次结果相同） |
| `ValidateTest`（新） | enabled 矩阵（4 组合）；baseUrl 非法；缺 key；key==token；model 空白 |
| `MigrateTest`（新） | v1 prefs 完整/缺 key/缺 model；升级不覆盖已存在 config.json |
| `ModelValidatorTest`（扩） | 已有 12 条；补 429、500 带 HTML、超时（本地 sleep 桩）、`baseUrl` 末尾多斜杠 |
| `McpTest`（扩） | 已有 80 条；补 `mergeConfig` 的"显式 null 覆盖"、`enabled` 与 `marker` 独立性、push payload 分组过滤（纯函数化后可测） |
| `LogsTest`（扩） | 断言 dump 中不含 `sk-` / 32 位 hex |

约束：新测试**必须**沿用 `T.ok/T.eq`（零框架），不引入 JUnit；每个新纯层类必须同时加进 `tests/run.sh` 的 javac 列表
（编译通过本身就是"无 android 引用"的证明）。

### 7.2 真机契约（`scripts/contract-test.sh`）的定位与边界

**定位**：验证"已安装的产物对外表现"——即 Hook 是否加载、端口是否在、鉴权是否生效、MCP 工具是否可用、
`/config` 合并语义是否仍符合基线。它是**回归网**，不是功能测试。

**边界（明确不做）**：
- 不测 UI（无 UI 自动化框架，也不该为此引入依赖）
- 不测 `FileObserver`（headless 环境不可靠；用 JVM 侧 hash/状态机测试替代）
- 不测真实模型可用性（会花钱，且依赖第三方）

**本轮建议增补的断言（改基线需要一次 `--save-baseline`）**：
1. `GET /health` 返回含 `port=` 且与 `adb forward` 端口一致（已部分具备）。
2. `POST /config` 只带 `{"mcp":{"enabled":true}}` 时，`xiaoai_config` 的 `model/baseUrl` 不变（合并语义回归）。
3. `xiaoai_config` 必须包含 `enabled/marker/markerTemplate/bridge/apiKeySet/tokenSet/port/version` 全部键。
4. 前缀模板写入后小爱 prefs 里 `marker_tpl` 存在（需 root；无 root 时降级为跳过并注明）。

### 7.3 手工冒烟清单（每次装机后 3 分钟）

| # | 步骤 | 期望 |
|---|---|---|
| 1 | 冷启动 App → 配置页 | 状态条有明确文案，无空白 |
| 2 | 切一个模型 → 保存 | 状态条变「已生效」或「未生效+原因」 |
| 3 | 对小爱说一句话 | 日志 `model=` 与所选一致 |
| 4 | `adb push` 改文件 model | 2s 内状态条更新 |
| 5 | 关 MCP → 保存 | 桥不再监听（`ss -ltn`），模型仍生效 |
| 6 | 删除当前模型 | active 落到下一个，无崩溃 |
| 7 | 导出 | 默认脱敏 |
| 8 | 切深色 | 全页无白底输入框、无裁切 |

### 7.4 "必须装机"的判定

| 改动类型 | 能否离线验证 |
|---|---|
| `ConfigCodec` / `normalize` / `validate` / `ModelValidator` / `Mcp.mergeConfig` | **能**（JVM 100%） |
| `ConfigSync` 状态机 | 大部分能（把文件与 HTTP 抽成接口后可测） |
| `ConfigRepository` 原子写 | 能在桌面用临时目录测（`File` 无 android 依赖） |
| `Bridge` / `Hook` | 不能，必须装机 |
| `activity_config.xml` / `ConfigActivity` | 部分能（`aapt2 compile/link` 静态校验 + 自写 id 引用检查），最终仍需 1 次装机 |

---

## 8. 分阶段计划

> 工作量按 1 人日 = 6 小时有效编码（含自测）估算。阶段之间**不互相阻塞**的说明写在每段末尾。

### P0 收口与清理（0.5 人日）— 不做完不进 P1

| 内容 | 文件 |
|---|---|
| 提交当前工作区的 `ModelValidator` 相关改动（否则后续所有 diff 都夹带它） | `ModelValidator.java`、`ConfigActivity.java`、`ConfigSync.java`、`activity_config.xml`、`tests/*` |
| 修 R7：`ConfigRepository.defaults()` 从模块 prefs 回填 `base_url/model/api_key` | `ConfigRepository.java` |
| 修 R1 的一半：`Bridge.writeTargetPrefs()` 补 `KEY_MARKER_TPL` | `Bridge.java` |
| 删除 `SettingsActivity`（死代码）与 manifest 声明 | `SettingsActivity.java`、`AndroidManifest.xml` |
| 文档改口：`ui-architecture.md` 去掉不存在的"动态端口/打开文件/默认脱敏"；README 修正 `Android/data` 的可编辑性描述 | `docs/*`、`README.md` |

验收：`tests/run.sh` 全绿；升级路径 C4 手工验证通过；`grep -rn SettingsActivity` 为空。
**不阻塞 P1**：P1 只新增纯层类，与 P0 的文件不重叠。

### P1 数据层定稿（1.5 人日）

| 内容 | 文件 |
|---|---|
| 新增 `ConfigCodec`（parse/toJson/normalize/validate/migrate，纯 JVM） | 新文件 |
| `ConfigRepository` 退化为 IO + 副作用，调用 codec | `ConfigRepository.java` |
| `AppConfig` 增加 `version` 读取与未知字段承载 | `AppConfig.java` |
| 新增 4 个测试套件（≥ 60 断言） | `tests/src/**`、`tests/run.sh` |
| 迁移前写 `config.json.bak` | `ConfigRepository.java` |

验收：`tests/run.sh` ≥ 240 断言全绿；**行为零变化**（用当前 `config.json` 做黄金串 round-trip，逐字节一致）。
**不阻塞 P2**：P2 只改推送路径与 `ConfigSync`，接口以 `ConfigCodec` 为界。

### P2 同步内核（1 人日）

| 内容 | 文件 |
|---|---|
| 推送改为 `/health` 认端口 + 全端口 POST + 结果聚合（§5.3） | `ConfigRepository.pushAsync` → 抽成 `ConfigSync.pushAll()` |
| 无密钥按字段分组推送（§5.4） | `ConfigSync.applyIfChanged` |
| 冲突基线（fileHash）+ `onResume` 兜底比对（§5.5、§5.2 #6） | `ConfigSync`、`ConfigActivity`、`HomeActivity` |
| `pending` 标记：推送失败下次启动重试 | `ConfigSync` |
| 监听器注销（修 Activity 泄漏） | `ConfigActivity.onDestroy` |

验收：D1–D4、E1–E2 通过；JVM 侧用本地 HTTP 桩测 pushAll 的三类结果（全成功/部分/全失败）。
**不阻塞 P3**：P3 只消费"状态机枚举 + 结果对象"，可用桩数据先做 UI。

### P3 UI 重排（2 人日）

| 内容 | 文件 |
|---|---|
| 状态条 + 7 状态机（§4.2） | `activity_config.xml`、`ConfigActivity` |
| 模型卡改 chips + 管理列表（含删除服务商） | 同上 |
| 保存状态机（真实推送结果，§4.3 F） | `ConfigActivity.save` |
| 冲突 banner + 导入 diff 预览 | `ConfigActivity` |
| 空态/错误态文案统一（§4.4） | 同上 + `strings.xml`（可选） |
| 删掉伪 JSON 展示与空按钮；`enabled=false` 用整体可点击屏蔽而非 `setEnabled` | `activity_config.xml`、`ConfigActivity` |

验收：§6 A/B/C 全部通过；**本阶段只装机 2 次**（第 1 次静态检查后冒烟，第 2 次验收）。
**可与 P2 并行**：UI 先用状态机枚举的假数据，接 P2 时只换数据源。

### P4 导入导出与密钥边界（0.5 人日）

- 导出默认脱敏 + "含密钥"二次确认；导入走 codec 校验；`ShareProvider` 分享脱敏。
- 验收：E3、E4。
- **依赖 P3** 的入口位置，但代码面独立。

### P5 契约与文档（0.5 人日）

- 增补 §7.2 的 4 条契约断言，`--save-baseline` 一次。
- 更新 `README`（设置页字段表 → 以本文 §3.4 为准）、`CHANGELOG`、`docs/ui-architecture.md`。
- 验收：`scripts/check-all.sh` 全绿。

**总计约 6 人日**（不含真机联调等待）。关键路径 P0 → P1 → P2 → P3 → P4/P5。

---

## 9. 风险与回滚

### 9.1 最坏情况（按严重度）

| 情况 | 影响 | 现有防线 | 本轮加固 |
|---|---|---|---|
| 推送把运行时配置冲成空 | 小爱静默回原生模型，用户以为模块坏了 | 无 key 不推送（`ConfigSync`） | 字段分组推送 + 推送前 `normalize` + `.bak` |
| 迁移写坏 `config.json` | 用户手填的服务商/模型全丢 | 无 | 写前 `.bak`；解析失败不覆盖；保留未知字段 |
| 升级丢 key | 用户被强制重新填 key（R7） | 无 | P0 回填 prefs |
| UI 新布局在小屏/深色裁切 | 无法操作 | 手工冒烟 | 布局静态检查 + 只用现有令牌 + 底部 `wrap_content` 约定 |
| 契约基线漂移 | CI 红但不确定是回归还是噪声 | `--check` 归一化 | 只增断言，不改归一化规则 |

### 9.2 回滚手段

| 层 | 回滚方式 | 代价 |
|---|---|---|
| 配置数据 | `config.json.bak` 还原；未知字段保留保证旧版本可读新文件 | 无 |
| 代码（单阶段） | 每阶段一个 commit，`git revert` 单阶段 | 低（P1/P2 是纯层，revert 不影响 UI） |
| UI | `activity_config.xml` 整体还原 + `ConfigActivity` 同 commit | 低（不改其他页面） |
| 功能 | `enabled=false` 即停重定向，配置全保留 | 无 |
| 模块 | LSPosed 停用作用域 / 卸载 APK | 无（不修改小爱 APK） |

### 9.3 兼容性承诺（写进 CHANGELOG）

- `config.json` v2 保持可读；新增字段一律可选、有默认值。
- `POST /config` 合并语义不变（缺失字段保持），**不新增必填字段**。
- `xiaoai_config` 只增键不删键（现有契约测试会锁住）。
- eol：`marker` 字段保留但不再驱动行为（见 D5）；至少保留一个大版本。

---

## 10. 流程教训与本项目开发协议

### 10.1 为什么这几轮会反复

把 `git log` 与 `git diff v0.8.0..HEAD --stat` 摊开看，问题不在"某个 bug 难"，而在**方法**：

1. **没有"设计定稿"这个工件。**
   讨论直接在代码里进行：`51b5540`（重写配置页）→ `94ada7c`（模型选择重写）→ `6bbe079`（文件即事实）。
   三次提交都在改同一个功能的同一个面，但中间没有任何一份"目标/字段/状态机"的文字约定，
   于是每次都在解决上一次没想清楚的部分。**缺少定稿 ⇒ 每次改动都只是下一轮的输入。**

2. **一次改多个关注点，出问题无法二分。**
   `6bbe079` 一个提交里同时改了：数据源（文件优先）、字段语义（enabled）、推送路径、启动引导、测试。
   真机一旦不对，只能靠猜"是哪一层"，于是又产生一轮"改一版试试"。**这是循环成本的乘数。**

3. **验证手段只有真机。**
   构建+装机+重启小爱+看日志 ≈ 5–10 分钟，且必须串行、必须有人拿手机。
   而本轮真正难的东西（字段搬运、normalize、validate、merge 语义）**本来 100% 可以桌面测**。
   `tests/run.sh` 已经证明了这条路可行（200 断言、秒级），只是没往配置层伸。

4. **逆向结论与功能改动混在一起。**
   响应前缀的逆向（`sm.b$c/d` 构造器）是研究产出，却直接进了功能提交，最后留下
   `Hook.java` 里一段禁用代码 + 一个"已知问题"。研究未收敛就上线，会让功能状态不可判定。

5. **没有"字段集唯一清单"。**
   `enabled` 上线时漏掉了 `marker_tpl` 的小爱 prefs 写入（本文 §2.4 R1）。
   这类缺陷的症状是"当时好、重启后不对"，最容易被误判成玄学，从而诱发更多试错。

6. **文档先于实现写，且写完不回收。**
   `ui-architecture.md` 描述了"动态端口探测""打开配置文件""默认脱敏分享"，三项都不存在。
   下一个人（或下一次自己）读文档会当成需求，于是又改一遍。

7. **"完成"的定义太低。**
   这一轮的隐含 DoD 是"能编译 + 真机上看到一个变化"。没有"契约绿 + 回归绿 + 文档同步 + 可回滚"。

### 10.2 开发协议（以后做功能默认按这个走）

| 编号 | 规则 | 触发条件 | 违反的代价 |
|---|---|---|---|
| **R1** | **先定稿再写码**：跨 ≥2 个文件，或涉及持久化格式/HTTP 协议/字段语义 → 先写 `docs/plan-*.md` 并让用户确认 | 任何非单文件改动 | 回到本次的循环 |
| **R2** | **按层提交**：纯层（codec/validator/mcp）→ 接线层（Bridge/ConfigSync）→ UI 层；一层一个 commit | 所有功能 | 无法二分定位、无法单独回滚 |
| **R3** | **JVM 优先**：能在桌面跑的逻辑不许只在真机上验；新增纯层类必须同时进 `tests/run.sh` 并加断言 | 新增/修改纯层 | 唯一验证手段变成装机 |
| **R4** | **批量装机**：一个阶段最多装机 2 次（冒烟 + 验收）。中间验证用 `logcat` / `curl` / `adb pull` | 所有阶段 | 每改一行装一次机 |
| **R5** | **门禁先行**：改完先 `scripts/check-all.sh --no-device`；动到协议再 `contract-test.sh --check` | 每次提交前 | 靠人眼回归 |
| **R6** | **字段单一清单**：所有持久化/协议字段只在规划文档的表里登记一次；新增字段必须同提交改全部搬运点并加 round-trip 断言 | 新增字段 | 漏一处 ⇒ 重启后失效 |
| **R7** | **研究与功能分离**：逆向产出先落 `docs/research-*.md`，不把半成品 hook 带进功能提交 | 逆向/探索 | 死代码污染 + 功能状态不可判定 |
| **R8** | **UI 静态检查后再装机**：`aapt2 compile/link` + 自写脚本检查 id 引用、令牌使用、字号 | UI 改动 | 白底输入框/裁切重演 |
| **R9** | **DoD 四条**：① 测试绿 ② 文档与 CHANGELOG 同步 ③ 3 条 adb 命令内可证生效 ④ 有明确回滚路径 | 每阶段收尾 | "做完了"但没人能确认 |
| **R10** | **开工前一句话**：本轮只改哪一层、验收看哪条命令 | 每次开工 | 范围蔓延 |

配套建议（成本极低、收益明确）：

- 在 `scripts/` 加一个 `ui-lint.sh`：扫描布局里 `android:id` 是否都在代码中引用、是否误用 `#fff` 硬编码色、`textSize` 是否来自令牌。
- 给 `ConfigSync` 的文件/HTTP 依赖抽两个接口（`FileStore`/`ConfigPusher`），让状态机的 JVM 测试成为可能——这是 P2 顺带完成的事。
- 每次装机前先 `adb pull` 一份 `config.json` 到 `/tmp`，出问题对比而不是回忆。

---

## 11. 待用户决策的问题

### D1 `config.json` 是否继续放明文 `apiKey`？

- **A（推荐）**：保持单文件。理由：与 `security.md` 现有描述一致；用户可用 `adb pull/push` 手工救援；
  实现零成本。配套：导出默认脱敏 + 保存前检测 key==token + 文档明确"这里是明文"。
- B：拆 `secrets.json` 到内部私有目录，文件里只留 `apiKeyRef`。更安全，但外部编辑配置后无法自解释、
  备份/恢复要处理两个文件、`ModelValidator` 与 UI 都要跨文件读，成本约 +1 人日。

### D2 多服务商是"保留历史"还是"一等并行"？

- **A（推荐）**：一个 active + 其余保留可切换（chips 模型）。真实需求是"换模型"，
  A 已完全覆盖，且不会引入"哪个在生效"的歧义。
- B：支持多个同时可用（按场景/按图片分流）。这需要 hook 层路由策略，超出本功能范围。

### D3 是否自动拉取 `/v1/models` 做模型发现？

- **A（推荐）**：本轮不做，保留手动添加。「＋」表单加一个"粘贴模型 ID"的便捷路径即可。
  理由：端点差异大（有的返回 404、有的返回 HTML、有的需要额外权限），会把 `ModelValidator` 的分类逻辑复杂化，
  与"零依赖 + 失败即降级"冲突。
- B：P2 之后作为独立小功能（独立规划文档），用 `/models` 成功才展示，失败静默隐藏入口。

### D4 响应前缀（`markerTemplate`）在本轮怎么处理？

- **A（推荐）**：从配置页移除整张前缀卡，代码与字段保留，标注为"实验，暂不可用"。
  理由：当前注入被禁用，一个点了没有效果的开关持续消耗信任，也会让"是否生效"更难回答。
- B：保留但置灰 + 一行说明「多进程下会重复注入，暂不可用」。
- C：本轮顺手修多进程重复注入（需要在 hook 层做进程识别 + 单点注入），预计 +2 人日且风险高，**不建议**。

### D5 v1 `SettingsActivity` 与 `marker` 字段

- **A（推荐）**：删除 `SettingsActivity`（死代码，第三份 prefs 写入逻辑）；`marker` 字段保留读兼容一个大版本，
  但 UI 不再暴露，行为统一由 `markerTemplate` 决定。
- B：保留 `SettingsActivity` 作为"精简回退页"。代价是三份写入逻辑继续并存，R1 会再犯一次。

---

## 附：本轮不解决的问题（明确记录，避免被当成遗漏）

1. 响应前缀多进程重复注入（D4）。
2. 图片请求的模型分流（`Hook.routeByImage()` 已被 `if (true) return false` 短路，属已知取舍）。
3. `8770` 端口被其它服务占用的根因（只做"认端口"，不调查那个服务）。
4. 非 OpenAI 兼容协议的适配。
5. 桥的 TLS / 非 loopback 访问。
