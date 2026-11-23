# 10 能力全景：112 个 AppTool 的用户视角

> 证据级别：实测（`artifacts/reverse/xiaoi-8.2.70.8421/apptools.json`，17 provider / 112 工具全量声明）
> ／静态（各 APK `assets/app_tools.json`）｜版本 8.2.70.8421｜设备 REDMI K90 Ultra
>
> 口径：工具名、参数个数、只读/写入均来自声明本身。**「用户问题」「价值」「风险」是产品判断，
> 不是实测**，依据是工具描述 + 参数形状。分组按用户场景，不按包。

## 1. 全景：8 个场景 / 17 个 provider / 112 个工具

| # | 场景 | 它解决的用户问题 | provider（包 · 工具数） | 只读/写/混合 | 价值·风险 |
|---|---|---|---|---|---|
| 1 | 时间与提醒 | 别忘事：定闹钟、约会议、记生日/纪念日/倒数日 | calendar 9、deskclock 9 | 5 / 13 / 0 | 高·中 |
| 2 | 内容与笔记 | 写下、找到、整理文字与待办 | notes 26 | 13 / 13 / 0 | 高·中 |
| 3 | 媒体与相册 | 找到那张照片、整理相册、听录音/放音乐 | gallery 11、soundrecorder 4、voiceassist 2 | 10 / 7 / 0 | 高·中 |
| 4 | 通讯 | 打电话、接/挂、通话中静音免提、通话录音 | phone 4、telecom 5 | 1 / 6 / 2 | 中·高 |
| 5 | 设备与互联 | 投屏、跨端解锁、传文件、找设备、遥控电视、改桌面 | milink 8、mishare 1、home 1、scanner 2 | 1 / 2 / 9 | 中·中 |
| 6 | 信息与生活服务 | 天气预警、系统事件、记账、查保险 | weather2 3、aicr 1、wallet 3 | 6 / 1 / 0 | 中·低 |
| 7 | AI 与记忆 | 记住一件事、翻译、生图、识屏、查官方知识 | voiceassist 16 | 9 / 5 / 2 | 高·中~高 |
| 8 | 系统维护 | 反馈问题、清垃圾 | bugreport 6、cleanmaster 1 | 3 / 3 / 1 | 低·中 |

合计 **只读 48 / 写入 50 / 读写混合 14 = 112**。「混合」= 同一工具靠 `action` 切换查询与修改
（`milink_*`、`home_settings`、`phone_settings`、`manage_cleaner`、`settings_access`、`favorite`）。

## 2. 工具清单（场景 → 包 → 只读 / 写入）

