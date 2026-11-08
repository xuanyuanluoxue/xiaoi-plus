# A4 · 首页（Home）报告

> 分支：`feat/ui-home`（基于 main `3a6edab`）
> 范围：`app/src/main/java/plus/xiaoi/module/ui/screen/home/**` + 本报告。
> 未改 `MainActivity.kt`、`ui/components/**`、`ui/theme/**`、`core/**`、`agent/**`、资源与构建脚本。
> 提交：`feat(ui): 首页只读总览（四维/待处理/双域/运行/活动）`（本分支，未 push、未 merge）

## 1. 改动文件

| 文件 | 说明 |
| --- | --- |
| `ui/screen/home/HomeScreen.kt` | 页面骨架与 5 个区块；保持签名 `HomeScreen(onNavigate, onMessage)` |
| `ui/screen/home/HomeStateHolder.kt` | 只读状态持有者：IO 线程读配置/快照，5 秒重读（仅 RESUMED） |
| `ui/screen/home/HomeFacts.kt` | 四维徽章映射、Hero CTA、时间格式化（纯展示，不重判状态） |

原 P2 占位（“P2 待接入” + 旧版首页入口）已整体替换；首页不再提供旧版入口。

## 2. 数据接线（单一事实源）

| 区块 | 数据来源 | 说明 |
| --- | --- | --- |
| 状态总览 | `ConfigRepository.load()` + `StatusCache.probe()` + `BridgeStatus.get()` + `StateModel.resolve()` | 只调用 `StateModel` 判读；Hero 标题/副行直接用 `heroTitle()/heroSub()`；副行追加“最近上报”时间 |
| 待处理 | `IssueInputFactory.issues(facts, bridge, agent, perms, now)` → `IssueResolver.limit(3)` | 不自行拼规则；`route` 原样用于导航 |
| 双域入口 | `BridgeStatus.Snapshot`（8771）/ `AgentRuntimeStatus.Snapshot`（8770） | 两个快照分开渲染，任一缺失只影响自己 |
| 运行概况 | 同上 + `HomeFacts.stamp()` | 8771：leader 端口/绑定/局域网/最后更新；8770：端口/启动时间/最后错误 |
| 最近活动 | `ActivityHub.recent(5)` | 按旧→新渲染；空列表显示“暂无活动” |

- 配置读取与权限探针（`PermissionProbe.capture`）都在 `AppExecutors.io` 上执行，不阻塞主线程；结果经 `AppExecutors.ui` 回主线程。
- 重读节奏：`repeatOnLifecycle(RESUMED)` 内先读一次，之后每 5 秒重读；离开前台/页面即停止，满足“刷新靠进入页面重读”。
- `ConfigFacts` 由 `IssueInputFactory.ConfigFacts.from(config, !readOnly, readOnly)` 生成；`probe` 只取 `StatusCache` 的缓存结论（指纹不匹配/过期自动回到 UNKNOWN）。

## 3. 状态分支（自查矩阵）

| 场景 | Hero | 8771 卡 | 8770 卡 | 待处理 |
| --- | --- | --- | --- | --- |
| 未配置 | 未配置 | 已停止 / 端口 — | 已停止 / 端口 — | 尚未配置模型 → `Model` |
| 已暂停 | 已暂停 | 已停止 | 已停止 | 第三方模型已暂停 → `Xiaoai` |
| 配置损坏（只读） | 配置异常 | 未确认（“配置损坏，无法确认通道”） | 未确认 | 配置文件损坏 → `ConfigManage` |
| 8771 离线（MCP 开） | 端点可用 + “小爱未连接” | 等待 / 端口 — / 绑定未确认 | 按 8770 自己的快照 | 等待小爱上报 → `Xiaoai`；必要时 `Bridge` |
| 8770 已停止 | 同上 | 不受影响 | 已停止 / 端口 — / 绑定未确认 | 动作面未启动 → `Bridge` |
| 8770 启动失败 | 同上 | 不受影响 | 启动失败 / 显示 `lastError` | 动作面启动失败 → `Bridge` |
| 8771 超时（>30s） | 端点可用 + “超过 30 秒未上报” | 等待（“不确认在线”） | 不受影响 | 控制面未上报 → `Bridge` |
| D4 `IN_USE` | **已确认** | 在线 | 按自身快照 | 仅真实配置/权限类问题 |
| 权限缺失 | 由配置/端点决定 | 不受影响 | 不受影响 | 通知/精确闹钟等 → `Diagnostics` |
| 0 条 issue | 正常 | 正常 | 正常 | 不显示空卡，只显示一行“没有待处理项” |
| 无活动 | — | — | — | 最近活动显示“暂无活动” |

