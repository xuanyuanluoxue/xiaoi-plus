# 文档索引

只保留**现在仍然有用**的文档。已完成的批次报告、一次性任务书、恢复记录和旧版草案
都已删除——历史变更看 [CHANGELOG](../CHANGELOG.md)，不要在新文档里重复版本沿革。

## 架构与契约

| 文档 | 内容 |
|---|---|
| [architecture.md](architecture.md) | 单端口拓扑、hook 点、控制面、分层与依赖方向 |
| [ui-architecture.md](ui-architecture.md) | 页面结构与数据流 |
| [compatibility.md](compatibility.md) | 小爱版本矩阵与适配清单 |
| [security.md](security.md) | 密钥、数据去向、桥边界、回滚 |

## 接入与运维

| 文档 | 内容 |
|---|---|
| [integration.md](integration.md) | cURL / Python / MCP 接入 |
| [ci.md](ci.md) | CI 与本地门禁、token 存放 |
| [release.md](release.md) | 签名、打 tag、发布检查清单 |
| [remote-debug.md](remote-debug.md) | adb / logcat / forward / root 排障 |

## 界面规范

| 文档 | 内容 |
|---|---|
| [design-system.md](design-system.md) | 颜色 / 字号 / 间距 / 组件 / 状态矩阵 |
| [ui/SCREENS.md](ui/SCREENS.md) | 路由表与每页交互 |
| [ui/COMPONENTS.md](ui/COMPONENTS.md) | 组件契约 |
| [ui/CONVENTIONS.md](ui/CONVENTIONS.md) | 页面写法约定 |
| [ui/TOPBAR.md](ui/TOPBAR.md) | 顶栏与沉浸式处理 |
| [ui/BUILD.md](ui/BUILD.md) | 构建环境与产物路径 |

## 能力与规划

| 文档 | 内容 |
|---|---|
| [agent/CAPABILITIES.md](agent/CAPABILITIES.md) | 能力清单与风险分级（唯一事实源） |
| [agent/PLAN.md](agent/PLAN.md) | Agent 能力层：设备动作、root/Shizuku、记忆 |
| [agent/OSBOT-PROTOCOL.md](agent/OSBOT-PROTOCOL.md) | 小爱内部协议逆向笔记 |
| [agent/P2-PROMPT-MEMORY.md](agent/P2-PROMPT-MEMORY.md) | 提示词与记忆：设计、验收、未做项 |
| [feature-candidates.md](feature-candidates.md) | 候选能力与取舍 |
| [plan-model-config.md](plan-model-config.md) | 模型与配置的规划 |
| [../reverse/README.md](../reverse/README.md) | **逆向子项目**：架构、模型链路、工具链、技能、记忆与重放脚本 |

## 重构

| 文档 | 内容 |
|---|---|
| [refactor/README.md](refactor/README.md) | 重构总览：目标、边界、决策与文档地图 |
| [refactor/NEXT.md](refactor/NEXT.md) | **下一步做什么**（入口） |
| [refactor/LAYERING.md](refactor/LAYERING.md) | 分层与依赖倒置 |
| [refactor/LAYERING-2.md](refactor/LAYERING-2.md) | 分层第二阶段 |
| [refactor/LOGGING-1.md](refactor/LOGGING-1.md) | 日志子系统：设计、合并视图、未做项 |

## 归档

| 文档 | 内容 |
|---|---|
| [../ARCHIVE.md](../ARCHIVE.md) | 仓库外的历史快照说明 |

> 写作约定：**不要在新文档里写"某版本起支持"**。版本沿革只属于 CHANGELOG；
> 设计文档描述"现在是什么样"，需要历史时链接 CHANGELOG。
