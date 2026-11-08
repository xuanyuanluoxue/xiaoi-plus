# 小爱同学plus · UI 声明（Design System）

> 目的：四页共用一套令牌与组件，禁止页面各写各的。
> 规则：**先声明，后实现**；实现时只允许引用本文的令牌与组件。

## 1. 设计原则

1. **一个强调色**：全站只有 primary 一个品牌色，成功/错误只用于状态
2. **一种卡片**：同一圆角、同一背景、同一内边距，不出现"另一种卡"
3. **信息分层靠字号与留白**，不靠边框、阴影、渐变
4. **数值居中、文本左对齐**；同一列表行高固定
5. **深浅两套只换颜色令牌**，布局与尺寸完全一致

## 2. 颜色令牌

> 令牌已收敛为 19 个语义名（原 32 个别名已删除：card/input_bg/divider/surface_2 旧称等）。
> 代码里只允许出现 `@color/<name>` 或 `R.color.<name>`，不允许裸 `#RRGGBB`。

| 令牌 | 浅色 | 深色 | 用途 |
|---|---|---|---|
| bg | #F7F8FA | #0B0B0D | 页面底色 |
| surface | #F1F2F5 | #161618 | 卡片 |
| surface_2 | #FFFFFF | #1F1F23 | 输入框 / 浮层 |
| line | #E6E9EE | #2A2A2E | 分割线 / 导航上边线 |
| text | #111318 | #F2F3F5 | 主文字 |
| text-secondary | #7A828E | #A0A4AB | 副文字 |
| text-hint | #9AA1AC | #6E7278 | 占位 / 注释 |
| primary | #2F4A6B | #C7D2FE | 强调 / 主按钮 |
| on-primary | #FFFFFF | #1A1B1E | 主按钮文字 |
| success | #2E7D5B | #4ADE80 | 已启用 / 成功 |
| warning | #B26A00 | #FBBF24 | 暂停 / 需注意 |
| error | #C43D3D | #F87171 | 失败 |
| hero-on | #16202E | #E8ECF8 | Hero 内文字 |
| icon-green | #248A3D | #34C759 | 状态 / 运行 |
| icon-blue | #007AFF | #0A84FF | 接入 / 通道 / 令牌 |
| icon-orange | #B25000 | #FF9F0A | 模型 / 服务商 |
| icon-pink | #D70015 | #FF375F | 日志 / 活动 |
| icon-red | #C62828 | #FF453A | 诊断 / 权限 / 危险 |
| icon-cyan | #0071A4 | #64D2FF | 隐私 / 局域网 |
| icon-purple | #8944AB | #BF5AF2 | 配置 / 备份 |
| icon-indigo | #3634A3 | #5E5CE6 | 关于 / 致谢 |

Hero 底色按状态：`已启用 = success 14% 透明`、`未配置 = surface`、`已暂停 = warning 14%`、`异常 = error 14%`。
彩色圆图标统一 48dp 圆 + 26dp 白色图形；颜色只表达“是什么”，状态仍由徽章文字表达。

## 3. 字体

| 名称 | 字号/行高 | 字重 | 用途 |
|---|---|---|---|
| Display | 28/34 | 600 | 数字砖 |
| Title | 22/28 | 500 | 页面标题 |
| Hero | 20/26 | 500 | Hero 状态 |
| RowTitle | 16/22 | 500 | 列表行标题 |
| Body | 14/20 | 400 | 正文、输入 |
| Caption | 12/16 | 400 | 说明、时间 |
| Mono | 12/16 | 400 | 版本、端口、日志 |

中文字体统一 `sans-serif`；数字与版本用 `monospace`。不使用自定义字体文件。

## 4. 间距与圆角

