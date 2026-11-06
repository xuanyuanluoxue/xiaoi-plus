# Phase 0 侦察计划（临时）

> 状态：**已完成**（报告：`docs/agent/PHASE0-REPORT.md`）
> 关联：`docs/agent/PLAN.md`（D1 Agent 能力层）
> 日期：2026-11-06
> 目标：用最小风险回答 Phase 1 开始前必须知道的事实，不改运行时代码、不写小爱记忆。

## 1. 范围

| 编号 | 任务 | 产出 |
|---|---|---|
| P0-1 | 枚举小爱 CLI 方法 | method 列表、参数、是否设备动作、是否依赖账号/LLM |
| P0-2 | 超级岛通知能力 | 调用方式、文案限制、锁屏/频率、失败表现 |
| P0-3 | 权限矩阵 | 5 个动作在无特权 / Shizuku / root 下的可用性 |
| P0-4 | 小爱记忆只读可行性 | MEMORY.md 结构、DB 是否可读/加密、读取成本 |
| P0-5 | Phase 1 接口清单 | ActionDescriptor、工具、错误码、CLI 参数 |

## 2. 安全约束

- 只读；不修改 `/data/data/com.miui.voiceassist` 下任何文件。
- 不调用 `agent.submit` 执行真实任务；只调用只读/探活方法。
- 不打印 token、apiKey、记忆正文；报告只写结构和数量。
- 设备操作前先备份当前 `config.json`（已有 `pre-wp8` 备份）。
- 临时文件写 `~/xiaoi-plus-dev/tmp/agent-phase0/`，含敏感内容 `chmod 600`。
- 如果设备断开/重启，记录状态后继续，不强行改系统设置。

## 3. P0-1 CLI 方法枚举

步骤：

1. 确认设备与小爱版本。
2. 拉取 APK（root），用 `unzip`/`strings`/`jadx`（若可用）搜索：
   - `executeCommand`
   - `session.open`、`agent.submit`、`auth.status`
   - `app.`、`alarm.`、`device.`、`memory.`
3. 通过 `/cli` 只调用安全方法（如有 `help`/`auth.status`）。
4. 记录：method、参数、返回、是否直接动作、是否依赖 LLM/账号。

验收：能判断“路线 B（只复用动作层）是否可行”。

## 4. P0-2 超级岛

步骤：

1. 读 `Island.java` / `/island` 实现，记录文案限制、频率、返回。
2. `adb forward` 后调用一次 `/island`，观察响应与实际显示。
3. 检查锁屏/后台/小爱未运行时的表现（不强制改系统设置）。
4. 记录：是否只能提醒、是否可交互、失败降级。

验收：确认 `xiaoai_notify` 的 island 通道可用，且不能作为批准输入。

## 5. P0-3 权限矩阵

对 5 个动作逐项标注：

| 动作 | 无特权 | Shizuku | root |
|---|---|---|---|
| app.open | | | |
| device.info | | | |
| alarm.set | | | |
| notification.send | | | |
| media.control | | | |

验证：

- `su -c id` 是否可用。
- `cmd media_session`、`settings get`、`input`、`am start` 在 shell/root 下的表现。
- 通知权限、后台启动限制、锁屏状态的已知限制。
- Shizuku 服务是否在跑；应用侧是否已有 API 依赖（当前应无）。

验收：能力表能明确显示“不可用/需要权限”，而不是运行时静默失败。

## 6. P0-4 小爱记忆只读

步骤：

1. 列目录/文件大小（已完成一轮，复核）。
2. 复制 `MEMORY.md`、`claw_memory.db`、`claw_memory_index.db` 到临时目录（root，只读）。
3. 用 `file`/`sqlite3`/Python 判断 DB 是否明文、表结构。
4. 解析 `MEMORY.md` 分节与条目格式；不输出正文。
5. 记录：读取是否需要 root、DB 是否加密、是否有向量索引依赖。

验收：明确 `xiaoai_memory_read` 是否值得做；写入仍留到 Phase 5。

## 7. P0-5 Phase 1 接口清单

产出：

- `ActionDescriptor` 字段定稿。
- `xiaoai_action` / `xiaoai_capabilities` / `xiaoai_notify` / `xiaoai_confirm`
  的参数与返回。
- 错误码映射。
- 5 个动作的输入/输出 schema。
- App 前台服务启动/停止/开机自启的接口清单。
- 需要新增的权限与 manifest 项。

验收：可以直接拆成 Phase 1 的开发任务。

## 8. 执行记录

已完成，结果见 [`PHASE0-REPORT.md`](PHASE0-REPORT.md)。关键结论：

- 小爱 CLI 没有本地设备动作层，5 个动作必须走 Android/root。
- `agent.submit` 是连接态会话，当前 `xiaoai_submit` 不返回 sessionId。
- 超级岛可提醒，`actions` 为空，不能批准。
- 小爱记忆 DB 是加密/自定义格式，`MEMORY.md` 明文可读（root）。
- Shizuku 覆盖 shell 命令；记忆读写只能 root。
