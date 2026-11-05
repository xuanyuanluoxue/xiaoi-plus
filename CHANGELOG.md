# 更新日志

## 0.11.0-beta.1 — 2026-11-05

> **测试版**：工程化收敛后的第一个稳定候选。接口与配置格式仍可能在小版本内调整，
> 升级前请先看本页与 Release Notes。
>
> 交流群：QQ **1121057362**（问题反馈 / 版本适配 / 使用交流）

### 信任修复
- 主页探测同时读取运行时 `enabled`：暂停后不再显示“可用”
- 状态缓存加 10 分钟 TTL，过期回到「已启用 / 待校验」
- 主页副行区分「已确认 / 未确认（小爱未运行或 MCP 未开启）」，并提示运行时与文件模型不一致
- 「输出前缀」整卡隐藏：多进程重复注入未解决前不再暴露假功能
- 日志页：/logs 失败四分类（MCP 未开 / 无令牌 / 小爱未运行 / 读取失败），点按去配置或重试；跟随最新改为真实轮询
- 导出拆分：脱敏导出只用于分享，含密钥备份可恢复；导入检测 `***` 并阻断
- MCP 复制内容从 `Mcp.toolsList()` 单一来源生成（补 `xiaoai_stats`），端口改为实际探测

### 图标
- 全套 24dp 单色矢量：home/settings/logs/info/kernel/device/refresh/license/tag/key/model/link/chevron
- 导航改用 `setImageTintList`，品牌笑脸不再被染成色块
- launcher 改 adaptive-icon（含 monochrome）

### 工程
- 颜色令牌 32 → 19，新增 `dimens.xml`、`PageTitle` 样式
- 新增 `BridgeClient`、`AppExecutors`、`StatusResolver`、`ThemeManager`
- 构建顺序改为 aapt2 → R → javac；去除 `getIdentifier`


## 未发布 — 信任修复与图标

### 产品信任
- 主页探测同时读取运行时 `enabled`：暂停后不再显示“可用”
- 状态缓存加 10 分钟 TTL，过期回到「已启用 / 待校验」
- 主页副行区分「已确认 / 未确认（小爱未运行或 MCP 未开启）」，并提示运行时与文件模型不一致
- 「输出前缀」整卡隐藏：多进程重复注入未解决前不再暴露假功能
- 日志页：/logs 失败四分类（MCP 未开 / 无令牌 / 小爱未运行 / 读取失败），点按去配置或重试；跟随最新改为真实轮询
- 导出拆分：脱敏导出只用于分享，含密钥备份可恢复；导入检测 `***` 并阻断
- MCP 复制内容从 `Mcp.toolsList()` 单一来源生成（补 `xiaoai_stats`），端口改为实际探测

### 图标
- 导航/信息行/关于页换用单色矢量图标，导航改为 `setImageTintList`
- 新增 `ic_home/settings/logs/info/kernel/device/license/tag/refresh/chevron` 等
- launcher 改 adaptive-icon（含 monochrome），品牌图标不再兼任 24dp 导航

### 测试
- JVM 新增：脱敏文件识别、工具清单单一来源断言


## 0.10.0 — 2026-11-04

### P3b 设计系统（第二批）

- 颜色令牌 32 → 19：删除 card/card_primary/card_subtle/input_bg/divider/hairline/
  surface/surface_2(旧)/hero_*/nav_bg/pressed/stroke/icon_tile_stroke 等别名，
  统一为 `bg / surface / surface_2 / line / text* / primary* / chip* / success /
  warning / warning_bg / error / error_bg / icon_tile`
- 新增 `dimens.xml`（页面/卡片/导航/FAB/字号令牌），页面边距、导航高度、FAB 尺寸、
  卡片内边距改用令牌
- 新增 `Widget.XiaoiPlus.PageTitle`，四页标题同一写法
- 日志错误行改用 `error_bg` 令牌（去掉 Java 里的 `0x1AC43D3D`）
- 真机验证：深浅两档、四个页面、标题/卡片/导航/FAB 均正常

### P3a 设计系统（第一批）

