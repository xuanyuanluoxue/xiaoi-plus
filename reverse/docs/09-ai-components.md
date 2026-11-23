# 09 澎湃 AI 与 AppTool 生态：谁在实现什么

> 证据级别：实测（`dumpsys package` / `cmd package query-services` / 逐条点击设置页 /
> 各 APK 的 `assets/app_tools.json`）｜设备：REDMI K90 Ultra，8.2.70.8421 同代系统｜
> 复现：`python3 reverse/scripts/survey-apptools.py`

## 1. 设置页「小米澎湃 AI」12 项 → 实现组件

`设置 → 小米澎湃AI` 是 `com.android.settings/.SubSettings`，条目本身只是入口，
真正的实现分散在 8 个应用里（逐条点击后取 `topResumedActivity`）：

| 条目 | 设置入口（Activity） | 实现包 |
|---|---|---|
| 超级小爱 | `com.miui.voiceassist…settings.MiuiVoiceSettingActivity` | `com.miui.voiceassist` |
| 全局 AI 帮写 | `com.xiaomi.aicr…GlobalAiWritingActivity` | `com.xiaomi.aicr` |
| AI 写作 | `com.miui.notes…miui.ai.text.widget.AISettingActivity` | `com.miui.notes` |
| AI 服务 | `com.xiaomi.aiservice…ServiceSettingsActivity` | `com.xiaomi.aiservice` |
| AI 壁纸 | `com.android.thememanager…util.ai.AIFromSettingsActivity` | `com.android.thememanager` |
| AI 相册编辑 | `com.miui.mediaeditor…settings_function.SettingsFunctionActivity` | `com.miui.mediaeditor` |
| AI 识音 | `com.android.soundrecorder`.SettingsFunctionActivity | `com.android.soundrecorder` |
| AI 对话翻译 | `com.xiaomi.aiasst.vision…VoiceTransPreferenceSettingActivity` | `com.xiaomi.aiasst.vision` |
| AI 通话翻译 | `com.xiaomi.aiasst.vision…translate.VoiceTranslateSettings` | `com.xiaomi.aiasst.vision` |
| AI 搜索 | `com.xiaomi.aicr.aisearch.AiSearchSettingActivity` | `com.xiaomi.aicr` |
| AI 反诈 | `com.hicorenational.antifraud.ui.activity.WelcomeActivity` | 国家反诈中心（第三方 App） |
| AI 实时字幕 | `com.xiaomi.aiasst.vision…SubtitlesPreferenceSettingActivity` | `com.xiaomi.aiasst.vision` |

**入口 ≠ 实现**：只有「超级小爱」是小爱本体，其余是别的系统 App 的 AI 功能，
只是被聚到同一个设置页。

## 2. `com.xiaomi.aiservice` —— 端侧 AI 能力运行时

`dumpsys package com.xiaomi.aiservice`：**55 个 Service**，全部由同一个签名权限保护：

```
com.xiaomi.aiservice.permission.AI_ABILITY      ← 声明为 signature
```

按能力归类（服务名 → 用途）：

| 组 | 服务 | 用途 |
|---|---|---|
| LLM 推理 | `llmprocess.LlmService` / `VlmService` / `MimoVlmService` / `CloudApiService` / `LLMProcessService` / `IntentService`；`llmmanager.LLMManagerService`；`aios…AppLLMService` | 端侧大模型 / 多模态 / 云端兜底 / 意图 |
| 语音 | `multiprocess.AsrService` / `Asrv2Service` / `TtsService` | 识别 / 合成 |
| 文本 | `NluService` / `NluIntentService` / `NluIntentImeService` / `NerService` / `Ner_travelService` / `SummaryService` / `WpsService` / `SegmentService` / `LacService` / `TagService` | 语义、实体、摘要、分词 |
| 视觉 | `OcrService` / `OcrExpService` / `CardRecognizeService` / `IqaService` / `SiglipService` / `ClipService` / `NsfwService` / `AestheticService` / `TimelineAlbumService` / `FaceClusterService` / `IconIsrService` / `VideoSegmentService` / `SimilarityClusterService` / `AutoCropService` / `PetService` | 识别、构图评分、相册整理 |
| 生成 | `CreationService` / `AISearchService` | 帮写、搜索 |
| 运维 | `DownloadService` / `ConfigDeliveryService` / `ScheduleService` / `QualityService` / `SmAuditorService` / `PoweraiInactivityPredictService` / `Stub0-2Service` | 模型下载、配置下发、调度、质量 |

