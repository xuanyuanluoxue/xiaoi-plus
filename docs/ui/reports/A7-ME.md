# A7 · 我的 + 配置/诊断 报告

> 分支：`feat/ui-me`（worktree `~/xiaoi-plus-dev/worktrees/ui-me`，基于 main `3a6edab`）
> 范围：`app/src/main/java/plus/xiaoi/module/ui/screen/me/**`（含新增 `MeSupport.kt`）
> 未改：`MainActivity.kt`、`ui/components/**`、`ui/theme/**`、`core/**`、`agent/**`、`control/**`、Gradle、Manifest、`res/**`、其他页面目录。

## 1. 页面结构

| 页面 | 内容 |
| --- | --- |
| 我的 `MeScreen` | 本机卡（版本 / 版本号、设备型号、Android+API 分三行）；Root / LSPosed 两行徽章；设置入口（外观、配置管理、诊断、日志+错误数、关于、开源许可）；危险区默认折叠 |
| 外观 `AppearanceScreen` | 跟随系统 / 浅色 / 深色三档，`onThemeChange` 即时生效；说明不写 `config.json`、不参与导出 |
| 配置管理 `ConfigManageScreen` | 当前配置摘要；备份（含密钥）；文件/剪贴板还原；脱敏导出/分享；运行时导入；损坏恢复（仅只读时出现） |
| 诊断 `DiagnosticsScreen` | D1–D4、8771/8770、配置完整性、Root/LSPosed、6 项权限矩阵、复制诊断信息、导出日志、清空本机日志、兼容模式入口 |
| 兼容模式 `LegacyScreen` | 只读横幅、路径/状态/修改时间、关键字段、脱敏 JSON、`.legacy`/`.corrupt` 文件名、旧版页面过渡入口；无保存/导入 |
| 开源许可 `LicensesScreen` | GPL-3.0、Apache-2.0（Miuix）、Kotlin/Compose/AndroidX 与致谢 |
| 日志 `LogsScreen` | 仅修 P1 遗留：消息单行省略、错误行 2dp 左竖条；三种空态与筛选保持原实现 |

本机卡不再显示头像/账号；删除了 P1 预览卡与调试路由文案。旧版首页/旧版配置入口只保留在「诊断 > 兼容模式」。

## 2. 备份 / 还原策略

1. **完整备份（含密钥）**
   - 点击后先弹 `XiaoiConfirmDialog`，明确「包含明文 API Key 与 MCP Token，任何拿到文件的人都能使用」。
   - 确认后走 SAF `CreateDocument("application/json")`，写 `ConfigRepository.toJson(cfg).toString(2)`；导出完成提示「用完请删除」。
2. **脱敏导出 / 分享**
   - 复用 `ConfigCodec` 的 JSON 结构，只把每个 provider 的 `apiKey` 与非空 `token` 替换为 `***`；导出副本强制写 `token=***`。
   - 还原时用 `ConfigCodec.containsMaskedSecrets` 拒绝 `***` 文件，提示改用完整备份；脱敏副本不可还原。
3. **还原（文件 / 剪贴板）**
   - SAF `OpenDocument` 或剪贴板读取 → `ConfigCodec.fromJson` → `containsMaskedSecrets` → `ConfigCodec.validate`。
   - 只有全部通过才弹出覆盖确认；确认后 `ConfigRepository.save()`（校验 → `ConfigStore.writeAtomic` tmp+fsync+rename → prefs → reload）。
   - 解析、校验、写入任一步失败都不调用 `save`；失败提示「现有配置未改动」。
   - 读取上限 2 MB，避免误选大文件。
4. **运行时导入**
   - 保留 `ConfigSync.importFromRuntime`；确认框说明只覆盖模型/端点、密钥沿用当前文件，失败不写文件。
5. **损坏只读**
   - `ConfigRepository.isReadOnly()` 为 true 时，本页只显示只读原因与恢复入口；普通备份/还原/导入隐藏。
   - 恢复仅两种：`restoreLegacy(最近 .legacy)` 或 `resetToDefaultsAfterCorruption()`；均二次确认，失败不改原文件，`.corrupt` 原文保留。
