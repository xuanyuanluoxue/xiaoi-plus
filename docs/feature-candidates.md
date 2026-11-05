# 功能候选：root 生态里哪些能力值得移植到「小爱同学+」

> 结论先行：**不要把小爱做成第二个 Thanox。**
> 我们的独占资源是「语音入口 + 常驻进程 + 已重定向的模型」，所以优先级应当是
> **入口与编排（语音 → 动作）> 单点系统能力（冻结/开关/体检）> 全局治理（权限/网络/内核）**。
> 全局治理类已经被 Thanox / Scene / Hail 做到很深，重复造轮子收益低、维护成本高。

调研范围：KernelSU Next、APatch、Magisk（含 Zygisk/Shamiko 生态）、Shizuku、Scene、
Thanox、Hail（雹）、冰箱、黑洞、App Ops，以及 LSPosed 常见模块（应用变量、阻止运行、
绿色守护、XPrivacyLua、Hide My Applist、TrickyStore、PlayIntegrityFix）。

---

## 0. 现状与约束（决定了"能不能做"）

| 维度 | 现状 | 影响 |
|---|---|---|
| 运行位置 | LSPosed 注入 `com.miui.voiceassist`，作用域只有小爱 | 只能改小爱自己的行为；跨应用要另开作用域或走 helper |
| 已有能力 | 模型重定向（`sm.a` getter）、provider 标识请求头（`sm.a.getModelProviderId`）、图片直通（`sm.f.getSupportsVision`）、本地桥 + MCP | 桥是天然的"动作入口" |
| 桥 | `GET /health /logs /cli`、`POST /mcp /config /island`，`127.0.0.1:8771~8775`，Bearer token | 已有 MCP 动作入口（7 个工具）；M1 的 `/action` 沿同一鉴权加 |
| 权限 | 模块 APK 只有 `INTERNET`；小爱是 priv-app，继承其已授予权限 | **很多系统开关不用 Shizuku**，但需实测小爱权限清单 |
| root | 设备为 KernelSU Next，`ksud` 可用 | 可做 root helper，但**不要给 voiceassist 的 UID 授权 root** |
| 签名 | 模块自带签名，与小爱无关 | 不能调用小爱的 signature 级 API，只能走公开 API/反射 |

**建议的三层落点**（后文"落点"列引用）：

```
L1 小爱进程内（LSPosed hook）   语音/文本入口、动作解析、回复改写
L2 模块 App 进程（普通应用）    通知监听、UsageStats、体检、持有 root 的执行器
L3 root helper（KSU 模块/守护） 冻结、appops、sysfs、MIUI 私有配置
```

L1→L2 用 loopback 桥（带 token）；L2→L3 用 `su -c` 白名单脚本。**永远不把原始命令透传给 root。**

---

## 1. 功能候选表（18 项）

难度＝工程量（低 <3 人日 / 中 3~10 / 高 >10）；契合度＝能否复用现有 hook 与桥，且不扩大信任边界。

### A. 语音与交互（我们的主场）

| # | 功能 | 来源软件 | 用户价值 | 难度 | 所需权限 | 落点 | 契合度 |
|---|---|---|---|---|---|---|---|
| 1 | 语音指令执行系统动作（开关/打开应用/发通知/定时） | Shizuku、Thanox 情景、小爱原生 | 不解锁、不找图标，一句话完成 | 低 | 无（部分开关需 WRITE_SECURE_SETTINGS） | L1+L3 | **高** |
| 2 | 通知摘要 + 语音追问（"谁找我"→"回了什么"） | 小爱通知播报、第三方摘要 | 开车/做饭时免看屏 | 低中 | 通知使用权 | L2+L1 | **高** |
| 3 | 语音速记 → 待办/日程/账本 | LifeOS、小爱 | 说完自动落库，不用再打开 App | 低 | 无（Calendar/备注 API） | L1+L2 | **高** |
| 4 | 语音查询本机状态（电量/存储/流量/谁在耗电） | Scene、系统设置 | 排障口语化 | 低 | 无（StatsManager 公开 API） | L1 | 高 |

### B. 应用治理（root 生态的核心区）