| 场景 | 包（provider） | 只读 | 写入 / 混合 |
|---|---|---|---|
| 时间与提醒 | `com.android.calendar` | `query_events` `query_festivals` `query_legal_holidays`（3） | `create_event` `create_birthday` `create_anniversary` `create_countdown` `update_event` `delete_event`（6） |
| 时间与提醒 | `com.android.deskclock` | `deskclock_query_alarms` `deskclock_query_timer`（2） | `deskclock_add_alarm` `deskclock_update_alarm` `deskclock_delete_alarm` `deskclock_start_timer` `deskclock_pause_timer` `deskclock_resume_timer` `deskclock_cancel_timer`（7） |
| 内容与笔记 | `com.miui.notes` | `note_read` `note_list` `note_children_list` `note_search` `note_search_by_time` `note_search_by_type` `note_search_by_tag` `podcast_search` `folder_list` `folder_notes` `folder_uncategorized` `card_template` `time_now`（13） | `note_create` `note_write` `note_append` `note_update` `card_create` `folder_create` `note_move_to_folder` `note_batch_move_to_folder` `note_pin` `note_insert_image` `todo_create` `note_sync_schedule` `rollback_operation`（13） |
| 媒体与相册 | `com.miui.gallery` | `gallery_search` `gallery_resolve_path` `gallery_get_photo_info` `gallery_sort` `gallery_assess` `gallery_assess_trash` `gallery_list_collection`（7） | `gallery_add_to_collection` `gallery_mark` `gallery_create_collection` `gallery_manage_story`（4） |
| 媒体与相册 | `com.android.soundrecorder` | `search_recordings` `get_transcription`（2） | `manage_recording` `play_audio_file`（2） |
| 媒体与相册 | `com.miui.voiceassist`（媒体部分） | `search_media_tool`（1） | `play_media_tool`（1） |
| 通讯 | `com.android.server.telecom` | — | `phone_make_call` `phone_call_action` `phone_hold_call` `phone_set_audio_route` `phone_set_mute`（5） |
| 通讯 | `com.android.phone` | `phone_get_caller_info`（1） | `phone_call_recording`（写）；`phone_settings` `phone_network_settings`（混合） |
| 设备与互联 | `com.milink.service` | — | 8 个全部混合：`milink_cast_settings` `milink_connectivity_settings` `milink_manage_screen_media` `milink_manage_device_control` `milink_manage_tv_control` `milink_desktop_device_switch` `milink_desktop_position` `milink_unlock_switch` |
| 设备与互联 | `com.miui.mishare.connectivity` | — | `manage_file_transfer`（外发） |
| 设备与互联 | `com.miui.home` | — | `home_settings`（混合） |
| 设备与互联 | `com.xiaomi.scanner` | `scan_qr_image`（1） | `open_scan`（拉起相机） |
| 信息与生活服务 | `com.miui.weather2` | `weather_get_city_weather` `weather_get_city_alert` `func_version_message`（3） | — |
| 信息与生活服务 | `com.xiaomi.aicr` | `get_support_event_detail`（1） | — |
| 信息与生活服务 | `com.mipay.wallet` | `get_bookkeeping_categories` `query_available_insurance_products`（2） | `record_bill`（1） |
| AI 与记忆 | `com.miui.voiceassist` | `nlp_query` `search_mi_knowledge` `read_memory` `get_memory_data`（已废弃）`image_to_text` `trans_text` `trans_image` `get_screen_context` `get_current_package`（9） | 写：`write_memory` `text_to_image` `take_photo_recognize` `show_instruction` `exit_xiaoai`；混合：`favorite` `settings_access` |
| 系统维护 | `com.miui.bugreport` | `get_domains` `get_tags` `get_feedback_list`（3） | 外发：`submit_feedback` `add_reply`；界面：`open_feedback_detail` |
| 系统维护 | `com.miui.cleanmaster` | — | `manage_cleaner`（混合，含删除） |

## 3. 价值-风险矩阵

| 场景 | 价值 | 风险 | 风险来源 | 可逆性 | 一句话评级 |
|---|---|---|---|---|---|
| 时间与提醒 | 高 | 中 | 写系统日历/闹钟，`need_alarm` 会弹全屏闹钟 | 可逆（update/delete/取消） | 价值最高、风险可控，首个该接 |
| 内容与笔记 | 高 | 中 | `note_write` 是整篇覆盖；批量移动文件夹 | **唯一自带撤销**（`rollback_operation`，仅笔记） | 读强写强，唯一有后悔药的写入域 |
| 媒体与相册 | 高 | 中 | 改相册归属、设为私密、时光卡片 | 部分可逆（`gallery_mark` 可取消） | 读取很值钱，写先别接 |
| 通讯 | 中 | 高 | 外呼/挂断/静音误触发，已拨出不可收回 | 不可逆 | 默认不接 |
| 设备与互联 | 中 | 中 | 改互联开关、跨端解锁、传文件外发 | 开关可逆，但影响别的设备 | 只接 `action=get_status` |
| 信息与生活服务 | 中 | 低 | 记账写钱包账本；其余纯查询 | 可逆（需手动改账单） | 最安全的一批，适合放开 |
| AI 与记忆 | 高 | 中~高 | 截屏/前台内容、图片上传翻译、写记忆 | 记忆可删但删除工具不在 AppTool 里 | 读好用；截屏与上传类要闸门 |
| 系统维护 | 低 | 中 | 反馈内容连同日志/联系人外发到小米；清理删除文件 | 不可逆 | 默认关闭，不进任何批量 |

## 4. 产品缺口（用户想要但没有）

