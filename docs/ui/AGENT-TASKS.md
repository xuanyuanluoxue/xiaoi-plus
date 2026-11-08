# Agent 任务分工（P2 / 0.14 UI 重写）

> 状态：**执行中**（2026-11-08）
> 原则：一个文件一个 owner；每个 Agent 独立 worktree；合并由主 Agent 负责；共享组件不跨 Agent 直接改。
> 关联：[PLAN.md](PLAN.md)、[CONVENTIONS.md](CONVENTIONS.md)、[COMPONENTS.md](COMPONENTS.md)、[SCREENS.md](SCREENS.md)。

## 0. 当前状态

### 已完成

| Agent | 结果 |
| --- | --- |
| QA-Acceptance | P1 功能验收 41 PASS / 0 FAIL，报告 `xiaoi-plus-ui/tests/2026-11-08/acceptance-p1.md` |
| QA-Visual | P1 视觉审核“有条件通过”，4 条 P1 已修，报告 `xiaoi-plus-ui/tests/2026-11-08/visual-review-p1.md` |
| A3 Core Data | `eb71416`：`AgentRuntimeStatus`/`PermissionProbe`/`IssueInputFactory`/`ActivityHub`，104 断言 |
| A2 UI Foundation | `4f7f7e4`：19 路由、16 占位页、14 共享组件，报告 `docs/ui/FOUNDATION-REPORT.md` |

### Wave 2 页面（已完成并合并）

| Agent | Commit | 产出 |
| --- | --- | --- |
| A4 Home | `0b47440` | `ui/screen/home/**` + `docs/ui/reports/A4-HOME.md` |
| A6 Access | `762663c` | `ui/screen/access/**` + `docs/ui/reports/A6-ACCESS.md` |
| A5 Xiaoai+Model | `d2ba076` | `ui/screen/xiaoai/**` + `docs/ui/reports/A5-XIAOAI.md` |
| A7 Me+Config/Diagnostics | `3eb3967` | `ui/screen/me/**` + `docs/ui/reports/A7-ME.md` |
| 集成修复 | `c03aa40` | 能力页不自动弹键盘、FilterChips 换行 |

### P2 验收

| Agent | 结果 |
| --- | --- |
| QA-Functional | 65 PASS / 0 FAIL / 2 BLOCKED，报告 `acceptance-p2.md` |
| QA-Visual | 有条件通过：0 P0 / 5 P1 / 13 P2，报告 `visual-review-p2.md` |

### P1 修复与 Wave 3（已完成）

| Agent | Commit | 结果 |
| --- | --- | --- |
| P2-Fix | `d6ea907` | 5 条 P1 全修：对比度 5.01:1、统一 chip、模型页布局、8771 口径、次按钮 |
| 集成补修 | `c59954d` | 诊断页 3 秒重采，Access/Diagnostics 8770 结论一致 |
| A8 Release | `c0355b0` | R8 8.4→1.6 MiB、0.14.0/19、CI 切 JDK 21/Gradle 9.9 |
| A9 Docs | `963fa30` | README/CHANGELOG/PLAN/SCREENS/COMPONENTS/RELEASE-0.14 |

### 最终状态

- `main` 已推送；门禁全绿；R8 包真机安装、LSPosed 注入与 8771 已复验。
- 已知限制：8770 与小米自带服务同端口，存在 EADDRINUSE 抢占；8771 不受影响。
- 剩余 P2 视觉项（顶栏动作、底栏图标风格、日志域筛选、Connect Sheet 等）转 0.15。
- 发布：`v0.14.0` 已打 tag 并通过 CI 创建 GitHub Release；资产
  `xiaoi-plus-0.14.0.apk`（1.6 MiB）SHA-256 与签名均已复验。

## 1. Wave 1：地基（2 个 Agent，可并行）

### A2 · UI Foundation（路由 + 组件库）

