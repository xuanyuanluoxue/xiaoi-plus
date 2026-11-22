# 人格 / 提示词 / 技能 / 多 Agent 产品线规划

> 状态：**规划**（未实现，未改代码）。基线：小爱 8.2.70.8421 / 模块 0.17.0-dev。
> 证据级别逐条标注：**【已验证】/【推测】/【已证伪】**。
> 输入：`reverse/docs/02-model-pipeline.md`、`04-toolchain.md`、`05-skills.md`、`08-internal-api.md`、
> `docs/agent/P2-PROMPT-MEMORY.md`、`CAPABILITIES.md`、`tmp/plan-p2-injection.md`（本轮实测，一次性任务书）。
> 关联：CAPABILITIES 的 A8/A9/A11/A13、P2 §8.4 未做项。

## 1. 一句话结论

**不要再跟「那份 30KB 的 system」较劲——它不在我们手里。** 在 `xiaoai_submit` 路径上，
客户端侧改提示词已被证伪；真正能落地的是另外三条边界：**输入文本边界**（我们发出的那句话）、
**工具面边界**（20 个 Agent 的 allow/deny + 工具执行拦截）、**文件面边界**
（`custom_prompt.md` / `SKILL.md` / `agents/*/config.json`）。人格包与技能包都要按
「文件层 + 文本层」双落地设计，不能只押文件层。

### 1.1 三条能走的边界

| 边界 | 机制 | 状态 | 依据 |
|---|---|---|---|
| 输入文本 | `xiaoai_submit` / `xiaoai_ask` 的 `text` 就是进模型的那句话，发送前可拼约束/人格前缀 | **【推测-强】** 机制清楚，一轮可验 | 改模型名、改请求体都不影响回复，说明 `text → 云端 → 回复` 这条链是通的，且入口在我们代码里 |
| 工具面 | 20 个 `config.json` 的 `tools_allowlist/denylist/preload_tools`；常驻 20 工具 + 481 个 `call_tool` 按需 | **【已验证】** 格式与 `reload_agents` 热载存在 | `reverse/docs/05-skills.md` §2、`04-toolchain.md` |
| 文件面 | `files/agents/osbot.main/custom_prompt.md`、`skills/*/SKILL.md`、`agents/*/config.json` | **【推测】** 文件格式已验证、写入已实现；**是否被回复采纳未验证** | `platform/PromptFiles.java` 已能写 + `invalidateMemoryCache`；`xiaoai_submit` 侧尚未看到效果 |

### 1.2 已证伪（不要再投入）

| 实验 | 结果 | 结论 |
|---|---|---|
| 改 `build*Request` 返回值（重建 JsonObject） | 重建成功、system 变 116 字符，回复不变 | **【已证伪】** 返回值不是最终发送对象 |
| 改 `streamCompletion` 的 `messages` | 该处 `MessageRole` 只有 USER/ASSISTANT/TOOL，无 system | **【已证伪】** 无 system 可改 |
| hook `sm.a#getSystemPrompt()` | 原始 29870 → 116，请求体里确实是 116，回复不变 | **【已证伪】** 请求体不是回复来源 |
| 原位改请求体 backing `Map` | 日志 `role=system contentLen=116`，回复不变 | **【已证伪】** 同上 |
| 模型名改成 `bogus-model-does-not-exist` | 回复依旧正常 | **【已证伪】** `xiaoai_submit` 的回复不来自被重定向的 `qm.i` |
| 改 `files/agents/osbot.main/soul.md` + 重启 | 启动被 APK assets 覆盖，回复不变 | **【已验证】** 该文件不可持久化 |

### 1.3 两个容易搞混的区分

- `soul.md` / `prompt.md` 启动时被 assets 覆盖 **【已验证】**；`custom_prompt.md` 不在
  `assets/agents/osbot.main/` 里，**推测不会被覆盖**【推测】，但需一次重启实验定论（见 V2）。
- `update_custom_prompt` 被 `osbot.main` 的 `tools_denylist` 禁用 **【已验证】**——那只限制
  **模型调用工具**。我们直接写文件并失效缓存，不经过工具，**已能写入成功**【已验证】，
  但「写入后回复是否变化」是另一个问题【未验证】。