结论：**澎湃 AI 的「算力」几乎都在这里**，各 App（笔记/相册/录音机/翻译/小爱）
通过 binder 调它。它是端侧能力，不对外暴露，第三方 App 拿不到 `AI_ABILITY`。

## 3. App 侧的真实组件

| 包 | 关键组件 | 说明 |
|---|---|---|
| `com.xiaomi.aicr` | `access.AiCrCoreService`、`AiCrCognitionService`、`searchpro.AiCrSearchDataBaseService`、`llm.LLMService`（权限 `OPEN_CAPABILITY_INTERFACE`）、`apptools.tools.AIEngineToolService` | AI 帮写 / AI 搜索 / 能力编排；同时是 AppTool provider |
| `com.xiaomi.aiasst.vision` | `engine.offline…TranslateService`、`control.translation.AiTranslateService`、`sdk.TranslateSdkService`（权限 `xiaomi.permission.aivision.MI_AI_TRANSLATE`）、`SimultaneousInterpretationService`、`TransparentService`（权限 `…ACCESS_SI`）、`ModelDownloadService` | 对话翻译 / 通话翻译 / 实时字幕 + 对外翻译 SDK |
| `com.miui.notes` | `agent.tool.provider.NoteToolService`（26 工具）、`action.provider.AiActionProvider`（`hyperos.action.AIACTION_PROVIDER`）、AppFunctions | AI 写作 + 笔记/待办全量工具 |
| `com.android.soundrecorder` | `SoundRecorderToolService`（4 工具，权限 `BIND_APP_TOOL`）、`RecorderService`（权限 `miui.permission.RECORD_AUDIO`） | AI 识音 / 转录 |
| `com.miui.gallery` | `com.aios.apptoolsdk.service.AppToolProviderService`（11 工具） | 相册搜索 / 评估 / 整理 |
| `com.miui.mediaeditor` | `service.EditorService`、`render.BackgroundRenderService`、`aigc.download.NotificationService` | 相册编辑 / AIGC 下载 |
| `com.android.thememanager` | `aiicon.MiuiModIconService`、`depthvideo…DepthVideoDownloadService`、`ThemeService` | AI 壁纸 / 图标 |
| `com.hicorenational.antifraud` | 无 AI 组件（只有 Umeng/HMS/OPPO 推送等 SDK） | 第三方反诈 App，与系统 AI 无关 |

## 4. AppTool 生态：17 个 provider / 112 个工具

AppTool 是 HyperOS 的「应用能力对 Agent 开放」标准通道，机制固定：

```text
action   com.aios.osbot.action.APP_TOOL_PROVIDER
声明     APK 内 assets/app_tools.json（name / method / class / paramCount / params[index]）
协议     IAppToolProvider#handleRequest(JSON-RPC: {"method":"tools/call","params":{"name","arguments"}})
权限     com.aios.osbot.permission.BIND_APP_TOOL（签名级，系统应用与 Agent 持有）
```

设备上全部实现者（`cmd package query-services -a com.aios.osbot.action.APP_TOOL_PROVIDER`）：