- 分支/worktree：`feat/ui-foundation` / `~/xiaoi-plus-dev/worktrees/ui-foundation`
- 范围：
  - `MainActivity.kt`：把全部路由（Home/Xiaoai/Model/Access/Bridge/Token/Connect/Capabilities/CapabilityDetail/Tools/Permissions/Me/Appearance/ConfigManage/Diagnostics/Logs/About/Licenses/Legacy）接到占位 composable；后续 Agent 只替换自己的 screen 文件，不再改 `MainActivity`。
  - `ui/theme/**`：补 `XiaoiTheme` 的 `surfaceContainer` 等映射；保持 Miuix 默认尺寸。
  - `ui/components/**`：Miuix 没有的业务组件：`StatusHero`、`DimensionBadge`、`IssueRow`、`PortRow`、`CapabilityRow`、`PermissionRow`、`FilterChips`、`SearchField`、`LoadingSkeleton`、`CodeBlock`、`DirtyBar`、`ConfirmCard`。
  - 每个 screen 包创建同名占位 composable（`HomeScreen`、`XiaoaiScreen`、`AccessScreen`、`MeScreen` 等），签名固定。
- 不碰：`core/**`、`agent/**`、`Mcp.java`、`app/build.gradle.kts`、资源/Manifest。
- 验收：`assembleDebug`、`tests/run.sh`、`check-consistency.sh`；组件 gallery 深浅色截图。
- 交付：组件契约与占位路由；报告 `docs/ui/FOUNDATION-REPORT.md`。

### A3 · Core Data（只读状态/探针）

- 分支/worktree：`feat/ui-core-data` / `~/xiaoi-plus-dev/worktrees/ui-core-data`
- 范围：
  - `agent/AgentRuntime.java`：8770 运行/端口/绑定/启动时间/最后错误只读快照。
  - `core/BridgeStatus.java`：补 8771 实际 bind/LAN 字段（不破坏现有 API）。
  - `core/PermissionProbe` 或 `PermissionProbe` Android 实现：通知/精确闹钟/全屏/电池优化/usage/通知监听。
  - `core/IssueInputFactory`：把 `StateModel/BridgeStatus/AgentRuntime/PermissionSnapshot` 适配为 `IssueResolver.Input`。
  - `ActivityFeed` 接线：8770/8771 事件写入（只读、内存）。
- 不碰：UI、Compose、`MainActivity`、`Actions/AndroidExecutor` 行为。
- 验收：`tests/run.sh` 新增断言；`assembleDebug`；不改变现有动作结果。
- 交付：纯 Java/Android 只读 API + 测试；报告 `docs/ui/CORE-DATA-REPORT.md`。

## 2. Wave 2：页面（4 个 Agent，并行）

> 全部依赖 A2 的占位路由与组件；Home/Access 额外依赖 A3。

### A4 · Home

- 分支：`feat/ui-home`；目录：`ui/screen/home/**`
- 内容：首页 5 块（状态总览、待处理、双域入口、运行概况、最近活动）；接 `StateModel/IssueResolver/AgentRuntime/BridgeStatus/PermissionProbe/ActivityFeed`。
- 约束：首页不做配置；无输入/开关/保存；统计未知显示“—”；只有 D4 能显示“已确认”。
- 验收：8 种状态截图；0 条 issue 无空卡；8770/8771 分开；断链 CTA 正确。

### A5 · 小爱 + 模型

- 分支：`feat/ui-xiaoai`；目录：`ui/screen/xiaoai/**`
- 内容：小爱一级（状态卡/主开关/当前模型/运行态/增强占位）、模型与服务商（CRUD/端点/密钥/校验/保存/冲突）。
- 约束：主开关即时保存；表单显式保存；配置损坏只读；不新增提示词/工具功能。
- 验收：模型 CRUD、校验、冲突、损坏、无服务商/无模型空态；与旧 Config 行为 parity。

### A6 · 接入 + 能力

- 分支：`feat/ui-access`；目录：`ui/screen/access/**`
- 内容：接入一级、端口与服务、令牌/LAN、连接信息、能力清单、能力详情、工具清单、权限矩阵。
- 约束：8770 无独立开关；弱令牌回退；复制提示含令牌；能力只读。
- 验收：8771/8770 分开；弱令牌/LAN/离线/启动失败；能力 67 条筛选；5+8+6 工具详情。

