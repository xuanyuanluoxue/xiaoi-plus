# P2 视觉修复报告（5 条 P1 + 低成本 P2）

> 日期：2026-11-08
> 分支：`feat/ui-p2-fixes`（基于 `main@c03aa40`）
> 工作区：`~/xiaoi-plus-dev/worktrees/ui-p2-fixes`
> 设备：`31106e85`（1280×2772 @520dpi）
> 基准：`visual-review-p2.md`、`docs/design-system.md` §9、`docs/ui/COMPONENTS.md`、
> `docs/ui/P1-REPORT.md` §9
> 截图目录：`~/xiaoi-plus-dev/xiaoi-plus-ui/tmp/2026-11-08/`
> 修前截图：`p2b-*` / `p2-*`；修后截图：`p3-*`

## 结论

**5 条 P1 全部完成；低成本 P2 全部落地。** 构建/单测/一致性/APK 签名门禁全绿。
浅色全量 12 页、深色 8 页、字号 1.3 四页已真机截图；手机未新增 PNG。

- 修前：输入/搜索占位 **2.06:1**，warning/success/error 徽章 **3.84–4.47:1**；
  Hero D1/D2 标签 **3.0/3.91:1**；次级按钮浅色下白底白卡不可见。
- 修后：占位 **5.01:1**，三色徽章 **6.08/6.85/6.54:1**，Hero D1/D2 标签
  **4.81/10.83:1**，次级按钮 **7.42:1**（深色 **8.32:1**）。

---

## 1. P1-1 对比度批量修

### 根因

1. `XiaoiTextField` / `SearchField` 没有传 `TextFieldColors`，Miuix 默认标签
   `#A9A9A9` 落在 `#F0F0F0` 输入底上只有 **2.06:1**。
2. 浅色 `success/warning/error` 同时充当徽章文字；14% 淡底上分别只有
   **4.13 / 3.84 / 4.47:1**。
3. `DimensionBadge` 标签用状态色；UNKNOWN 用 `textSecondary`，在自身 14% 淡底
   上只有 **3.80:1**；D1/D2 实测 **3.0/3.91:1**。

### 改法

- `ui/components/TextField.kt`、`SearchField.kt`：显式
  `TextFieldDefaults.textFieldColors(labelColor = colors.textSecondary)`。
- `ui/theme/XiaoiTheme.kt`（只改浅色）：`success #1B5E43`、`warning #7A4A00`、
  `error #9E2B2B`，`errorBg` 同步为 `#1A9E2B2B`。深色令牌不动。
- `ui/components/DimensionBadge.kt`：UNKNOWN 标签改用 `heroOn`；
  其余状态色使用调深后的令牌。
- `docs/design-system.md` §9.1 重写实测表；§9.2 的 chip 例外改为 `FilterChips`。

### 验证（Python，PNG 取样 + WCAG 2.1）

| 组合 | 修前 | 修后 | 证据 |
| --- | --- | --- | --- |
| 输入 label / 占位 | 2.06:1 | **5.01:1** | `p3-model-light.png`、`p3-capabilities-light.png` |
| success 徽章 | 4.13:1 | **6.08:1** | `p3-permissions-light.png`、`p3-diagnostics-light.png` |
| warning 徽章 | 3.84:1 | **6.85:1** | `p3-access-light.png`、`p3-diagnostics-light.png` |
| error 徽章 | 4.42:1 | **6.54:1**（截图；令牌计算 6.43:1） | `p3-permissions-light.png` |
| Hero D1 标签 | 3.00:1 | **4.81:1** | `p3-xiaoai-light.png`（配置/已暂停） |
| Hero D2 未知标签 | 3.91:1 | **10.83:1** | `p3-xiaoai-light.png`（端点/未确认） |
| 次级按钮文字 | 白底白卡 | **7.42:1** | `p3-token-light.png` |
| 深色 success 徽章 | 4.13:1 | **6.71:1** | `p3-access-dark.png` |
| 深色 warning 徽章 | — | **9.02:1** | `p3-access-dark.png` |
| 深色次级按钮 | — | **8.32:1** | `p3-token-dark.png` |