| # | 功能 | 来源软件 | 用户价值 | 难度 | 所需权限 | 落点 | 契合度 |
|---|---|---|---|---|---|---|---|
| 5 | 应用冻结/解冻（含锁屏自动冻结） | Thanox、Hail、冰箱、黑洞 | 省电、防后台、防链式启动 | 中 | root 或 Shizuku | L2+L3 | 中 |
| 6 | 后台/唤醒限制（Alarm、JobScheduler、后台网络） | Thanox、阻止运行、绿色守护 | 待机掉电明显下降 | 高 | root + 系统级 hook | L3 | 中低 |
| 7 | AppOps 权限模板批量管理 | App Ops、Thanox | 一次关掉全家桶的定位/剪贴板 | 中 | Shizuku 或 root | L2+L3 | 中 |
| 8 | 自启动/组件管理（receiver/service 级） | Thanox、MAT、Scene | 切断互相唤醒 | 中高 | root（含 MIUI 私有库） | L3 | 中 |
| 9 | 权限使用审计（谁在偷看摄像头/定位/麦克风） | App Ops、隐私类模块 | 事后可追溯，心里有数 | 中 | 无（UsageStats 降级）/ root 更全 | L2 | 中高 |
| 10 | 应用隔离/双开/工作资料 | Hail、黑洞、Island | 把可疑 App 关进笼子 | 高 | DevicePolicy/多用户，部分 root | L3 | 低 |

### C. 系统与性能

| # | 功能 | 来源软件 | 用户价值 | 难度 | 所需权限 | 落点 | 契合度 |
|---|---|---|---|---|---|---|---|
| 11 | 系统开关语音控制（WiFi/蓝牙/亮度/勿扰/热点/移动数据） | Shizuku、快捷方式类 | 一句话改设置，长辈也能用 | 低 | 部分需 WRITE_SECURE_SETTINGS | L1+L3 | **高** |
| 12 | 性能/温控/充电模式切换（"开游戏模式"） | Scene | 游戏帧率、发热、电池健康 | 中 | root（sysfs / MIUI powerkeeper） | L3 | 中 |
| 13 | 充电限流/旁路充电（80% 停充） | Scene、AccuBattery | 长期插电不鼓包 | 中 | root（`/sys/class/power_supply`） | L3 | 中 |
| 14 | 按应用断网（防火墙） | AFWall+、Thanox | 防偷跑流量 | 高 | root（iptables）或 VPN | L3 | 低 |
| 15 | 卡顿/耗电诊断快照（一键抓 dumpsys/logcat） | Scene、开发工具 | 出问题能留下证据 | 低 | root 可选 | L2+L3 | 中 |

### D. 安全与体检

| # | 功能 | 来源软件 | 用户价值 | 难度 | 所需权限 | 落点 | 契合度 |
|---|---|---|---|---|---|---|---|
| 16 | 安装包签名/来源体检（仿冒、被替换、侧载标记） | 安全工具、Hail | 防山寨包、防"更新后被换芯" | 低 | 无（PackageManager） | L2 | **高** |
| 17 | Root/环境隐藏、Play Integrity 绕过 | Shamiko、TrickyStore、PIF | 银行/支付可用 | 高 | root + 持续对抗 | L3 | 低（**不建议**） |
| 18 | 剪贴板/传感器拦截 | Thanox、XPrivacyLua、AppOps | 防偷读剪贴板、防后台开麦 | 中高 | 系统级 hook / root | L3 | 低 |

---

## 2. Top 5 推荐（价值 × 可行性）

### ① 语音动作引擎：一句话 → 系统动作（#1 + #11）

**为什么第一**：零新增权限就能覆盖最高频场景；其他 root 工具全都没有语音入口，这是我们唯一别人抄不走的点。

**技术路径（两层，先做 L1 再做 L2）**

- **L1 本地命令词（MVP，1~2 天）**
  - hook 小爱 ASR 最终文本回调 / skill 分发入口（8.2.65.4714 需在 `qm.*` 响应族里定位 final 回调；找不到就退回 hook `OsbotFacadeImpl` 的 query 方法）。
  - 命中 30~50 条正则（`^(打开|关闭)(wifi|蓝牙|移动数据|勿扰)$`、`^把(.+)的音量调到(\d+)`）→ 直接执行 → 合成一句回复，不再走上游模型（省时省钱）。
  - 执行面：
    - 应用：`Intent` + `FLAG_ACTIVITY_NEW_TASK`，`getLaunchIntentForPackage`
    - 亮度/音量/勿扰：`Settings.System` / `AudioManager` / `NotificationManager.setInterruptionFilter`
    - WiFi/蓝牙/数据：`svc wifi disable`、`svc bluetooth disable`（root helper，HyperOS 上 `svc` 部分受限，需实测）
    - 通知栏/返回/主页：`cmd statusbar expand-notifications`、`input keyevent 4/3`
