# skill 目录

这里放**可移植的实体 skill 文件**，不使用软链接。其他 Agent 克隆本仓库后，
直接读对应目录的 `SKILL.md` 即可。

```text
skill/
└── xiaoi-mcp/
    ├── SKILL.md              # 如何连接并调用手机上的小爱
    └── references/
        └── tools.md          # MCP 工具 schema、示例、错误码
```

## 当前 skill

### xiaoi-mcp

用途：让外部 Agent 通过 xiaoi-plus 的 MCP 能力桥调用手机小爱。

- 端点：`http://127.0.0.1:8771/mcp`（`adb forward`）或 `http://<手机IP>:8771/mcp`
- 鉴权：`Authorization: Bearer <token>`
- 核心工具：`xiaoai_submit`、`xiaoai_status`、`xiaoai_config`、
  `xiaoai_switch_model`、`xiaoai_logs`、`xiaoai_stats`、`xiaoai_island`
- 安全边界：远程只能改 `model` / `marker`；`/config` 固定 410

入口：`skill/xiaoi-mcp/SKILL.md`

## 与 `.opencode/skills` 的关系

- `skill/`：给其他 Agent / 人看的可移植副本，实体文件。
- `.opencode/skills/`：OpenCode 自动发现的位置，当前放 `xiaoi-release` 发版 skill
  和 `xiaoi-mcp` 的实体副本。

两边都是实体目录，没有软链接。新增 skill 时按用途放置：

- 需要被其他项目/Agent 直接读取：放 `skill/<name>/`
- 需要当前仓库的 OpenCode 自动加载：放 `.opencode/skills/<name>/`

如果一个 skill 两边都要用，复制实体文件并在修改后同步；不要用软链接。