### A7 · 我的 + 配置/诊断

- 分支：`feat/ui-me`；目录：`ui/screen/me/**`（Me/Appearance/ConfigManage/Diagnostics/Legacy；Logs/About 可复用）
- 内容：本机/版本、外观、备份/还原/脱敏/导入、权限与诊断、危险区「停用全部」、只读兼容模式。
- 约束：备份含密钥确认；脱敏不能还原；Root/LSPosed 不撒谎；危险区默认折叠。
- 验收：备份/还原/导入/损坏恢复；主题三档；停用全部；兼容模式无保存。

## 3. Wave 3：收尾（2 个 Agent，可并行）

### A8 · Build / Release

- 分支：`feat/ui-release`；文件：`app/build.gradle.kts`、`gradle/**`、`scripts/ci*`、`.github/**`、`proguard-rules.pro`
- 内容：R8 + shrinkResources、keep 规则、multidex、依赖 lock/verification、CI、APK ≤6MB、签名/指纹。
- 验收：release 构建、verify-apk、LSPosed/模型重定向回归、APK 体积。

### A9 · Docs / Skill

- 分支：`feat/ui-docs`；文件：`README.md`、`CHANGELOG.md`、`HANDOVER.md`、`docs/**`、`skill/**`
- 内容：0.14 UI 说明、截图路径、Miuix 致谢、Agent/skill 文档同步。
- 约束：不改代码；不写未实现功能。

## 4. 文件 ownership

| 路径 | Owner |
| --- | --- |
| `MainActivity.kt`、`ui/theme/**`、`ui/components/**`、screen 占位签名 | A2 |
| `agent/AgentRuntime.java`、`core/BridgeStatus.java`、`core/PermissionProbe*`、`core/IssueInputFactory`、`core/ActivityFeed` 接线 | A3 |
| `ui/screen/home/**` | A4 |
| `ui/screen/xiaoai/**` | A5 |
| `ui/screen/access/**` | A6 |
| `ui/screen/me/**` | A7 |
| `app/build.gradle.kts`、`gradle/**`、`scripts/ci*`、`.github/**`、`proguard-rules.pro` | A8 |
| `README.md`、`CHANGELOG.md`、`HANDOVER.md`、`docs/**`、`skill/**` | A9 |
| 验收/视觉报告 | QA-A/QA-V |

**共享组件变更**：A4-A7 需要改 `ui/components` 或 `XiaoiTheme` 时，不直接改；向 A2 提 issue/说明，由 A2 合并后同步。

## 5. 合并与验收流程

1. A2/A3 先合并到 `main`，主 Agent 跑门禁与真机 smoke。
2. A4-A7 从最新 `main` 建 worktree；各自只改自己目录；完成后提交。
3. 主 Agent 按 A4 → A5 → A6 → A7 顺序合并；每次合并后跑：
   - `tests/run.sh`
   - `scripts/check-consistency.sh`
   - `assembleRelease` + `verify-apk.sh`
   - 真机安装/截图（用 `scripts/ui-screenshot.sh --review`）
4. A8 最后做 R8/CI；A9 文档收尾。
5. 每个 worktree 合并后立即 `git worktree remove`，不散落 `~/code`。

## 6. 时间估算

| Wave | 内容 | 人日 |
| --- | --- | --- |
| Wave 0 | 验收 + 视觉审核 | 1–2 |
| Wave 1 | A2 组件/路由 + A3 数据 | 3–5 |
| Wave 2 | A4–A7 四页并行 | 6–10 |
| Wave 3 | A8 构建/R8 + A9 文档 | 3–5 |
| 回归 | 截图矩阵 + 真机 + 发布 | 2–3 |

总计约 **15–25 人日**（并行后墙钟时间约 8–12 天）。

## 7. 启动顺序

1. 等 QA-A/QA-V 报告，修 P1 阻塞项（如有）。
2. 派 A2 + A3（互不重叠，可并行）。
3. A2/A3 合并后派 A4–A7。
4. 最后派 A8、A9，并做最终 QA/视觉验收。
