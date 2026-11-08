# P1 报告：单 Activity + 4 tab + 主题 + 日志/关于

> 日期：2026-11-08
> 分支：`feat/ui-p1`（从 `main@ac5ef73` 创建）
> 工作区：`~/xiaoi-plus-dev/worktrees/ui-p1`
> 范围：P1 地基；首页/小爱/接入暂为占位，真实页面在 P2。

---

## 1. 交付内容

| 文件 | 内容 |
| --- | --- |
| `MainActivity.kt` | 单 Activity 壳；4 tab；`NavStack` 单栈；返回规则；Snackbar |
| `ui/theme/XiaoiTheme.kt` | 19 色令牌、尺寸、字号；深浅色；`surfaceContainer` 对齐 |
| `ui/components/XiaoiBars.kt` | 顶栏、底栏、Snackbar |
| `ui/components/XiaoiSurfaces.kt` | Card、Button、GroupHeader、Badge、Chip、EmptyState、StatTile、Divider |
| `ui/components/XiaoiRows.kt` | InfoRow、ValueRow、SwitchRow |
| `ui/screen/me/MeScreen.kt` | 本机/版本、主题三档、日志/关于入口、旧版入口 |
| `ui/screen/me/LogsScreen.kt` | 日志读取/筛选/跟随/清空/分享、三种空态 |
| `ui/screen/me/AboutScreen.kt` | 版本、作者、GitHub、许可证、致谢、更新、免责 |
| `ui/screen/PlaceholderScreen.kt` | P2 占位页，保留旧版入口 |
| `AndroidManifest.xml` | 新增 `MainActivity`（非导出；P2 接替 launcher） |

- 旧 `HomeActivity/ConfigActivity/LogsActivity/AboutActivity` 保留；P1 不改变启动入口。
- `MainActivity` 支持调试深链：`--es route Me|Logs|About|Home|Xiaoai|Access`（P2 换成正式 `TAB/PAGE`）。

## 2. 构建与门禁

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleRelease --offline
```

| 项 | 结果 |
| --- | --- |
| release APK | 7.5 MB（R8 仍关） |
| versionCode / versionName | 18 / 0.13.0-agent.2（P1 不升版本） |
| 签名 / 包名 / 指纹 | PASS；`plus.xiaoi.module`；`d7dd34b2…d19b` |
| `tests/run.sh` | 全部通过（1653 断言） |
| `check-consistency.sh` | 通过 |
| `verify-apk.sh` | PASS |

## 3. 真机验证（31106e85）

| 项 | 结果 |
| --- | --- |
| 覆盖安装 | 成功，配置保留 |
| `MainActivity` 启动 | COLD 502ms / WARM 150ms；无 FATAL |
| 底部 4 tab | 首页 / 小爱 / 接入 / 我的 均可切换 |
| 深链 `route=Logs` | 正常；筛选/跟随/清空/分享可见；空态“等待小爱上报日志” |
| 深链 `route=About` | 正常；版本/作者/GitHub/许可证/致谢/更新/免责 |
| 深链 `route=Me` | 正常；本机卡、主题三档、日志/关于、旧版入口 |
| 深色主题 | 可切换；文字/卡片/底栏可读；切回“跟随系统”正常 |
| 返回 | 二级页返回一级；非首页根页返回首页；首页退出 |

### 发现并修复

1. **`painterResource(R.mipmap.ic_launcher)` 崩溃**
   adaptive icon 不是 Vector/PNG，Compose 抛
   `Only VectorDrawables and rasterized asset types are supported`。
   改为现有矢量 `R.drawable.ic_xiaoi`。
2. **深色标题黑字**
   P0 Spike 的 `MiuixTheme.textStyles.title1` 默认色在深色下仍为黑；
   P1 所有 `Text` 显式传 `XiaoiTheme.colors.text`，已验证可读。
3. **深色卡片颜色**
   P1 曾显式传入 `surfaceContainer`；P1.1 为严格使用 Miuix 默认值已移除覆盖。
   浅色卡为 `#FFFFFF`，深色卡为 Miuix 默认容器色 `#242424`。这是组件默认值优先的
   结果，已记录在 `design-system.md` §9。
4. **“我的”tab 点按偶发打开支付宝**
   是设备上支付宝浮层/前台残留造成的输入拦截；`force-stop` 支付宝后点按正常。
   不是 `XiaoiBottomBar` 的布局问题。

## 4. 截图

```text
~/xiaoi-plus-dev/xiaoi-plus-ui/review/2026-11-08/
├─ p1-home-light.png       # 首页占位 + 4 tab
├─ p1-me-light2.png        # 我的（浅色，最终卡片色）
├─ p1-me-dark2.png         # 我的（深色）
├─ p1-logs-light.png       # 日志空态
└─ p1-about-light.png      # 关于
```

## 5. 已知限制

- 首页/小爱/接入仍是占位，P2 接入；`MainActivity` 还不是 launcher。
- 日志页当前显示 0 条（控制面镜像为空）；非空 LogRow 待 P2 用真实日志补验。
- 底栏已接 `miuix-icons`（P1.1）；选中色/对比度见 `design-system.md` §9。
- 深链仍是调试用 `route` extra；P2 按 `SCREENS.md` 换成 `TAB/PAGE/FOCUS`。
- R8 未开，release 8.1MB（新增 miuix-icons），P3 处理。

## 6. 结论

**P1 完成，可以进入 P2 页面开发。**

下一步：Home / Xiaoai+Model / Access+Capability / Me 完整页；先补 `AgentRuntime`、`IssueResolver`
接入首页，再把 `MainActivity` 设为 launcher。

## 7. 视觉验收修正（2026-11-08）