补充规则：

- 只有 D4 `IN_USE` 时 Hero 才显示“已确认”；D2 `OK` 最多显示“端点可用”，与 `StateModel` 一致。
- 端口/绑定/局域网未知一律 `—` / `未确认`；不会用 8770 推断 8771，也不会把旧快照当在线。
- `mcpEnabled=false` 时 8771/8770 卡显示已停止，且 `IssueResolver` 不生成令牌/通道类 issue。
- 待处理最多 3 条；超过时在列表下方提供“查看全部 N 条”→ `Access`；每行仍跳自己的 route。
- 首页只读：无输入框、无开关、无保存；CTA 全部是导航。

## 4. 组件复用

| 组件 | 用途 |
| --- | --- |
| `StatusHero` / `DimensionState` | 状态总览：标题、副行、2×2 四维徽章、主按钮 |
| `IssueRow` / `IssueUi` | 待处理行（64dp，整行可点，无“忽略”） |
| `XiaoiCard` | 双域入口（可点击）、运行概况、最近活动 |
| `XiaoiPreferenceCard` + `XiaoiDivider` | 待处理列表容器与分隔 |
| `XiaoiBadge` | 双域状态（在线/等待/已停止/启动失败/未确认） |
| `XiaoiGroupHeader` | 5 个分组标题（待处理/双域入口/运行概况/最近活动） |
| `XiaoiTextButton` | “查看全部 N 条”“查看日志” |
| `XiaoiLoadingSkeleton` | 首次读取占位（168dp + 120dp），不用 spinner |

颜色/字号/尺寸全部取 `XiaoiTheme` 令牌；无硬编码 `0xFF...`、无自绘 Card/Button。时间与端口用 Mono；`textHint` 只用于卡片内的时间列（浅色白卡 4.83:1、深色 Miuix 容器 4.97:1）。

## 5. 验收结果

| 命令 | 结果 |
| --- | --- |
| `JAVA_HOME=… ./gradlew :app:assembleDebug --offline` | **BUILD SUCCESSFUL**（仅既有 `Actions.java` varargs 与 `MainActivity` 弃用警告） |
| `./tests/run.sh` | 全部通过（NavStack 49/49，其余用例全绿） |
| `./scripts/check-consistency.sh` | 通过（7/7：minSdk/targetSdk/R/AppExecutors/HTTP/提示/死资源） |

另外用桌面 JVM 场景程序直接调用 `StateModel` / `IssueInputFactory`（不入库），核对 10 种输入的 Hero、D1~D4、
8771/8770 端口状态与 issue 列表，输出与第 3 节矩阵一致：
未配置、已暂停、配置损坏、8771 离线、8770 停止、8770 失败、权限缺失、0 issue、D4 已确认、8771 超时。

未做：真机安装与截图（本轮 4 个页面 Agent 并行，设备由主 Agent 合并后统一验收，明确禁止 `adb install` 与本机截图）；
深/浅色与字体 1.3 的视觉验收留到合并后的 QA-V。

## 6. 已知风险

1. **`StateModel.Input` 映射重复**：`IssueInputFactory.from()` 只返回 `IssueResolver.Input`，首页为了拿 Hero 文案在 `HomeFacts.resolve()` 里按同样字段构造一次 `StateModel.Input`。判断仍只在 `StateModel`，但若 A3 的核心映射将来变更，这里要同步。后续可在 `core` 暴露 `Result` 以消除重复（本轮不改 `core/**`）。
2. **权限探针每 5 秒重采**：`PermissionProbe.capture` 调用多个系统 API，开销小但非零；若后续出现卡顿，可把权限刷新降到进入页面一次。
3. **`ActivityHub` 只有本进程事件**：App 进程重启后为空，首页显示“暂无活动”是事实，不是错误。
4. **旧版桥不报 bind 字段**：`bindHost=""`、`lanEnabled=null` 时显示“未确认”，不会回退成“仅本机”。
5. **未做视觉回归**：卡片间距/徽章在字体 1.3 下可能省略（`DimensionBadge` 单行省略），需要合并后真机确认。