| 包 | 服务 | 工具数 | 代表工具 |
|---|---|---|---|
| `com.miui.voiceassist` | `UIAgentServiceForOSBot` | **18** | `nlp_query`/`write_memory`/`read_memory`/`trans_text`/`get_screen_context`… |
| `com.miui.notes` | `NoteToolService` | **26** | `note_*`/`card_*`/`folder_*`/`todo_create`/`podcast_search` |
| `com.miui.gallery` | `AppToolProviderService` | **11** | `gallery_search`/`gallery_assess`/`gallery_manage_story` |
| `com.android.calendar` | `CalendarToolService` | 9 | `create_event`/`query_events`/`query_legal_holidays` |
| `com.android.deskclock` | `AlarmToolService` | 9 | `deskclock_*alarm`/`*timer` |
| `com.milink.service` | `MiLinkToolService` | 8 | 投屏、跨端流转、电视遥控、跨端解锁 |
| `com.miui.bugreport` | `BugReportToolService` | 6 | `submit_feedback`/`get_feedback_list` |
| `com.android.server.telecom` | `TelecomToolService` | 5 | `phone_make_call`/`phone_call_action`/`phone_set_mute` |
| `com.android.phone` | `PhoneToolService` | 4 | `phone_settings`/`phone_call_recording` |
| `com.android.soundrecorder` | `SoundRecorderToolService` | 4 | `manage_recording`/`get_transcription`/`search_recordings` |
| `com.mipay.wallet` | `WalletAppToolProviderService` | 3 | `record_bill`/`get_bookkeeping_categories` |
| `com.miui.weather2` | `AppToolProviderService` | 3 | `weather_get_city_weather`/`weather_get_city_alert` |
| `com.xiaomi.scanner` | `UIAgentServiceForOSBot` | 2 | `scan_qr_image`/`open_scan` |
| `com.miui.home` | `LauncherToolService` | 1 | `home_settings` |
| `com.miui.mishare.connectivity` | `MiShareToolService` | 1 | `manage_file_transfer` |
| `com.miui.cleanmaster` | `CleanerToolService` | 1 | `manage_cleaner` |
| `com.xiaomi.aicr` | `AIEngineToolService` | 1 | `get_support_event_detail` |

合计 **17 个 provider / 112 个工具**（本仓库 `reverse/scripts/survey-apptools.py` 可一键复现）。

## 5. 对 8771 MCP 的意义

1. **我们现在只暴露了 112 个里的 18 个**（小爱自己的）。剩下 94 个同协议、同 AIDL，
   差别只是「provider 在别的包」。
2. 跨包 bind 的门槛是签名权限 `BIND_APP_TOOL`。我们的代码跑在 voiceassist 进程里，
   binder 调用方 uid 就是小爱，**权限天然满足**（与 `todo_create` 走 `com.miui.notes`
   是同一条路——那条已经真机跑通）。所以「把录音转录、日历、闹钟、天气也变成
   MCP 工具」在技术上是同构的，不需要 root，也不需要新权限。
3. 需要设计的是**语义边界**：这些工具会改系统状态（建日程、设闹钟、清理垃圾、记账、
   发文件）。建议沿用现有做法——只读工具默认开放，写类工具走确认 + 审计。
4. `com.xiaomi.aiservice` 的能力（ASR/OCR/NLU/LLM）**没有** AppTool 入口，
   只能由各 App 内部调用；要从外部用，只能借道小爱的 18 个工具（`trans_text`、
   `image_to_text`、`text_to_image`、`get_screen_context` 就是它的薄封装）。

## 6. 能力侦测（怎么确认设备上有哪些实现）

> 用途：换机、刷版本、疑似「功能被砍」时，5 分钟内确认 provider 还在不在、是否被禁用、
> 入口是不是只是被隐藏。设备串号以 `192.168.0.118:5555` 为例（见 `phone-use-skill`）。
> 每条都标了证据级别；标「推测」的只给了验证方法，没有本仓库的原始输出。

### 6.1 列出全部 AppTool provider

```bash
adb -s 192.168.0.118:5555 shell cmd package query-services --brief \
  -a com.aios.osbot.action.APP_TOOL_PROVIDER
```

- 判读：每行 `<包>/<服务>`，**期望 17 行**。少一行 = 该包升级后不再声明。
  （包被禁用时是否仍出现在这个列表里，本仓库未验证；先按 6.2 交叉确认。）