| 问题 | 修正 |
| --- | --- |
| 主按钮白字 + Miuix 默认浅灰底，几乎不可见 | `XiaoiButton` 显式 `ButtonDefaults.buttonColors(primary/onPrimary)`，高 48dp；占位页按钮全宽 |
| 深色主题状态栏仍是白底 | `MainActivity.applySystemBars()` 同步状态栏/导航栏颜色与浅色图标；强制浅/深色也生效 |
| 深色卡片色不对 | `XiaoiTheme` 显式 `surfaceContainer/onSurfaceContainer` |
| 自适应图标不能 `painterResource` | 改用 `R.drawable.ic_xiaoi`（Vector） |

干净截图与拼图：

```text
~/xiaoi-plus-dev/xiaoi-plus-ui/review/2026-11-08/
├─ p1-final3-home-light.png
├─ p1-final5-me-light.png
├─ p1-final-logs-light.png
├─ p1-final-about-light.png
├─ p1-final2-me-dark.png
├─ p1-final2-home-dark.png
└─ p1-visual-review.png   # 3×2 拼图（Review 面板已打开）
```

### 仍待 P2 决定的视觉项（P1.1 已处理 1–4）

1. **浅色卡片对比**：已改 Miuix 白卡，做完了。
2. **底部导航**：已接 24dp `miuix-icons` 与选中态，做完了。
3. **返回箭头**：已换 `MiuixIcons.Back`，做完了。
4. **关于页“作者”行**：已改 `XiaoiCopyRow`（无 chevron，复制展示值），做完了。
5. **P1 预览卡**：P2 删除，不属于最终页面。

## 8. 严格 Miuix 重构（P1.1，2026-11-08）

根据“严格按 Miuix 组件开发”的要求，P1 壳已改成原生组件：

| 原实现 | 现在 |
| --- | --- |
| 自绘顶栏 Row + 文本 `‹` | `SmallTopAppBar` + `IconButton(MiuixIcons.Back)` |
| 自绘 4 个文字 tab | `NavigationBar` 容器 + `miuix-icons`；item 因对比度自绘 |
| 自绘 Snackbar | `Scaffold` + `SnackbarHostState` + `SnackbarHost` |
| 自绘 InfoRow/SwitchRow | `ArrowPreference` / `SwitchPreference` / `BasicComponent` |
| 卡片 24dp 覆盖 | Miuix `Card` 默认 16dp；浅色白卡、深色默认容器色 |
| 按钮 48dp 覆盖 | Miuix `Button` 默认高度，仅保留 primary/onPrimary 配色 |

- 新增依赖：`miuix-icons:0.9.4`（底部导航图标）。
- release APK：8.1MB（R8 未开）；P3 会 shrink。
- 截图（已归档）：
  `miuix2-home-light.png`、`miuix2-home-dark.png`、`miuix2-me-light.png`、
  `miuix2-me-dark.png`、`miuix2-logs-light.png`、`miuix2-about-light.png`；
  拼图 `p1-visual-review-miuix.png`。
- 门禁：`tests/run.sh`、`check-consistency.sh`、`verify-apk.sh` 全绿。

仍保留的自定义组件：`XiaoiTheme`（颜色/字号映射）、`XiaoiBadge`/`XiaoiChip`、
`XiaoiEmptyState`、`StatusHero` 等 Miuix 没有直接对应的业务组件。

## 9. P1 视觉审核修正（2026-11-08）

视觉报告：`~/xiaoi-plus-dev/xiaoi-plus-ui/tests/2026-11-08/visual-review-p1.md`
结论“有条件通过”（0 P0 / 4 P1 / 10 P2）。4 条 P1 已修：

| # | 问题 | 修正 |
| --- | --- | --- |
| P1-1 | 日志空态用 `Text("·")` 渲染成 17dp 灰点 | `XiaoiEmptyState` 增加 `icon: ImageVector`，默认 `MiuixIcons.Notes`，48dp `textHint` |
| P1-2 | 关于页“作者”复制却显示 chevron，展示值与复制值不一致 | 新增 `XiaoiCopyRow`：`BasicComponent` + 右侧“复制”，复制展示值“轩辕落雪” |
| P1-3 | 日志“清空”无确认，与“分享”同权重 | 两段式确认：清空 → 取消/确认清空（`error` 色）；0 条时禁用清空与分享 |
| P1-4 | 灰色文本对比度不足（2.45–3.88:1） | 令牌改为 `textSecondary #5F6772`、`textHint #6B7280`、深色 hint `#8E9299`；底栏未选中不再叠 alpha |

顺带修复的 P2：

- `XiaoiTextButton` 改用 Miuix `TextButton`（透明底、48dp）；`XiaoiDivider` 改用 Miuix `HorizontalDivider`。
- `XiaoiChip` 提到 48dp 触控；未选中文字用新的 `textSecondary`。
- 日志“跟随最新”包进 `XiaoiPreferenceCard`，去掉裸分割线。
- 日志空态分支改为 `!received` → `lines.isEmpty()` → 筛选无结果，去掉不可达分支。
- 我的页版本行拆成“版本”和“设备型号 + Android”，不再混在一起。
- 关于页“免责声明”增加独立分组标题。
- 文档补对比度表与自绘例外（`design-system.md` §9）。

底栏 item 自绘原因：Miuix `NavigationBarItem` 对未选中内容强制乘 0.4 alpha，
纯黑在 `bg #F7F8FA` 上最高 2.82:1，无法达到 3:1；容器仍用 Miuix `NavigationBar`，
item 用 Miuix `Icon`/`Text` + `selectable(Role.Tab)`，已在 `design-system.md` §9.2 登记。
