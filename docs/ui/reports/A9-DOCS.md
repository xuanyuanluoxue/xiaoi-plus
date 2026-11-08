# A9 · Docs / Release Notes 报告（0.14）

> 日期：2026-11-08
> 分支：`feat/ui-docs`（基于 `main@c59954d`）
> 工作区：`~/xiaoi-plus-dev/worktrees/ui-docs`
> 范围：只改文档；未改代码、Gradle、Manifest、资源、既有 `docs/ui/reports/**` 与 QA 报告；
> 只新增本报告。

## 1. 改了哪些文档

| 文件 | 改动 |
| --- | --- |
| `README.md` | 版本头改 0.14.0；新增 4 tab/19 路由/组件/深浅色/日志/关于说明；安装步骤与字段位置改到新 UI；补安全提示（明文备份/脱敏不可还原/Token=访问凭证）；构建段改 JDK 21 + Gradle 9.9 + AGP 9.5.1 + `verify-apk.sh`；补旧入口过渡说明；文档索引加 RELEASE/BUILD |
| `CHANGELOG.md` | 新增 `0.14.0 — 2026-11-08`，汇总 P1 严格 Miuix、P2 四页、P2 视觉修复、A3 数据层、验收、兼容/升级、已知限制；保留 0.13.0 及更早条目 |
| `docs/ui/PLAN.md` | 状态改“P0/P1/P2 完成、P3 A8 进行中”；目标版本 0.14.0；P0/P1/P2/P3 标题与验收清单更新；Connect 改二级页；新增 §15 状态、P3 事项与剩余 P2 视觉列表 |
| `docs/ui/SCREENS.md` | Connect 从 BottomSheet 改二级页；Access 不再放“停用全部”（移到我的危险区）；Bridge 注明无重启按钮；Logs 明确 0.14 不做域筛选；新增“0.14 明确不做”表 |
| `docs/ui/COMPONENTS.md` | `FilterChips` 补“全站唯一、XiaoiChip 已删除、日志间距 12dp”；`XiaoiButton(SECONDARY)` 补 7.42/8.32 对比度；TextField 5.01:1；Copy/Permission 48dp；新增 `AgentStatus / agentPortUi` 单点判定 |
| `docs/ui/BUILD.md` | 环境表改 JDK 21/SDK/Gradle 实际状态；`miuix-icons` 加入依赖；版本号改为 Manifest 单一来源（0.14.0/19）；R8 标注 A8 进行中、8.3 MiB 现状；新增 §14 0.14 构建/路由/截图命令与结果 |
| `docs/ui/RELEASE-0.14.md` | **新增**：版本内容、19 页清单、P1/P2 验收、构建门禁、已知限制、截图索引、升级/兼容 |
| `HANDOVER.md` | 顶部状态改 0.14（P3 A8 进行中）；设备表版本更新；验证命令改 Gradle/verify-apk；新增 0.14 完成项；删除“单 Activity 未采用”等过期描述；下阶段建议改为 0.14 收尾/0.15 |
| `skill/xiaoi-agent/SKILL.md`、`skill/xiaoi-mcp/SKILL.md` | “App 配置页”改为「接入 › 令牌与局域网」/「接入 › 连接信息」/「端口与服务」；保留 MCP/动作面说明与安全边界 |
| `.opencode/skills/xiaoi-agent/SKILL.md`、`.opencode/skills/xiaoi-mcp/SKILL.md` | 与 `skill/` 实体副本同步同样的文案 |

未改：任何 `.kt/.java`、`app/build.gradle.kts`、`AndroidManifest.xml`、`res/**`、
`docs/ui/reports/{A4,A5,A6,A7,P2-VISUAL-FIXES}.md`、`tests/2026-11-08/**`。

## 2. 0.14 说明要点

- **版本**：目标 `0.14.0` / versionCode 19（A8 定版；本分支写作时 Manifest 仍是
  `0.13.0-agent.2` / 18，文档已注明以 A8/RELEASE-REPORT 为准）。
- **导航**：单 Activity + 4 tab（首页/小爱/接入/我的），19 条路由；一级页有底栏，
  二级/三级页隐藏底栏并显示返回。