- **L2 模型动作块（3~5 天）**
  - 在 `qm.j.buildResponsesRequest` 的 `instructions` 里追加工具协议：需要执行时输出 `<xiaoi_action>{"name":"wifi.set","args":{"on":false}}</xiaoi_action>`。
  - 在响应侧 hook 同一族的 SSE/最终回调，**整段收完再解析并剥离**该块（流式边播边解析会把 JSON 念出来）；剥离后再交给 TTS/UI。
  - `ActionRegistry` 白名单：只允许注册表内的动作，参数做类型/范围校验，危险动作（拨号、发短信、卸载、转账类 Intent）要求二次口头确认。
- **验收**：说"关掉 WiFi"，≤2s 完成且有一句口语反馈；说"打开支付宝"，冷启动 ≤4s；未识别时明确说"这个我还不会"。

### ② 通知摘要 + 语音追问（#2）

**为什么**：小爱已有播报，但"摘要 + 可追问"是模型能力带来的增量，且完全在 L1/L2 内闭环。

**技术路径**
- 模块 App 内实现 `NotificationListenerService`（独立进程，用户手动授予通知使用权），落 SQLite 保留 24h，字段：包名、标题、正文、时间、是否已读。
- 小爱侧桥新增 `GET /notify/summary?since=`；摘要请求复用**同一个 BYOK 端点**（key 已在模块私有目录，不必二次配置），提示词固定为"按重要性分 3 条，每条 ≤20 字"。
- 追问走正常对话，把摘要作为上下文注入；TTS 用系统 `TextToSpeech` 或小爱播报回调。
- 隐私：默认只处理"允许通知"的应用；银行/验证码类包名默认打码；提供一键清空。

### ③ 应用冻结（#5）— 语音 + 自动

**为什么**：root 生态最刚需的能力，且我们只需要"触发"和"编排"，执行交给成熟的 `pm`。

**技术路径**
- **不要**给 `com.miui.voiceassist` 的 UID 授 root（同 UID 的任何代码都会拿到 root）。改为：模块 App（`plus.xiaoi.module`）在 KSU 里授权 root，作为 L2 执行器。
- 命令：`su -c 'pm suspend --user 0 <pkg>'`（隐藏+不可运行，可逆）；兼容旧方案 `pm disable-user --user 0 <pkg>`。解冻对应 `unsuspend` / `enable`。
- 链路：语音 → L1 解析 → `POST http://127.0.0.1:<模块App端口>/freeze`（Bearer）→ 模块 App `su -c` → 回执 → 语音确认。
- 自动冻结：模块 App 监听 `ACTION_SCREEN_OFF`，延迟 60s 冻结"用户勾选列表"，白名单排除闹钟/IM/音乐/下载。
- 坑：`suspend` 会让桌面图标消失、Widget 失效、部分 App 收不到推送；默认只对用户显式勾选的应用生效，并在设置页写明。

### ④ 一键体检：安装包签名 + 权限审计（#16 + #9）

**为什么**：纯公开 API、零风险、建立信任；也是设置页从"配置页"变成"有用页面"的最低成本方式。

**技术路径**
- 签名：`getPackageInfo(pkg, GET_SIGNING_CERTIFICATES)` 取 SHA-256，与内置的常见包签名基线比对（基线随 APK 发布，不做联网更新；或做成可选的本地 JSON）。
- 来源：`getInstallSourceInfo()` 判断 installer（`com.android.vending` / 小米商店 / 未知侧载）。
- 权限：`AppOpsManager` 只能读自己；全量需 `cmd appops get <pkg>`（root/Shizuku），降级方案用 `UsageStatsManager` 看"最近谁在用麦克风/定位"。
- 输出：每项 0~100 分 + 三条最值得处理的建议；修复动作只做"跳到系统设置页"，不代改。
- 注意：`GET_SIGNING_CERTIFICATES` 在 API 28+；模块 minSdk 26，需分支。

### ⑤ 场景自动化（#12 + 触发器）

**为什么**：Thanox 的情景模式很强但全是 GUI 配置；语音 + 大模型可以把"配置规则"变成"说一句"。

**技术路径**
- 触发器：`BOOT_COMPLETED`、`SCREEN_ON/OFF`、`ACTION_POWER_CONNECTED`、`ConnectivityManager` 网络变化、前台应用（`UsageStatsManager`，需 `PACKAGE_USAGE_STATS`）、时间（`AlarmManager`）、语音。
- 规则：JSON 存 SharedPreferences，`{trigger, condition, action}`；动作复用 ①的 `ActionRegistry`。
- 性能/充电模式作为一类动作：`cmd power set-fixed-performance-mode-enabled true`（HyperOS 子命令需实测）、`/sys/class/power_supply/battery/charge_control_limit`（内核相关，写前先读存在性）、MIUI 的 `powerkeeper` 建议用广播而非直接改库。
- 语音建规则（第二阶段）："以后连上车载蓝牙就开热点" → 模型输出规则 JSON → 存盘 → 回读确认。

