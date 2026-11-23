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