- 主页数字砖改成两个真实指标「今日三方 / 今日官方」（28sp 单行），
  删除恒为 0 的「日志条数」
- FAB 干净态改为次按钮（浅底主色字），不再用 35% 透明导致白字不可读
- 对话框主题补齐 values-night；管理/导出统一走同一套 `Theme.XiaoiPlus.Dialog`
- 删除 `values-v31` Monet 覆盖：深浅统一品牌色，避免浅色动态色、深色静态紫
- 系统 Toast 全部换成应用内提示条 `BaseActivity.showMessage()`；门禁禁止 `Toast.makeText`
- 一致性门禁新增 Toast 检查

### P0 工程地基

- `build.sh` 顺序改为 **aapt2 → 生成 R → javac**；业务代码 22 处
  `getIdentifier` 全部改为 `R.id/R.color/R.style/R.layout`，资源改名编译期报错
- 删除无引用资源：`activity_settings.xml`、`bg_accent`、`bg_card_primary`、
  `bg_card_subtle`、`bg_chip_off`
- `AndroidManifest` 显式声明 `targetSdk=26`，Gradle IDE 元数据同步（不再一边 26 一边 35）
- 新增 `scripts/check-consistency.sh`（版本/SDK/R/死资源），接入 `check-all` 与 `pre-push`

### P2 数据通路统一

- 新增 `BridgeClient`（纯 JVM）：端口发现（认 `port=`）、鉴权头、MCP 取文本、
  logs/stats/island 全部收敛；Home/Logs/ConfigSync 不再各写 HttpURLConnection
- 新增 `AppExecutors`：App 进程统一 3 个 IO 线程 + 主线程回调，替换 12 处裸 `new Thread`
- 修复 `ConfigRepository.pushAsync` 创建线程但从未 `start()` 的静默 bug
- 一致性门禁新增：禁止 App 层裸 `new Thread`、禁止额外的 HTTP 客户端
- 新增 `BridgeClientTest` 13 断言

### P1 主题单源

- 新增 `ThemeManager` / `ThemeMode`：主题只存 App prefs，`config.json` 的
  `themeMode` 读时忽略、写时不再输出（消除双源）
- `BaseActivity.onResume` 比对实际生效模式，不一致就 `recreate()`；
  被 `REORDER_TO_FRONT` 复用的旧页面也会立即切换，不再一页深一页浅
- `ConfigSync` / `save()` 不再回写主题；切主题不再需要点保存
- 配置页切主题时未保存的编辑随 Bundle 恢复，不再静默丢弃
- 新增 `ThemeModeTest`（11 断言）；JVM 总计 **353 断言**


## 0.9.0 — 2026-11-04

### 调用量统计（三方 / 官方）

- 在**真实模型客户端**上计数，一次模型调用记一次：`qm.i` OpenAIClient、
  `qm.j` ResponsesClient、`qm.b` AnthropicClient → 三方；`um.h` TaiyiLlmClient
  （mimo / 本地 AI）→ 官方。不挂 `qm.c`（DynLlmClient）与 `ft.j`（埋点包装），
  避免同一次调用被记两次
- 主对话才计入使用量（带 tools，或消息数 ≥6）；记忆抽取、意图分类、标题生成等
  子调用单独记在 `sub` 里，避免一次闲聊被算成三五次
- 账本是小爱进程 `files/xiaoi_usage.log` 的追加日志（一行一次调用，O_APPEND 跨进程
  安全），进程重启不丢、7 天后自动清理；App 读不到小爱私有目录，统一经桥取数
- 新增 `GET /stats?days=1..90`（Bearer 鉴权）与 MCP 工具 `xiaoai_stats`
- 主页「今日调用 · 三方/官方」显示当天两个数字（经桥实时取数），不再显示日志条数

### 暂停语义修复

- 此前 `enabled=false` 只停掉标记注入，`sm.a` 的 baseUrl / apiKey / model getter
  仍在覆盖，实际仍走三方；现在暂停时不改写这三个 getter，请求回到小爱原生模型
  （与配置页「关闭后小爱使用原生模型」的承诺一致）

