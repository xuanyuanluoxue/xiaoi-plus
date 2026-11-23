# AppTool 接入清单（8771 MCP）

> 依据：`reverse/docs/09-ai-components.md`；工具与参数取自各 APK 的 `assets/app_tools.json`
> （`python3 reverse/scripts/survey-apptools.py --json`）。
> 现状：8771 只暴露了小爱自己的 18 个（`ToolOwnership.BRIDGE`）。

## 1. 接入形态（建议）

不逐个硬编 94 个工具（会把 `tools/list` 撑爆，且各 App 的工具会随版本变），改成两层：

| 新工具 | 参数 | 作用 |
|---|---|---|
| `xiaoai_apptools` | `app`（可选，别名或包名） | 列出 provider 与工具（名称/参数摘要/风险级），供外部 Agent 发现 |
| `xiaoai_apptool` | `app`、`tool`、`args`（对象）、`confirm`（写类必填） | 通用调用，走「别名 → 包/Service」映射表 + 白名单 + 风险分级 |
| `xiaoai_transcript` | `q`（可选）、`limit` | 便捷封装：录音转写/搜索（高频、纯读） |
| `xiaoai_events` | `start`、`end`、`keyword` | 便捷封装：日程查询 |
| `xiaoai_alarms` | 无 | 便捷封装：闹钟列表 |
| `xiaoai_weather` | `city`、`alert`（可选） | 便捷封装：天气/预警 |

- 工具总数 18 → 24（桥 18 + 设备动作 6）。四个便捷工具只是「拼好参数的 `xiaoai_apptool`」，降低 Agent 出错率。
- `app` 别名：`alarm`(deskclock) / `calendar` / `notes` / `recorder` / `gallery` / `weather` / `phone` / `mishare` / `cleaner` / `wallet` / `launcher` / `milink` / `scanner` / `bugreport`。
- 白名单：**只有清单里出现的 (app, tool) 组合可调**，其余 `UNKNOWN_APPTOOL`。
- 风险分级沿用现有做法：`read` 直接放行；`write` 需要 `confirm=true`；`danger`（外呼、清理、发文件、提交反馈）默认关闭，需 App 内开关。

## 2. 第一批：只读（白名单口径 31 个）

> 112 个工具里标只读的有 48 个，我们只收录 31 个：排除 telecom/scan/bugreport
> （不接）、小爱自己的 16 个（已有）、钱包两个查询（留到第二批）、
> milink/home/phone 的混合工具（按写处理）。

### 闹钟 `com.android.deskclock` → `AlarmToolService`

| 工具 | 参数 | 说明 |
|---|---|---|
| `deskclock_query_alarms` | 无 | 已有闹钟列表（id/hour/minutes/enabled/daysOfWeek/label） |
| `deskclock_query_timer` | 无 | 当前倒计时状态与剩余时间 |

### 日历 `com.android.calendar` → `CalendarToolService`

| 工具 | 参数 | 说明 |
|---|---|---|
| `query_events` | `start_time*`、`end_time*`、`keyword`、`max_results` | 按时间范围查日程 |
| `query_festivals` | `year`、`month`、`name` | 节日 |
| `query_legal_holidays` | `year`、`month`、`name` | 法定节假日 |

### 录音机 `com.android.soundrecorder` → `SoundRecorderToolService`

| 工具 | 参数 | 说明 |
|---|---|---|
| `search_recordings` | `keyword`、`record_type`、`offset`、`limit` | 录音列表（不传 keyword 返回全部） |
| `get_transcription` | `db_id` 或 `file_name` | 转录全文（含说话人标注），未转录会自动发起 |

### 天气 `com.miui.weather2` → `AppToolProviderService`

| 工具 | 参数 | 说明 |
|---|---|---|
| `weather_get_city_weather` | `city*`、`belongings` | 实时 + 24h + 多日 |
| `weather_get_city_alert` | `city*`、`belongings` | 气象预警 |

### 笔记 `com.miui.notes` → `NoteToolService`

