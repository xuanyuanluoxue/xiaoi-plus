# P2 / A2 · UI Foundation 报告

> 分支：`feat/ui-foundation`（从 `8e1fc09` 创建）
> 范围：路由表、页面占位签名、共享组件、语义令牌。
> 不含：A4–A7 页面实现、`core/**`、`agent/**`、`Mcp.java`、构建脚本、资源与 Manifest。

## 1. 改动文件

### 1.1 路由壳

- `app/src/main/java/plus/xiaoi/module/MainActivity.kt`
  - 19 个路由的统一表：`Routes`（路由名 / 标题 / 所属 tab）。
  - 一级根页显示底栏；二级/三级页隐藏底栏、显示返回箭头。
  - `--es route XXX` 白名单扩到全部 19 个路由；未知路由回首页。
  - 支持 `Name:arg` 透传（当前仅 `CapabilityDetail:<id>`），壳不保存页面状态。
  - 页面只收到 `onNavigate` / `onMessage`（外观额外收 `themeMode`），无业务逻辑。

### 1.2 页面占位（签名固定，A4–A7 只替换自己目录）

| 文件 | 签名 |
| --- | --- |
| `ui/screen/home/HomeScreen.kt` | `HomeScreen(onNavigate, onMessage)` |
| `ui/screen/xiaoai/XiaoaiScreen.kt` | `XiaoaiScreen(onNavigate, onMessage)` |
| `ui/screen/xiaoai/ModelScreen.kt` | `ModelScreen(onNavigate, onMessage)` |
| `ui/screen/access/AccessScreen.kt` | `AccessScreen(onNavigate, onMessage)` |
| `ui/screen/access/BridgeScreen.kt` | `BridgeScreen(onNavigate, onMessage)` |
| `ui/screen/access/TokenScreen.kt` | `TokenScreen(onNavigate, onMessage)` |
| `ui/screen/access/ConnectScreen.kt` | `ConnectScreen(onNavigate, onMessage)` |
| `ui/screen/access/CapabilitiesScreen.kt` | `CapabilitiesScreen(onNavigate, onMessage)` |
| `ui/screen/access/CapabilityDetailScreen.kt` | `CapabilityDetailScreen(capabilityId, onNavigate, onMessage)` |
| `ui/screen/access/ToolsScreen.kt` | `ToolsScreen(onNavigate, onMessage)` |
| `ui/screen/access/PermissionsScreen.kt` | `PermissionsScreen(onNavigate, onMessage)` |
| `ui/screen/me/AppearanceScreen.kt` | `AppearanceScreen(themeMode, onThemeChange, onMessage)` |
| `ui/screen/me/ConfigManageScreen.kt` | `ConfigManageScreen(onNavigate, onMessage)` |
| `ui/screen/me/DiagnosticsScreen.kt` | `DiagnosticsScreen(onNavigate, onMessage)` |
| `ui/screen/me/LegacyScreen.kt` | `LegacyScreen(onNavigate, onMessage)` |
| `ui/screen/me/LicensesScreen.kt` | `LicensesScreen(onMessage)` |

- 占位统一用 `XiaoiPlaceholder(title, note)`，固定显示「P2 待接入」。
- 仅首页保留「打开旧版首页」入口；其余占位页没有旧版按钮。
- 现有 `MeScreen` / `LogsScreen` / `AboutScreen` 未改实现。

### 1.3 共享组件

`ui/components/` 下新增/扩展，均为无状态（参数 = 数据 + 回调）：

| 组件 | 文件 | 状态 |
| --- | --- | --- |
| `StatusHero` + `DimensionState` | `StatusHero.kt` | 为 A4 预留 |
| `DimensionBadge` + `DimensionStatus` | `DimensionBadge.kt` | 为 A4 预留 |
| `IssueRow` + `IssueUi` / `IssueSeverity` | `IssueRow.kt` | 为 A4 预留 |
| `PortRow` | `PortRow.kt` | 为 A4/A6 预留 |
| `CapabilityRow` + `CapabilityStatus` / `CapabilityRisk` | `CapabilityRow.kt` | 为 A6 预留 |
| `PermissionRow` + `PermissionStatus` | `PermissionRow.kt` | 为 A6/A7 预留 |
| `FilterChips` | `FilterChips.kt` | 为 A6 预留 |
| `SearchField` | `SearchField.kt` | 为 A6 预留 |
| `XiaoiLoadingSkeleton` | `LoadingSkeleton.kt` | 为 A4–A7 预留 |
| `XiaoiCodeBlock` | `CodeBlock.kt` | 为 A6/A7 预留 |
| `XiaoiDirtyBar` | `DirtyBar.kt` | 为 A5/A6/A7 预留 |
| `ConfirmCard` | `ConfirmCard.kt` | 为 A7 危险区预留 |
| `XiaoiConfirmDialog` | `ConfirmDialog.kt` | 为 A7 强确认预留 |
| `XiaoiTextField` | `TextField.kt` | 为 A5/A6/A7 预留 |

同时补齐 `XiaoiCard(onClick)`、`XiaoiButton(style/loading)`（`XiaoiSurfaces.kt`），
默认参数保持旧调用兼容。所有颜色/字号/尺寸只取 `XiaoiTheme` 令牌；
Card/Button/TextField/Switch/ArrowPreference/BasicComponent/TextButton/
HorizontalDivider/WindowDialog 均直接用 Miuix，不自绘替代。

**预留说明**：占位页尚未消费上述组件，A4–A7 接入时才进入真实页面；
A2 不做演示 Gallery，避免在 `MainActivity` 增加非规范路由。

### 1.4 主题

- `XiaoiTheme.kt`：仅新增 `XiaoiColors.heroOn`（映射 `design-system.md` 已有 `hero-on`），
  浅色 `#16202E` / 深色 `#E8ECF8`。