- 栅格：4dp
- 页面左右：16dp；顶部：4–12dp；底部：24dp（二级页）/ 悬浮底栏高度 + 手势条（一级页）
- 卡片内边距：20dp
- 卡片间距：12dp（独立卡清单）；分组之间 24dp（仅诊断等密集页保留）
- 列表行高：72dp（信息行）、56dp（输入/开关行）、48dp（次级行）
- 圆角：卡片 28dp、输入框 12dp、胶囊 999dp

## 5. 组件声明

### 5.1 AppBar
状态栏下 48dp 行，背景 bg，无阴影；标题 32sp（左对齐），返回按钮 48dp，
页面级动作与标题同排。不做折叠大标题的空白行。

### 5.2 HeroCard
圆角 24，内边距 20；左 44dp 图标；状态 Hero；右侧徽章（Chip，最多 2 个）；副行 Caption。
高度不固定，但上下留白各 20dp。

### 5.3 StatTile
等宽两列，间距 12dp；背景 surface；内边距 16dp；上标签 Caption，下数字 Display。
数字为 0 时显示「0」而非「—」；桥不可达时显示「—」。
主页固定两格：**今日三方 / 今日官方**（`/stats`），不再放恒为 0 的日志条数。

### 5.4 GroupHeader
文字 Caption，颜色 text-secondary；上间距 24dp，下间距 8dp；不加线。

### 5.5 Card
背景 surface，圆角 28，内边距 20，无描边无阴影；独立卡之间间距 12dp。
Hero 卡按状态使用 14% 语义色底。

### 5.6 InfoRow
高 72dp；左 48dp 语义色圆形图标（白图形，`XiaoiIconTone`）；
标题 RowTitle，副标题 Body/Caption；右侧值/徽章 + chevron 可选；整行可点。

### 5.7 InputRow
高 56dp；左标签 88dp（Caption）；右输入框高 44dp、圆角 12、背景 surface_2、
内边距 12dp、字号 Body；聚焦时 1dp primary 描边。

### 5.8 SwitchRow
高 56dp；左标题 + 副标题，右侧 Switch；整行可点。
关闭态副标题变 text-hint。

### 5.9 Chip
高 28dp，圆角 999，左右内边距 10dp，字号 Caption；
默认 surface_2，选中 primary 15% 底 + primary 文字；禁用 text-hint。

### 5.10 Button
主：高 48dp，圆角 16，primary 底，on-primary 文字，字号 Body 500。
次：同尺寸，surface_2 底，text 文字。
禁用：primary 40% 透明。

### 5.11 FAB
56dp 圆形，右下，距右 16dp、距导航 16dp。
**脏态** = 主按钮（primary 实色 + on-primary 字）；**干净态** = 次按钮
（chip 底 + primary 字），不使用低透明度（浅色下白字不可读）。

### 5.12 BottomNav（悬浮液态玻璃）
一级页底部悬浮胶囊：左右 16dp、底部 12dp、高 56–64dp，4 项等宽；
选中项有 pill 底与 primary tint，未选中 textSecondary；
FULL（API 33+）走 miuix-blur 模糊 + 折射 + 高光；BLUR（31–32）走平台 RenderEffect；
SOLID（26–30 / 减少动画 / 用户关闭）为同尺寸实心胶囊。
底栏不占 Scaffold `bottomBar`，内容底部预留条高；二级页隐藏。

### 5.13 Feedback（应用内提示条）
由 `BaseActivity.showMessage()` 统一实现（替代系统 Toast）：
底部导航上方居中，圆角 16，surface_2 底 + line 描边，左右 24dp；
2.6 秒自动淡出；四页同一位置与时长。

### 5.14 LogRow
高 40dp；时间 Mono 12 text-hint 固定宽 80dp；级别胶囊 24dp 宽；
正文 Mono 12 text；错误行左侧 2dp error 竖条；长按复制。

### 5.15 EmptyState
居中；图标 48dp text-hint；标题 RowTitle；说明 Caption；可选一个次按钮。

