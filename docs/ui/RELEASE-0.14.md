# 0.14.0 版本说明（Compose + Miuix UI）

> 日期：2026-11-08
> 目标：`0.14.0` / versionCode 19（A8 定版）
> 状态：**P0/P1/P2 完成；P3（R8/CI/真机）由 A8 进行中**
> 详细验收：`docs/ui/reports/`、`~/xiaoi-plus-dev/xiaoi-plus-ui/tests/2026-11-08/`

## 1. 本版内容

设置 App 从「4 个 Activity + XML」迁到**单 Activity + Compose + Miuix**：

- 4 个底部 tab：首页 / 小爱 / 接入 / 我的。
- 19 条路由；一级页显示底栏，二级/三级页隐藏底栏并显示返回箭头。
- 颜色/字号/尺寸走 `XiaoiTheme`；组件优先 Miuix，自绘只在 Miuix 缺失时登记例外。
- 深浅色三档（跟随系统/浅色/深色），主题只存 App prefs，不写 `config.json`。
- 功能与 0.13 parity：配置、四维状态、8771/8770、令牌/LAN、备份/还原、日志、
  诊断、兼容模式行为不变。

0.14 **不新增**提示词、工具注入、技能包、记忆、语音与场景路由；这些页面只有
只读“规划中”占位，没有开关或执行入口。

## 2. 页面清单

| tab | 路由 | 说明 |
| --- | --- | --- |
| 首页 | `Home` | 四维 Hero、待处理（≤3）、8771/8770 双域入口、运行概况、最近活动；只读 |
| 小爱 | `Xiaoai` | 主开关即时保存；当前模型；D3/D4 运行态；5 个增强能力占位 |
| 小爱 | `Model` | 服务商/模型列表、端点/密钥/模型 ID 表单；显式保存/放弃；冲突/只读 |
| 接入 | `Access` | 8771 控制面 + 8770 动作面分开；令牌强度/LAN；6 个入口 |
| 接入 | `Bridge` | 8771/8770 只读详情；没有重启按钮 |
| 接入 | `Token` | 令牌掩码/生成/复制；8771/8770 LAN；显式保存 |
| 接入 | `Connect` | 整页连接信息（不是 BottomSheet）：URL、adb forward、JSON、Skill |
| 接入 | `Capabilities` | 67 条能力；搜索 + 域/状态筛选；只读 |
| 接入 | `CapabilityDetail` | 单条能力详情；未知编号空态 |
| 接入 | `Tools` | 5 个内置动作 + 8771 8 个工具 + 8770 6 个工具 |
| 接入 | `Permissions` | 通知/精确闹钟/全屏/电池/usage/通知监听；去设置 |
| 我的 | `Me` | 本机信息、设置入口、危险区（折叠） |
| 我的 | `Appearance` | 跟随系统/浅色/深色；即时生效 |
| 我的 | `ConfigManage` | 完整备份、还原、脱敏导出/分享、运行时导入、损坏恢复 |
| 我的 | `Diagnostics` | D1~D4、8771/8770、配置完整性、6 项权限、复制诊断、兼容模式入口 |
| 我的 | `Logs` | 级别筛选、跟随、清空、分享、三种空态 |
| 我的 | `About` | 版本/作者/GitHub/许可证/致谢/检查更新/免责 |
| 我的 | `Licenses` | GPL-3.0、Apache-2.0（Miuix）、Kotlin/Compose/AndroidX |
| 我的 | `Legacy` | 只读兼容模式：路径/状态/脱敏 JSON/`.corrupt`/`.legacy` |

## 3. 验收结果

### 功能

| 轮次 | 结果 | 来源 |
| --- | --- | --- |
| P1.1 严格 Miuix | 41 PASS / 0 FAIL / 0 BLOCKED | `~/xiaoi-plus-dev/xiaoi-plus-ui/tests/2026-11-08/acceptance-p1.md` |
| P2 全页面 | 65 PASS / 0 FAIL / 2 BLOCKED | `.../acceptance-p2.md` |

2 个 BLOCKED 都是“不改用户配置”约束下不可实测，已给静态证据：

1. 弱令牌回退仅本机（需把强令牌换成短 token）。
2. 配置损坏只读（需写入损坏 JSON）。
   发布前建议用备份配置在测试机补测。

### 视觉

- P2 审核：0 P0、5 P1、13 P2；5 条 P1 与低成本 P2 已修。
- 修后对比度：输入/搜索 **5.01:1**；success/warning/error 徽章
  **6.08/6.85/6.54:1**；Hero D1/D2 **4.81/10.83:1**；次按钮浅色 **7.42:1**、
  深色 **8.32:1**。
- 浅色 12 页、深色 8 页、字号 1.3 四页已截图；手机端 PNG 新增 0。
- 详见 [reports/P2-VISUAL-FIXES.md](reports/P2-VISUAL-FIXES.md)。

### 构建/门禁