## 2. 功能点表

| 编号 | 名称 | 用户故事 | 依赖（文件/工具/类） | 实现要点 | 价值 | 成本 | 风险 | 验收标准 |
|---|---|---|---|---|---|---|---|---|
| **F1** | 人格包一键切换 | 我想让小爱换一套性格和说法，一键切，随时恢复默认 | `platform/PromptFiles.java`（已有 `sync`/`invalidateCache`）、`custom_prompt.md`、`Config.prompt*` | 人格包 = 一个 `.md`（frontmatter：`name`/`description`/`append\|replace`）；写入走 `PromptFiles.sync`，保留他人内容；切换前快照；关闭时传空 block 精确摘除我们的标记块 | 4 | S | 中：可能整体不生效；`replace` 会压掉原生能力描述 | 切换后 UI 与 submit 各问「你是谁」；关闭后文件只剩他人内容且回复复原 |
| **F2** | 人格包库 / 导入导出 / 回滚 | 我攒了几套人格（工作/闲聊/角色），能导入导出、能回滚 | F1、`files/xiaoi-plus/personas/`、Config 新增 persona 段 | 导入做编码/大小/必填校验；快照保留 N=5；回滚=把快照写回再 sync；导出为单个 `.md` | 3 | S/M | 低 | 非法文件被拒且现有文件字节不变；回滚后内容与快照一致 |
| **F3** | 技能包导入（SKILL.md） | 我把一份 `SKILL.md` 丢进去，小爱就按这本手册办事 | **个人 skill 目录（未知）**、`skill` 工具、`reload_skills` | 先用 list 差异法定位目录；导入=复制目录（`SKILL.md` + `data/`）；校验 frontmatter `name`/`description`；调 `reload_skills` | 4 | M | 中：目录未知、索引不刷新、与原生技能重名 | 导入后 `skill action=list` 出现该名；提问能触发 `skill action=load` |
| **F4** | 技能包管理（列表/启停/删除） | 能看见装了哪些技能，能临时关掉 | F3、Bridge/UI | 启停=目录改名加 `.disabled`（不删）；删除进回收站目录；每次操作后 `reload_skills` | 3 | M | 低 | 停用后 list 不出现，启用后恢复；删除可从回收站还原 |
| **F5** | 子 Agent 工具权限（按 Agent 粒度） | 我不想让「便签 Agent」能联网/写文件 | `files/agents/<id>/config.json`、`reload_agents`、20 个 Agent 清单 | 读现有 json → 只改 `tools_allowlist/denylist/preload_tools`，**保留未知字段**；写前备份；提供「恢复出厂」（从 assets 重新解出） | 4 | M | 中高：启动可能被 assets 覆盖（与 soul.md 同机制）；改错会让 Agent 失能 | 改 deny 后问一件需要该工具的事，日志显示工具未被调用/被拒 |
| **F6** | 主 Agent 工具收窄 | 我不想它自己跑 shell / 写文件 | F5 | 预置 3 档模板（宽松/标准/严格），**不给自由编辑 JSON**；严格档 deny `cli`、`cloud_sandbox`、`write_file`、`remote_file`、`mcp` | 5 | M | 高：可能让原生流程报错 | 严格档下「用 cli 执行 xxx」被拒；普通对话/闹钟/天气不受影响 |
| **F7** | 多 Agent 编排（外部派活） | 我让电脑 Agent 直接把活派给「便签/相机 Agent」 | CLI `agent.submit`（**是否支持指定 agentId 未知**）、`agent`/`start_agent`、A2A `agent://` | 先探 schema 是否支持 agent 参数；不支持则降级为「在 text 里要求主 Agent 转交」（不可靠，需标注）；MCP 新增 `xiaoai_agent_submit(agentId,text)` | 3 | M | 中：黑盒、路由不可控 | 指定 agentId 时日志出现对应 Agent 名；不指定时行为不变 |
| **F8** | 提示词版本管理 | 改坏了能回滚，能看到改了什么 | F1、本地版本目录 | 每次保存生成快照（时间 + 摘要 + 内容 hash），保留 20 份；行级文本 diff；回滚=写快照再 sync | 3 | S | 低 | 连改 3 次能列出 3 版并回滚到第 1 版 |
| **F9** | 工具执行层拦截（硬兜底） | 不管云端怎么说，危险工具调到我这儿一律拒 | **工具执行入口（ToolRegistry / `call_tool`，未定位）**、Hook | hook 工具分发，按「名 + 参数」黑/白名单拒绝并返回结构化错误；**只 deny 不改写**；默认只拦 `cli`/`write_file`/`cloud_sandbox` | 4 | L | 高：入口漂移、可能卡住正常调用 | 单测覆盖分发；真机：名单内调用返回拒绝，名单外不受影响 |
| **F10** | 输入文本边界：任务模板 / 约束前缀 | 我让电脑 Agent 干活时，每次自动带「别废话」「只输出清单」 | `xiaoai_submit` / `xiaoai_ask`（已实现）、`Bridge`、`Mcp` | 新增 profile（prefix/suffix/仅首轮）；**只作用于我们自己发起的调用**，不碰 UI/语音；支持 `{{date}}`；上限 2 KB；默认关 | 4 | S | 低：长前缀挤占上下文；用户可能误以为是人格 | 同题开/关前缀风格可区分；关掉后回复与现状一致 |
| **F11** | 人格生效诊断（persona doctor） | 我想知道「我改的人格到底生效没有」，而不是瞎试 | `xiaoai_logs`、`请求结构` 日志、`xiaoai_prompt` stats、`PromptFiles.describe` | 一键跑：写强标记 → submit 问 → 查回复是否含标记 → 再 UI 问 → 对照；输出三分支（文件层有效 / 仅文本层有效 / 都无效）；**不改任何东西** | 4 | S | 低 | 能把当前版本判成三分支之一，两次复现结论一致 |
| **F12** | 用户侧提醒型约束 | 它要做危险动作时，我手机上先看到提醒 | `xiaoai_notify`（已实现）、F9 的事件 | F9 拦截时发一条超级岛/通知，不阻断；纯提示 | 2 | S | 低 | 拦截同时岛上出现提示（≤22 字） |