### 5.16 Icon
24dp 网格（viewport 24，活动区 20×20），描边 1.8，圆头/圆角，全部 outline。
文件内只允许 `#000000`，颜色由使用方 `android:tint` 提供：
导航未选中 `text_secondary`、选中 `primary`；信息行 `text_secondary`；
状态图标才可用 `success/warning/error`。
一个文件只表达一个语义：导航用 `ic_home/settings/logs/info`，
信息行用 `ic_model/link/kernel/device/key/tag`，关于页用 `ic_external/license/refresh`。
品牌多色图 `ic_xiaoi` 只用于关于页 64dp 与 launcher，禁止当 24dp 导航图标。
launcher 使用 adaptive-icon（前景 108、安全区 66、monochrome 层），不随主题变色。

## 6. 状态矩阵

状态枚举唯一来源：`AppStatus`；规则唯一来源：`StatusResolver`。
主页 Hero、配置页状态条、日志/关于提示都只消费这两个类，不再各自写 if/else。

| `AppStatus` | 标题 | 典型副文案 | 颜色 |
|---|---|---|---|
| NOT_CONFIGURED | 未配置 | 选择服务商与模型后即可启用 | text-hint |
| PAUSED | 已暂停 | 配置已保留，打开上方开关即恢复 | warning |
| ENABLED | 已启用 | 待校验 · 点按校验 / 密钥为空 | primary |
| CHECKING | 校验中 | 正在校验端点与模型… | primary |
| OK | 可用 | 响应 123ms · 点按重新校验 | success |
| AUTH | 密钥无效 | 检查密钥是否属于该服务商 | error |
| MODEL | 模型不存在 | 检查模型 ID 是否拼写正确 | error |
| ENDPOINT | 端点不可达 | 检查地址、网络或账号配额 | warning |

补充规则：

- 「上次校验结论」缓存（`StatusCache`）以「端点+密钥+模型」哈希为指纹，
  配置一变自动失效；主页据此与配置页显示同一结论。
- 推送状态（未同步/部分推送）是独立维度，不塞进 `AppStatus`，由配置页
  状态条第二行单独展示。

## 7. 主题实现

- `values/` 浅色，`values-night/` 深色，同名 `Theme.XiaoiPlus`
- 三档：跟随系统 / 浅色 / 深色，**唯一事实源是 App prefs**
  （`Prefs.KEY_THEME`），不写入 `config.json`、不参与推送
- 唯一入口 `ThemeManager`：`get/set/wrap`；页面不再自己读写 prefs
- `BaseActivity.attachBaseContext` 用 `createConfigurationContext` 覆写
  `uiMode`；`onResume` 发现偏好变了就 `recreate()`，所以被
  `REORDER_TO_FRONT` 复用的旧页面也会立即对齐
- 切主题导致的重建不会丢未保存编辑：配置页把草稿放进 Bundle 恢复
- 所有颜色必须来自令牌；代码里 **0 处** `0xFFxxxxxx`

## 8. 不要做

1. 不要在页面里写死颜色、字号、圆角
2. 不要新增第二种卡片样式或第二种按钮样式
3. 不要用阴影/渐变/描边来区分层级
4. 不要给图标上色（除非是状态）；24dp 图标文件内不得写品牌色
5. 不要让多色品牌图兼任导航/信息行图标
5. 不要让同一个语义出现两种文案（如"保存"与"提交"并存）

## 9. 0.14 Compose/Miuix 例外

0.14 起设置页走 Compose + Miuix，组件默认值优先，不再套用旧 XML 的 24dp/56dp 自定尺寸：

- Card 默认 16dp 圆角；Button 默认 40dp；SmallTopAppBar 默认 52dp。
- 颜色仍映射本文的语义令牌（bg/surface/text/primary 等）。
- 旧 XML 页面（HomeActivity/ConfigActivity 等）仍按 24dp 卡片，不做回改。
- 设置行不带前置图标：Miuix `ArrowPreference`/`BasicComponent` 默认无 icon，
  0.14 暂不补 `startAction`，页面层级靠分组标题和行高区分。
