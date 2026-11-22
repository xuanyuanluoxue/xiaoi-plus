# 路线图：从逆向结论到下一版功能

> 汇总日期：2026-11-22　｜　状态：**规划**（未实现）
> 来源：四份专项规划 —— [PLAN-MCP-SURFACE](PLAN-MCP-SURFACE.md)、[PLAN-PERSONA-SKILLS](PLAN-PERSONA-SKILLS.md)、
> [PLAN-MEMORY](PLAN-MEMORY.md)、[PLAN-INTEROP](PLAN-INTEROP.md)。
> 本文只做三件事：**去重、排期、列出需要拍板的分叉**。细节看各专项文档。

## 1. 一页结论

**下一条版本线只做三件小事，全部围绕「先证明通道可用」，不做功能堆叠。**

| 序 | 事情 | 为什么是它 | 来源 |
|---|---|---|---|
| 1 | **AppTool 探针**：`xiaoai_status` 增加 `apptools=ok methods=N/18`，并只读调用一次 `nlpQuery` | 18 个进程内方法是记忆、NLU、屏幕三条线的共同前提；拿不到实例就要整线回退 | MCP F1/F2、MEMORY M01、INTEROP I4a |
| 2 | **官方记忆只读**：`xiaoai_memory op=search` 转调 `readMemory` | 用户感知最强、只读、失败不伤主流程 | MEMORY M01/M03、MCP F3 |
| 3 | **LAN 写拦截**：LAN 来源一律拒绝写类 op | 现状存在「局域网 + 写开关」可远端 append 的缺口，属安全修复而非新功能 | MEMORY M13 |

其余方向（人格包、技能包、双向接入、apptoolsdk）都保留，但**都要等这三点出结论**再排。

## 2. 四份规划怎么合并

同一件事在多份文档里以不同编号出现，按「一个底层能力只做一次」合并：

| 底层能力 | 出现在 | 合并后归属 | 备注 |
|---|---|---|---|
| 进程内调 `UIAgentServiceForOSBot` | MCP F1/F2/F3/F4/F6、MEMORY M01/M02、INTEROP I4a/I4b | **先建 `XiaoiAppToolClient` + `xiaoai_status` 探针**，再按工具逐个接 | 一次探针同时服务三条线 |
| 官方记忆读写 | MEMORY M01/M02/M03、MCP F3 | MEMORY M01→M03→M02；MCP 侧只加 `op=search` | 契约 v2 由 MEMORY 主导 |
| 8771 反向注册（小爱调我们） | INTEROP I0/I1/I2/I3/I11 | 单独一条线，**不与工具面同期** | 会引入新攻击面，需先有入站画像 |
| 人格/提示词 | PERSONA F1/F10/F11 | F11 诊断 → F10 文本前缀 → F1 人格包 | 客户端注入已证伪，不再投 |
| 工具权限收窄 | PERSONA F5/F6、MEMORY M13、INTEROP I2 | 统一成「来源 × 画像 × 拦截」三层 | 见 §5 |
| 技能 | PERSONA F3/F4 | 依赖 V5（个人 skill 目录位置） | 目录未知前不开工 |

## 3. 统一排期

### P0（0.18.0，建议 1 个迭代）

| # | 事项 | 来源编号 | 成本 | 验收（真机可判） |
|---|---|---|---|---|
| P0-1 | AppTool 探针 + `xiaoai_nlp` | MCP F1、F2 | S+M | `xiaoai_status` 出现 `apptools=ok methods=18/18`；`xiaoai_nlp("明天八点叫我")` 返回含 `alarm` 的 JSON |
| P0-2 | 官方记忆只读 + 契约 v2 | MEMORY M01、M03、（MCP F3） | S+S | `xiaoai_memory op=search&q=…` 命中 ≥1 条；`MEMORY.md` mtime 不变 |
| P0-3 | LAN 写拦截 | MEMORY M13 | M | LAN + token 调 `append` 回 `DENIED_LAN`；本机调用不受影响 |
| P0-4 | 审计补 `caller` 字段 | INTEROP I11（最小版） | S | `xiaoai_audit` 能按 `caller=local|lan` 过滤 |

> P0 门禁：默认行为不变；所有新能力 opt-in；`tools.md` 与实现逐字一致。

### P1（0.19.0）

| # | 事项 | 来源编号 |
|---|---|---|
| P1-1 | 官方记忆写入（自然语言，含「忘掉」拒绝） | MEMORY M02 |
| P1-2 | 记忆页双视图 + 隐私加固（负向断言测试） | MEMORY M04、M05 |
| P1-3 | `xiaoai_catalog`（工具/Agent/技能目录，纯 assets） | MCP F5 |
| P1-4 | 人格诊断 → 文本前缀 → 人格包最小版 | PERSONA F11、F10、F1/F2 |
| P1-5 | 子 Agent 工具权限（先验 V4：config.json 是否启动被覆盖） | PERSONA F5/F6 |
| P1-6 | `xiaoai_screen`（默认关 + 审计） | MCP F4 |

