# 05 技能（skill）与 Agent 定义

> 证据级别：静态（assets/agents/）+ 运行时字符串｜版本：8.2.70.8421

## 1. 两套东西，别混

小爱里「能力扩展」有两种完全不同的机制：

| 机制 | 形态 | 谁用它 | 加载方式 |
|---|---|---|---|
| **Agent**（子智能体） | `assets/agents/<id>/config.json` + `prompt.md` | 主 Agent 通过 `agent` / `start_agent` 调度 | 启动时注册，`reload_agents` 可热重载 |
| **skill**（技能知识库） | `SKILL.md`（YAML frontmatter + Markdown） | 会话中通过 `skill` 工具按需加载 | 索引化，`reload_skills` 可重扫 |

Agent 是「一个专职助手」，skill 是「一份操作手册」。二者的共同点：
都是**文件驱动的**，都能在不升级 APK 的情况下增删。

## 2. Agent 清单（20 个）

`assets/agents/*/config.json` 的公共字段：

```json
{
  "id": "...", "name": "...", "description": "...",
  "execution_mode": "main | sub",
  "prompt_file": "prompt.md",
  "max_agent_depth": 3, "max_iterations": 30,
  "tools_allowlist": ["*"], "tools_denylist": ["update_custom_prompt"],
  "preload_tools": ["skill", "web_search", ...],
  "tags": [], "summary": "...", "search_hint": "...", "rejects": "...", "scope": []
}
```

| id | 名称 | 模式 | 深度 | 轮次 | 备注 |
|---|---|---|---|---|---|
| `osbot.main` | 超级小爱 | main | 3 | 30 | `allow=*`，仅 deny `update_custom_prompt` |
| `osbot.overlayassistant` | 悬浮窗助手 | main | 3 | 30 | `allow=*` |
| `osbot.chat` | 好友消息助手 | sub | 1 | 5 | preload `im_chat`、`personaldata_search` |
| `osbot.group_chat` | 群聊助手 | sub | 1 | 8 | 自带 `tool_selection_rules.md` |
| `osbot.taiyi` | 太乙 | sub | 1 | 4 | 白名单：日历、个人数据、记忆、便签 |
| `com.android.camera` | 相机 | sub | — | 15 | 自带 `skills/smart-camera/SKILL.md` |
| `com.xiaomi.lyrabridge-watch` | 手表协同 | sub | 3 | 20 | 白名单含 `weather2__*`、应用商店 |
| `com.miui.notes-*`（8 个） | 便签系列 | sub | 1–3 | 1–30 | 编辑/搜索/分类/标题/导入/语音创作 |
| `com.xiaomi.type*`（5 个） | 输入法/写作 | sub | 1 | 1 | 纠错、改写、回复、翻译、通用 |

要点：

- `execution_mode=main` 的只有两个（主对话、悬浮窗），其余都是 `sub`。
- `max_agent_depth` 限制嵌套：主 Agent 3 层，子 Agent 多为 1 层（不能再派）。
- **`tools_allowlist` 是能力边界**：`notes-fast_pipeline` 这类 `max_iterations=1`
  的 Agent 实际是「一次性改写」，不给工具。
- `rejects` 字段是负向提示（「不要用它」的场景），会参与 Agent 检索打分。

### 2.1 `profile.json`（人格来源）

`osbot.main/profile.json`：

```json
{
  "display_name": "超级小爱",
  "avatar": "avatar.png",
  "signature": "你的全生态AI助手",
  "personality": "真诚可信赖，有温度不冰冷；开放积极有活力…",
  "expertise": [ ... ]
}
```

这份 profile 与 `prompt.md` 一起构成主 Agent 的人格。**这是我们 P2「改人格」最该看的地方**：
如果 profile 能改，就不必去动 30KB 的 system 请求体。

## 3. skill 的格式

唯一的本地样例 `assets/agents/com.android.camera/skills/smart-camera/SKILL.md`：

```markdown
---
name:  smart-camera
description: 理解用户的语音指令，实现拍照、录像、场景设置等功能
tags: [摄影, 拍照, 拍照功能]
version: 9
---

# smart-camera

## 0. 工具说明
| 工具 | 用途 |
| `csv_query` | 查询参数数据表 … |

### 通过 call_tool 调用
| `camera__get_frame` | 获取当前取景框预览帧 |
```

这与 Anthropic Agent Skills 的 `SKILL.md`（YAML frontmatter + Markdown）**格式相同**。
配套 `data/` 目录放数据文件（`camera_params.csv`），由 `csv_query` 读取。

另有三份顶层技能文档：

```
assets/skills/skill-creator.md    教模型怎么造新 skill
assets/skills/system-knowledge.md 系统知识
assets/skills/xiaomi-home.md      米家
```

## 4. 运行时怎么用 skill

| 工具 | 参数 | 行为 |
|---|---|---|
| `skill` | `action=load / list / search / create / update` | 技能知识库的读写入口 |
| `tool_search` | `action=search` | 同时检索**扩展工具 + 技能知识库** |
| `reload_skills` | — | 重扫所有技能目录并刷新索引 |
| `reload_agents` | — | 重载 `agents/` 下的 Agent 定义 |
| `reload_prompt` | — | 把主 Agent 的 prompt 从文件系统重载 |

推论（**推测**，待验证）：skill 的索引与工具索引共用 `ToolHintsLoader` 的语料机制，
所以 skill 也是「先检索、再 load 全文」，而不是一次性全塞进 system。

## 5. A2A：跨 Agent/跨设备的技能协议

`com.aios.osbot.a2a`（151 个类）：

| 包 | 类 | 作用 |
|---|---|---|
| `a2a/model` | `A2aAgentSkill`、`RemoteSkillDetail` | 技能描述（输入/输出/参数） |
| `a2a/discovery` | `A2aSkillDetailCache`、`discovery.a`（38 方法） | 发现与缓存 |
| `a2a/index` | `A2aAgentIndex`、`A2aAgentIndexManager`、`A2aIndexEntry`、`A2aIndexSource`、`A2aIndexRender` | 索引、来源、渲染 |

配套 Manifest 组件：

```
com.aios.osbot.interconnect.transport.continuity.A2AStaticTopicService
com.aios.osbot.interconnect.rpc.VerifyCodeCopyService
```

含义：小爱支持「从远端 Agent 拉技能、建索引、渲染进对话」。这与
`send_message(to="agent://agent_id")`、`device_list` / `send_task_to_device`
（跨设备派发）是同一套体系。

## 6. 已知未知

| 项目 | 状态 | 验证方法 |
|---|---|---|
| 个人 skill 放哪个目录（非 assets） | 未知 | 找 `skill` 工具的路径常量；查 `/data/data/.../files/skills` |
| `skill-creator.md` 是否允许模型自建 skill | 推测允许 | 读该文件 + 试 `skill action=create` |
| A2A 技能是否真能跨设备拉取 | 未知 | 两台设备同账号，看日志 |
| `profile.json` 是否可在运行时被改写 | 未知 | 试 `update_custom_prompt` 前后对比文件 |
