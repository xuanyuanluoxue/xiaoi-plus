# 文档索引

> **当前环境（2026-11-17 起）**：Windows 原生 `D:\code\Android\xiaoi-plus`
> （WSL：`/mnt/d/code/Android/xiaoi-plus`）。
> `docs/agent/*`、`docs/ui/*-REPORT.md`、`docs/ui/DEVICE-CHECK-*`、`docs/ui/P0-REPORT.md`、
> `docs/refactor/RECOVERY-*`、`docs/refactor/drafts/*` 等是**历史存档**，
> 其中 `~/xiaoi-plus-dev`、`/home/chatxavier/...`、`./gradlew`、`./tests/run.sh`
> 均为迁移前 WSL 环境的写法，仅作记录，不再代表当前路径。
>
> **继续开发入口**：[refactor/NEXT.md](refactor/NEXT.md)（Windows 环境、下一阶段优化）。

| 文档 | 内容 |
|---|---|
| [../ARCHIVE.md](../ARCHIVE.md) | 归档快照说明：最终模块、验证结果、已知限制（项目已恢复开发） |
| [refactor/NEXT.md](refactor/NEXT.md) | **下一阶段架构优化**：删旧页面、拆 `:infra`、清 `ui_no_control`、Bridge 拆分 |
| [design-system.md](design-system.md) | UI 声明：颜色/字号/间距/组件/状态矩阵 |
| [ui-architecture.md](ui-architecture.md) | 四页结构与数据流图（Mermaid） |
| [remote-debug.md](remote-debug.md) | 远程调试手机：adb / logcat / adb forward / root / 排障 |
| [architecture.md](architecture.md) | 模块 hook 点与设计原则 |
| [compatibility.md](compatibility.md) | 小爱版本矩阵与适配清单 |
| [integration.md](integration.md) | cURL / Python / MCP 接入 |
| [security.md](security.md) | 密钥、数据去向、桥边界、回滚 |
| [build.md](build.md) | 从零构建 |
| [ci.md](ci.md) | CI 与本地门禁：单测、认知检查、真机契约、token 存放 |
| [release.md](release.md) | 版本号、签名、发 tag |
| [feature-candidates.md](feature-candidates.md) | 功能候选与路线图 |
| [refactor/PLAN.md](refactor/PLAN.md) | 0.12.0 核心重构：目标、架构、工作包、验收 |
| [refactor/WORKPACKAGES.md](refactor/WORKPACKAGES.md) | 重构工作包与子 Agent 指令 |
| [refactor/LAYERING.md](refactor/LAYERING.md) | 分层与依赖倒置改造提案：目标层、端口、WP0~WP5、验收指标 |
| [refactor/WP2-HOOK.md](refactor/WP2-HOOK.md) | WP2 执行计划与记录：Hook 策略、进程宿主、双负例门禁 |
| [refactor/WP3-BRIDGE.md](refactor/WP3-BRIDGE.md) | WP3 执行计划与记录：Mcp 端口拆分、BridgeRuntime、生命周期决策 |
| [refactor/WP4-UI.md](refactor/WP4-UI.md) | WP4 执行计划与记录：AppRuntime、UI 去 Context、门禁 |
| [refactor/WP5-CORE.md](refactor/WP5-CORE.md) | WP5 执行计划与记录：`:core` 物理模块、路径/门禁迁移 |
| [refactor/WP6-DOMAIN.md](refactor/WP6-DOMAIN.md) | WP6 执行计划与记录：`:domain` / `:application` 拆分 |
| [refactor/RECOVERY-2026-11-17.md](refactor/RECOVERY-2026-11-17.md) | 2026-11-17 仓库异常清空后的恢复记录 |
| [agent/PLAN.md](agent/PLAN.md) | D1 Agent 能力层与记忆系统：8770 动作面、root/Shizuku、记忆读写 |
| [agent/PHASE0.md](agent/PHASE0.md) | Phase 0 侦察计划（已完成） |
| [agent/PHASE0-REPORT.md](agent/PHASE0-REPORT.md) | Phase 0 侦察报告：CLI/岛/权限/记忆结论 |
| [agent/PHASE1.md](agent/PHASE1.md) | Phase 1 工作包：ActionRegistry、App 8770、5 个动作 |