### 测试

- 新增 `UsageCounterTest`（33 断言）：主对话判据、行编码/清洗、按天聚合、窗口过滤、
  超期清理；JVM 总计 **342 断言** 全绿
- 契约新增 `/stats` 鉴权与形状、`xiaoai_stats` 可调用，工具数 7 → 8

## 0.8.1 — 2026-11-04

### config.json 成为事实源（文件优先）

- 新增 `ConfigSync`：App 进程内 FileObserver 监听 `Android/data/plus.xiaoi.module/files/config.json`，
  改动 1~2 秒内自动应用（写 prefs 镜像 → 推小爱）。直接编辑文件即生效，不再依赖点保存
- 启动引导：config.json 缺失或仍是默认骨架（无密钥）时，自动从运行中小爱导入真实
  model/baseUrl 生成，**不再用默认值覆盖运行时**
- 运行时变更（MCP switch_model 等）不自动写回文件；配置页提供「从运行中小爱导入」
  手动回流，文件永远优先、不被静默覆盖
- 硬保护：**无密钥的配置绝不推送**，从机制上根除「保存冲掉生效配置」

### 显式 enabled 字段（暂停不再清空配置）

- 配置新增 `enabled`：暂停 = enabled=false（保留 model/baseUrl/apiKey），恢复 = true
- 推送协议、prefs、MCP `xiaoai_config`/`mergeConfig` 全链路贯通
- 测试：JVM 188 断言（含 enabled 合并与黄金串）

### 模型选择重写

- 修复 v2 配置页无「添加模型」入口、新服务商模型列表永远为空的根问题
- 选择对话框（含显示名）/ 添加模型（ID+显示名、去重）/ 管理对话框点选删除
- 删除当前激活模型自动回退列表首项

### 输出前缀（区分三方模型是否生效）

- 配置新增 `markerTemplate` 模板（`{model}` 占位符），配置页新增开关与输入框
- 响应侧注入已逆向定位并跑通（hook `sm.b$c/d` 构造器改写 delta，前缀必然出现在
  回复开头），但小爱多进程架构下同一条回复会被多次注入（实测 2~5 次），
  **暂禁用**，代码保留待单点 hook 方案；模板写入后模型会从历史学到前缀格式
- 逆向结论：`qm.j`/`qm.i` 的 build\*Request 返回 kotlinx JsonObject（非 org.json），
  直接 cast 会 CCE 被吞

### 界面修复

- 底部导航单源化（`nav_bar.xml` 共享布局 + `BaseActivity.setupNav`），
  修复跨页图标/文字乱切与配置页导航缺图标
- 配置页重写：修深色输入框白底、导航裁切、脏标记误报、外观卡运行时注入
- 主页 Hero 重设计：去「能力桥/重定向」chips，新增状态副行
  「模型重定向 · <小爱侧真实生效模型>」；状态改「已激活/未激活」
- MCP 板块去「能力桥」叫法，新增终端风格 JSON 字段速览（endpoint/auth/
  protocol/model/redirect/tools/bind，token 只显示是否设置）

### 模型校验（真实探针）

- 新增 `ModelValidator`（纯 JVM，可桌面单测）：向 baseUrl 发 max_tokens=1 的
  chat/completions，区分 可用 / 密钥无效 / 模型不存在 / 端点不可达 / 超时 / 非兼容端点
- 配置页「模型状态」行：页面打开、文件变更、保存、导入后自动校验（30s 节流），
  可点按手动重试；结果防陈旧（序列号只采纳最新一次）
- JVM 测试 +12 断言（本地 HTTP 桩覆盖 200/401/404/400/非 JSON/不可达/空参数）

### 配置文件与 UI 同步

- 外部编辑 `config.json` 后，配置页自动重载重绘并重新校验（此前需退出重进）

### P2 同步内核

- 推送改为「认端口 + 全端口聚合」：新增纯 JVM `BridgePusher`，`/health` 必须含
  `port=` 才认领（8770 冒名服务会被跳过），对每个已知桥 POST /config 并聚合结果