| 工具 | 参数 | 说明 |
|---|---|---|
| `note_list` | 无 | 全部主笔记 |
| `note_search` | `query*`、`limit` | 标题+正文模糊搜索 |
| `note_read` | `noteId` 或 `note_id` | 读一篇正文 |
| `note_search_by_time` | `startTime`、`endTime`、`limit` | 时间范围（新建或修改过） |
| `note_search_by_type` | `noteType`、`limit` | main/todo/mindmap/schedule/… |
| `note_search_by_tag` | `tag*`、`limit` | 固定枚举：工作/学习/生活/创意/其他 |
| `note_children_list` | `parentId` | 主笔记下的子卡片 |
| `folder_list` / `folder_notes` / `folder_uncategorized` | `folderId` 视情况 | 文件夹视图 |
| `podcast_search` | `keyword`、`startTime`、`endTime`、`limit` | AI 播客 |
| `time_now` | 无 | 当前时间（拼时间参数用） |

### 相册 `com.miui.gallery` → `AppToolProviderService`

| 工具 | 参数 | 说明 |
|---|---|---|
| `gallery_search` | `query*`、`time_ranges`、`location`、`media_type`、`ai_category`、`limit`… | 唯一搜索入口 |
| `gallery_resolve_path` | `paths*` | 路径 → media_id |
| `gallery_get_photo_info` | `media_ids*`、`fields` | 照片详情 |
| `gallery_sort` | `media_ids*`、`sort_by`、`order`、`min_aesthetic_score` | 排序/筛选 |
| `gallery_list_collection` | 无 | 相册列表（含智能相册） |
| `gallery_assess` | `media*`、`type`(1 美学 / 2 质量) | 评分（会跑端侧模型，较慢） |
| `gallery_assess_trash` | `media_ids*` | 只回「建议保留/移除」的 ID，不改相册 |

### 电话 `com.android.phone` → `PhoneToolService`

| 工具 | 参数 | 说明 |
|---|---|---|
| `phone_get_caller_info` | 无 | 当前来电号码归属（仅来电时可调） |

> `phone_settings`（`paramCount=7`）与 `phone_network_settings` 的描述是「查询和**管理**」，
> 同一工具能用 `action` 改设置，因此归到第二批（需 confirm），不放在只读清单。

### 其他

| app | 工具 | 说明 |
|---|---|---|
| `aicr` | `get_support_event_detail` | 系统事件清单（AI 事件中枢） |
| `launcher` | `home_settings` | 桌面设置查询（action=get） |
| `milink` | `milink_cast_settings` / `milink_connectivity_settings` / `milink_desktop_position` / `milink_unlock_switch` | 均为 `action=get_status` 查询时只读 |

> 合计 31 个只读工具。默认 `/mcp` 可见。

**入站画像（`?profile=readonly`）暂不放这两组**：小爱自己就是调用方，开放 `xiaoai_apptool`
会形成「小爱 → 我们 → 小爱/笔记」的递归与写放大；需要时再按 provider 白名单逐个子集开放。

## 3. 第二批：写（需 `confirm=true`，约 40 个）

| 组 | 工具 |
|---|---|
| 闹钟（7） | `deskclock_add_alarm`、`deskclock_update_alarm`、`deskclock_delete_alarm`、`deskclock_start_timer`、`deskclock_pause_timer`、`deskclock_resume_timer`、`deskclock_cancel_timer` |
| 日历（6） | `create_event`、`create_birthday`、`create_anniversary`、`create_countdown`、`update_event`、`delete_event` |
| 笔记（12） | `note_create`、`note_write`、`note_append`、`note_update`、`card_create`、`folder_create`、`note_move_to_folder`、`note_batch_move_to_folder`、`note_pin`、`note_insert_image`、`note_sync_schedule`、`rollback_operation`（`card_template` 只读，已移到第一批） |
| 录音（2） | `manage_recording`、`play_audio_file` |
| 相册（4） | `gallery_mark`、`gallery_add_to_collection`、`gallery_create_collection`、`gallery_manage_story`（`gallery_assess_trash` 只读，已移到第一批） |
| 互联（4） | `milink_manage_screen_media`、`milink_manage_device_control`、`milink_manage_tv_control`、`milink_desktop_device_switch` |
| 电话（2，混合） | `phone_settings`、`phone_network_settings`（描述含「管理」，可改设置） |
| 清理（1） | `manage_cleaner` |
| 钱包（2） | `record_bill`、`get_bookkeeping_categories`（读，但属钱包域） |

