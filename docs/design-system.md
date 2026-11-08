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

Hero 底色按状态：`已启用 = success 12% 透明`、`未配置 = surface`、`已暂停 = warning 12%`、`异常 = error 12%`。

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
- 页面左右：16dp；顶部：12dp；底部：112dp（导航 80 + FAB 32）
- 卡片内边距：20dp
- 卡片间距：24dp（分组之间）、12dp（同组之间）
- 列表行高：72dp（信息行）、56dp（输入/开关行）、48dp（次级行）
- 圆角：卡片 24dp、输入框 12dp、胶囊 999dp、FAB 18dp

## 5. 组件声明

### 5.1 AppBar
高 56dp，背景 bg，无阴影；左标题 Title，右侧动作图标 24dp。

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
背景 surface，圆角 24，内边距 20，无描边无阴影，间距 24dp。

### 5.6 InfoRow
高 72dp；左 24dp 线性图标（text-secondary）；标题 RowTitle，副标题 Caption（值）；
右箭头可选；整行可点，按压态背景 `line` 20%。

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

### 5.12 BottomNav
高 80dp（含安全区），背景 bg，顶部 1dp line；
4 项等宽，图标 24dp + Caption；选中项 primary 文字 + 图标；
不悬浮、不加背景块。

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

## 7. 0.14 Compose/Miuix 例外

0.14 起设置页走 Compose + Miuix，组件默认值优先，不再套用旧 XML 的 24dp/56dp 自定尺寸：

- Card 默认 16dp 圆角；Button 默认 40dp；SmallTopAppBar 默认 52dp；
  NavigationBar/NavigationBarItem 使用 Miuix 默认。
- 颜色仍映射本文的语义令牌（bg/surface/text/primary 等）。
- 旧 XML 页面（HomeActivity/ConfigActivity 等）仍按 24dp 卡片，不做回改。

规则：**有 Miuix 组件就直接用，不用 Row/Column 模拟**；自定义仅保留 Miuix 没有的业务组件。
