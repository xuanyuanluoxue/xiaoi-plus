# A5 · Xiaoai + Model 报告

> 分支：`feat/ui-xiaoai`（基于 main `3a6edab`）
> 范围：`app/src/main/java/plus/xiaoi/module/ui/screen/xiaoai/**` 与本文档
> 未改：`MainActivity.kt`、`ui/components/**`、`ui/theme/**`、`core/**`、`agent/**`、
> `AppConfig/ConfigCodec/ConfigRepository`、Gradle、Manifest、`res/**`、其他页面目录。
> 页面签名保持 `XiaoaiScreen(onNavigate, onMessage)`、`ModelScreen(onNavigate, onMessage)`。

## 1. 改动文件

| 文件 | 行数 | 说明 |
| --- | ---: | --- |
| `XiaoaiScreen.kt` | 237 | 小爱一级页：Hero、主开关、当前模型、运行态、增强能力占位 |
| `XiaoaiStateHolder.kt` | 235 | 读 `ConfigRepository`/`StateModel`/`BridgeStatus`/`AgentRuntimeStatus`；主开关即时保存 |
| `ModelScreen.kt` | 367 | 模型与服务商页骨架、保存/放弃/退出流程 |
| `ModelSections.kt` | 352 | 服务商/模型列表、表单、添加卡片 |
| `ModelStateHolder.kt` | 354 | 草稿、保存、冲突、只读、端点校验 |
| `ModelDraft.kt` | 138 | 深拷贝、provider slug、逐项校验（纯函数） |
| `ModelDialogs.kt` | 74 | 放弃草稿 / 覆盖保存 / 删除确认三个弹窗 |
| `XiaoaiSupport.kt` | 46 | `AppExecutors` 协程桥、host/时间格式化、返回壳 |

## 2. 小爱一级（SCREENS.md §2）

区块顺序与规范一致：

1. **状态卡**：`StatusHero` 四维（配置 / 端点 / 已应用 / 运行），标题与副行直接取
   `StateModel.Result.heroTitle()` / `heroSub()`；未知一律“未确认”，不写“在线”。
   端点维度读取 `StatusCache`（仅指纹匹配且 10 分钟内有效），否则 UNKNOWN。
2. **主开关**：`XiaoiSwitchRow`「启用第三方模型」，即时保存（`ConfigRepository.save`）。
   失败回滚：重新 `load()` 文件事实并 Snackbar 说明；关闭文案固定“配置全部保留”。
3. **当前模型**：服务商、模型 ID、端点 host、密钥是否设置；按钮「管理模型」→ `Model`。
4. **运行态**：8771 连接态（`BridgeStatus`，30 秒不新鲜显示“超过 30 秒未上报”）、
   已应用 rev/文件 rev（`StateModel.resolveApplied`）、最近上报时间（Mono HH:mm:ss）、
   运行模型 vs 文件模型（`StateModel.resolveRuntime`）、最后错误（`ActivityHub` 最近
   ERROR，退化到 `AgentRuntimeStatus.lastError`）与「查看日志」。
5. **增强能力**：系统提示词 / 工具注入 / 技能包 / 记忆 / 语音与路由，全部只读行 +
   “规划中”徽章，卡片明写“P2 占位，暂不可用”，无开关、无点击。
6. **损坏态**：顶部只读警告卡；开关禁用，提示去「我的 › 配置管理」恢复。

刷新：`LaunchedEffect` 进入即读，之后 5 秒一次；保存期间跳过刷新，避免把乐观开关
状态覆盖回旧值。

## 3. 模型与服务商（SCREENS.md §3）

**结构**：服务商列表（整行点选，显示 host/模型数/密钥/当前）→ 模型列表（当前服务商，
点选切换）→ 添加服务商/添加模型内联卡 → 服务商设置表单。

- 新增：名称 + 端点（`http(s)://` 必填、同名去重、id 用 `slug(name)` 生成并去重）。
- 编辑：表单直接改名称 / 端点 / 模型 ID / API Key；模型 ID 改名同步显示名。
- 删除：服务商（至少保留一个，说明模型与密钥连带删除）与模型（当前项自动切相邻）。
- 表单字段：名称、端点、模型 ID、API Key（`isSecret` 掩码 + 显示/隐藏）。
  `AppConfig` 没有超时字段，因此超时以只读说明展示（连接 5s / 读取 8s，`ModelValidator`
  固定值），不新增持久化格式。
- 显式保存：仅 `XiaoiDirtyBar` 的「保存」写盘；「放弃」二次确认。
- 加载骨架：`XiaoiLoadingSkeleton`；读取失败有独立卡片与「重新载入」。

## 4. 保存 / 校验 / 冲突策略

**分层校验**

| 层 | 实现 | 结果 |
| --- | --- | --- |
| 字段级 | `ModelDraft.validateDraft` | 名称空/同名、端点空或非 http(s)、模型 ID 空/同名、启用时缺密钥；逐项挂在对应输入框下 |
| 语义级 | `ConfigRepository.validate`（`ConfigCodec.validate`） | 缺服务商/模型/密钥、密钥误填 MCP Token、MCP LAN 无令牌等；显示为 general 错误 |
| 连通性 | `ModelValidator.check`（仅点「校验端点」时） | 只写 `StatusCache`（D2），不保存、不改运行态 |

保存失败保留草稿；错误不进日志、不含密钥明文。

**URL/密钥/模型为空**：分别落到 `baseUrl` / `apiKey` / `modelId` 字段下，保存与校验
都会指出具体项；暂停态允许保存不完整草稿（与旧配置页一致），启用前必须补齐。

