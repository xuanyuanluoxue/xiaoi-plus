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