- 保持 Miuix 默认 Card/Button/AppBar 尺寸与默认深浅卡片色；
  **未**新增 `surfaceContainer` 覆盖，未改底栏自绘例外。
- `docs/design-system.md` §9 已同步该令牌说明。

## 2. 路由表（SCREENS.md §0）

| Route | 标题 | tab | 底栏 | 返回箭头 |
| --- | --- | --- | --- | --- |
| Home | 首页 | 首页 | 显示 | 无（根页） |
| Xiaoai | 小爱 | 小爱 | 显示 | 无（根页） |
| Model | 模型与服务商 | 小爱 | 隐藏 | 有 |
| Access | 接入 | 接入 | 显示 | 无（根页） |
| Bridge | 端口与服务 | 接入 | 隐藏 | 有 |
| Token | 令牌与局域网 | 接入 | 隐藏 | 有 |
| Connect | 连接信息 | 接入 | 隐藏 | 有 |
| Capabilities | 能力清单 | 接入 | 隐藏 | 有 |
| CapabilityDetail | 能力详情 | 接入 | 隐藏 | 有 |
| Tools | 工具清单 | 接入 | 隐藏 | 有 |
| Permissions | 权限 | 接入 | 隐藏 | 有 |
| Me | 我的 | 我的 | 显示 | 无（根页） |
| Appearance | 外观 | 我的 | 隐藏 | 有 |
| ConfigManage | 配置管理 | 我的 | 隐藏 | 有 |
| Diagnostics | 诊断 | 我的 | 隐藏 | 有 |
| Logs | 日志 | 我的 | 隐藏 | 有 |
| About | 关于 | 我的 | 隐藏 | 有 |
| Licenses | 开源许可 | 我的 | 隐藏 | 有 |
| Legacy | 兼容模式 | 我的 | 隐藏 | 有 |

深链白名单 = 上表 19 项；`CapabilityDetail:<id>` 只透传参数，不携 token、不执行动作。

## 3. 验收命令与结果

| 命令 | 结果 |
| --- | --- |
| `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug --offline` | 成功（仅 `Actions.java` 既有 varargs 警告） |
| `./gradlew :app:assembleRelease --offline` | 成功，已签名，APK 8.2 MB |
| `./tests/run.sh` | 全部通过（NavStack 49/49，其余用例全绿） |
| `./scripts/check-consistency.sh` | 通过（7/7） |

### 3.1 真机 smoke（`31106e85`，Android 17 / API 37）

- 安装：`adb install -r app-release.apk` → Success（版本仍 0.13.0-agent.2 / 18）。
- 路由逐条启动（`am start --es route` + `uiautomator dump`）：
  - 19/19 路由均打开，标题正确。
  - 4 个一级根页：无返回箭头，底栏可见（selected=1）。
  - 15 个二级/三级页：`content-desc="返回"` 存在，底栏三个 tab 文本 0 次出现。
- 返回链路：`Model → Xiaoai`、`CapabilityDetail → Access`、`Logs → Me`，
  返回后底栏恢复、标题正确。
- 4 tab 点击切换：首页 / 小爱 / 接入 / 我的 均正确，选中态唯一。
- 配置未变：安装前后 `config.json` SHA-256 均为
  `69129e53b9add6684090eec64316fd393cc2ca75ebcb0f893414f447cd2dc656`
  （8771 model/baseUrl/bridge/enabled 与 token 未改）。
- 主题临时切到深色截图后已恢复原值 `theme_mode=1`（浅色）。

### 3.2 截图

| 内容 | 路径 |
| --- | --- |
| 首页（浅色） | `~/xiaoi-plus-dev/xiaoi-plus-ui/tmp/2026-11-08/a2-home-light.png` |
| 二级页 Capabilities（浅色，返回箭头 + 无底栏） | `~/xiaoi-plus-dev/xiaoi-plus-ui/tmp/2026-11-08/a2-capabilities-light.png` |
| 我的（浅色） | `~/xiaoi-plus-dev/xiaoi-plus-ui/tmp/2026-11-08/a2-me-light.png` |
| 我的（深色） | `~/xiaoi-plus-dev/xiaoi-plus-ui/tmp/2026-11-08/a2-me-dark.png` |
| 二级页 Capabilities（深色） | `~/xiaoi-plus-dev/xiaoi-plus-ui/tmp/2026-11-08/a2-capabilities-dark.png` |

截图均由 `scripts/ui-screenshot.sh` 生成，未在手机留 PNG。

## 4. 已知风险 / 后续

1. **Connect 目前是整页占位**：SCREENS.md 定义为 BottomSheet，A6 接入时由
   Access 页拉起；路由保留用于调试与深链，当前底栏隐藏、返回正常。
2. **Me 的子页入口未加**：现有 `MeScreen` 按任务要求保持不变，Appearance /
   ConfigManage / Diagnostics / Legacy 在 A7 重写 Me 时补入口；当前只能通过
   `--es route` 或深链进入。
3. **组件尚未被页面消费**：上表 14 个组件仅编译验证，未做 Gallery 截图；
   视觉/交互验收在 A4–A7 首次接入时完成。
4. **CapabilityDetail 参数约定**：`CapabilityDetail:<id>` 由 A6 使用；
   `id` 不含 `/`、`?`、`#`，也不允许携带 token。
5. **Android 17 的 SharedPreferences 已迁移**：真实文件在
   `/data/misc/<uuid>/prefs/plus.xiaoi.module/xiaoi_plus.xml`，
   `/data/data/.../shared_prefs` 是遗留副本；后续脚本改主题需读写真实路径。
6. **Release APK 8.2 MB**：超过 0.14 目标 6 MB，由 A8 的 R8/shrink 处理。