**冲突**：保存前先 `load()` 当前文件并比较 `rev`：

- 不同 → 返回 `Conflict`，弹窗“配置已被其他页面修改”，可选「覆盖保存」（危险色）
  或取消；页面同时出现黄条「载入最新（放弃草稿）」。
- 不静默覆盖；`force=true` 只在用户显式确认后执行。

**只读**：`ConfigRepository.isReadOnly()` 时整页禁用输入、隐藏添加/删除，顶部警告卡，
无保存入口；再次保存也会被阻止。

## 5. 组件复用

| 复用 | 用途 |
| --- | --- |
| `StatusHero` + `DimensionState` | 小爱四维状态 |
| `XiaoiCard` / `XiaoiPreferenceCard` | 内容卡 / 列表卡 |
| `XiaoiButton` / `XiaoiTextButton` | 主/次按钮、行内文字动作 |
| `XiaoiSwitchRow` | 主开关（Miuix `SwitchPreference`） |
| `XiaoiTextField` | 四个表单字段（含密钥掩码） |
| `XiaoiDirtyBar` | 保存/放弃 |
| `ConfirmCard` | 冲突黄条 |
| `XiaoiConfirmDialog` | 放弃、覆盖、删除确认 |
| `XiaoiLoadingSkeleton` | 加载骨架 |
| `XiaoiBadge` / `XiaoiValueRow` / `XiaoiGroupHeader` | 状态徽章、值行、分组 |
| Miuix `BasicComponent` | 服务商/模型可点选行（含行尾删除） |

颜色/字号/尺寸只取 `XiaoiTheme`；无 `Color(0x…)`、无系统 Toast、无 `new Thread`。

## 6. 与旧 Config 的差异（parity）

保持：同一 `AppConfig` v3 字段、`ConfigRepository.save`（写文件 + prefs + reload）、
保存后 `AgentService.startIfEnabled`、`StatusCache` 指纹缓存、暂停允许保存不完整草稿、
删除当前项自动切换、至少保留一个服务商、密钥掩码。

差异（有意）：

1. 小爱页主开关即时保存，不再需要点 FAB。
2. 模型页**不再自动校验**（旧页打开即发请求）；改为显式「校验端点」，避免打开页面就联网。
3. 保存成功显示 Snackbar 后调用壳的返回分发器 `pop()` 回小爱页；若壳无返回栈则留在本页。
4. 删除改为页内确认弹窗（旧版是管理对话框）。
5. 页内不再有 MCP/令牌/前缀/备份/主题等配置（分属接入与我的）。

## 7. 验收

| 命令 | 结果 |
| --- | --- |
| `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug --offline` | 成功 |
| `./tests/run.sh` | 全部通过（NavStack 49/49） |
| `./scripts/check-consistency.sh` | 通过（7/7，无裸线程/Toast/getIdentifier/死资源） |

未执行 `adb install` 与真机截图（本轮由主 Agent 合并后统一验收），未改动用户配置。

### 自查覆盖（代码路径）

| 场景 | 表现 |
| --- | --- |
| 无服务商 | 列表空态 + 「添加」；表单提示先添加；保存被 general 错误阻止 |
| 无模型 | 模型列表空态；模型 ID 输入禁用；暂停可保存，启用时报“启用前需要添加模型” |
| 缺密钥 | 启用时 `apiKey` 行内错误；小爱开关保存失败并回滚 |
| 配置损坏 | 两页顶部只读警告；开关禁用、输入禁用、无保存 |
| 保存冲突 | 弹窗 + 黄条；载入最新或显式覆盖 |
| 暂停态 | Hero/徽章“已暂停”；小爱开关副文案“配置全部保留”；模型页提示可保存草稿 |

## 8. 已知风险 / 边界

1. **顶栏返回无法在页内拦截**：顶栏由壳直接 `pop()`，页面只能拦截系统返回
   （`BackHandler` → 放弃/取消）。为满足“保留草稿”，未保存草稿放在进程内
   `ModelDraftCache`，再次进入且文件 rev 未变时恢复；rev 变了自动作废。
   进程被杀后草稿丢失。
2. **草稿缓存含 API Key（仅内存）**：不落盘、不写日志；保存/放弃/只读时清空。
   与旧配置页把草稿 JSON 放进 Bundle 的做法同级。
3. **保存成功后的返回**通过 `ComponentActivity.onBackPressedDispatcher` 触发壳的
   `BackHandler`，不是新增导航 API；若宿主不是 `ComponentActivity` 则停留在本页。
4. **rev 检查与写入之间仍有极小窗口**：冲突检查后调用 `ConfigRepository.save`
   会重新读文件 rev，同进程内 UI 操作串行，实际风险很低。
5. **`ModelValidator` 返回的上游响应摘要**会显示在页面（与旧版一致），不包含请求里的
   API Key；Snackbar 只提示固定文案。
6. **8771 运行态是进程内快照**：App 进程重启后归零，页面显示“未确认/未连接”，
   不伪造在线。
7. 文件行数略超 `CONVENTIONS.md` 的 300 行建议（ModelScreen 367 / ModelStateHolder 354 /
   ModelSections 352），已按职责拆分出 `ModelDraft` 与 `ModelDialogs`。

## 9. 交付

- 分支：`feat/ui-xiaoi`（不 push、不 merge）
- 功能提交：本分支最新提交（`feat/ui-xiaoi`），报告与实现同一提交。
