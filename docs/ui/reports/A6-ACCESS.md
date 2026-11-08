# A6 · Access + Capability 报告

> 分支：`feat/ui-access`（基于 main `3a6edab`）
> 范围：`app/src/main/java/plus/xiaoi/module/ui/screen/access/**`
> 不含：`MainActivity.kt`、`ui/components/**`、`ui/theme/**`、`core/**`、`agent/**`、`control/**`、Gradle、Manifest、`res/**`、其他页面目录。
> 验收：`assembleDebug`、`tests/run.sh`、`check-consistency.sh` 全绿；未使用 `adb install`，未做真机截图。

## 1. 页面结构

| 文件 | 内容 |
| --- | --- |
| `AccessScreen.kt` | 8771/8770 两张通道卡（端口、状态、bind、LAN）；令牌强度（NONE/WEAK/STRONG）与 8771/8770 实际监听；MCP 未开启/弱令牌回退提示；6 个入口（端口、令牌、连接、能力、工具、权限） |
| `BridgeScreen.kt` | 8771 只读详情：状态、端口、bind、LAN、最后连接/断开/上报、fresh、appliedRev、运行模型；8770：状态、端口、bind、LAN、启动时间、最后错误、动作数、事实来源；无重启/停止按钮 |
| `TokenScreen.kt` | 令牌掩码/显示/复制、生成 32 hex、强度、8771/8770 LAN 开关、回退预览、显式保存（`XiaoiDirtyBar`）、三条风险说明 |
| `ConnectScreen.kt` | 本机/局域网 URL、MCP 路径、adb forward、客户端 JSON、Skill 文本、复制；含令牌警告 |
| `CapabilitiesScreen.kt` | 67 条能力；搜索 + 域（A/B/C）+ 状态（已实现/部分可用/需授权/规划中/不提供）筛选；分组行；空结果空态 |
| `CapabilityDetailScreen.kt` | 编号、描述、来源、状态、风险、暴露给、依赖（未记录）、配置入口、最近变化（未记录）；未知编号空态 |
| `ToolsScreen.kt` | 5 个动作 + 8771 8 个工具 + 8770 6 个工具；名称、描述、风险、参数摘要 |
| `PermissionsScreen.kt` | 通知/精确闹钟/全屏/电池/usage/通知监听；状态徽章 + 去设置 Intent + 重新检测 |
| `AccessData.kt` | 配置/快照读取、令牌掩码、强度、绑定文案、状态与时间格式化 |

页面沿用路由壳的 Toolbar 与底栏，不自建第二个 Toolbar；内容区不重复大标题；所有页面底部 24dp。

## 2. 数据来源

- 配置：`ConfigRepository.get(context).loadForUi()`，附带 `isReadOnly()/loadError()/isSkeleton()`；读取在 `Dispatchers.IO`。
- 8771：`BridgeStatus.get()`，页面可见时 1.5s 轮询进程内快照；端口/绑定/LAN/连接时间均为实际上报，空值显示“未确认”。
- 8770：`AgentRuntimeStatus.get()`；`known()` 区分“从未上报”和“已知停止”，`failed()` 只用于启动失败。
- 令牌强度：`IssueInputFactory.tokenStrength()`（`BridgePolicy` 的 32 hex 规则）；LAN 生效地址用 `BridgePolicy.bindHost` / `AgentLanPolicy`。
- 能力：`CapabilityRegistry.all()`；状态用 `Descriptor.defaultStatus()` 的静态映射，不伪造运行时权限。
- 工具：`Actions.builtins()`、`Mcp.toolsList()`、`AgentMcp("0").toolsList()`；参数从 `inputSchema.properties/required` 生成。
- 权限：`PermissionProbe.capture(context)`；`UNKNOWN` 只显示“未确认”。
- 连接：`Lan.ipv4()`、`BridgeStatus.port`、`Bridge.PORT_START`、`AgentHttpServer.PORT`；JSON/Skill 由现有工具表生成。

## 3. 数量

| 项 | 数量 |
| --- | ---: |
| 能力 | 67（A 26 + B 31 + C 10） |
| 内置动作 | 5 |
| 8771 工具 | 8 |
| 8770 工具 | 6 |
| 权限项 | 6 |

## 4. 验收结果

| 命令 | 结果 |
| --- | --- |
| `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug --offline` | 成功 |
| `./tests/run.sh` | 全部通过（NavStack 49/49，其余全绿） |
| `./scripts/check-consistency.sh` | 通过（7/7） |

### 状态自查（静态走查）

| 场景 | 处理 |
| --- | --- |
| 8771 离线 / 无 leader | 状态“等待小爱”，端口“—”，bind/LAN“未确认”，不从 8770 推断 |
| 8770 停止 / 从未上报 | 已知停止显示“已停止”；从未上报显示“未确认”；有 `lastError` 显示“启动失败” |
| 弱令牌 + LAN | Access 显示“已回退仅本机”；Token 显示“实际将监听 127.0.0.1”，确认框与卡片均有警告 |
| MCP 未开启 | Access/Bridge 显示“未开启”，Connect 提示地址不可用；不生成假端口 |
| 能力筛选无结果 | `XiaoiEmptyState`“没有匹配的能力” |
| 搜索无结果 | 同上；计数显示当前 0 条 |
| 权限未授权/未确认 | `DENIED` 显示“未授权”，`UNKNOWN` 显示“未确认”；每行都有去设置入口，失败提示 |
| 配置损坏/只读 | Access/Token 显示只读卡；Token 禁止保存与开关，生成按钮禁用 |
| 未知能力编号 | 详情空态显示缺失编号，不伪造描述 |

## 5. 已知风险 / 后续

1. **未做“停用全部”**：任务给定的 A6 页面内容未包含该危险动作；它需要写配置并跨页同步，留待危险区（A7/0.15）统一做，避免在接入页引入不可回滚操作。
2. **Bridge 只读**：SCREENS §5 提到的“重启 8770”未实现，因为 A6 明确要求只读；目前通过保存配置触发重绑。
3. **8771 leader/工具数**：`BridgeStatus` 快照没有 leader 标识与工具数，页面不伪造；显示实际端口/绑定、appliedRev 与运行模型。
4. **MCP 工具风险**：8771/8770 的 `tools/list` 不含风险字段，工具清单显示“未声明”；5 个动作的风险来自 `ActionRisk`。
5. **Connect 为整页**：按任务允许的“先做整页”实现，未造第二个 Toolbar；路由保留 BottomSheet 升级空间。
6. **配置保存路径**：Token 页使用 `ConfigRepository.save()` + `AgentService.startIfEnabled()`，与旧配置页一致；未在验收中执行真实令牌轮换，不改用户配置。
7. **权限探针依赖 ROM**：部分机型 usage access / 全屏 Intent 可能长期 UNKNOWN；页面按“未确认”展示，不当作拒绝。
8. **快照非持久化**：App 进程被杀后 8771/8770 状态回到未知，页面按“未确认”显示。