- 无密钥时按字段推送（marker/markerTemplate/bridge/token）：改前缀、开关、暂停
  不再被“密钥为空”整体拦掉
- 冲突基线与 onResume 兜底、推送失败记 `sync_pending` 下次启动重试、监听器注销
- 测试 +51（BridgePusherTest：正常桥/冒名/401/不可达/部分成功/载荷分组）

### P3 配置页 UI 重排

- 状态条 7 状态（未配置/待校验/校验中/可用/密钥无效/模型不存在/端点不可达/已暂停）
- 模型卡改 chips：服务商/模型点击即选；「管理」对话框统一 选/重命名/删模型/删服务商
- 保存显示真实推送结果（已推送 · N 个进程 / 失败原因 + 重试），不再无条件成功
- 冲突条：外部改文件且有未保存输入时提示，点按才载入；enabled=false 整体屏蔽点击
- MCP 终端 JSON 保留并标明「文件里的期望配置」

### P4 导入导出与密钥边界

- 导出默认脱敏，含密钥需二次确认；分享默认脱敏
- 导入走 ConfigCodec 校验，非法配置不覆盖当前编辑状态

### P1 数据层定稿

- 抽出 `ConfigCodec`（纯 JVM）：parse / toJson / normalize / validate / defaults，
  `ConfigRepository` 只保留 IO 与副作用
- 补上此前缺失的语义：悬空 activeProviderId/activeModelId 自动修正、
  字段去空白、模型去重、schema 版本保留、未知顶层字段原样写回
- 校验新增防呆：暂停（enabled=false）合法；「密钥误填成 MCP Token」明确报错
- 配置层测试从 0 到 53 断言（配置层总断言 253）；用真机 config.json 验证幂等

### P0 收口

- 修升级丢密钥：`ConfigRepository.defaults()` 从旧 prefs 回填端点/模型/密钥
- 修前缀重启失效：`Bridge.writeTargetPrefs()` 补写 `marker_tpl`（此前漏写，
  推送后当前进程有效、小爱重启后失效）
- 删除死代码 `SettingsActivity`（572 行、第三份 prefs 写入逻辑）及清单声明
- 文档纠偏：`ui-architecture.md` 去掉不存在的「打开配置文件/默认脱敏」，
  README 说明 Android 13+ 文件管理器可能看不到 `Android/data`

### 契约测试

- 端口发现改为 `/health` 自证身份（8770 被其它服务占用且会返回
  `ok facade=true`，此前会误拨）；基线更新（含 enabled 字段）

## 0.8.0 — 2026-11-03

> 地基版本：无新面向用户的功能，聚焦可测试性与一处真实 bug 修复。

### 修复：token 轮换死锁

- 症状：在设置页更换「能力桥 Token」后，推送永远 401 且静默失败；小爱 prefs 里
  留着旧 token，重启也无解——包括 MCP 客户端在内全部被锁在门外。
- 根因（两半）：`/config` 只认内存里的旧 token；且全量替换语义要求重发整份配置，
  而推送端只有新 token 和它自己那份（可能为默认值的）配置。
- 修复：`/config` 鉴权放宽为「内存 token 或模块 prefs 当前 token」匹配其一；
  `POST /config` 改为合并语义——**缺失的字段保持当前值**，换 token 只需推
  `{"token":"..."}`，不会再把小爱侧正在生效的模型配置冲掉。
- 兼容性：v1 设置页 / v2 配置页都全量发送全部字段，行为不变。

### 重构：能力桥拆出纯 JVM 层（行为不变）

- 新增 `Json`（自写 JSON 解析/写出，紧凑输出与 org.json 逐字节一致）、
  `Http`（HTTP 线协议 + Bearer 匹配）、`Mcp`（JSON-RPC 2.0 / MCP 工具表/工具执行，
  通过 `Mcp.Host` 接口隔离 Android 依赖）。
- `Bridge.java` 从 1071 行降到约 700 行，只剩 Android 接线（Xposed hook、
  CLI binder 反射、prefs 写入、socket 生命周期、鉴权策略）。