> 取样方法：uiautomator bounds + PIL 取文字核心像素与背景众数；纯色令牌组合用同一
> WCAG 公式复核。详细脚本结果见本报告末尾。

---

## 2. P1-2 统一筛选 chip + 修日志裁切

### 根因

- 日志/外观用 48dp `XiaoiChip` 实心药丸，能力页用 28dp `FilterChips`，两套视觉。
- 日志 chip 与 `XiaoiPreferenceCard` 之间没有间距，卡片压住 chip 下缘约 2dp。

### 改法

- `LogsScreen`、`AppearanceScreen` 改用 `FilterChips`。
- 日志 chip 行后补 `Spacer(12.dp)`。
- `FilterChips` 统一为：未选中 `chipBg/textSecondary`，选中 `primary/onPrimary`；
  28dp 视觉 + 48dp 触控，FlowRow 自动换行。
- 删除无引用的 `XiaoiChip`；`docs/ui/COMPONENTS.md` 同步。

### 验证

- `p3-logs-light.png` / `p3-logs-dark.png`：chip 下缘完整，卡片顶边与 chip 之间
  有 12dp 空隙；选中“全部”为实心 primary。
- `p3-appearance-light.png` / `p3-appearance-dark.png`：选中态实心 primary，
  与未选中 chipBg 区分明显。
- `p3-capabilities-light.png`：同一视觉；6 枚状态 chip 仍正常换行。
- 对比度：选中 **9.11:1**（浅）/ **11.55:1**（深），未选中 **4.76:1**。

---

## 3. P1-3 模型页右侧动作挤压

### 根因

- 服务商 summary 里又拼了 ` · 当前`，和 endAction 的“当前”徽章重复；`BasicComponent`
  的 summary 没有 maxLines，被徽章挤成两行，出现“已设置密 / 钥”。
- 卡内又写了一次“服务商设置”，与分组头重复。
- `endActions` 外层 `Row` 默认 Top 对齐，28dp 徽章与 48dp `XiaoiTextButton`
  上下错位。

### 改法

- `ModelSections.kt`：服务商/模型行改为 Miuix `BasicComponent` 的 content-slot
  重载，标题与 summary 都 `maxLines = 1 + Ellipsis`；去掉 summary 的 ` · 当前`。
- 删除卡内重复的“服务商设置”标题。
- `endActions` 内再包一层 `Row(verticalAlignment = CenterVertically)`，
  “当前”与“删除”同一水平行。
- “当前”只保留 endAction 徽章；“当前使用/点按切换”留在 summary。

### 验证

- `p3-model-light.png`：服务商副标题单行省略；`当前` 只在右侧出现一次；
  “服务商设置”只在分组头出现一次；模型行 `当前 + 删除` 水平居中。
- `p3-model-dark.png`：深色同样成立。
- `p3-model-light-130.png`：1.3 字号下仍单行省略，无断词/上下错位。

---

## 4. P1-4 8771 命名与 8770/未知状态统一

### 根因

- 8771 在 Connect/Tools 叫“数据面”，在 Access/Diagnostics 叫“控制面/能力桥”。
- 未知状态混用“未知/未校验/未确认”。
- Access 用 `agentStateText`，Diagnostics 用 `IssueInputFactory.agentPort` 的
  `PortState`，判定顺序不同；且 Diagnostics 的 8771 文案是“能力桥”。

### 改法

- 新增 `ui/status/AgentStatus.kt`：`agentPortUi(mcpEnabled, AgentRuntimeStatus.get())`
  唯一判定 8770，输出 `RUNNING/STOPPED/FAILED/DISABLED/UNKNOWN` + 统一文案/色调；
  Access、Bridge、Diagnostics 全部改用它。