- **页面**：首页 5 块只读；小爱主开关即时保存 + 模型页显式保存；接入 8771/8770 分开、
  令牌/LAN、连接信息、67 能力 + 5 动作 + 8 + 6 工具、6 项权限；我的含外观/配置管理/
  诊断/日志/关于/许可/兼容模式/危险区。
- **视觉**：严格 Miuix；`FilterChips` 28dp 视觉 + 48dp 触控；对比度修到 4.5:1 以上
  （输入 5.01、徽章 6.08/6.85/6.54、次按钮 7.42/8.32）。
- **状态口径**：8771 统一“控制面”；8770 由 `AgentStatus.agentPortUi` 单点判定，
  未知统一“未确认”。
- **构建**：JDK 21 + Gradle 9.9 + AGP 9.5.1 + Compose 1.12.2 + Miuix 0.9.4；
  `tests/run.sh`、`check-consistency.sh`、`verify-apk.sh`。
- **兼容**：同包名同签名覆盖安装，config v3 保留；`POST /config` 仍 410；旧
  `HomeActivity`/`ConfigActivity` 过渡保留，0.15 删除。

## 3. 已知未实现 / 不在 0.14

1. 提示词、工具注入、技能包、记忆、语音与路由：只读“规划中”占位，无开关/执行。
2. 日志只有级别筛选，**没有域筛选**。
3. Connect 是二级整页，不是 BottomSheet。
4. 能力/工具/权限只读；审计、确认队列、测试台/试运行 0.15。
5. Bridge 无“重启 8770”；保存配置触发重绑。
6. 危险区只做“停用全部”；重置/清除数据 0.15。
7. 剩余视觉项：第二条工具栏、首页严重度、底栏图标风格、能力词表、Tools 风险 badge、
   ConfigManage 风险分级、About 许可重复、Token“实际”语义、外观留白、筛选条件返回重置。
8. R8/shrink、CI、发布脚本、LSPosed/8770/8771 真机回归由 A8 收口；
   APK ≤6MB 未确认，文档不承诺。
9. 弱令牌回退、损坏只读两个用例在 P2 验收为 BLOCKED（仅静态证据）。

## 4. 截图索引

目录：`~/xiaoi-plus-dev/xiaoi-plus-ui/tmp/2026-11-08/`

- 修后浅色：`p3-xiaoai-light`、`p3-model-light`、`p3-access-light`、`p3-token-light`、
  `p3-connect-light`、`p3-capabilities-light`、`p3-tools-light`、`p3-permissions-light`、
  `p3-diagnostics-light`、`p3-logs-light`、`p3-appearance-light`、`p3-about-light`。
- 修后深色：`p3-xiaoai-dark`、`p3-model-dark`、`p3-access-dark`、`p3-token-dark`、
  `p3-logs-dark`、`p3-appearance-dark`、`p3-tools-dark`、`p3-permissions-dark`。
- 字号 1.3：`p3-model-light-130`、`p3-permissions-light-130`、`p3-tools-light-130`、
  `p3-configmanage-light-130`。
- 一致性：`p3-access-consistency3`、`p3-diagnostics-consistency3`。
- 修前对照：`p2b-*`、`p2-capabilities-fixed`、`a4-home-light`、`a6-access-light2`。

详细清单见 [RELEASE-0.14.md §5](../RELEASE-0.14.md#5-截图索引)。

## 5. 验收

- `git diff --stat` 只含 Markdown（含新增 `docs/ui/RELEASE-0.14.md` 与本报告）。
- README/CHANGELOG 版本号写作 0.14.0，页面名与 `MainActivity` 路由表一致
  （首页/小爱/模型与服务商/接入/端口与服务/令牌与局域网/连接信息/能力清单/
  能力详情/工具清单/权限/我的/外观/配置管理/诊断/日志/关于/开源许可/兼容模式）。
- Markdown 相对链接目标已核对存在（`RELEASE-0.14.md`、`BUILD.md`、`SCREENS.md`、
  `COMPONENTS.md`、`reports/P2-VISUAL-FIXES.md`、`CHANGELOG.md`、`docs/build.md`、
  `docs/release.md`、`docs/ui-architecture.md`）。
- 未构建；未改代码/Gradle/Manifest/资源；未 push、未 merge。

## 6. 交付

- 分支：`feat/ui-docs`（基于 `main@c59954d`）。
- 本报告：`docs/ui/reports/A9-DOCS.md`。