- `Logs` 去掉 org.json 依赖、Context 改反射判定，可进桌面测试。

### 测试地基（从 0 到 1）

- `tests/run.sh`：零依赖桌面测试（javac + java，无 JUnit / 无 Gradle / 无 android.jar），
  覆盖 Json / Http / Mcp / Logs 共 186 个断言，含 JSON-RPC 黄金串（逐字节锁定 v0.7 输出）。
- `scripts/contract-test.sh`：真机契约测试一条命令化（重启小爱 → 日志断言 →
  端口发现 → forward → 免鉴权/鉴权/MCP 全链路），支持 `--save-baseline` / `--check`
  基线对比（归一化版本/端口等噪声，屏蔽 baseUrl 等隐私值）。
- CI（build.yml）新增 JVM 测试步骤，先测试后构建。

### 文档对齐

- 版本号唯一来源改为明确指向 `AndroidManifest.xml`（`scripts/version.sh` /
  `release.sh` / `docs/release.md` 原先指向 build.gradle.kts，照做不生效）；
  `app/build.gradle.kts` 降级为 IDE 元数据壳并加文件头说明。
- CHANGELOG 补 0.7.0；integration.md 工具表补齐 7 个 MCP 工具；
  architecture/feature-candidates 同步 v0.5+ 的请求头标识语义与桥端点现状。

## 0.7.0 — 2026-11-03

### 设置页重构为四页结构

- 四页导航（Home / Config / Settings / Logs）+ 固定底部导航，About 独立成页
- 深色三档：跟随系统 / 浅色 / 深色，`values-night` 全量补齐；主题入口与 BaseActivity 修正
- 配置仓库 v2（`ConfigRepository`）：设置页与主页共用同一读写路径，预填与回显一致
- 设置页按 PM 方案重排分组；命名统一为「小爱同学plus」
- 主页新增版本动态显示、设备信息卡、最后错误行、桥端口 chip
- 修复：日志页崩溃、日志滚动 id、导航图标不统一
- 文档：新增远程调试指南（`docs/remote-debug.md`）与文档索引（`docs/README.md`）

## 0.6.0 — 2026-11-03

### 超级岛单次提示

- 逆向 `gp1/c`（FocusNotificationV3）与 `om/s`（焦点通知）：channel `super_island`、
  extras `miui.focus.param`（`{"param_v2":{...}}`）与 `miui.focus.pics`
- 新增 `Island.showOnce(model)`：首次走第三方模型时发一次焦点通知，6 秒后自动消失
- 新增 MCP 工具 `xiaoai_island`（`force` 可重复触发，便于验证）
- 已确认：SystemUI 收到完整 focusExtra（NotifySyncService 日志可见），通知正常投递、
  显示为「小爱+ / 第三方模型 …」卡片
- 未达成：HyperOS 的 `DynamicIslandEventCoordinator` 对当前 payload 报 `checkError`，
  未折叠成胶囊岛——缺少小爱自身的 RemoteViews（`miui.focus.rv*`）布局；下一步可复用其
  `om.t.i.layout_island_task`

## 0.5.0 — 2026-11-03

### 模型标识改走请求头（不再注入提示词）

- 移除 `instructions` / system 提示里的 `[model=...]` 规则：不再占上下文、不再依赖模型服从
- 改为 hook `sm.a.getModelProviderId()`：小爱原生的 `X-Model-Provider-Id` 头写成 `xiaoi-plus`
- 请求体只保留上游需要的 `model` 字段；标识留在传输层，模型完全看不到
- 真机验证：`模型标识已改走请求头 X-Model-Provider-Id=xiaoi-plus`；回复正文无前缀
- 注意：旧会话里模型可能模仿历史回复的前缀，这是上下文残留，不是模块注入

### 移除

- `applyMarkerResponses` / `applyMarkerChat`：不再改写请求体
- 请求 hook 保留为空实现，作为后续图文分流的接入点

## 0.4.0 — 2026-11-03

### 运行日志