- 深色卡片使用 Miuix 默认容器色（实测 `#242424`），不等于 `surface2 #1F1F23`；
  这是组件默认值优先的结果，不在页面里覆盖。
- 0.14 起 `XiaoiColors` 显式暴露 `heroOn`（映射本表 `hero-on`），供 `StatusHero`
  与 `DimensionBadge` 使用；未新增其他颜色令牌。

### 9.1 对比度（WCAG 2.1）

浅色：

| 前景/背景 | 对比度 |
| --- | --- |
| `text #111318` / `surface2 #FFFFFF` | 18.58:1 |
| `textSecondary #5F6772` / `surface2` | 5.72:1 |
| `textSecondary` / `bg #F7F8FA` | 5.39:1 |
| `textSecondary` / `chipBg #E4EBF3` | 4.76:1 |
| `textSecondary` / 输入框底 `#F1F2F5` | 5.11:1 |
| `textHint #6B7280` / `surface2` | 4.83:1 |
| `textHint` / `bg` | 4.55:1 |
| `primary #2F4A6B` / `bg` | 8.55:1 |
| `primary` / `chipOkBg #DCE9F7`（次按钮） | 7.37:1 |
| `success #1B5E43` / 14% success 淡底 | 6.16:1 |
| `warning #7A4A00` / `warningBg #FFF3D6` | 6.78:1 |
| `error #9E2B2B` / 10% error 淡底 | 6.43:1 |
| Hero `success` 标签 / success 12% + 14% 双层 | 4.94:1 |
| Hero `warning` 标签 / warning 12% + 14% 双层 | 4.81:1 |
| Hero `error` 标签 / error 12% + 14% 双层 | 4.63:1 |
| Hero `heroOn` 标签 / UNKNOWN 14% 淡底 | 10.88:1 |

深色：

| 前景/背景 | 对比度 |
| --- | --- |
| `text #F2F3F5` / `surface #161618` | 16.28:1 |
| `textSecondary #A0A4AB` / Miuix 容器 `#242424` | 6.20:1 |
| `textHint #8E9299` / Miuix 容器 `#242424` | 4.97:1 |
| `primary #C7D2FE` / `bg #0B0B0D` | 13.18:1 |
| `primary #C7D2FE` / `chipOkBg #2E3444`（次按钮） | 8.33:1 |
| `success #4ADE80` / 14% success 淡底 | 6.59:1 |
| `warning #FBBF24` / `warningBg #2E2511` | 9.06:1 |
| `error #F87171` / 10% error 淡底 | 4.52:1 |
| `onPrimary #1A1B1E` / `primary #C7D2FE`（选中 chip） | 13.18:1 |

规则：正文/标签 ≥4.5:1，未选中导航/交互图标 ≥3:1；`textHint` 只用于白卡或页面底，
不要放到 `surface` 灰块或输入框上。输入框 label/placeholder 显式取 `textSecondary`，
徽章文字直接使用调深后的 `success/warning/error`；UNKNOWN 维度标签取 `heroOn`。

### 9.2 已登记的自绘例外

| 组件 | 原因 | 替代方案 |
| --- | --- | --- |
| 玻璃底栏（容器与 item 均自绘） | Miuix 无悬浮玻璃栏组件；参考实现需要 squircle + 背景采样 | 改编 LiquidTab（GPL-3.0）的底栏源码；低版本/关闭动画时退化为实心胶囊，交互与选中态一致 |
| `FilterChips` | Miuix 0.9.4 无 chip 组件；`TabRow` 是文本页签，形态不符 | 保留自绘，28dp 视觉 + 48dp 触控；未选中 `chipBg/textSecondary`，选中 `primary/onPrimary` |
| `XiaoiEmptyState` | Miuix 无空态组件 | 自绘，但图标 48dp、文字用令牌 |

规则：**有 Miuix 组件就直接用，不用 Row/Column 模拟**；自绘必须在本节登记原因。