| # | 缺口 | 现象 | 证据 |
|---|---|---|---|
| 1 | 没有跨场景统一检索 | 用户问「我上周说的那个事」，Agent 得分别翻 5 个库 | `gallery_search` 自称「系统中搜索照片和视频的**唯一**入口」但只覆盖相册；`note_search` 只覆盖笔记；`search_recordings` 只覆盖录音；`read_memory` 只覆盖记忆+收藏 |
| 2 | 写操作没有统一确认/预演 | 64 个能改状态的工具（50 写 + 14 混合）没有一个带 `confirm`/`dry_run` | `apptools.json` 全部 `params` 中无此类字段；目前确认只能由调用方（我们）在协议外做 |
| 3 | 记忆与笔记是两套孤岛 | 记住的话搜不到笔记，笔记里的内容也不会进记忆 | `write_memory`/`read_memory` 属 `com.miui.voiceassist`（`06-memory.md` §3 的 `files/memory/`）；`note_*` 属 `com.miui.notes`；`read_memory` 描述只提「本地记忆与收藏列表」 |
| 4 | 「提醒我」有三个落点，没有唯一答案 | 同一句话可能进待办页、进日历、或只在笔记里 | `todo_create`「直接出现在待办页面」；`note_sync_schedule`「同步到系统日历」；`create_event` 直接建日程 |
| 5 | 时间基准挂在笔记下面 | 不写笔记的调用方（日历/闹钟/相册）也要 bind 笔记服务才能拿当前时间 | 声明里唯一语义明确的取时工具是 `com.miui.notes/time_now`（p=0，描述「配合 note…」）；`nlp_query` 也能解析 time，但那是语义入口，不是时钟 |
| 6 | 没有统一的「本机状态」只读面 | 前台 App、网络/SIM、电量分散在三个包，本机电量没有专门工具 | 前台包=`voiceassist/get_current_package`；网络=`phone/phone_network_settings`；电量只能借 `milink_manage_device_control` 的跨设备 `action`（`device_type` 必填） |
| 7 | 外发类工具没有「先给我看要发什么」 | 反馈、传文件、图片翻译一调就发 | `submit_feedback`（p=8，含 `attachmentPaths`/`contactInfo`/`needLog`）、`manage_file_transfer`（`files` 数组）、`trans_image`（先上传拿 imageId） |
| 8 | 媒体控制不完整 | 用户说「暂停/下一首」时没有对应工具 | `play_media_tool` 只有播放语义（`mediaType`/`uri`/`resourceId`，无 pause/next）；本地录音播放又在另一个 provider 的 `play_audio_file` |

## 5. 与设置页 12 项的关系

### 5.1 设置页能看到、且同一包又是 AppTool provider 的只有 4 个

| 设置页条目 | 实现包 | 该包 AppTool 工具数 | 说明 |
|---|---|---|---|
| 超级小爱 | `com.miui.voiceassist` | 18 | 唯一「小爱本体」 |
| AI 写作 | `com.miui.notes` | 26 | 笔记/待办全量工具 |
| AI 识音 | `com.android.soundrecorder` | 4 | 录音 + 转录 |
| 全局 AI 帮写 / AI 搜索 | `com.xiaomi.aicr` | **1** | 只有 `get_support_event_detail`，帮写与搜索本身**没有**工具 |
| AI 服务 / AI 壁纸 / AI 相册编辑 / 对话翻译 / 通话翻译 / 实时字幕 / AI 反诈 | aiservice、thememanager、mediaeditor、aiasst.vision ×3、反诈 App | 0 | 无 AppTool 入口（`09` §2、§3） |

### 5.2 设置页完全看不到、但 Agent 已经能调的（13 包 / 63 工具，占 56%）

