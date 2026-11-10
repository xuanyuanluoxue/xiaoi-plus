# 顶栏设计：问题、失败尝试与迁移方案

> 2026-11-10 · 手机端 DSH 记录 · **开发已交给电脑端 opencode**

## 结论先行

**不要再自己写顶栏的滚动/模糊逻辑**。`miuix-ui 0.9.4` 已自带 `TopAppBar`（大标题折叠）、
`TopAppBarState.collapsedFraction`、`MiuixScrollBehavior`、`SmallTopAppBar`（紧凑版）。
项目当前的自研实现（`XiaoiTopBar` + `LocalXiaoiScrollY` 上报 + `TopBlurScrim`）应该被替换。

## 自研版本踩过的坑（按时间顺序，别再重复）

| # | 问题 | 根因 | 结果 |
|---|---|---|---|
| 1 | 顶栏纯色、无模糊无渐变 | 内容被 Scaffold 顶栏槽位挡住，模糊层背后没有内容可采 | 未达预期 |
| 2 | 出现"两个顶栏" | 一层是 Scaffold 顶栏（实色），一层是 `TopBlurScrim`（矩形模糊），未合并 | 未达预期 |
| 3 | 模糊边缘是硬边 | `onDrawSurface` 只画了渐变**填充**，没有遮罩模糊本身 | 改为 `BlendMode.DstIn` 渐变遮罩后才"渐隐" |
| 4 | 内容压住标题、且顶部拉不下来 | 内容容器顶部留白为 0（为让内容穿到顶栏下） | 改为页面内 `padding(top = 顶栏高度)` |
| 5 | 切 tab 后页面不对齐 | 四个主页的额外顶部内边距不一致（首页 4dp，其他 12dp） | 统一 `pagePadTop` |
| 6 | 二级页标题压字 + 顶部鬼影 | 模糊带高度（状态栏+88dp）> 顶栏高度（状态栏+40dp），多出的 48dp 盖住内容 | 高度必须精确等于顶栏高度 |

## 迁移方案（交给 opencode）

1. 一级页：`TopAppBar`（大标题，滚动折叠）；二级页：`SmallTopAppBar`（返回 + 标题）。
2. 滚动交给 `MiuixScrollBehavior`，删除自研的：
   - `LocalXiaoiScrollY` / `xiaoiScrollState()` / `rememberXiaoiListState()`
   - `MainActivity` 里的 8dp 阈值判断
3. 模糊：`miuix-ui` 内**没有** blur 组件，仍在 `miuix-blur`；模糊带高度 = 顶栏**折叠后**高度，
   透明度可由 `collapsedFraction` / `overlappedFraction` 驱动。
4. 页面顶部留白 = 顶栏折叠高度（单源常量，别再两处硬编码）。
5. **不要动**：底栏玻璃胶囊、pager 滑动、8770/8771/8772 端口报文。

## 已确认的 Miuix API（javap `miuix-ui-android/0.9.4/miuix-ui.aar`）

```
TopAppBar(title, modifier, containerColor, scrolledContainerColor, navigationIcon, ...,
          title: @Composable, actions: RowScope.()->Unit, scrollBehavior: ScrollBehavior,
          expandedHeight, collapsedHeight, ...)
SmallTopAppBar(...)
rememberTopAppBarState(initialHeightOffsetLimit, initialHeightOffset, initialContentOffset)
MiuixScrollBehavior(state, canScroll, flingAnimationSpec, snapAnimationSpec)
TopAppBarState: heightOffset / collapsedFraction / overlappedFraction
TopAppBarDefaults: collapsedHeight / titlePadding / navigationIconPadding
NavigationBar / FloatingNavigationBar / FloatingNavigationBarItem
```