### P2（按需 / 观察）

| # | 事项 | 来源编号 | 触发条件 |
|---|---|---|---|
| P2-1 | 8771 反向注册成小爱的 MCP（含双 token 画像） | INTEROP I0/I1/I2 | 你确认要「对小爱说一句就能调我们的工具」 |
| P2-2 | ExternalAgentService 流式会话 + CLI 直连 | INTEROP I5/I6 | 需要流式文本/思考/工具事件 |
| P2-3 | 技能包导入与管理 | PERSONA F3/F4 | V5 找到个人 skill 目录 |
| P2-4 | apptoolsdk 探针（让小爱把我们当原生工具） | INTEROP I7 | 愿意花一轮验证发现机制 |
| P2-5 | 记忆镜像/导出/恢复 | MEMORY M07/M09/M11 | 有换机或备份诉求 |
| P2-6 | 工具层硬拦截 | PERSONA F9 | 先定位工具分发入口（纯本地 dex） |

```
P0-1 ──┬── P0-2 ── P1-1 ── P1-2
       ├── P1-3
       ├── P1-6
       └── P2-1（需 P0-4 的 caller 审计）
P1-4 ── P1-5 ── P2-3
P0-3 独立（安全修复）
```

## 4. 需要你拍板的五个分叉

1. **人格这条线收到什么程度？**
   现实是客户端改不到 `xiaoai_submit` 的回复。建议收缩为：①只影响「我们自己发起的调用」（前缀）；②文件层尽力而为（`custom_prompt.md`，待 V1 验证）；③不再宣传「改小爱人格」。
   → 你要的是「改小爱的人格」，还是「让外部 Agent 用我的设定说话」？两者实现完全不同。

2. **8771 要不要变成双向枢纽（让小爱调我们）？**
   收益是「对小爱说一句就能用本项目的能力」，代价是多一个入站面（需要双 token + 画像 + 确认）。建议放到 P2，且必须先有 P0-4 审计。

3. **记忆要不要全面转官方？**
   官方是云端真相、会改写文本、且不保证原文留存。转过去意味着放弃「本地可读的确定副本」作为主路径。
   → 建议：**读官方、写官方、本地只读快照**；但需要你接受「写进去可能被改写」。

4. **LAN 暴露要不要保留？**
   目前 `lan=true` + 强 token 才绑 `0.0.0.0`，边车场景有用；但一旦加写类 op 就有远端写风险。建议保留只读、拒绝写（P0-3）。

5. **0.18.0 的版本号与范围**
   建议 0.18.0 = 上面 P0 四项（探针 + 记忆只读 + LAN 写拦截 + caller 审计），不夹带人格线。是否接受这个「小而实」的版本？

## 5. 统一的三层安全模型（合并三份文档的建议）

| 层 | 位置 | 做什么 | 来源 |
|---|---|---|---|
| 来源 | `Bridge.handle` → `Mcp` | 标记 `loopback / lan / inbound-xiaoi`，写类 op 只允许 loopback | MEMORY M13、INTEROP I2 |
| 画像 | 工具表 + token 槽 | 入站 token 只映射 readonly 子集，排除 `submit/ask/cli/config/switch_model/confirm` | INTEROP I2/I3 |
| 拦截 | 工具分发 | 按「工具名 + 参数」拒绝危险调用（默认只拦 `cli/cloud_sandbox/write_file`） | PERSONA F9 |

## 6. 统一的验证顺序（不占手机）

1. **本地**：`dexindex.py cls Lcom/xiaomi/voiceassistant/UIAgentServiceForOSBot;` 看构造与字段，确认实例怎么拿；
2. **本地**：读 `Bridge.handle` → `Mcp.handle`，确认 `source` 能否干净透传（P0-3 前置）；
3. **真机（短）**：只跑探针 + `nlpQuery` 一次，看 `apptools=` 与返回 JSON 字段名；
4. **真机（短）**：`read_memory` 一次，记录键名与条数（不记正文）；
5. **真机（短）**：LAN 写拦截一次（另一台机器或本机带 LAN 标记调用）。

> 需要「对小爱说一句话」的验证（V1/V3）统一攒到一次会话里做，不反复占屏。

## 7. 各文档的「不做」清单不重复列

四份文档各自都有明确的不做项，合并后的红线只有四条：

- 不重打包/改签名小爱；
- 不解析加密记忆库、不提供删除；
- 不把 481 个工具做成通用直通（`adb_bash`/短信/联系人一律不暴露）；
- 不再投入「客户端改 system 提示词」（已六次证伪）。
