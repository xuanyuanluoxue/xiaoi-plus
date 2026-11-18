# Windows 继续开发指南与下一阶段优化

> 状态：**生效**（2026-11-17 起，Windows 原生主环境）
> 基线：`main`，架构重构 WP0–WP6 已完成（真实历史，非恢复仓库）
> 归档 tag：`archive-2026-11-17`（仅快照，项目已恢复开发）
> 旧归档说明：[../../ARCHIVE.md](../../ARCHIVE.md)

## 1. 环境

| 项 | 值 |
|---|---|
| 仓库 | `D:\code\Android\xiaoi-plus` |
| WSL 路径 | `/mnt/d/code/Android/xiaoi-plus` |
| JDK | `C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot` |
| Android SDK | `C:\Users\Chatxavier\AppData\Local\Android\Sdk` |
| 模块 | `:domain` / `:application` / `:infra` / `:app` |

```bat
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"
gradlew.bat :domain:compileJava :application:compileJava :infra:compileJava :app:assembleRelease --offline
```

脚本用 Git Bash 或 WSL：

```bash
bash tests/run.sh
bash scripts/check-consistency.sh
bash scripts/check-layering.sh --strict
bash scripts/verify-apk.sh app/build/outputs/apk/release/app-release.apk
```

## 2. 当前架构基线

```
:domain       53 文件 / 5.3k 行   模型/策略/协议 DTO/端口；无 I/O、无 Android、无 Xposed
:application  29 文件 / 5.5k 行   用例/编排/端口定义；零 I/O
:infra         9 文件 / 1.5k 行   Http/ConfigStore/传输/探测/文件锁；纯 JVM
:app         108 文件 / 22.5k 行  Android/Xposed/Compose UI
```

- `app -> {domain, application}`，`application -> domain`
- `tests/run.sh` 先用空 classpath 单编 domain
- 分层基线 11 行：`PermissionProbe` 8 + `ui_no_control` 3

## 3. 下一阶段优化（按 ROI 排序）

> 下面的 P0–P4 已细化成工作包 WP7–WP13（含文件清单、门禁、验收与回滚），
> 见 **[LAYERING-2.md](LAYERING-2.md)**。本节的 P 编号保留作路线索引。

### P0：删除旧 Java 页面（约 -1.5k 行）

- `ConfigActivity`（1148 行）、`HomeActivity`、`ui/config/*`、`ui/config` 相关布局/资源。
- 入口统一到 `MainActivity` + Compose；`LegacyScreen` 的“旧版入口”一起删除。
- 目标：app 层减少约 6%，消除两套配置页行为分叉。
- 验收：19 条路由仍可用；主题/配置保存/导入导出/诊断全走新页面；无 `ConfigActivity` 引用。

### P1：拆 `:infra`（application 纯化）— ✅ 已完成（WP8，2026-11-18）

把纯 JVM 适配器从 `:application` 移出：

- `Http`、`ConfigStore`、`FileConfigStore`、`BridgeClient`、`ModelValidator`、
  `LeaderElection`、`Lan`、`AgentHttpServer`、`BridgeAskHost`（9 个文件）
- `:application` 只保留用例/端口/协议编排；`ConfigStorePort` 端口留在 `:application`，
  实现下沉 `:infra`（方向 `infra -> application`）
- `:app -> {domain, application, infra}`

验收结果：`:application` 内 `java.io|net|nio` = 0（`application_no_io` 门禁上线），
基线从 49 行降到 11 行；`ConfigCodec.fromStream` 删除，改由适配器读流。

### P2：清掉 `ui_no_control` 3 条

当前 `DiagnosticsScreen` / `LogsScreen` / `MeScreen` 直接 import
`plus.xiaoi.module.control.BridgeControlService`。

- 新增 application 查询端口（如 `ControlPlaneQuery`）：
  `logs() / clearLogs() / instanceRecords()`。
- Android 适配器在 `:app`，UI 只依赖端口。
- 验收：`scripts/layering-baseline.tsv` 只剩 `PermissionProbe` 8 行。

### P3：domain 内部整理

- `core -> agent`、`agent -> core` 包级环消除：
  `domain/model`、`domain/policy`、`domain/protocol`、`domain/registry`。
- 进程级可变注册表（`BridgeEndpoint` 的 `Runnable`、`LogSettings`、`AgentRuntimeStatus`）
  移到 `:application`/`:infra` 或改为显式端口。
- 验收：domain 包级无环；domain-only javac 仍通过。

### P4：Bridge 拆分

`Bridge` 仍有 1073 行，混 socket / Binder / CLI 反射 / 路由 / 线程池：

- `BridgeSocketServer`（accept/线程池/HTTP 写回）
- `BridgeCliAdapter`（Binder/反射/会话）
- `BridgeRouter`（路径/鉴权/限流；已有 `BridgeHttpPolicy`）
- `Bridge` 只做组装

验收：45 项契约不变；8771 工具表/报文不变。

### P5：真机 P0 与 git 历史

- WP1–WP6 的 P0：权限矩阵、D2 TTL、8770 启停、配置导入、watcher、
  8771 LAN/leader、控制面端口上报。
- 恢复远端 git 历史并推送 `main`；归档 tag 仅作快照。

## 4. 每个阶段的固定门禁

```bash
bash tests/run.sh
bash scripts/check-consistency.sh
bash scripts/check-layering.sh --strict
gradlew.bat :app:assembleRelease --offline
bash scripts/verify-apk.sh app/build/outputs/apk/release/app-release.apk
```

## 5. 子 Agent 约束

- 只读审查可并行；写操作默认串行。
- 实现子 Agent 必须给文件白名单。
- 每个阶段独立 commit；不跨阶段改同一文件。
- 不碰 Xposed hook 目标、8771/8770 协议、`config.json` 格式。
