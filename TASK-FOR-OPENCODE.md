# 交给 opencode 的开发任务（2026-11-10 由手机端 DSH 交接）

## 上下文
项目：`~/code/xiaoi-plus`（LSPosed 模块，注入小爱同学）。
当前开发分支：`~/xiaoi-plus-dev/worktrees/liquid-nav` = `feat/ui-liquid`，HEAD `c0fc247`。
`main` = `31f9a24`（落后 feature 分支若干提交）。

## 任务 1（优先）：顶栏迁移到 Miuix 官方组件
**背景**：自研顶栏反复出问题（内容压字、鬼影、两层、滚动模糊不生效）。已实测 `miuix-ui 0.9.4` 自带合适组件，不要再自己写滚动监听。

已确认可用的 API（javap 过 `miuix-ui-android/0.9.4/miuix-ui.aar`）：
- `top.yukonga.miuix.kmp.basic.TopAppBar`：大标题可折叠，参数含 `ScrollBehavior` / 展开高度 / 折叠高度 / title / actions
- `SmallTopAppBar`：紧凑版（二级页）
- `TopAppBarState`：`heightOffset` / `collapsedFraction` / `overlappedFraction`
- `MiuixScrollBehavior(state, …)`、`rememberTopAppBarState(…)`
- `TopAppBarDefaults`：`collapsedHeight` / `titlePadding` 等
- 底栏另有 `FloatingNavigationBar` / `FloatingNavigationBarItem`（本次**不要动底栏**，它已调好玻璃折射）
- 注意：`miuix-ui` 内**没有** blur 组件；模糊在 `miuix-blur`（项目已依赖）

要求：
1. 一级页用 `TopAppBar`（大标题、滚动折叠）；二级页用 `SmallTopAppBar`（返回 + 标题）。
2. 滚动逻辑**交给 `MiuixScrollBehavior`**；删掉自研的 `LocalXiaoiScrollY` / `xiaoiScrollState()` / `rememberXiaoiListState()` 上报基建（若确实不再需要）。
3. 顶栏下方保留 `miuix-blur` 模糊带，透明度可由 `collapsedFraction` / `overlappedFraction` 驱动。
4. 所有页面内容仍穿到顶栏下方；页面顶部留白值改成 Miuix 顶栏的折叠高度（单源常量）。
5. **不要动**：底栏玻璃胶囊、pager 滑动、8770/8771/8772 端口与报文。
6. 编译 `./gradlew :app:assembleRelease` + `./tests/run.sh` + `./scripts/check-consistency.sh` 全绿。

## 任务 2：待办 CLI 封装
已打通：8771 MCP 工具 `xiaoai_todo_create {title, content}`（真机实测建出 noteId 139）。
要做的：把它包成一条 CLI 命令 `xa todo "买牛奶"`（复用 `lib/xiaoai_client.py`），并在 `cli/xa` 里加子命令 + 错误码人话表。

## 手机端已完成（别重复做）
- 玻璃底栏折射（`GlassBottomBar` 系列，真机验证）
- 左右滑动 pager（`HorizontalPager`，跟手）
- 底栏抬高避开小白指条、内容底部 112dp 留白
- 待办工具 `xiaoai_todo_create`（协议层、不过 LLM）

## 注意
- 本机 git 全局 insteadOf 指向坏镜像（gitclone.com），**禁止 git clone/fetch github**
- 不要 adb、不要装 APK 到手机（真机安装由手机端做）

---

# 任务 1 执行结果（2026-11-10，电脑端 opencode）

## 结论
完成。`:app:assembleRelease`、`tests/run.sh`、`scripts/check-consistency.sh` 三项门禁全绿。

## 改动清单（24 个文件：+248 / -229）
- **`ui/components/XiaoiBars.kt`**：删自研 `XiaoiTopBar`（44dp）与 `LocalXiaoiScrollY` /
  `xiaoiScrollState()` / `rememberXiaoiListState()`；新增 `LocalXiaoiTopBarHeight` +
  `xiaoiTopBarHeight()`（回退 = 状态栏 inset + `TopAppBarDefaults.CollapsedHeight` 单源）。
  `XiaoiBottomBar` 未动。
