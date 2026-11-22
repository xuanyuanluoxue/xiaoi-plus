# 小爱逆向子项目（reverse/）

本目录是仓库内的**独立子项目**：只做小爱（`com.miui.voiceassist`）的静态与动态分析，
不改产品代码。产品侧的 hook 实现仍在 `app/`、`platform/`；这里负责「先搞清楚它是什么」。

> 大文件（APK、dex、dexdump 输出）不入库，统一放在 `artifacts/reverse/<版本>/`
> （已在 `.gitignore`）。本目录只入库存文本、脚本与结论。

## 目录

```
reverse/
├── README.md            本文件：目标、约定、快速开始
├── docs/
│   ├── 00-method.md     方法论：证据分级、定位策略、产出规范
│   ├── 01-architecture.md  进程与组件、控制面、我们模块的落点
│   ├── 02-model-pipeline.md 模型链路：配置对象、四个客户端、请求体
│   ├── 03-agent-runtime.md  Agent 侧：facade / CLI / 意图分发
│   ├── 04-toolchain.md  工具链与函数调用（持续剖析）
│   ├── 05-skills.md     技能（skill）清单与触发方式（持续剖析）
│   ├── 06-memory.md     记忆系统：文件布局、分节、写入协议
│   ├── 07-obfuscation.md 混淆漂移与重定位配方
│   └── findings/        单点深挖（一个类/一个协议一篇）
└── scripts/
    ├── env.sh           路径与设备常量
    ├── pull-apk.sh      从设备拉运行中的 APK
    ├── dump-dex.sh      批量 dexdump（带缓存）
    ├── scan.py          按结构特征扫类/方法/字符串
    ├── runtime-logs.sh  从 8771 取合并日志与注入统计
    └── report.sh        一键生成当前版本的证据快照
```

## 快速开始

```bash
# 0) 环境：WSL + Android SDK（dexdump/aapt2）+ 已 root 的设备
source reverse/scripts/env.sh

# 1) 拉当前运行版本（设备上跑的那份，不是出厂包）
bash reverse/scripts/pull-apk.sh

# 2) 解 dex 并 dump（已 dump 过的会跳过）
bash reverse/scripts/dump-dex.sh

# 3) 按特征扫（例：找所有含 buildOpenAIRequest 的方法）
python3 reverse/scripts/scan.py classes --contains buildOpenAIRequest
# 找所有持有 kotlinx Json 单例的类
python3 reverse/scripts/scan.py classes --field-type kotlinx/serialization/json/Json
# 找字符串
python3 reverse/scripts/scan.py strings --grep 系统提示

# 4) 运行时取证（走 8771，不碰设备文件）
bash reverse/scripts/runtime-logs.sh --grep 提示词注入
```

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
reverse/  ──结论──▶  docs/compatibility.md（版本矩阵）
          ──结论──▶  app/.../Hook.java（hook 点）
          ──结论──▶  application/.../PromptInjector（请求改写）
          ──结论──▶  Mcp.java（暴露给外部 Agent 的工具）
```

发现新能力时，**优先做成 MCP 工具**：本项目的定位就是让外部 Agent 调用小爱的工具链，
而不是让小爱自己变强。