- 工具级枚举：`python3 reverse/scripts/survey-apptools.py --json`，末尾应打印
  `合计 17 个 provider / 112 个工具`（脚本 `providers()` 用的就是上面这条命令）。
- 证据：**实测**——§4 的 17 / 112 即由这两个命令产生。

### 6.2 看某个 provider 的版本、安装状态、是否被禁用

| 目的 | 命令 | 判读 | 证据 |
|---|---|---|---|
| APK 路径 | `pm path com.miui.notes` | 输出 `package:/…apk`；空 = 未安装或未对当前用户安装 | **实测**（`survey-apptools.py` 用它定位 APK 读 assets） |
| 版本 | `dumpsys package com.miui.notes` → `versionName` / `versionCode` | 与本文的 8.2.70.8421 比对；变了就重跑 survey | **实测**（本篇版本号即取自此） |
| 是否禁用 | `pm list packages -d` | 包名出现在列表 = 已禁用，其 AppTool 服务随之不可用 | **推测**（AOSP 标准行为，本仓库未留原始输出） |
| 用户级状态 | `dumpsys package com.miui.notes` → `User 0:` 段 | `enabled=0` 默认 / `enabled=3` 已禁用 / `installed=false` 未装 | **推测** |
| 服务与其权限 | `dumpsys package com.miui.notes` → 搜 `NoteToolService` | 应能看到服务条目及 `permission`，含 `com.aios.osbot.permission.BIND_APP_TOOL` | **静态**（§4 权限行来自 manifest） |

Windows 下把管道换成 `Select-String`，例如
`adb -s … shell dumpsys package com.miui.notes | Select-String 'versionName|User 0:|enabled='`。

### 6.3 判断某个 AI 入口是否可用

设置页条目是 `com.android.settings/.SubSettings` 的跳转项，可见性由 Settings 读的开关控制（**推测**）。
三种现象，从外到内：

| 现象 | 说明 | 怎么确认 | 证据 |
|---|---|---|---|
| 条目直接消失 | 入口被开关隐藏，能力可能还在 | 用 §1 表里的 Activity 直接 `am start -n …`；能起来 = 只是隐藏 | **推测** |
| 条目在、点进去空白或全灰 | 依赖的模型/服务没就绪（如端侧模型未下载） | 起 Activity 后 `dumpsys activity top` 看最终落到哪个页面 | **推测** |
| 条目在且能开 | 正常 | 逐条点击、取 `topResumedActivity`（§1 的做法） | **实测** |

- 找开关键：`adb shell settings list global | Select-String -i 'ai|assist'`，`settings list secure` 同理。
  键名本仓库**未确认**，不要照抄；先用 `dumpsys activity top` 验证实际行为再回填。
- §1 表里被省略号截断的类名，用 `adb shell dumpsys package com.miui.voiceassist | Select-String MiuiVoiceSettingActivity`
  取全名，再 `am start -n 包/全类名`。
- `am start` 报 `Activity class {…} does not exist` → 该版本真的没有这个组件，不是被隐藏。
- 反向提醒：设置页 12 项在这台设备上都在（§1 实测），但 `10-capability-map.md` §5.2 那 13 个包
  （日历/闹钟/相册/天气/钱包/电话…）在设置页**没有任何 AI 入口**。
  「设置页没有」不等于「设备没有」，更不等于「Agent 不能用」。

### 6.4 换版后的回归清单

1. 重跑 `survey-apptools.py`，与基线 **17 / 112** 比；工具名有增删就更新 §4 与本目录。
2. 记录 `assets/app_tools.json` 的摘要，便于判断是「工具没变」还是「只是没重新统计」。
3. `paramCount` 变化会让 `docs/agent/PLAN-APPTOOLS.md` 的调用示例失效——先看 `params` 再复用参数。
4. 证据：**推测**（流程建议，无实测记录）。