6. **未改动用户配置**：验收期间只在 JVM 构建/单测层运行，未安装 APK、未读写设备上的 `config.json`。

## 3. 危险动作确认（停用全部）

- 默认折叠：主界面只有一行「停用全部对外能力」。
- 展开后列出四项影响：模型重定向、8771 能力桥、8770 动作面、两侧局域网；说明令牌保留、可逆。
- 点击按钮再弹 `XiaoiConfirmDialog(danger=true)`，确认后才执行。
- 执行：读取当前配置 → 四项置 false → `ConfigRepository.save` → `AgentService.startIfEnabled` → 重新 `load()` 核对四项确为 false；结果卡片显示「已执行并核对」或「未完成」，并提供「到诊断页核对 8771/8770」。
- 配置只读、未初始化或已全部停用时直接拒绝/提示，不写文件。

## 4. 诊断策略（不撒谎）

- D1–D4 统一走 `IssueInputFactory.ConfigFacts` + `StateModel`，D2 只取 `StatusCache` 指纹匹配的探针结论；D4 只有 `IN_USE` 显示「已确认」。
- 8771/8770 分别读 `BridgeStatus` 与 `AgentRuntimeStatus`：未知端口显示「未知/等待上报」，不猜 loopback，不从 8770 推断 8771。
- 权限用 `PermissionProbe.capture`；`UNKNOWN` 显示「未确认」，不当成拒绝。无标准 API 的机器不制造假问题。
- Root 只查常见 `su` 路径，显示「已安装（未验证授权）/未检测到/未确认」，不显示「已授权」。
- LSPosed 只以控制面连接为证据：已连接 / 未确认，不判「未启用」。
- 「复制诊断信息」只包含版本、设备、Android、schema/rev、模型 ID、端点 host、密钥/令牌是否设置、D1–D4、端口、权限、包名；不含 API Key、令牌、完整 URL 或 query。
- 「导出日志」沿用 `BridgeControlService.logDump()` 的系统分享；「清空」只清 App 内存镜像。

## 5. 验收结果

| 命令 | 结果 |
| --- | --- |
| `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug --offline` | BUILD SUCCESSFUL（无新增警告） |
| `./tests/run.sh` | 全部通过（NavStack 49/49，其余用例全绿） |
| `./scripts/check-consistency.sh` | 通过（7/7） |

按主 Agent 要求**未** `adb install`、**未**真机截图；设备验收由合并后统一执行。

### 自查清单

- [x] 备份含密钥必须确认
- [x] 还原失败不破坏现有配置（解析/校验/原子写）
- [x] 脱敏副本不可还原（`***` 被拒绝）
- [x] 配置损坏只读 + 恢复入口，不覆盖
- [x] 主题三档即时生效且不写 config
- [x] 停用全部默认折叠 + 二次确认 + 执行后重新读取验证
- [x] Root/LSPosed/权限不伪造
- [x] 兼容模式无保存/导入
- [x] 日志错误竖条与单行省略
- [x] 仅改本目录；无硬编码色值、无 Toast、无 `new Thread`

## 6. 已知风险 / 边界

1. **Root 检测是启发式**：只看常见 `su` 路径；隐藏 Root/KernelSU 非标准路径可能漏报，页面文案已说明「未验证授权」。
2. **LSPosed 只有控制面证据**：小爱未运行或控制面未绑定时显示「未确认」，这是刻意的保守结论。
3. **权限探针受 ROM 影响**：全屏通知、使用情况访问在部分机型可能始终 UNKNOWN；不制造 issue。
4. **完整备份是明文**：文件由用户选择保存位置，可能被同步/转发；页面只在确认框与完成提示中反复警告。
5. **脱敏文件仅用于排障**：分享会写入 `externalFiles/exports`，内容已脱敏，但仍可能被转发。
6. **停机后端口状态滞后**：`ConfigRepository.save` 后 8771/8770 的实际断开由控制面/服务异步更新；页面用配置文件核对四项开关，并引导到诊断页核对端口。
7. **旧版页面仍在**：`ConfigActivity`/`HomeActivity` 只作为 0.14 过渡入口，0.15 删除。
8. **未做真机视觉验收**：字体 1.3、深浅色、权限设置跳转需合并后在设备上确认。