## 3. 优先级与依赖

```
P0  F11 ──┬─ F1 ── F2(最小：快照/回滚/禁用)
          └─ F10
P1  F5 ── F6        F3 ── F4        F8          (F8 依赖 F1)
P2  F7        F9(依赖入口定位) ── F12
```

**P0（先做，1–2 个迭代）**

1. **F11 诊断**：成本最低、信息量最大。它决定 F1 走文件层还是退化成 F10 的前缀——
   不做 F11 就做 F1，等于把已证伪的思路换个包装再赌一次。
2. **F10 文本边界**：唯一「机制上确定能进模型上下文」的通道，且只影响我们自己的调用，
   风险最低。同时是 F1 的兜底落地方式。
3. **F1 + F2 最小版**：人格包文件 + 切换 + 关闭 + 回滚。F1 的验收标准里**必须**同时包含
   「UI 路径」和「submit 路径」两条，避免再次出现「对象改成功、回复没变」的自嗨。

**P1**：F5 → F6（安全收益最大，但依赖「启动不被覆盖」的结论 V4）；F3 → F4（技能包一次做完
导入+管理才有用，只做导出让用户没法收场）；F8（纯本地，可随时插入）。

**P2**：F7（黑盒程度最高，先把 CLI 能力探清）；F9（唯一真正的硬约束，但成本 L 且入口未定位，
**入口定位只在本地 dex 做，不占手机**）；F12（依赖 F9 的事件，独立价值低）。

## 4. 明确不做

