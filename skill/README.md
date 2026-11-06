# skill 目录

这里放**可移植的实体 skill 文件**，不使用软链接。其他 Agent 克隆本仓库后，
直接读对应目录的 `SKILL.md` 即可。

```text
skill/
├── xiaoi-agent/            # 8770 App 动作面（推荐给 PC Agent）
│   ├── SKILL.md
│   └── references/actions.md
└── xiaoi-mcp/              # 8771 小爱 MCP（旧版/对话代理）
    ├── SKILL.md
    └── references/tools.md
```

## 当前 skill

### xiaoi-agent（推荐）

用途：让外部 Agent 通过 `8770` 调用手机能力。

- 端点：`http://127.0.0.1:8770/mcp`（先 `adb forward tcp:8770 tcp:8770`）
- 能力：`device.info`、`app.open`、`alarm.set`、`notification.send`、`media.control`
- Agent 工具：`xiaoai_capabilities`、`xiaoai_action`、`xiaoai_confirm`、
  `xiaoai_audit`、`xiaoai_notify`、`xiaoai_ask`
- 确认：`risk=confirm` 需要 60 秒一次性 token
- 幂等：`idempotencyKey` 24 小时
- 超级岛：只提醒，不能批准危险动作

入口：`skill/xiaoi-agent/SKILL.md`

### xiaoi-mcp

用途：`8771` 小爱进程内的 MCP，适合直接让小爱执行一句话任务、读配置/日志/调用量。

- 端点：`http://127.0.0.1:8771/mcp`（`adb forward`）或局域网地址
- 核心工具：`xiaoai_submit`、`xiaoai_status`、`xiaoai_config`、
  `xiaoai_switch_model`、`xiaoai_logs`、`xiaoai_stats`、`xiaoai_island`
- 安全边界：远程只能改 `model` / `marker`；`/config` 固定 410

入口：`skill/xiaoi-mcp/SKILL.md`

## 与 `.opencode/skills` 的关系

- `skill/`：给其他 Agent / 人看的可移植副本，实体文件。
- `.opencode/skills/`：OpenCode 自动发现的位置，当前放 `xiaoi-release`、`xiaoi-mcp`、
  `xiaoi-agent` 的实体副本。

两边都是实体目录，没有软链接。新增 skill 时按用途放置：

- 需要被其他项目/Agent 直接读取：放 `skill/<name>/`
- 需要当前仓库的 OpenCode 自动加载：放 `.opencode/skills/<name>/`

如果一个 skill 两边都要用，复制实体文件并在修改后同步；不要用软链接。