- Diagnostics 的 `DiagSnapshot` 删除 `agent8770`，不再保留第二套状态；
  `diagnosticsText` 也用同一 helper。
- 8771 统一为“8771 控制面”：Connect、Tools、Diagnostics、Me、Legacy、About、
  Home 提示；`ui/config/McpSection.java` 的旧版复制文本同步。
- 未知状态统一“未确认”：D2/D3/D4、8770 `PortState.UNKNOWN`、绑定未知、
  Legacy 时间缺失。
- Access 的端口行副标题统一加 `绑定 · ` 前缀，明确不是运行状态。

### 验证

- `p3-connect-light.png`：`8771 控制面`；`p3-tools-light.png`：`8771 控制面工具`。
- `p3-access-light.png`：`运行中` 徽章旁是 `绑定 · 未确认` / `绑定 · 局域网 · 0.0.0.0`。
- 同进程一致性：`p3-access-consistency3.png` 与
  `p3-diagnostics-consistency3.png` 在同一 App 进程内先后进入，两页 8770 均为
  **运行中**，8771 均为运行中（不再出现 Access 运行中 / Diagnostics 未确认）。
- `p3-diagnostics-light.png`：D2/D3/D4 均显示“未确认”。

> 说明：用 `--route` 重开页面会 force-stop App，进程内 `AgentRuntimeStatus`
  会归零；因此一致性证据使用“先 Access → 我的 → 诊断 → 返回接入”的手动路径，
  不 force-stop。

---

## 5. P1-5 次级按钮浅色不可见

### 根因

`XiaoiButton(SECONDARY)` 容器取 `surface2`：浅色 `#FFFFFF` 落在白卡上等于隐形；
深色 `#1F1F23` 反而明显。

### 改法

`ui/components/XiaoiSurfaces.kt`：SECONDARY 容器 `chipOkBg`、文字 `primary`
（浅色 `#DCE9F7/#2F4A6B`，深色 `#2E3444/#C7D2FE`）。PRIMARY/TEXT/DANGER_TEXT 不动。

### 验证

- `p3-token-light.png`：“生成新令牌”是可辨认的浅蓝按钮，**7.42:1**。
- `p3-xiaoai-light.png`：“管理模型”同样可见。
- `p3-token-dark.png` / `p3-xiaoai-dark.png`：深色容器 `#2E3444`，**8.32:1**。
- `p3-model-light.png`：“校验端点”次按钮可见。

---

## 6. 顺带修的 P2

| 项 | 改法 | 验证 |
| --- | --- | --- |
| P2-2 触控 48dp | `XiaoiCopyRow` 的“复制”、`PermissionRow` 的“去设置”改为 48×48 最小触控区，字号不变 | `p3-about-light.png`、`p3-connect-light.png`、`p3-permissions-light.png` |
| P2-10 许可证死行 | About“许可证”改为 `XiaoiInfoRow(onClick)` → `Licenses` | `p3-about-light.png` 右侧 chevron |
| P2-8 工具重复计数 | 分组头去掉 `（5）` | `p3-tools-light.png` |
| P2-6 全屏通知断行 | 副标题缩短为“Android 14+ 需单独授权” | `p3-permissions-light.png`、`p3-permissions-light-130.png` |
| P2-9 组名语序 | “从运行动态导入”→“从运行中的小爱导入” | 代码 + `p3-configmanage-light-130.png` |

> `AboutScreen` 新增 `onNavigate` 参数，`MainActivity` 只加了一行
> `onNavigate = { navigate(it) }` 接线；这是让“许可证”进入 `Licenses` 的唯一路径，
> 未改壳层其它逻辑。

---

## 7. 验收

### 门禁

| 项 | 结果 |
| --- | --- |
| `:app:assembleRelease --offline` | BUILD SUCCESSFUL |
| `tests/run.sh` | 全部通过（NavStack 49 通过 / 0 失败） |
| `check-consistency.sh` | 通过 |
| `verify-apk.sh` | PASS；`plus.xiaoi.module`；versionCode 18；`d7dd34b2…d19b` |