| 不做 | 理由 |
|---|---|
| 继续死磕请求体 / system 注入（`build*Request`、`streamCompletion`、`sm.a.getSystemPrompt`、inplace backing Map） | 六条路径全部【已证伪】，包括「改模型名仍正常回复」这条反证；再投一次只是验证同一个否定 |
| 用改模型名 / 换端点来「试探链路」当作产品功能 | 该链路根本不提供回复，探针价值已耗尽 |
| 改 `soul.md` / `prompt.md` 做人格 | 启动被 assets 覆盖【已验证】，只能当"临时实验"，不能当功能 |
| 重打包 APK、改 `assets/` | 违反「不修改不重打包小爱」原则，破坏升级与完整性（CAPABILITIES A26） |
| 输出前缀 / 响应流后处理伪造人格 | A23 已因「多进程重复注入 + 模型模仿历史」禁用；这是假人格，且会污染上下文 |
| 解密记忆 DB（`claw_memory*.db`）、记忆云同步 | 格式未知，真相在云端，写了会被覆盖（P2 §7 既有结论） |
| 代小爱确认授权（A18）、A2A 跨设备拉技能 | 前者是用户对小爱的授权，后者黑盒且责任边界不清 |
| 用无障碍（B26/B27）「注入人格文本」 | 权限过大、脆弱、不可审计；CAPABILITIES 已列为不做 |
| 面向所有小爱版本的通用实现 | 只承诺白名单版本；人格链路版本差异比模型 hook 更大，不做兼容层 |

## 5. 待验证清单

全部可在**不占用手机（不用 adb、不长连）**的前提下完成：手机侧只需要「对小爱说一句话」的
几秒交互，其余靠 8771 MCP（`xiaoai_submit` / `xiaoai_logs` / `xiaoai_prompt` / `xiaoai_cli`）
与设置 App 的日志页读取。每条验证完把结论回写 §1 与 `reverse/docs/02-model-pipeline.md` §7。

| 编号 | 问题 | 方法 | 判据 |
|---|---|---|---|
| **V1** | `custom_prompt.md` 写的内容会不会被回复采纳 | 写一句强指令「每次回答第一句必须是『人格包已生效』」，分别用 UI 说一句、用 `xiaoai_submit` 问一句 | 首句出现即采纳；一边生效一边不生效 → 说明两条链路不同源，**必须分开验收** |
| **V2** | `custom_prompt.md` 是否在启动时被覆盖 | 写入 → 结束小爱进程 → 重新打开 → `xiaoai_prompt` / `PromptFiles.describe` 看文件长度与内容 | 长度/内容保留=可持久；被还原=与 `soul.md` 同病，F1 只能走文本层 |
| **V3** | UI / 语音路径是否也被云端接管 | UI 发一句开放问题，同时看日志是否出现 `模型调用: 三方 qm.i`，以及 `请求结构 role=system contentLen=?` | system 长度=我们的注入值且回复体现人格 → UI 路径可用（P2 只需在 UI 验收）；否则两条路都不可用 |
| **V4** | `agents/*/config.json` 是否启动被 assets 覆盖 | 改一个 Agent 的 denylist → 重启小爱 → 看 `reload_agents` 日志与实际工具是否被拒 | 权限仍生效=F5 可做；被还原=必须 hook Agent 加载器，F5 成本升到 L |
| **V5** | 个人 skill 目录在哪 | 往候选目录（`files/skills`、`files/agents/<id>/skills`）各放一个测试 `SKILL.md` → `reload_skills` → `skill action=list` | list 里出现=目录命中；F3 才可开工 |
| **V6** | CLI `agent.submit` 是否支持指定 agentId | `xiaoai_cli` 传带 `agentId` 的 `agent.submit`，比对错误码；再看 `schema.commands` | 接受=F7 低成本；拒绝=F7 降级为「text 里转交」并明确标注不可靠 |
| **V7** | 工具执行入口是否可稳定 hook | **本地**反编译搜索 `ToolRegistry` / `call_tool` 的分发方法（不碰手机） | 找到单一入口且签名稳定=F9 可评估；找不到=F9 不做 |
| **V8** | 文本前缀是否被当成「用户说的话」而削弱约束 | F10 实现前，手工用 `xiaoai_submit` 发「[系统] 你现在只输出 JSON」+ 正常问题，重复 3 次 | 3 次都遵守=前缀有效；偶发不遵守=需要 profile 里加「首次会话」策略 |

> 建议顺序：**V1 + V3 一起做**（一次交互同时回答两个问题）；V2 紧随；V4/V5 在 P1 开工前做；
> V7 随时可做（纯本地）。任何一条的结论若与 §1 冲突，**以实测为准并立刻改本文档**。
