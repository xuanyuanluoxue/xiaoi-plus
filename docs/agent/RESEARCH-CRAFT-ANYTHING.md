# 调研：Craft Anything 对本项目是否有用

> 日期：2026-11-22　｜　性质：**只读调研**（未改代码、未装软件、未连设备）
> 证据标记：**[官方]** README/文档/源码原文　**[实测]** 本次真实请求到的数据（GitHub API/raw）　**[推测]** 未验证判断

## 1. 一句话结论

**部分可借鉴**：它不是 Agent 框架，而是一个纯 Rust 的「逆向研究 + 重做」引擎；对我们的**协议层细节**和**工具表设计**有 3 处可以直接抄的成熟做法（版本协商、`annotations`/effects 派生、工具名归一），对 `reverse/` 子项目可能是一把更快的 APK 分析工具；但**不能复用其代码**（Rust + 只有 stdio 传输），也**不能替代**我们的设备动作与技能体系。

## 2. 它是什么

### 2.1 候选与判定

| 候选 | 是什么 | 判断 |
|---|---|---|
| **[storytold/craft-anything](https://github.com/storytold/craft-anything)** ★40,744 | ArtCraft 的开源「看清任意软件怎么工作，再让 AI 重做一个」引擎；纯 Rust，桌面 App + CLI + MCP server | **选定** |
| [Fanqi89/dsh-craft-anything](https://github.com/Fanqi89/dsh-craft-anything) ★421 | 第三方 DSH 插件：把 `craft-anything-cli mcp` 桥成 `mcp__craft__*` 工具 | 上游的接入层，佐证上者是 CLI+MCP 形态 |
| [47thtechcorner/RayCodes_CraftAnything](https://github.com/47thtechcorner/RayCodes_CraftAnything) ★10 | 名字相近的营销页式仓库（"Clone ANY App UI"） | 同名噪声，**不要**当上游 |
| [PrimitiveAnything](https://github.com/PrimitiveAnything/PrimitiveAnything) ★397 | SIGGRAPH 2025 的 3D 基元生成论文 | 无关（只是名字像） |

**判定依据**：① 名字逐字匹配且是唯一 40k★ 级项目；② 它的 Android/APK 逆向能力正好落在本仓库 `reverse/` 的射程内；③ 你本机栈里已有 DSH/OpenCode，而上者官方支持把 opencode 等 ACP Agent 当 builder，并已有 DSH 桥插件。

### 2.2 关键事实

| 项 | 值 | 来源 |
|---|---|---|
| 仓库 / 站点 | github.com/storytold/craft-anything · getartcraft.com | [官方] README |
| Star / Fork / 提交 | 40,744 / 8,093 / 1,629 commits | [实测] GitHub API |
| 语言 / License | Rust · **MIT OR Apache-2.0**（与我们的 GPL-3.0 双向兼容） | [实测] API + [官方] README |
| 活跃度 | 建仓 2026-10-31，最近 push 2026-11-22；v0.3.0→v0.6.1 在 10 天内发了 5 个版 | [实测] API / Releases |
| 规模 | README 称 320 工具；`docs/tools.md` 记为 **305 个引擎工具 / 17 个工具集** | [官方] 两处口径不一，以生成物为准 |
| 形态 | 桌面 App(egui) + CLI + **stdio MCP server**，三者同一引擎 | [官方] README、`crates/mcp/src/lib.rs` |
| 状态 | 作者自标 **early alpha**；Android 静态 9 项 verified / 4 未验证，adb 24 项中仅 **2 项 verified**（"no device attached"） | [官方] `docs/tool-status.md` |

## 3. 与本项目 5 个痛点的对照

可用性：**直接复用** / **借鉴思路** / **不适用**。成本 S ≤1 人日、M 2~4 人日、L ≥1 周。

| 痛点 | 它有什么（原文位置） | 可用性 | 成本 | 风险 |
|---|---|---|---|---|
| **① MCP 传输/协议层**（我们手写 JSON-RPC over HTTP，2025-03-26） | 只有 **stdio 的 NDJSON**，无 HTTP/SSE/会话（`crates/mcp/src/lib.rs` 首行注释）。但其协议细节成熟：`SUPPORTED_VERSIONS=["2025-06-18","2025-03-26","2024-11-05"]`，客户端要未知版本就回自己的最新（`craft-libs/crates/mcp-server/src/server.rs`）；`Limits{integer_ids_only, allow_null_ids, max_batch_items}`；`initialize.instructions` 写死使用顺序；`notifications/tools/list_changed` + `enable_toolset` 渐进暴露；单条响应字节预算（默认 8MB、Claude Code 降到 80KB），超限不是报错而是**退回摘要 + 指向 `evidence://<id>`** 分页 | 借鉴思路（Rust crate 不可用；stdio 也换不掉我们的 URL+Header） | S（版本降级 + `title`/`annotations`）+ M（预算/摘要分页，可选） | 低：改动集中在 `application/Mcp`；但工具顺序被单测逐字断言，需同步改测试 |
| **② 工具 schema 生成与校验**（我们手写 schema、手写 unknown-argument 检查） | 单一 `ToolSpec{id,title,summary,beginner,toolset,effects,requires,input_schema,run}`（`crates/engine/src/tool.rs`）→ 同时生成 CLI 命令、MCP `tools/list`、Agent 工具、UI 面板、以及 `docs/tools.md`（`cargo xtask tools`，文档不能漂移）。`mcp_descriptor()` 把 `Effects` 位图**派生**成 `annotations.readOnlyHint/destructiveHint/idempotentHint/openWorldHint`；`check_arguments()` 统一校验 required/未知键/类型/枚举（含 `anyOf` 多类型，注释点出"Gemini 拒收类型数组"这个真实坑） | 借鉴思路（Java 侧可照抄表 + 派生关系，不拷代码） | M（17 个工具重排成表，含测试与 `initialize.instructions`） | 低-中：`annotations` 小爱是否认要真机验证；纯重构期建议单独一个 PR |
| **③ Agent 编排**（我们目前只有"外部 Agent → 小爱"单向） | **不是**多 Agent 框架：一个 builder（Claude Code / 任意 ACP Agent，含 opencode）+ 固定状态机 Study→Build→Check→Ready + 证据账本 + inbox 人工审批（`ask_consent`：`consent_required` → 30 分钟审批 → `always` 记 `tool:<id>` 到项目）。另有 `Caller{Ui,Cli,Mcp,Agent}` 与 `CRAFT_ANYTHING_AGENT` 环境变量，防止被自己启动的 Agent 拿到"本人"权限 | 借鉴思路 | S（Effects→consent 派生、caller 分层） | 低：直接对上 ROADMAP P0-4 的 `caller` 与 I11 |
| **④ 技能体系**（小爱用 SKILL.md，我们找不到个人技能目录） | **没有 SKILL.md**：文档目录无 skills 篇，`docs/extending.md` 的扩展单元只有 ToolSpec / TargetKind / Check / stacks·toolchains.toml / 插件（MCP server、WASM 组件）。等价物是 **MCP prompts**：6 个引导 prompt + 共享 `DISCIPLINE` 规则块（`crates/mcp/src/prompts.rs`） | 不适用（互通不了）；但"用 MCP prompts 承载纪律/人格"可作为 PERSONA 线的替代落点 | S | 低，但**小爱取不取我们的 prompts 未验证** |
| **⑤ 设备动作**（app.open/alarm.set/media.control 走进程内 Binder） | 走 **adb**，24 个工具（`adb_app_launch/adb_app_force_stop/adb_screenshot_take/adb_setting_get`…）。命令命名规则 `<area>_<object>_<action>`（`docs/tool-ids.md`），`MUTATES_DEVICE` 位自动进 `destructiveHint` 并要求 consent | 不适用（架构不同，进程内比 adb 更强、更安全）；命名与位图可借 | S | 中：其 adb 工具 23/24 未验证，无参考价值 |

**额外发现（超出 5 点，但对 `reverse/` 直接相关）**：它有 14 个 **Android 静态**工具（`android_package_show` / `classes_find` / `class_show` / `method_show` / `class_users_find` / `resources_read` / `components_list`），用**自带纯 Rust 解析器**读 APK/二进制 XML/ARSC/DEX/签名块，**不需要 JADX、不需要 root**（`crates/engine/src/tools/android_tools.rs` 头注释 + `craft-anything-formats`）；JADX/Apktool/JEB 只是可选增强，且路径未验证。这正好覆盖 `reverse/scripts/dexindex.py` + `dexdump` 的活。

## 4. 建议

1. **不投入**：不 fork、不作为运行时依赖、不把 8771 改成 stdio、不引入 WASM/插件体系、不引多 Agent 框架（它本来也没有）。
2. **A（S，只动 `reverse/`，可先做）**：装官方 Windows portable CLI，用 `android-package-show` / `android-classes-find` 对**当前那版混淆 APK**做一次对拍（类数、方法命中、耗时、内存 vs `dexindex.py`）。结论只写进 `reverse/`，不进产品；无收益就停。
3. **B（S/M，进 0.18.0 之前）**：把三件小事搬进 `application/Mcp` —— ①协议版本协商：请求版本不认识就回自己的最新并接受降级（现只硬认 `2025-03-26`，这正是 I0 的验收点）；②`tools/list` 每条加 `title` + 由声明效果派生的 `annotations`；③工具名归一（`mcp__<server>__<name>` / kebab / snake 视为同一工具）——直接消掉 I1 里 `tool_prefix` 造成 `xiaoi-plus__xiaoai_*` 双前缀的隐患。
4. **C（M，0.19.0）**：工具表 + 效果位 + 派生 consent 取代现在手写的 `risk=confirm|danger`，`xiaoai_audit` 记 `caller`（合并 ROADMAP P0-4 与 I11）。
5. **观察，不排期**：响应字节预算 + 摘要优先 + `evidence://` 式引用（我们的"证据"是日志/截图，收益不明）；`enable_toolset`（17 个工具太少，用不上）。
6. **明确不做**：MCP prompts 替代人格线放到 PERSONA 诊断（F11）之后；它们的 `docs/pitfalls.md`（102KB 的"错一次记一条规则"）看着很香，但那是长跑团队的产物，我们现在照抄成本高于收益。

## 5. 不确定项（没查到 / 没验证，不猜）

- **小爱作为 MCP 客户端**是否识别 `annotations` / `title` / `structuredContent` / `_meta.provenance` / `prompts`：全部未验证；`_meta.provenance.from_target`（它的"结果是数据不是指令"约定）与小爱侧"个人 MCP 结果不可信"规则是否同一语义，未对账。
- **它的 Android 静态工具在我们这份 32 dex / 341MB 的 APK 上的表现**：没跑过；大 dex 的耗时与内存未知。
- **adb 工具是否支持无线 adb / root 域 / 多设备**：未验证（官方自评 23/24 项 Unverified）。
- **技能体系**：文档与扩展指南里没发现；未对全仓库源码做全文搜索，故只能写"未见"，不写"确定没有"。
- **40.7k★ / 3 周的增长来源**：未查证是否有推广或刷星，只记录 API 事实。
- 同名噪声仓库（RayCodes）内容未评估，不推荐任何人下载。