- 新增 `Logs`：300 条内存环形缓冲 + logcat + 滚动落盘 `files/xiaoi_plus.log`（256KB 上限，保留 `.1`）
- 只记录 hook 成败、模型名、端口、调用摘要；**不记录 apiKey / token / 对话正文**
- MCP 新增 `xiaoai_logs(limit, since)`，可按 seq 增量拉

### MCP 配置与切模

- 新增 `xiaoai_config`：读当前生效的 model / baseUrl / marker / bridge / 密钥是否已设置
- 新增 `xiaoai_switch_model`：热切换模型，可只传 `model` 复用现有端点与 key；也可一并改 baseUrl/apiKey
- 目标：日常运维不再打开模块设置页或 LSPosed 管理器

### 跨进程配置热读（修 bug）

- `Config.current()`：800ms 节流 + prefs mtime 检查，任一进程改 prefs，其他进程 1 秒内跟上
- 此前 `POST /config` 只热更新承载桥的那个进程，首发回复仍带旧模型标记；现在切换无需重启小爱

## 0.3.0 — 2026-11-03

### 图片理解

- hook `sm.f.getSupportsVision()` 恒为 `true`：模型名不在小爱已知视觉白名单时，截图/图片不再被替换成「当前模型不支持查看图片」占位文本
- 图文改走第三方模型（实测支持视觉），不再回退原模型——回退只换 model 名、端点仍是第三方，会 404

### MCP 端点

- 能力桥新增 `POST /mcp`：标准 MCP Streamable HTTP（协议 `2025-03-26`）、JSON-RPC 2.0、无会话
- 工具：`xiaoai_status`、`xiaoai_submit`（自动 `session.open`）、`xiaoai_cli`
- 与 `/cli` 共用 Bearer token 与 loopback 限制；`GET /mcp` 返回 405
- 桥线程池 4 → 8，避免 `agent.submit` 等 LLM 回复时互相饿死

### 其他

- `chat/completions` 链路同样注入 `[model=<id>]` 标记规则（此前只有 Responses 链路）

## 0.2.0 — 2026-11-03

- 设置页改为 Shizuku 风格 UI + Monet 动态取色 + 配置预填
- 版本号改为从 AndroidManifest 读取（CI 与 release 脚本统一）

## 0.1.0 — 2026-11-03

首个可安装的预览版。0.x 阶段接口与 hook 目标都可能变化，升级前请看 Release Notes。

### 模型重定向

- hook 小爱配置对象的 `getBaseUrl` / `getApiKey`，把主对话请求指向自建 OpenAI 兼容端点（BYOK）
- 「端点」和「模型名」都非空才启用；任一为空时记录「配置为空」并跳过，小爱保持原行为
- 仅对兼容性矩阵中已验证的小爱版本生效（当前 `8.2.65.4714`），其他版本直接 no-op，避免升级后 bootloop

### 输出标记

- 向 Responses API 请求的 `instructions` 追加格式规则，回复开头显示 `[model=<id>]`
- 设置页可开关，默认开启；只影响提示词，不改上游返回内容
- 标记用于一眼确认请求确实走了自己的端点

### 本地能力桥（实验性，默认关闭）

- 在小爱进程内监听 `127.0.0.1:8770`，提供 `GET /health` 与 `/cli` 命令通道
- Bearer token 鉴权；token 留空时拒绝除首次配置外的所有请求
- 只绑定回环地址，不对局域网或公网暴露；需要用户显式打开

### 配置推送

- 设置页保存后，通过环回 `POST /config` 把配置推送给正在运行的小爱进程
- 小爱收到后热更新内存配置并幂等重装 hook，无需强制停止再打开
- 小爱未运行或模块未启用时只写本地，下次启动读取；设置页给出明确提示
- 首次配置允许在小爱尚未持有 token 时从 loopback 推送，之后必须带 Bearer

### 稳定性

- 所有 hook 均用 try/catch 隔离，异常只写日志，不抛回小爱主流程
- 未命中白名单、配置缺失、桥启动失败都按降级处理
