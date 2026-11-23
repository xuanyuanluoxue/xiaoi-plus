# 小爱逆向子项目（reverse/）

本目录是仓库内的**独立子项目**：只做小爱（`com.miui.voiceassist`）的静态与动态分析，
不改产品代码。产品侧的 hook 实现仍在 `app/`、`platform/`；这里负责「先搞清楚它是什么」。

> 大文件（APK、dex、dexdump 输出、解包的 assets）不入库，统一放在
> `artifacts/reverse/<版本>/`（已在 `.gitignore`）。本目录只入库存文本、脚本、
> 生成的数据目录与结论。

## 目录

```
reverse/
├── README.md                  本文件：目标、约定、快速开始
├── docs/
│   ├── 00-method.md           方法论：证据分级、定位策略、产出规范
│   ├── 01-architecture.md     进程模型、代码地图、组件、控制面
│   ├── 02-model-pipeline.md   模型链路：配置对象、四个客户端、请求体、tools 参数
│   ├── 03-agent-runtime.md    Agent 运行时：facade / CLI / 意图分发
│   ├── 04-toolchain.md        工具链：481 个工具、20 个常驻、两级检索
│   ├── 05-skills.md           Agent 定义（20 个）与 skill（SKILL.md）
│   ├── 06-memory.md           记忆：ClawMemory、加密库密钥链、云端同步 API
│   ├── 07-obfuscation.md      混淆漂移与重定位配方
│   ├── 08-internal-api.md     **内部 API**：18 个 App 工具 + 5 个导出 AIDL + MCP
│   └── findings/              单点深挖（一个类/一个协议一篇）
├── data/
│   └── tool-catalog.md        481 个工具 + 18 个 App 工具 + Agent 清单（生成物）
└── scripts/
    ├── env.sh                 路径与设备常量
    ├── pull-apk.sh            从设备拉运行中的 APK
    ├── extract-dex.sh         只解 dex（快）
    ├── dexindex.py            dex → SQLite 索引（推荐，秒级查询）
    ├── dump-dex.sh            dexdump 全量文本（慢，备用）
    ├── scan.py                在 dump 文本里按结构扫类/方法/字符串
    ├── extract-assets.sh      解出 assets（工具目录/Agent/提示词/MCP 配置）
    ├── catalog.py             assets → Markdown 工具目录
    ├── runtime-logs.sh        从 8771 取合并日志与调用量
    └── report.sh              一键生成当前版本的证据快照
```

## 快速开始

```bash
# 0) 环境：WSL + Android SDK（dexdump/aapt2）+ 已 root 的设备
source reverse/scripts/env.sh

# 1) 拉当前运行版本（设备上跑的那份，不是出厂包）
bash reverse/scripts/pull-apk.sh

# 2) 建索引（32 个 dex / 341 MB，约 2 分钟；之后查询是毫秒级）
bash reverse/scripts/extract-dex.sh
python3 reverse/scripts/dexindex.py build

# 3) 查结构
python3 reverse/scripts/dexindex.py stats
python3 reverse/scripts/dexindex.py pkgs Lcom/aios/osbot/ 4      # 包分布
python3 reverse/scripts/dexindex.py methods --contains buildOpenAIRequest
python3 reverse/scripts/dexindex.py cls Lcom/aios/osbot/tools/Tool;
python3 reverse/scripts/dexindex.py strings --grep tool_calls

# 4) 解 assets 并生成工具目录
bash reverse/scripts/extract-assets.sh
python3 reverse/scripts/catalog.py --version 8.2.70.8421 --out reverse/data/tool-catalog.md

# 5) 运行时取证（走 8771，不碰设备文件）
bash reverse/scripts/runtime-logs.sh --grep 工具采样
```

## 两条常用查询以外的姿势

| 想找 | 命令 |
|---|---|
| 某包里有哪些类 | `dexindex.py pkgs Lcom/aios/osbot/tools/ 5` |
| 某个类的全部方法/字段 | `dexindex.py cls <descriptor>` |
| 方法名模糊匹配 | `dexindex.py methods --contains <子串>` |
| 字段类型匹配（如谁持有 Json 单例） | `dexindex.py fields --type kotlinx/serialization/json/Json` |
| dex 里的字符串 | `dexindex.py strings --grep <子串>` |
| 某个接口的 AIDL 形状 | `dexindex.py cls Lcom/aios/apptoolsdk/aidl/IExternalAgentService;` |

## 证据分级（每篇文档都要标）

| 级别 | 含义 | 写法 |
|---|---|---|
| **实测** | 我们在自己设备上跑出来的（日志/文件/协议返回） | 附原始输出片段 |
| **静态** | 从 APK 的 dex/manifest/resources 读出来的 | 附类名+方法签名 |
| **推测** | 由前两者推断，尚未验证 | 明确标「推测」，写验证方法 |

没有证据的结论不写。宁可留 `TODO: 待验证`，也不把猜测写成事实。

## 边界

- 不碰加密记忆库（`claw_memory*.db` 等），只做只读文件分析。
- 不导出用户隐私内容（记忆正文、对话正文）到文档或日志。
- 不提供「重打包小爱」的分发方式；本子项目只服务于自有设备上的模块开发。
- 服务端行为（云端技能、模型路由）不可见，只能记录「客户端侧观察 + 推测」。

## 与产品的关系

```
reverse/  ──结论──▶  docs/（产品文档）
          ──结论──▶  app/.../Hook.java（hook 点）
          ──结论──▶  application/.../PromptInjector（请求改写）
          ──结论──▶  AgentMcp / Mcp（暴露给外部 Agent 的工具）
```

发现新能力时，**优先做成 MCP 工具**：本项目的定位就是让外部 Agent 调用小爱的工具链，
而不是让小爱自己变强。

## 当前最重要的三条结论

1. 小爱有 **481 个工具**、**20 个 Agent 定义**、**MCP 客户端**，全部是文件驱动的
   （`assets/agents/`、`assets/mcp/`），可 `reload_*` 热更新。
2. 它对外导出了 **5 个 AIDL 服务**（`ExternalAgentService` / `CliCommandService` …），
   权限是 signature|privileged —— 普通 App 拿不到，但我们的 hook 跑在它进程里。
3. 我们之前「反射摸黑改请求体」的做法，可能被 **`profile.json` + `update_custom_prompt`
   + `reload_prompt`** 这条官方路径取代，见 `docs/08-internal-api.md`。