## 4. 第三批：默认不接（危险/外发）

| 工具 | 原因 |
|---|---|
| `com.android.server.telecom` 全部 5 个 | 外呼、挂断、免提、静音——误触发代价高 |
| `phone_call_recording` | 通话录音涉及合规 |
| `manage_file_transfer` | 外发文件 |
| `bugreport` 6 个 | 提交反馈会把内容外发到小米 |
| `open_scan` / `scan_qr_image` | 拉起相机 / 读图 |
| `exit_xiaoai`、`show_instruction`、`get_screen_context` | 已有或涉及屏幕内容 |

## 5. 实现要点

1. **provider 定位**：`app` 别名 → `包/Service`（`cmd package query-services -a com.aios.osbot.action.APP_TOOL_PROVIDER` 已确认 17 个），复用 `AppToolClient` 的 bind/`handleRequest`/unbind 纪律。
2. **并发与超时**：现有 `CALLS` 池上限 2、5 s。`get_transcription`/`gallery_search`/`gallery_assess` 会超 5 s → 另开一个 `TOOLS` 池（上限 4 + 20 s），避免拖垮记忆/待办。
3. **返回体积**：转录、相册列表可能几十 KB → 统一截断（32 KB，带 `truncated:true`）。
4. **参数校验**：以 `assets/app_tools.json` 的 `params` 为准做必填检查，未知键拒绝（与小爱现有 `unexpectedArg` 一致）。
5. **审计**：写类工具走现有动作面日志，记录 `app/tool/argc/ok/ms`，不含参数值。
6. **失败语义**：沿用 `APPTOOL_UNAVAILABLE` / `BIND_REJECTED` / `CALL_TIMEOUT` / `CALL_FAILED`；新增 `UNKNOWN_APPTOOL`（不在白名单）。

## 6. 验收

| 项 | 判据 |
|---|---|
| 枚举 | `xiaoai_apptools` 返回 17 个 provider / 112 个工具，含参数摘要与风险级 |
| 只读真机 | `xiaoai_alarms` 返回设备真实闹钟；`xiaoai_events` 返回近期日程 |
| 通用调用 | `xiaoai_apptool app=recorder tool=search_recordings` 返回录音列表 |
| 白名单 | `app=telecom tool=phone_make_call` → `UNKNOWN_APPTOOL` |
| 写闸门 | `app=alarm tool=deskclock_add_alarm` 无 `confirm` → 拒绝；带 `confirm=true` 才执行 |
| 入站画像 | `?profile=readonly` 只放只读清单，写类返回 `FORBIDDEN_TOOL` |
| 门禁 | 单测 / 一致性 / 分层 `--strict` / `assembleRelease` 全绿 |

## 7. 校对记录（PM 复核后修正）

| 项 | 原稿 | 修正 |
|---|---|---|
| 只读数量 | 写「约 29 个」 | 逐条相加实为 **31**（含 `gallery_assess_trash`、`card_template`） |
| `phone_settings` / `phone_network_settings` | 列入只读 | 描述是「查询和**管理**」、`paramCount` 7/3，改列第二批（需 confirm） |
| `gallery_assess_trash` | 列入写批次 | 只回建议 ID、不改相册，移到只读 |
| `card_template` | 未收录 | 取模板不改数据，补进只读（notes 只读 13 条） |
| 闹钟写批次 | 标「4 个」 | 实际 7 个（3 个闹钟 + 4 个倒计时操作） |
| 工具总数 | 22 | 24（桥 18 + 设备动作 6） |