**工作量粗估**：① 4~6d（L1 1~2d）｜② 3~4d｜③ 3~5d｜④ 2d｜⑤ 5~7d。

---

## 3. 小爱 × root 的独占组合（别人没有的）

1. **语音即触发器**：所有 root 工具都是"打开 App → 找开关"，我们是"张嘴就行"。锁屏、驾驶、做饭、抱娃场景价值最大。
2. **常驻进程 + 语音唤醒 = 本地 agent 宿主**：小爱本来就有唤醒词和前台服务，天然适合承载"随时待命的动作执行器"，不用另建后台服务。
3. **特权继承**：小爱是 priv-app，已持有部分 signature/特权权限（悬浮窗、自启动、后台弹窗，部分版本含 `WRITE_SECURE_SETTINGS`）。**先实测** `dumpsys package com.miui.voiceassist | grep granted=true`，能省掉一半 Shizuku 依赖。
4. **多轮上下文编排**："把刚才那个也冻了"——小爱会话天然有上下文，桥的 `session.open` 还能跨进程复用会话；这是 GUI 工具做不到的指代消解。
5. **与 root 工具互补而非竞争**：Thanox 管规则、Scene 管调度、Hail 管冻结，我们做"入口 + 编排 + 播报"，通过 Intent/广播/helper 调它们，避免重复实现。
6. **长辈/无障碍场景**：语音帮长辈清后台、连 WiFi、调字体、开视频通话——root 工具对这类用户几乎不可用。

---

## 4. 不建议做（法律 / 风控 / 技术不可行）

| 项目 | 理由 |
|---|---|
| Root/环境隐藏、Play Integrity 绕过（Shamiko/TrickyStore/PIF 类） | 纯对抗性军备竞赛，Google 一改就失效；涉及金融 App 风控与 ToS，维护成本无上限，且与本项目"只增强自己设备"的定位冲突 |
| 伪造 IMEI/序列号/设备指纹（XPrivacyLua 类） | 多国法律明确禁止伪造唯一设备标识；需要把作用域扩到全部应用，等于把模块变成高风险框架 |
| 破解会员/去广告/解锁付费功能 | 著作权与合同风险，分发即侵权；直接违反 README 免责声明 |
| 静默安装/卸载、绕过 MIUI 安装校验 | 可被恶意利用；HyperOS 会拦截；与"最小侵入"设计原则冲突 |
| 内核级墓碑/内存压缩改造（KPM/自编译内核） | 需维护内核分支，OTA 即失效，与 HyperOS 自身内存管理打架，收益边际 |
| 全局 VPN 防火墙（#14） | Android 只允许一个 VPN，与代理/加速器冲突；iptables 方案在 HyperOS 上易被系统重置 |
| 自动抢红包/批量点击/营销自动化 | 平台风控、封号；部分场景违反服务条款 |
| 修改小爱云端协议、绕过计费/额度 | 服务端可识别并封号，法律风险最高的一类 |

---

## 5. 建议路线图

- **M1（1~2 周）**：① L1 命令词 + ④ 体检 + 桥加 `POST /action`（token + 白名单 + 审计日志）。
- **M2（2~3 周）**：③ 冻结（root helper）+ ② 通知摘要。
- **M3（3~4 周）**：① L2 模型动作块 + ⑤ 场景规则 + 语音建规则。
- 每期都保持：失败即降级、默认关闭、动作白名单、危险动作二次确认、日志不含内容只含动作名。

## 6. 待实测清单（动手前先跑一遍）

```bash
# 小爱拿到了哪些特权（决定要不要 Shizuku）
adb shell dumpsys package com.miui.voiceassist | grep -E "granted=true" | grep -E "SECURE_SETTINGS|WRITE_SETTINGS|STATUS_BAR|INJECT_EVENTS|BIND_NOTIFICATION"

# 桥实际端口
adb shell ss -ltnp | grep -E "877[0-9]"

# HyperOS 上这些命令是否可用
adb shell svc wifi disable; adb shell cmd statusbar expand-notifications
adb shell cmd power set-fixed-performance-mode-enabled true
adb shell pm suspend --user 0 com.example  # 需 root/shell

# 充电节点是否存在（不同内核名字不同）
adb shell su -c 'ls /sys/class/power_supply/battery/ | grep -E "charge|input"'
```