| 包 | 工具数 | 能干什么 | 设置页有无入口 |
|---|---|---|---|
| `com.miui.gallery` | 11 | 搜图、排序、评分、建相册、时光卡片 | 只有媒体编辑器的「AI 相册编辑」，不是这个包 |
| `com.android.calendar` | 9 | 建/改/删日程，生日、纪念日、倒数日、节假日 | 无 |
| `com.android.deskclock` | 9 | 闹钟增删改查、倒计时全套 | 无 |
| `com.milink.service` | 8 | 投屏、流转、电视遥控、跨端解锁、妙享桌面 | 无（在「互联」里，不在 AI 页） |
| `com.miui.bugreport` | 6 | 提反馈、看进度、追加回复 | 无 |
| `com.android.server.telecom` | 5 | 拨号、接挂、保持、免提、静音 | 无 |
| `com.android.phone` | 4 | 电话设置、通话录音、网络/SIM | 无 |
| `com.mipay.wallet` | 3 | 记账、记账分类、保险产品 | 无 |
| `com.miui.weather2` | 3 | 实时/逐时/多日天气、气象预警 | 无 |
| `com.xiaomi.scanner` | 2 | 扫码、扫名片 | 无 |
| `com.miui.home` | 1 | 桌面布局与显示设置 | 无 |
| `com.miui.mishare.connectivity` | 1 | 小米互传发文件 | 无 |
| `com.miui.cleanmaster` | 1 | 扫垃圾、清垃圾 | 无 |

**这是本次最有价值的发现**：用户以为「AI 能力」都藏在设置页那 12 个开关后面，
实际上日历、闹钟、天气、钱包记账、扫码、电视遥控这些**日常能力早就对 Agent 开放了**，
只是没有任何 UI 告诉用户。设置页管的是「功能开关」，AppTool 管的是「Agent 能不能调」，
两者没有一一对应（`com.xiaomi.aicr` 有两个设置项，却只开放 1 个事件清单工具）。

## 6. 优先级建议

判断标准：**价值**（用户多久问一次）× **风险**（能不能撤销、是否外发）× **成本**（是否要新连接池、
是否要新确认 UI）。三档都要求「先只读、后写、外发最后」。

| 档 | 范围 | 数量（本次新接） | 理由 |
|---|---|---|---|
| **P0** | `docs/agent/PLAN-APPTOOLS.md` §2 的只读清单，+ `func_version_message`、`card_template`、`gallery_assess_trash`、`get_bookkeeping_categories`/`query_available_insurance_products` | 41 | 零写入、零外发；`func_version_message` 拿天气字段契约版本，防字段漂移静默出错；`phone_settings`/`phone_network_settings` 只放查询分支 |
| **P1** | 闹钟 7 写（3 个闹钟 + 4 个倒计时）、日历 6 写、笔记 13 写、相册 4 写 | 30 | 影响限于本机数据，可改可删；需 `confirm=true` + 审计 |
| **P2** | `milink_*` 剩余 4 个、`manage_cleaner`、`record_bill`、`manage_recording`、`play_audio_file`、`play_media_tool` | 9 | 改系统状态或动别的设备，收益低；只放查询，写类默认关闭 |
| **不接** | telecom 5、bugreport 6、`manage_file_transfer`、`open_scan`/`scan_qr_image`、`phone_call_recording`、`get_screen_context`/`take_photo_recognize` | 17 | 外呼不可逆、外发到第三方、涉及屏幕与相机 |

> 计数口径：以上只统计**本次要新接**的工具。已由小爱暴露的 18 个里，15 个不重复计入，
> 2 个（`get_screen_context`/`take_photo_recognize`）归入「不接」，1 个（`play_media_tool`）归入 P2。
> 41 + 30 + 9 + 17 + 15 = 112，对得上。

## 7. 与 `docs/agent/PLAN-APPTOOLS.md` 的口径差异

| 项 | PLAN 的写法 | apptools.json 实际 | 建议 |
|---|---|---|---|
| 只读批次数量 | 「约 29 个」 | 逐条相加为 **36** 个 | 改数字或补 7 个到清单，二选一 |
| `phone_settings` / `phone_network_settings` | 列在只读 | `paramCount` 7 / 3，描述为「查询和**管理**」，可写入 | P0 只放查询分支，或移到 P1 |
| `gallery_assess_trash` | 列在写批次（该行标注「相册（4）」却列了 5 个名字） | 描述为「返回建议保留和建议移除的照片 ID」，是纯读 | 移到只读，数量回到 4 |
| `card_template` | 未列入 notes 只读 | 是读（返回卡片模板），notes 只读实为 13 | 补进 P0 清单 |