| 项 | 结果 |
| --- | --- |
| `./gradlew :app:assembleRelease --offline` | 成功（P2 验收时 versionCode 18、R8 关闭） |
| `./tests/run.sh` | 全部通过（NavStack 49/49） |
| `./scripts/check-consistency.sh` | 7/7 通过 |
| `./scripts/verify-apk.sh` | PASS；包名 `plus.xiaoi.module`；固定指纹 `d7dd34b2…d19b` |
| APK 体积 | 8.3 MiB（R8 关闭）；R8 后体积/≤6MB 以 A8 RELEASE-REPORT 为准 |

> 设备当前断开，LSPosed 注入、模型重定向、8770/8771 真机回归与 R8 体积
> 不在本文件下结论。

## 4. 已知限制

1. **P3 未收尾**：R8/shrink、CI、发布脚本的 0.14 适配由 A8 进行中；
   在 A8 报告确认前，不承诺 APK ≤6MB，也不声称真机矩阵全部通过。
2. **日志没有域筛选**：0.14 只有“全部/信息/错误”等级别 chip；域筛选留 0.15。
3. **Connect 是二级整页**：不是 BottomSheet；文档已按实际更新。
4. **能力/工具/权限只读**：审计、确认队列、试运行/测试台 0.15 提供。
5. **8770 无重启按钮**：Bridge 只读；保存配置触发重绑。
6. **增强能力是占位**：提示词、工具注入、技能包、记忆、语音与路由没有开关或执行。
7. **旧页面仍在**：`HomeActivity`/`ConfigActivity` 作为 0.14 过渡入口保留，0.15 删除；
   兼容模式只读，不提供第二套可编辑入口。
8. **两处 BLOCKED**：弱令牌回退、损坏只读仅静态证据。
9. 能力清单进详情再返回会重置筛选/搜索（导航壳重建页面），属体验问题，不阻断。
10. 外观页下半屏留白、底栏 Home 实心其余线性等剩余视觉项见
    [PLAN.md §15.3](PLAN.md#153-剩余-p2-视觉项0-14-不做转-015后续)。

## 5. 截图索引

目录：`~/xiaoi-plus-dev/xiaoi-plus-ui/tmp/2026-11-08/`

### 修后（P3，优先引用）

| 主题 | 文件 |
| --- | --- |
| 浅色 | `p3-xiaoai-light.png`、`p3-model-light.png`、`p3-access-light.png`、
`p3-token-light.png`、`p3-connect-light.png`、`p3-capabilities-light.png`、
`p3-tools-light.png`、`p3-permissions-light.png`、`p3-diagnostics-light.png`、
`p3-logs-light.png`、`p3-appearance-light.png`、`p3-about-light.png` |
| 深色 | `p3-xiaoai-dark.png`、`p3-model-dark.png`、`p3-access-dark.png`、
`p3-token-dark.png`、`p3-logs-dark.png`、`p3-appearance-dark.png`、
`p3-tools-dark.png`、`p3-permissions-dark.png` |
| 字号 1.3 | `p3-model-light-130.png`、`p3-permissions-light-130.png`、
`p3-tools-light-130.png`、`p3-configmanage-light-130.png` |
| 一致性 | `p3-access-consistency3.png`、`p3-diagnostics-consistency3.png` |

### 修前 / 对照

- `p2b-*.png`：P2 视觉审核原图（28 页）。
- `p2-capabilities-fixed.png`：能力页 chip 修复中间态。
- `a4-home-light.png`、`a6-access-light2.png`：A4/A6 交付截图。

### 说明

- 截图只经 `adb exec-out` 写 PC，手机端未保存 PNG。
- 含 token/key 的配置不进入截图目录；截图里的令牌均已掩码。
- 人工确认过的图可归档到 `~/xiaoi-plus-dev/xiaoi-plus-ui/review/2026-11-08/`。

## 6. 升级 / 兼容

```bash
adb install -r xiaoi-plus-0.14.0.apk
```

- 同包名（`plus.xiaoi.module`）、同签名可覆盖安装；`versionCode 18 → 19`。
- `config.json` 仍为 v3；服务商/模型/密钥/Token/LAN 保留；首次打开 App 确认配置迁移结果。
- 主题、导出/分享、诊断、8771/8770 行为与 0.13 一致；`POST /config` 仍返回 410。
- 旧入口：`HomeActivity`/`ConfigActivity` 过渡保留，0.15 删除；新 UI 以 `MainActivity` 为准。
- 降级：降回 0.13 前先做完整备份并关闭 MCP/LAN；旧版本可能读不到新 UI 的部分状态。

## 7. 相关文档

- [PLAN.md](PLAN.md)：计划与状态、剩余 P2 列表
- [SCREENS.md](SCREENS.md)：19 条路由与页面区块
- [COMPONENTS.md](COMPONENTS.md)：组件契约、`FilterChips`、8770 状态
- [BUILD.md](BUILD.md)：JDK 21 + Gradle 9.9 构建、门禁、截图
- [../design-system.md](../design-system.md)：令牌与对比度
- [../../CHANGELOG.md](../../CHANGELOG.md)：0.14.0 条目