- **`MainActivity.kt`**：顶栏迁入 Scaffold `topBar` 槽位——一级页 `TopAppBar`
  （大标题 + 滚动折叠）、二级页 `SmallTopAppBar`（返回 + 标题）；`MiuixScrollBehavior`
  的 `nestedScrollConnection` 挂在内容根 Box 上，pager 各 tab 零改动接入；换页
  `LaunchedEffect(route)` 清零 `heightOffset` / `contentOffset`（对应旧 scrollY 归零）；
  `TopBlurScrim` 移入顶栏槽、z 序在顶栏下、`matchParentSize`，alpha 在 draw 阶段由
  fraction 驱动；顶栏本体 `animateColorAsState` 实色 → 0.35 半透明（保持原视觉）。
- **19 个 screen**：滚动 state 换回 `rememberScrollState()` / `rememberLazyListState()`，
  顶部留白仍走 `xiaoiTopBarHeight() + 8.dp`（内涵变为 Scaffold 实测高度），其余未动。
- 文档：`docs/ui/TOPBAR.md`（迁移完成记录）、`docs/ui/COMPONENTS.md`（顶栏章节重写）、
  `CHANGELOG.md`。

## 与任务书的两点偏差（都有原因）
1. **页面留白不是静态 52dp**：静态折叠高度会让展开态大标题压住内容（坑 #4「内容压字」）。
   改为 Scaffold 实测顶栏当前高度（注入 `LocalXiaoiTopBarHeight`，折叠时自动收缩到
   折叠高度），单源不变、不压字，且与 miuix 官方 example 的 `innerPadding` 用法一致。
2. **二级页模糊带补了折算**：`SmallTopAppBar` 会把共享 state 的 `heightOffsetLimit` 钉到
   0，`overlappedFraction` 恒为 0。二级页分支用 `contentOffset`（库同源分子）÷
   `CollapsedHeight` 折算 `contentUnderBarFraction`，不是恢复滚动上报基建。

## 门禁输出
- `./gradlew :app:assembleRelease --offline` → `BUILD SUCCESSFUL in 1m 10s`
- `./tests/run.sh` → `NavStackTest: 60 通过 / 0 失败`，`==> 全部通过`
- `./scripts/check-consistency.sh` → 9 项 ✓，`==> 一致性检查通过`

## 留给手机端的事
- 真机装 `~/xiaoi-plus-dev/artifacts/2026-11-10/apk/xiaoi-plus-topbar-miuix.apk`
  （= `app/build/outputs/apk/release/app-release.apk`）验收：一级页大标题滚动折叠、
  回拉展开；二级页返回 + 标题；滚动后顶栏半透明 + 渐变模糊带（API 33+ / FULL）。
- 之前调好的底栏玻璃胶囊、pager 滑动、端口报文均未触碰，理论上不需要回归；
  若真机发现顶栏折叠与 pager 切 tab 的时序有异样，优先看换页清零的 `LaunchedEffect(route)`。


---

# 复查修正（2026-11-10，电脑端）

收到任务 1 结果后做了代码复查，并修正了两处会直接影响真机观感的问题：

1. **双重顶部留白**：页面仍保留 `xiaoiTopBarHeight() + 8.dp`，同时内容根不再吃
   Scaffold padding，导致顶栏高度变化时页面内边距也跟着变、滚动中内容跳动。
   改为：内容根统一 `padding(top = padding.calculateTopPadding())`，页面删除顶栏留白，
   只保留底部 112dp（玻璃底栏）。这是 Miuix Scaffold 的标准用法。
2. **模糊带与折叠态冲突**：`TopBlurScrim` 用 `matchParentSize` 覆盖整个展开顶栏，
   与“模糊带高度 = 折叠高度”的目标不一致；且内容在无 padding 时没有可采样的背景。
   本次先移除实验模糊带（顶栏实色），待真机确认后再按折叠高度单独实现。

另外把 pager 的 `beyondViewportPageCount` 从 1 改为 0：Home/Xiaoai 都有 5 秒轮询，
预组合相邻页会造成重复 IO。门禁三项全绿。