### 真机

| 项 | 结果 |
| --- | --- |
| 覆盖安装 | 成功；配置保留（5 条 P1 截图均来自已安装构建） |
| 浅色截图 | Model / Logs / Appearance / Access / Diagnostics / Connect / Token / Xiaoai / Capabilities / Permissions / Tools / About 共 12 页 |
| 深色截图 | Model / Access / Token / Xiaoai / Logs / Appearance / Tools / Permissions 共 8 页 |
| 字号 1.3 | Model / Permissions / Tools / ConfigManage 四页；无新增断词/裁切 |
| 8770 一致性 | 同进程 Access/Diagnostics 均“运行中” |
| 主题 | 初始“跟随系统”→ 切“深色”→ 还原“跟随系统”（实际 prefs `theme_mode=0`） |
| 手机 PNG | 截图前后 `DCIM/Screenshots`、`Pictures/Screenshots` 的 `.png` 数均为 **0**（只有用户 JPG） |
| config.json SHA-256 | 安装前后均为 `bc22b9b28d1feb04988b28ba223daf608ce53e76247186129224e0d12ca529e4` |

---

## 8. 剩余 P2（未做 / 部分做）

1. **P2-1 第二条工具栏**：Bridge“说明+查看日志”、Logs 大计数+右对齐动作仍在内容区。
2. **P2-3 首页待处理严重度**：三条仍全是 INFO 灰色 `i`。
3. **P2-4 底栏图标风格**：Home 实心、其余线性。
4. **P2-5 日志域筛选**：只有级别，没有域。
5. **P2-7 能力页词表**：“已实现/可用”仍两种叫法；“不提供”单枚落行。
6. **P2-8 风险语义（部分）**：分组计数已去；风险与“需确认”仍共用一枚 badge。
7. **P2-9 风险分级（部分）**：组名已改；高风险行仍只有白卡+灰副标题。
8. **P2-10 关于页重复（部分）**：许可证行已可进 Licenses；About 内“开源许可”卡仍重复。
9. **P2-11 令牌页“实际”语义**：未改。
10. **P2-12 Connect 形态**：仍是二级页，SCREENS.md 仍写 BottomSheet。
11. **P2-13 外观空白/选中态**：选中态已明显；下半屏空白仍在。

---

## 9. 对比度脚本结果（节选）

```
Model 名称 label/placeholder: fg #606771 bg #F0F0F0 = 5.01:1
Capabilities 搜索占位: fg #606771 bg #F0F0F0 = 5.01:1
Access 8771 warning 徽章: fg #734C16 bg #FDF3D9 = 6.85:1
Access 8770 success 徽章: fg #2F5D45 bg #E1E8E4 = 6.08:1
Permissions error 徽章: fg #923430 bg #F7EBEB = 6.54:1
Hero D1 warning label: fg #734C16 bg #D6CDBF = 4.81:1
Hero D2 unknown label: fg #18202D bg #D4D2CD = 10.83:1
Token 次按钮: fg #354968 bg #DEE9F6 = 7.42:1
Logs 选中 chip: fg #FFFFFF bg #354968 = 9.11:1
Logs 未选中 chip: fg #606771 bg #E5EBF2 = 4.76:1
Dark Access warning 徽章: fg #F2C14B bg #2D2514 = 9.02:1
Dark Access success 徽章: fg #77DB89 bg #2E3D32 = 6.71:1
Dark Token 次按钮: fg #C9D2FA bg #2F3443 = 8.32:1
Dark Logs 选中 chip: fg #1A1B1E bg #C9D2FA = 11.55:1
Dark Hero D1 warning label: fg #EEBE4A bg #443818 = 6.63:1
```

> 注：着色文字受抗锯齿影响，PNG 取样值是“核心像素”，与纯令牌计算值相差
> 0.1–0.6；两者都 ≥4.5:1。
