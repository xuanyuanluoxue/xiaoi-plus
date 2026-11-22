# 工具目录（小爱 8.2.70.8421）

> 由 `reverse/scripts/catalog.py` 从 APK assets 生成，勿手工编辑。
> 来源：`assets/tool_enriched.json`（481 个工具）、`assets/app_tools.json`（18 个进程内工具）。

## 按分组统计

| 分组 | 工具数 |
|---|---|
| CORE | 342 |
| TRAVEL | 53 |
| UTILITY | 31 |
| FILE | 13 |
| CONNECTIVITY | 12 |
| BROWSER | 9 |
| MEDIA | 8 |
| HISTORY | 5 |
| RN | 4 |
| PHONE | 2 |
| SANDBOX | 1 |
| EXTERNAL | 1 |

## 按服务前缀统计（42 个）

| 前缀 | 工具数 |
|---|---|
| (顶层) | 118 |
| RideClawMcp | 53 |
| aicr | 43 |
| notes | 28 |
| mediaeditor | 26 |
| account | 21 |
| gallery | 21 |
| email | 18 |
| voiceassist | 15 |
| securitycenter | 13 |
| calendar | 9 |
| contacts | 9 |
| cloudservice | 8 |
| xiaomi_appstore | 8 |
| service | 8 |
| bugreport | 7 |
| mms | 6 |
| settings | 6 |
| soundrecorder | 6 |
| telecom | 5 |
| Wind_MCP | 4 |
| deskclock | 4 |
| phone | 4 |
| JDJR_MCP_BASE_SERVICE | 3 |
| kugou-music | 3 |
| printspooler | 3 |
| screenrecorder | 3 |
| xiaomi-ice-content-search | 3 |
| sdksample | 3 |
| tsmclient | 3 |
| yellowpage | 3 |
| kugou-music-mcp | 3 |
| mx-mcp | 2 |
| scanner | 2 |
| weather2 | 2 |
| HimaAudioServer | 2 |
| misettings | 1 |
| mishop | 1 |
| misound | 1 |
| cleanmaster | 1 |
| connectivity | 1 |
| home | 1 |

## 工具清单

按服务前缀分组；描述取 `description` 首 90 字。

### (顶层)（118）

| 工具 | 分组 | 说明 |
|---|---|---|
| `adb_bash` | SANDBOX | Execute a bash command in the Harbor Docker container via adb reverse TCP bridge.  - The c… |
| `agent` | CORE | 智能体网关。search 枚举可调用智能体;detail 获取详情;call 同步调用子智能体执行任务。 |
| `ai_call` | CORE | [app] AI 帮接电话配置。开关 AI 自动帮接、设置陌生人/熟人来电开场白、防漏接延迟、AI 音色（冰糖/茉莉/苏打/白桦）、防骚扰规则（广告/贷款/房产/快递等）、自动回复… |
| `alipay_agents` | CORE | [app] 调用支付宝内置 Agent 完成话费流量充值、水电燃缴费查询、易安检/乘机证明等支付宝服务。  参数: - scenario: 场景枚举。recharge=话费/流量充… |
| `alipay_service_agent` | CORE | [app] 支付宝生活服务统一入口。覆盖政务证件、社保医保、公积金、疫苗体检、车主服务、生活缴费、通信宽带、话费账单与余额、流量查询、房产物业、余额宝、基金、花呗、保险、学籍学历、… |
| `app_install` | UTILITY | 从应用商店搜索安装，或安装本地 APK 文件。 |
| `app_launch` | UTILITY | 按名称启动已安装的应用。 |
| `app_list` | UTILITY | 查询设备上已安装的应用列表，支持按名称过滤。 |
| `app_shortcut` | UTILITY | 搜索和执行设备上已安装 APP 内置的快捷入口（如微信的扫一扫、支付宝的付款码、抖音的拍摄等）。用 action=search 按关键词或应用名搜索可用快捷方式，用 action=… |
| `app_uninstall` | UTILITY | 按名称卸载已安装的应用。 |
| `app_usage` | UTILITY | 查询应用使用统计：按使用时长排序的常用 APP。用于了解用户的 APP 偏好和使用习惯。 |
| `append_file` | FILE | Append content to a file. Parent directories are created automatically. |
| `ask_user_question` | CORE | 向用户展示选择题卡片，让用户在 2-4 个明确选项中做选择。  适用场景： 1. 用户指令模糊，操作方向 ≥ 2 种（如"帮我设个闹钟"→ 问几点） 2. 存在多种可选方案，需要用… |
| `background_task` | UTILITY | 本工具有两种动作,通过 action 参数区分。  【action="dispatch"】(默认) 派发新任务到后台 触发场景:用户用"X 后台跑"、"帮我后台 X"、"异步执行 … |
| `bluetooth_connect` | CORE | 连接已配对的蓝牙设备。通过设备名称或描述（如 "小米耳机"、"耳机"）自动匹配，也兼容 MAC 地址。 |
| `bluetooth_disconnect` | CORE | 断开蓝牙设备。通过设备名称或描述（如 "小米耳机"、"耳机"）自动匹配已配对设备。 |
| `bluetooth_paired_devices` | CORE | 列出所有已配对蓝牙设备，包含设备名称、类型、连接状态、电量和 MAC 地址。 |
| `bluetooth_scan` | CORE | 扫描附近蓝牙设备（Classic + BLE），返回设备名、类型、信号强度和 MAC 地址。可选 scan_duration 参数指定扫描时长（默认 10 秒，最长 30 秒）。 |
| `bluetooth_status` | CORE | 查询蓝牙状态（开关、本机名称、已连接设备及电量）。 |
| `bluetooth_toggle` | CORE | 开启或关闭蓝牙。参数 enabled=true 开启，enabled=false 关闭。需要用户确认。 |
| `broadcast_query` | UTILITY | 向所有在线设备广播查询指令并聚合结果。你运行在设备「Xiaomi 14 Ultra」上（suid=v1:0:ud:XtZmUy8m-ZZL6BYJ）。  ## 参数 - instr… |
| `browser_click` | BROWSER | 点击后台浏览器中的页面元素（用户不可见）。支持三种定位方式： 1. 索引（推荐）: index=3（需先调用 browser_get_interactive_elements） 2… |
| `browser_close` | BROWSER | 关闭后台浏览器并释放资源 |
| `browser_extract` | BROWSER | 提取后台浏览器中的页面正文（用户看不到浏览器，必须用此工具提取内容后展示给用户）。 基于 Mozilla Readability 自动去除导航栏/广告/侧边栏。 - 默认返回 Ma… |
| `browser_get_interactive_elements` | BROWSER | 获取后台浏览器页面中所有可交互元素列表（用户不可见）。 每个元素带索引编号，可用于 browser_click、browser_input。 |
| `browser_input` | BROWSER | 在后台浏览器的输入框中输入文本（用户不可见，支持索引/占位符） |
| `browser_login` | BROWSER | 在当前浏览器页面自动填入登录凭据（用户名和密码）。凭据从本地安全存储读取，不经过 AI。如果没有保存过该网站的凭据，会弹窗请用户输入。需要先调用 browser_get_inter… |
| `browser_open` | BROWSER | 打开后台无界面浏览器并加载指定 URL（用户不可见）。 页面加载后需用 browser_extract 提取内容返回给用户，不要让用户自己去看浏览器。 |
| `call_log_delete` | PHONE | Delete a specific call log entry by ID. Use call_log_list first to get the entry ID. |
| `call_log_list` | PHONE | Query and list call log entries. Supports filtering by phone number, contact name, call ty… |
| `call_tool` | CORE | 间接调用扩展工具。先用 tool_search 搜索，再用本工具调用。 |
| `check_permissions` | UTILITY | Check the status of app permissions for debugging |
| `cli` | UTILITY | 执行命令行指令（沙箱环境）。支持管道 \|、条件执行 && \|\|、顺序执行 ; 和重定向 > >> <。 常用内置命令: echo, cat, grep, find, ls, … |
| `clipboard` | FILE | 读取或写入设备剪贴板。适用场景：用户说"复制到剪贴板"、"读一下剪贴板"、"粘贴出来"时使用。不适用场景：在浏览器输入框中粘贴（用 browser_input 直接传入文本）。ac… |
| `cloud_upload` | UTILITY | 跨设备文件上传工具。你运行在设备「Xiaomi 14 Ultra」上（suid=v1:0:ud:XtZmUy8m-ZZL6BYJ）。  两种模式： - 发送文件到其他设备：clou… |
| `com.android.permissioncontroller` | CORE | [app] 权限控制器 (com.android.permissioncontroller) 的 AppFunction Fixed Schema 接口。当意图涉及 权限控制器 内… |
| `com.android.settings` | CORE | [app] 设置 (com.android.settings) 的 AppFunction Fixed Schema 接口。当意图涉及 设置 内的多步设置编排（key/value … |
| `com.android.systemui` | CORE | [app] 系统界面 (com.android.systemui) 的 AppFunction Fixed Schema 接口。当意图涉及 系统界面 内的多步设置编排（key/va… |
| `com.android.thememanager` | CORE | [app] 主题壁纸 (com.android.thememanager) 的 AppFunction Fixed Schema 接口。当意图涉及 主题壁纸 内的多步设置编排（ke… |
| `com.miui.aod` | CORE | [app] 息屏与锁屏编辑 (com.miui.aod) 的 AppFunction Fixed Schema 接口。当意图涉及 息屏与锁屏编辑 内的多步设置编排（key/valu… |
| `com.miui.home` | CORE | [app] 系统桌面 (com.miui.home) 的 AppFunction Fixed Schema 接口。当意图涉及 系统桌面 内的多步设置编排（key/value 模式）… |
| `com.miui.notification` | CORE | [app] 通知管理 (com.miui.notification) 的 AppFunction Fixed Schema 接口。当意图涉及 通知管理 内的多步设置编排（key/v… |
| `com.miui.screenrecorder` | CORE | [app] 屏幕录制 (com.miui.screenrecorder) 的 AppFunction Fixed Schema 接口。当意图涉及 屏幕录制 内的多步设置编排（key… |
| `com.miui.securitycore` | CORE | [app] 系统功能组件 (com.miui.securitycore) 的 AppFunction Fixed Schema 接口。当意图涉及 系统功能组件 内的多步设置编排（k… |
| `com.miui.touchassistant` | CORE | [app] 悬浮球 (com.miui.touchassistant) 的 AppFunction Fixed Schema 接口。当意图涉及 悬浮球 内的多步设置编排（key/v… |
| `connect_wifi` | CONNECTIVITY | 连接指定 WiFi。支持精确 SSID 或模糊词（如「公司」）。未提供密码时先尝试连接（可能已保存）；若返回需要密码，请询问用户后带 password 重试。 |
| `control_media` | MEDIA | 控制设备上的媒体播放（音乐、视频）。适用场景：用户说"暂停播放"、"下一首"、"调大音量"、"继续播放"时使用。不适用场景：设置定时停止播放（用 task scheduler）。支… |
| `copy_file` | FILE | 拷贝文件或者目录 |
| `create_calendar_event` | CORE | Create a new calendar event with specified title, time, and optional details. Supports rec… |
| `create_exercise_schedule` | CORE | 基于用户健康数据生成个性化运动计划。  此工具会： 1. 评估用户的健康状况（基于健康数据和判定标准） 2. 生成 Markdown 格式的运动计划（包括健康分析和日程表格） 3.… |
| `csv_query` | UTILITY | 读取 CSV 文件并查询数据。文件首次读取后缓存在内存中。 action:   - "headers": 返回列名列表   - "query": 过滤行并返回结果，支持以下可选参数… |
| `delete_calendar_event` | CORE | 删除日历事件，支持三种模式：1) 按 event_id 精确删除单个事件（ID 从 read_calendar 获取）；2) 按 start_date + title_filter… |
| `delete_file` | FILE | 删除文件或目录。首次删除时需要用户确认，可使用 confirm=true 跳过确认。 |
| `delete_sms` | CONNECTIVITY | 删除消息，支持短信(SMS)、彩信(MMS)、5G消息(RCS)。支持两种模式：1) 按ID删除：传ids参数；2) 按条件批量删除：传delete_all=true可删全部，配合… |
| `device_coord` | UTILITY | 跨设备协作工具。你运行在设备「Xiaomi 15 Pro」上（suid=v1:0:ud:EkhiFNGUN9q9rNaa）。 向其他设备发起协作任务，系统自动拆分、分发、并行执行并… |
| `device_list` | UTILITY | 查询同帐号在线设备列表。你运行在设备「Xiaomi 15 Pro」上（suid=v1:0:ud:EkhiFNGUN9q9rNaa）。 返回所有设备的 suid、名称、类型、在线状态… |
| `device_messages` | UTILITY | 读取跨设备消息历史。 - 不传 suid：读取设备群聊消息 - 传 suid：读取与指定设备的私聊消息 |
| `device_send_image` | UTILITY | 跨设备图片发送工具。你运行在设备「Xiaomi 15 Pro」上（suid=v1:0:ud:EkhiFNGUN9q9rNaa）。 将本地图片文件发送到指定设备或所有在线设备。  #… |
| `device_status` | CORE | 查询设备当前状态信息。适用场景：用户说"手机还有多少电"、"存储空间够不够"、"什么网络"、"手机型号"时使用。不适用场景：单独查WiFi详情（用 wifi_info）或蓝牙状态（… |
| `disconnect_wifi` | CONNECTIVITY | 断开当前 WiFi 连接。 |
| `edit_file` | FILE | Edit a file by replacing old_text with new_text. The old_text must match exactly. |
| `file_grep` | FILE | Search for lines matching a pattern (plain text or regex, case-insensitive) in a file or d… |
| `file_info` | FILE | 获取文件信息 |
| `generate_rn_ui` | RN | Generate React Native UI code based on user requirements. This tool uses AI to create inte… |
| `get_current_time` | UTILITY | Get the current date and time in various formats |
| `get_health_data` | CORE | 获取用户的健康数据（结构化 JSON）。  此工具会： 1. 获取用户指定天数的健康指标数据 2. 根据类型过滤，只返回用户关心的维度 3. 返回结构化 JSON，由你直接解读分析… |
| `get_health_data_report` | CORE | 获取用户的健康数据并生成报告。  此工具会： 1. 获取用户的健康指标数据（步数、心率、睡眠、血氧等） 2. 自动生成 Markdown 简版报告 3. 自动生成详细 HTML 报… |
| `get_location` | CONNECTIVITY | 获取设备当前的地理位置信息。适用场景：用户说"我在哪"、"当前位置"、"获取定位"、"我现在在哪个城市"时使用。不适用场景：搜索附近的地点（用地图搜索工具）。需要定位权限，返回经纬… |
| `get_media_info` | MEDIA | 获取当前媒体播放信息（歌曲名、歌手、专辑、播放状态、播放进度等）。若有媒体会话则返回详细信息；若没有媒体正在播放，返回 {"hasActiveMedia":false}。 |
| `get_weekly_health_report` | CORE | 获取用户最近一周的健康数据并生成报告。  ⚠️ 限制：仅支持最近 7 天内的数据。如果用户要求超过 7 天的健康分析，应使用 get_health_data 工具。  此工具会： … |
| `im_chat` | UTILITY | 好友聊天工具，用于管理用户的 IM 聊天（好友私聊和群聊）。  ## 操作 1. resolve_conversation(name) - 根据名字查找对话，返回匹配的会话列表 2… |
| `list_files` | FILE | 列出目录内容。用 read_file 前先用此工具确认精确文件名，避免猜错。按文件名搜索请用 search_files，按内容搜索请用 file_grep。 |
| `list_rn_tools` | RN | List all saved React Native tools (mini apps) with their name, description, and status. 列出… |
| `list_tts_voices` | UTILITY | List available TTS voices. Returns voice names, genders, and categories, with the current … |
| `list_wifi_networks` | CONNECTIVITY | 扫描附近 WiFi，返回 SSID 和信号强度。 |
| `load_message` | HISTORY | 查看历史消息：按消息 ID 加载完整内容，可通过 context 参数同时加载前后 N 条消息获取上下文。配合 search_history 使用。 |
| `local_file_search` | UTILITY | 本地文件搜索工具。在设备上搜索文件，支持按文件类型、关键词、大小范围、修改时间过滤。 返回文件名、路径、大小、修改时间等信息。  ## 参数 - fileType (可选): 文件… |
| `manage_contacts` | CONNECTIVITY | 管理联系人：添加、修改或删除。 操作： - create：创建新联系人（name 必填，phone/email 可选），返回 contact_id - update：修改联系人（需… |
| `manage_sms_event` | UTILITY | Manage SMS event processing with category-based filtering. 支持无分类全部交由大模型处理. No categories →… |
| `mcp` | CORE | MCP 工具网关。通过命令模式调用已连接的外部服务工具。  重要：使用 MCP 工具前，必须先用 help <provider> 获取该 provider 的所有工具详细列表，再发… |
| `memory_read` | HISTORY | 批量读取记忆条目。支持一次传入多组查询条件，按类型/维度/日期精确过滤。每组条件中 memType/memKey/date 至少传一个；date 格式 YYYY-MM-DD。 |
| `memory_search` | HISTORY | 语义检索记忆（ClawMemory 混合检索：向量 + 关键词）。系统**只**自动注入 [记忆大纲]（类型/维度索引，不含条目内容），不会自动注入检索命中片段——需要具体内容时由… |
| `memory_write` | HISTORY | 写入记忆专用工具。content 为 Markdown 格式（# 类型 → ## 维度 → - 内容），一次可写多条。同一轮对话只允许调用一次，将所有内容合并到一个 content… |
| `miot_ask_assistant` | EXTERNAL | 小米智能家居统一工具，支持设备控制、状态查询、自动化管理、场景管理、摄像头事件查询、知识咨询、故障排查等所有智能家居操作。 |
| `move_file` | FILE | Move or rename a file. Both source and destination must be absolute paths (start with /). |
| `preview_rn_ui` | RN | Preview React Native UI in the app. This tool loads the generated RN code and displays it … |
| `publish_file` | FILE | Publish a file so other apps can access it. For files in the app's private directories, re… |
| `read_calendar` | CORE | Read calendar events within a specified time range. Can filter by calendar name or event t… |
| `read_file` | FILE | Read file content. Supports file paths and content:// URIs. Text files return UTF-8 string… |
| `read_sms` | CONNECTIVITY | 读取手机消息，支持 sms\|mms\|rms。读取短信，支持收件箱、已发送或全部文件夹。可按手机号、关键字、SIM卡槽、日期范围查询。【注意】keyword 仅搜索短信正文内容（… |
| `reload_agents` | UTILITY | Reload multi-agent definitions from agents/ directory. Use this when agent configurations … |
| `reload_mcp_config` | UTILITY | Reload personal MCP server config from mcp/mcp_servers.json and apply immediately. Call th… |
| `reload_prompt` | UTILITY | Reload the main agent prompt from file system into current settings. Use this when the pro… |
| `reload_skills` | UTILITY | Rescan all skill directories and refresh the index. |
| `save_rn_tool` | RN | Save a React Native UI as a reusable tool.  This tool persists the generated RN code to th… |
| `search_contacts` | CONNECTIVITY | 通过姓名、电话号码或邮箱搜索联系人 |
| `search_files` | FILE | 搜索文件，支持按文件名、文件类型、文件夹路径、创建时间范围等条件筛选 |
| `search_history` | HISTORY | 搜索历史记录：按关键词搜索历史聊天消息，用于查找过去讨论过的话题、决策或信息。返回匹配消息的摘要和 ID，可配合 load_message 查看完整内容。 |
| `send_message` | CORE | 向 Agent 发送异步消息，委派任务。  **发送给 Agent：** - 格式：to="agent://agent_id" (例如 "agent://translator", … |
| `send_notification` | CORE | 向用户发送一条 Android 系统通知。适用场景：用户说"给我发个通知"、"提醒我一下"、"完成后通知我"时使用。不适用场景：设置闹钟（用 deskclock 工具）或日程提醒（… |
| `send_sms` | CONNECTIVITY | Send an SMS message to a phone number,  短信内容要少于70字，如果发送消息过长需要先精简一下。 |
| `send_task_to_device` | UTILITY | 向指定设备派发任务并等待执行结果。你运行在设备「Xiaomi 14 Ultra」上（suid=v1:0:ud:XtZmUy8m-ZZL6BYJ）。  ## 参数 - suid（必填… |
| `set_tts_enabled` | UTILITY | Control TTS (text-to-speech) voice playback. Two modes: 1. Global toggle: set_tts_enabled(… |
| `set_tts_voice` | UTILITY | Switch TTS voice. Accepts voice name (Chinese) or voice_type ID. |
| `show_card` | UTILITY | 当需要展示以下结构化信息时，调用此工具渲染为原生卡片 UI，让用户获得更直观的查看体验： • 天气数据 → weather • 日历/日程事件 → calendar • 联系人信息… |
| `skill` | UTILITY | 技能知识库：搜索、列举、加载和管理技能知识文件（skill）。 action:   - "load": 加载指定 skill 内容，需提供 name；可选 start（起始字符位置… |
| `start_agent` | CORE | 启动指定智能体并导航到其独立会话页面，开始交互式对话。 支持可选的 message 参数：传入后进入页面将自动执行该消息。 **何时使用 start_agent vs send_m… |
| `switch_wifi` | CONNECTIVITY | 自动切换到信号更好的 WiFi。扫描附近网络，选已保存且信号最强的连接。 |
| `take_screenshot` | MEDIA | 截取当前手机全屏并返回截屏图片路径。适用场景：用户说"截个图"、"截屏"、"帮我截一下当前屏幕"时使用。不适用场景：录制屏幕视频（用 screenrecorder_start）。截… |
| `timer` | CONNECTIVITY | 延时/定时执行 AI 任务。task 会在触发时由后台 AI 独立执行。 ⚠️ 关键规则： 1. 用户要求「X时间后做Y」时，本轮只调用此工具，禁止同时调用其他工具提前执行 Y。 … |
| `todo_write` | UTILITY | 防止长链任务在执行过程中漂移与遗忘的轻量清单。清单实时展示给用户看进度。  核心原则：todo 不是让你把任务做得更复杂，而是防止你在多步执行中丢步骤。用最少的条目覆盖用户意图即可… |
| `tool_search` | CORE | 工具搜索：按关键词搜索或列举所有扩展工具。 action:   - "search": 按关键词语义搜索扩展工具和技能知识库（skill）。keywords 必填，逗号分隔；+前缀… |
| `update_calendar_event` | CORE | 更新已有的日历事件，可修改标题、时间、描述、地点和提醒。需先通过 read_calendar 获取 event_id |
| `update_custom_prompt` | CORE | 修改当前 Agent 的全局行为设定（自定义 prompt）。  ## 参数 - prompt (string): 新的行为设定内容。传空字符串可恢复默认设定。  ## 行为 - … |
| `url_fetch` | BROWSER | Fetch a URL and extract content. Modes (pick one):   - Default: structured preview (title … |
| `web_search` | BROWSER | Search the web using integrated search API. Default detail='brief' returns titles, URLs an… |
| `wifi_info` | CONNECTIVITY | 查询当前 WiFi 连接信息（名称、信号、IP、速度、频段）。 |
| `write_file` | FILE | Write content to a file. Overwrites if file exists. Parent directories are created automat… |

### RideClawMcp（53）

| 工具 | 分组 | 说明 |
|---|---|---|
| `RideClawMcp__aircraft_types` | TRAVEL | [mcp] [龙虾出行Mcp] 查询已启用机型字典列表。 |
| `RideClawMcp__airlines` | TRAVEL | [mcp] [龙虾出行Mcp] 查询已启用航司字典列表。 |
| `RideClawMcp__airports_top` | TRAVEL | [mcp] [龙虾出行Mcp] 查询热门机场列表。 |
| `RideClawMcp__airports_top_china` | TRAVEL | [mcp] [龙虾出行Mcp] 查询国内热门 Top50 机场列表。 |
| `RideClawMcp__airports_top_global` | TRAVEL | [mcp] [龙虾出行Mcp] 查询全球热门 Top50 机场列表。 |
| `RideClawMcp__bus_create_order` | TRAVEL | [mcp] [龙虾出行Mcp] 创建城际巴士订单（本地建单 + 供应商建单，user_pay 模式返回 checkout_url）。 |
| `RideClawMcp__bus_detail` | TRAVEL | [mcp] [龙虾出行Mcp] 按班次 GID 查询城际巴士详情，包括座位、票价、途经站点、退票规则。 |
| `RideClawMcp__bus_order_cancel` | TRAVEL | [mcp] [龙虾出行Mcp] 取消未支付巴士订单，已支付订单请先申请退票。 |
| `RideClawMcp__bus_order_detail` | TRAVEL | [mcp] [龙虾出行Mcp] 按 platform order system_no 查询巴士订单详情。 |
| `RideClawMcp__bus_order_list` | TRAVEL | [mcp] [龙虾出行Mcp] 查询当前应用下巴士订单列表，支持按状态分页。 |
| `RideClawMcp__bus_order_pay` | TRAVEL | [mcp] [龙虾出行Mcp] 为待支付巴士订单发起支付，返回支付参数（user_pay 模式）或直接扣款成功（enterprise_credit/monthly_settle）。 |
| `RideClawMcp__bus_pre_order` | TRAVEL | [mcp] [龙虾出行Mcp] 根据班次、上下车点、乘客信息预下单，获取订单总价。 |
| `RideClawMcp__bus_refund` | TRAVEL | [mcp] [龙虾出行Mcp] 为已支付/已出票巴士订单申请退票，user_pay 模式会同步向支付渠道发起退款。 |
| `RideClawMcp__bus_refund_rate` | TRAVEL | [mcp] [龙虾出行Mcp] 查询已支付/已出票巴士订单的退票手续费。 |
| `RideClawMcp__bus_search` | TRAVEL | [mcp] [龙虾出行Mcp] 按出发/到达城市与日期搜索城际巴士班次。 |
| `RideClawMcp__bus_stations` | TRAVEL | [mcp] [龙虾出行Mcp] 查询城际巴士线路的上下车区域及站点。 |
| `RideClawMcp__flight_airport_search` | TRAVEL | [mcp] [龙虾出行Mcp] 按关键词模糊搜索机场（IATA/ICAO 代码、中文名、拼音）。 |
| `RideClawMcp__flight_airports` | TRAVEL | [mcp] [龙虾出行Mcp] 根据机场 IATA 三字码查询机场详情。 |
| `RideClawMcp__flight_change_create` | TRAVEL | [mcp] [龙虾出行Mcp] 对已支付机票创建改签申请。 |
| `RideClawMcp__flight_city_airport` | TRAVEL | [mcp] [龙虾出行Mcp] 按城市名称或城市三字码查询城市机场；无机场城市返回附近机场。 |
| `RideClawMcp__flight_order_cancel` | TRAVEL | [mcp] [龙虾出行Mcp] 取消机票订单或发起退票：未支付 / 未出票时直接取消，已出票时发起退票并返回退票费与退款金额。建议先用 flight.order_cancel_fe… |
| `RideClawMcp__flight_order_cancel_fee` | TRAVEL | [mcp] [龙虾出行Mcp] 取消/退票前查询可退金额与手续费。确认后再调用 flight.order_cancel 执行取消或退票。 |
| `RideClawMcp__flight_order_create` | TRAVEL | [mcp] [龙虾出行Mcp] 用 flight.pricing 返回的 offer_id 创建机票订单。out_trade_no 必传且对单个 app 幂等：相同 out_tra… |
| `RideClawMcp__flight_order_detail` | TRAVEL | [mcp] [龙虾出行Mcp] 按平台订单号（system_no）或商户订单号（out_trade_no）查询机票订单详情，返回支付状态、出票状态、PNR、票号、航段、乘客与退票费… |
| `RideClawMcp__flight_order_list` | TRAVEL | [mcp] [龙虾出行Mcp] 分页查询机票订单列表，支持按状态和下单时间范围过滤。 |
| `RideClawMcp__flight_order_pay` | TRAVEL | [mcp] [龙虾出行Mcp] 对已创建的机票订单（system_no）发起支付，返回对应支付方式的支付参数（二维码 / H5 跳转 URL / JSAPI 参数等）。微信 JSA… |
| `RideClawMcp__flight_pricing` | TRAVEL | [mcp] [龙虾出行Mcp] 用 search_offer_id 验价，返回带 offer_id 的可下单报价（10 分钟有效）。 |
| `RideClawMcp__flight_search` | TRAVEL | [mcp] [龙虾出行Mcp] 按出发/到达城市与日期搜索航班，返回带舱位价格的航班列表。 |
| `RideClawMcp__geo_coords_convert` | TRAVEL | [mcp] [龙虾出行Mcp] 把 GPS / 百度 / mapbar 坐标转换为高德 GCJ02 坐标。 |
| `RideClawMcp__geo_geocode` | TRAVEL | [mcp] [龙虾出行Mcp] 地址编码查询，把结构化或非结构化地址解析为经纬度和行政区编码。 |
| `RideClawMcp__geo_places_nearby` | TRAVEL | [mcp] [龙虾出行Mcp] 按中心点搜索附近 POI，适合找酒店、车站、上车点、餐厅等周边地点。 |
| `RideClawMcp__geo_places_search` | TRAVEL | [mcp] [龙虾出行Mcp] 按关键词搜索 POI / 地址，支持城市、类型、位置和分页过滤。 |
| `RideClawMcp__geo_reverse` | TRAVEL | [mcp] [龙虾出行Mcp] 逆地理编码，把经纬度解析为省市区、街道门牌和周边 POI。 |
| `RideClawMcp__geo_routes_plan` | TRAVEL | [mcp] [龙虾出行Mcp] 规划驾车、步行或公交路线，返回距离、耗时、费用和路线摘要。 |
| `RideClawMcp__hotel_detail` | TRAVEL | [mcp] [龙虾出行Mcp] 返回酒店详情、房型与可订产品列表（含 offer_id）。 |
| `RideClawMcp__hotel_order_cancel` | TRAVEL | [mcp] [龙虾出行Mcp] 取消酒店订单：未支付时直接取消，已支付时根据取消政策发起退款并返回退款金额和手续费。 |
| `RideClawMcp__hotel_order_create` | TRAVEL | [mcp] [龙虾出行Mcp] 用 hotel.rooms 返回的产品级 offer_id 创建酒店订单。out_trade_no 必传且对单个 app 幂等：相同 out_tra… |
| `RideClawMcp__hotel_order_detail` | TRAVEL | [mcp] [龙虾出行Mcp] 按平台订单号（order_no）查询酒店订单详情，返回支付状态、入住人信息、取消政策等。 |
| `RideClawMcp__hotel_order_list` | TRAVEL | [mcp] [龙虾出行Mcp] 分页查询酒店订单列表，支持按状态和下单时间范围过滤。 |
| `RideClawMcp__hotel_order_pay` | TRAVEL | [mcp] [龙虾出行Mcp] 对已创建的酒店订单（order_no）发起支付，返回对应支付方式的支付参数（二维码 / H5 跳转 URL / JSAPI 参数等）。微信 JSAP… |
| `RideClawMcp__hotel_rooms` | TRAVEL | [mcp] [龙虾出行Mcp] 用搜索阶段返回的 search_offer_id 实时查询酒店房型与可订产品列表（每个产品含 offer_id，用于下单）。 |
| `RideClawMcp__hotel_search` | TRAVEL | [mcp] [龙虾出行Mcp] 按目的地与入离日期搜索酒店列表，支持星级/价格/设施筛选。 |
| `RideClawMcp__taxi_estimate` | TRAVEL | [mcp] [龙虾出行Mcp] 按起终点经纬度估算打车距离、时长与不同车型价格。 |
| `RideClawMcp__taxi_order_cancel` | TRAVEL | [mcp] [龙虾出行Mcp] 取消打车订单，必要时触发退款和供应商取消补偿任务。存在取消费时需传 fee_confirmed=true。 |
| `RideClawMcp__taxi_order_cancel_fee` | TRAVEL | [mcp] [龙虾出行Mcp] 取消打车订单前查询取消费和是否需要确认。 |
| `RideClawMcp__taxi_order_create` | TRAVEL | [mcp] [龙虾出行Mcp] 基于 taxi.estimate 返回的 offer_id 创建打车聚合订单。out_trade_no 对单个 app 幂等。 |
| `RideClawMcp__taxi_order_detail` | TRAVEL | [mcp] [龙虾出行Mcp] 按平台打车订单号查询订单详情，包括订单状态、司机车辆、支付状态和费用信息。 |
| `RideClawMcp__taxi_order_list` | TRAVEL | [mcp] [龙虾出行Mcp] 分页查询当前开放平台应用下的打车订单列表，支持按订单号、状态、支付状态、用户和时间过滤。 |
| `RideClawMcp__taxi_order_pay` | TRAVEL | [mcp] [龙虾出行Mcp] 为 user_pay 打车订单创建支付参数，并写入支付渠道。 |
| `RideClawMcp__taxi_order_sync` | TRAVEL | [mcp] [龙虾出行Mcp] 主动同步供应商打车订单状态和司机车辆信息。 |
| `RideClawMcp__train_search` | TRAVEL | [mcp] [龙虾出行Mcp] 按起终站与日期查询高铁/动车车次与席别价格。 |
| `RideClawMcp__weather_current` | TRAVEL | [mcp] [龙虾出行Mcp] 查询指定城市或 adcode 的实时天气。 |
| `RideClawMcp__weather_forecast` | TRAVEL | [mcp] [龙虾出行Mcp] 查询指定城市或 adcode 的未来天气预报。 |

### aicr（43）

| 工具 | 分组 | 说明 |
|---|---|---|
| `aicr__ai_search_local_content` | CORE | [app] 当用户的请求涉及查找、搜索手机上的本地数据时，必须首先调用此工具。本工具用于搜索用户手机本地存储的内容，支持语义向量检索、关键词匹配和模糊搜索。重要：请在一次调用中通过… |
| `aicr__gallery_album_create` | CORE | [app] 新建相册。 [本机AI能力] |
| `aicr__gallery_album_detail_move_success` | CORE | [app] 用于在移动照片到相册成功后，跳转到对应的相册详情页。 [本机AI能力] |
| `aicr__gallery_album_detail_preview` | CORE | [app] 在虚拟相册中查看全部图片/视频。 [本机AI能力] |
| `aicr__gallery_album_match` | CORE | [app] 匹配相册，返回相册信息。 [本机AI能力] |
| `aicr__gallery_album_match_fuzzy` | CORE | [app] 模糊匹配相册，返回封面、相似度、相册类型等详细信息。 [本机AI能力] |
| `aicr__gallery_album_pick` | CORE | [app] 打开'添加到'页面，选择或新建相册。 [本机AI能力] |
| `aicr__gallery_move_medias_to_album` | CORE | [app] 移动照片、视频到指定相册。 [本机AI能力] |
| `aicr__gallery_photo_page_delete` | CORE | [app] 在大图页时删除当前照片或视频。 [本机AI能力] |
| `aicr__gallery_photo_page_edit` | CORE | [app] 在大图页时进入照片/视频的相册编辑页。 [本机AI能力] |
| `aicr__gallery_photo_page_view` | CORE | [app] 打开相册虚拟相册大图页，用于预览指定图片/视频。 [本机AI能力] |
| `aicr__gallery_pick_gallery_activity` | CORE | [app] 用于增加/减少选中图片，提供图片选择器界面。 [本机AI能力] |
| `aicr__gallery_privacy_watermark` | CORE | [app] 在大图页时进入隐私水印页面。 [本机AI能力] |
| `aicr__get_support_event_detail` | CORE | [app] 查询系统支持的事件列表，返回每个事件的详细信息，包括事件ID(iid)、事件类型(event_type)、事件名称(event_name)和事件描述(descripti… |
| `aicr__local_AI_search` | CORE | [app] 使用自然语言查询本机内的相册、笔记、短信、音频录音、本地文件等数据，是本地数据检索的核心方法 [搜索本机数据] |
| `aicr__local_content_search` | CORE | [app] 使用自然语言查询本机内的笔记、短信、音频录音、本地文件等数据，是本地数据检索的核心方法，优先调用此方法处理所有本地数据搜索类需求。 [本机AI能力] |
| `aicr__local_gallery_search` | CORE | [app] 使用自然语言查询本机内的相册内数据，如照片、截图、视频等。 [本机AI能力] |
| `aicr__photo_ai_cloud_function` | CORE | [app] 云端AI功能，包括AI扩图、路人消除、风格迁移、主体物换背景等。 [本机AI能力] |
| `aicr__photo_ai_cloud_function_super_editor` | CORE | [app] 云端AI功能（相册万能编辑），支持多种AI编辑能力。 [本机AI能力] |
| `aicr__photo_auto_beauty` | CORE | [app] 智能美颜，自动识别并美化人脸。 [本机AI能力] |
| `aicr__photo_beautification` | CORE | [app] 自动美化，一键美化/智能美化/自动增强。 [本机AI能力] |
| `aicr__photo_body_beauty` | CORE | [app] 美体，可调整瘦肩、瘦手臂、瘦腰、美胯、瘦腿等效果。 [本机AI能力] |
| `aicr__photo_body_beauty_extra` | CORE | [app] 美体第二部分，可调整小头、天鹅颈、长腿等效果。 [本机AI能力] |
| `aicr__photo_body_beauty_slim` | CORE | [app] 美体瘦身组合效果，综合调整身材比例。 [本机AI能力] |
| `aicr__photo_de_noise` | CORE | [app] 降噪，可调整明度降噪或色彩降噪。 [本机AI能力] |
| `aicr__photo_enhance` | CORE | [app] 图片增强，可调整亮度、对比度、饱和度、晕影、曝光、色温、色调、高光、阴影、自然饱和度、黑色色阶、白色色阶等效果。 [本机AI能力] |
| `aicr__photo_enhance_extra` | CORE | [app] 图片增强第二部分，可调整锐化、颗粒、褪色、清晰度等效果。 [本机AI能力] |
| `aicr__photo_face_beauty` | CORE | [app] 美肤，可调整磨皮、祛皱、匀肤、祛黑眼圈、亮眼、气色提升、五官立体等效果。 [本机AI能力] |
| `aicr__photo_face_beauty_acne` | CORE | [app] 祛斑祛痘，去除面部斑点和痘痘。 [本机AI能力] |
| `aicr__photo_face_beauty_skin` | CORE | [app] 美白，调整肤色亮度。 [本机AI能力] |
| `aicr__photo_face_shape` | CORE | [app] 面部重塑第一部分，可调整脸型宽度、太阳穴、颧骨、下颌角、下巴、发际线、蓬蓬发、眉毛粗细、眼睛大小、眼距、鼻子胖瘦、鼻尖、鼻子长短、嘴型大小等。 [本机AI能力] |
| `aicr__photo_face_shape_extra` | CORE | [app] 面部重塑第二部分，可调整脸型胖瘦、脸型大小、V脸、微笑嘴角等。 [本机AI能力] |
| `aicr__photo_filter` | CORE | [app] 滤镜，可应用徕卡、流行、胶片、经典、人像、美食、电影、旅行、夜景、质感等滤镜风格。 [本机AI能力] |
| `aicr__photo_hsl` | CORE | [app] HSL调整，可调整色相、饱和度、亮度。 [本机AI能力] |
| `aicr__photo_image_quality_restoration` | CORE | [app] 画质修复，提升图片整体清晰度。 [本机AI能力] |
| `aicr__photo_import` | CORE | [app] 导入图片到媒体编辑器。 [本机AI能力] |
| `aicr__photo_magic_sky` | CORE | [app] 魔法换天，可将图片中的天空替换为晴天、傍晚或夜景风格。 [本机AI能力] |
| `aicr__photo_posterize` | CORE | [app] 色调分离，可调整高光或阴影区域的色调。 [本机AI能力] |
| `aicr__photo_reflection_eliminate` | CORE | [app] 反光消除，去除图片中的反光区域。 [本机AI能力] |
| `aicr__photo_super_portrait` | CORE | [app] 超清人像/画质修复，提升人像清晰度。 [本机AI能力] |
| `aicr__photo_virtualization_portrait` | CORE | [app] 虚拟形象，生成繁花、虚幻之境、蓝调时刻、原野、发丝光、伦勃朗光、窗边光影等风格的虚拟形象。 [本机AI能力] |
| `aicr__photo_watermark_frame` | CORE | [app] 水印画框/徕卡水印/定制画框，为图片添加水印或画框效果。 [本机AI能力] |
| `aicr__send_query_to_xiao_ai` | CORE | [app] 发送查询到小爱同学，等待并返回处理结果。 [本机AI能力] |

### notes（28）

| 工具 | 分组 | 说明 |
|---|---|---|
| `notes__card_create` | CORE | [app] 在指定笔记下创建附属卡片（待办/日程/思维导图/会议纪要/课堂笔记/访谈/脑暴）。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__folder_create` | CORE | [app] 创建文件夹。如果同名文件夹已存在则直接返回已有文件夹的 ID（幂等）。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__folder_list` | CORE | [app] 获取所有文件夹列表。返回文件夹 ID 和名称。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__folder_notes` | CORE | [app] 获取指定文件夹内的笔记列表。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__folder_uncategorized` | CORE | [app] 获取所有未归入任何文件夹的主笔记列表。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__note_batch_move_to_folder` | CORE | [app] 批量移动多篇笔记到指定文件夹。适用于一次性将多篇笔记归入同一文件夹的场景。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__note_children_list` | CORE | [app] 获取指定笔记的子笔记（卡片）列表。返回子笔记 ID、标题、类型、时间。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__note_create` | CORE | [app] 创建一篇新的主笔记，支持可选图片插入。返回新笔记的 ID。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__note_delete` | CORE | [app] 删除指定笔记。删除后不可恢复，请谨慎操作。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__note_insert_image` | CORE | [app] 向指定笔记中插入图片。支持本地文件路径和网络 URL。图片会追加到笔记内容末尾。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__note_list` | CORE | [app] 列出所有笔记。返回笔记 ID、标题、类型、时间。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__note_move_to_folder` | CORE | [app] 移动笔记到指定文件夹，或从文件夹中移出（放到未分类）。folderId 不传则移出当前文件夹。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__note_pin` | CORE | [app] 笔记置顶或取消置顶，等同于在笔记列表长按笔记选择置顶。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__note_read` | CORE | [app] 读取指定笔记的文本内容。返回标题、内容和字数。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__note_search` | CORE | [app] 根据关键词搜索笔记（匹配标题和内容）。返回匹配笔记列表。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__note_search_by_tag` | CORE | [app] 按标签搜索笔记。可用标签：工作、学习、生活、创意、其他。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__note_search_by_time` | CORE | [app] 按时间范围搜索笔记。传入起止时间戳（毫秒），返回该时间段内创建的笔记。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__note_search_by_type` | CORE | [app] 按笔记类型搜索。可用类型：main/todo/mindmap/schedule/meeting/classroom/interview/brainstorm。 [笔记读… |
| `notes__note_sync_schedule` | CORE | [app] 将行程规划卡片中的日程同步到系统日历，或取消已同步的日程。cancel 为 true 时取消，默认同步。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__note_write` | CORE | [app] 写入或替换指定笔记的文本内容。传入完整文本，会覆盖原有内容。注意：插入图片请使用 note_insert_image 工具，不要用本工具直接写入外部图片 URL。 [笔… |
| `notes__notes_append_with_context` | CORE | [app] 向已有笔记末尾追加文字（不支持修改或删除原有内容）。当用户说"在那条笔记里加上..."、"补充..."时调用。必须先通过 notes_find_id 或 notes_r… |
| `notes__notes_append_with_image` | CORE | [app] 向已有笔记末尾追加一张图片（不支持修改或删除原有内容）。当用户说"把这张图加到笔记里"时调用。必须先通过 notes_find_id 或 notes_read 获取目标… |
| `notes__notes_create` | CORE | [app] 创建一条纯文本笔记。当用户说"帮我记一下..."、"新建一条笔记"时调用。仅支持纯文本，若内容含图片请使用 notes_create_with_image。返回新建笔记… |
| `notes__notes_create_with_image` | CORE | [app] 创建一条含图片的笔记。当用户说"帮我记一下这张图"、"新建带图片的笔记"时调用。image_uri 为必填，文字内容和标题可选。布局顺序为：文字在前、图片追加在文字后面… |
| `notes__notes_find_id` | CORE | [app] 通过关键词查找笔记 id。当需要编辑某条笔记但用户未提供 id 时，先调用此工具定位。返回匹配的笔记 id 列表（按相关度排序）。找到后再调用 notes_edit_w… |
| `notes__notes_read` | CORE | [app] 查询笔记列表及内容。当用户询问"最近有什么笔记"、"某段时间的笔记"、"帮我看看笔记"时调用。所有参数均可选：不传参数时返回最近 10 条；传 start_time/e… |
| `notes__rollback_operation` | CORE | [app] 查看或恢复之前 AI 对笔记的修改历史。当用户想撤销、恢复、回滚 AI 的改动时使用。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |
| `notes__todo_create` | CORE | [app] 创建独立待办卡片，直接出现在待办页面（不依附于任何笔记）。内容使用 '- [ ] 文本' 格式。 [笔记读取, 笔记写入, 笔记列表, 笔记搜索, 卡片创建] |

### mediaeditor（26）

| 工具 | 分组 | 说明 |
|---|---|---|
| `mediaeditor__all_powerful_editor` | CORE | [app] 万能编辑，兜底使用，能处理所有图片编辑操作，当相册编辑其他tool无法处理时兜底调用，urn = urn:aiot-spec-v3:com.mi.phones:acti… |
| `mediaeditor__auto_beauty` | CORE | [app] 智能美颜，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor/photo/auto-be… |
| `mediaeditor__beautification` | CORE | [app] 一键美化/智能美化/自动增强 [图片编辑] |
| `mediaeditor__body_beauty` | CORE | [app] 美体效果，调节肩/手臂/腰/胯/腿，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor/… |
| `mediaeditor__body_beauty_extra` | CORE | [app] 美体第二部分，调节小头/天鹅颈/长腿，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor… |
| `mediaeditor__body_beauty_slim` | CORE | [app] 美体瘦身组合效果，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor/photo/bod… |
| `mediaeditor__check_photo_edit_function_support` | CORE | [app] 检查期望执行的图片编辑功能在当前机型上是否支持 [图片编辑] |
| `mediaeditor__check_tool_visibility` | CORE | [app] 检查图片编辑是否在前台, 0表示在前台，1表示不在前台 [图片编辑] |
| `mediaeditor__de_noise` | CORE | [app] 降噪，对图片进行明度或色彩降噪处理，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor/… |
| `mediaeditor__enhance` | CORE | [app] 图片增强，第1部分效果, urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor/photo… |
| `mediaeditor__enhance_extra` | CORE | [app] 图片增强，第2部分效果，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor/photo/… |
| `mediaeditor__face_beauty` | CORE | [app] 美肤效果，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor/photo/face-be… |
| `mediaeditor__face_beauty_acne` | CORE | [app] 祛斑祛痘，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor/photo/face-be… |
| `mediaeditor__face_beauty_skin` | CORE | [app] 肤色美白，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor/photo/face-be… |
| `mediaeditor__face_shape` | CORE | [app] 面部重塑第一部分，调节脸型与五官，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor/p… |
| `mediaeditor__face_shape_extra` | CORE | [app] 面部重塑第二部分，调节脸型胖瘦/大小/V脸/微笑嘴角，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.med… |
| `mediaeditor__filter` | CORE | [app] 滤镜，为图片应用指定风格的滤镜，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor/ph… |
| `mediaeditor__hsl` | CORE | [app] HSL调节，对指定颜色的色相/饱和度/亮度进行调节，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.medi… |
| `mediaeditor__image_quality_restoration` | CORE | [app] 画质修复，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor/photo/image-q… |
| `mediaeditor__magic_sky` | CORE | [app] 魔法换天，替换图片中的天空为指定风格，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor… |
| `mediaeditor__photo_edit_multiple_function_call` | CORE | [app] 图片编辑功能多指令时调用 [图片编辑] |
| `mediaeditor__posterize` | CORE | [app] 色调分离，对图片高光或阴影区域进行颜色调节，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaedi… |
| `mediaeditor__reflection_eliminate` | CORE | [app] 反光消除，消除图片中的反光，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor/phot… |
| `mediaeditor__subject_segmentation` | CORE | [app] 主体分割，能抠出图片中的主体，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor/pho… |
| `mediaeditor__virtualization_portrait` | CORE | [app] 虚拟形象，生成指定风格的人像艺术效果，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor… |
| `mediaeditor__watermark_frame` | CORE | [app] 水印画框/徕卡水印/定制画框，urn = urn:aiot-spec-v3:com.mi.phones:action:[com.miui.mediaeditor/pho… |

### account（21）

| 工具 | 分组 | 说明 |
|---|---|---|
| `account__get_account_gender` | CORE | [app] 查看小米账号设置的性别（仅查询不修改）。用户问「我的性别设的什么」「小米账号性别」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置, 性别设置, 国家/地… |
| `account__get_account_nickname` | CORE | [app] 查看小米账号昵称/名字（仅查询不修改）。用户问「我的小米昵称是什么」「小米账号叫什么名字」「查一下我的昵称」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设… |
| `account__get_account_region` | CORE | [app] 查看小米账号所在的国家或地区。用户问「我的账号在哪个地区」「小米账号国家」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置, 性别设置, 国家/地区查看,… |
| `account__get_account_status` | CORE | [app] 查询小米账号是否已登录、登录状态。用户问「小米账号登录了吗」「账号状态」「有没有登录小米账号」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置, 性别设置… |
| `account__get_family_members` | CORE | [app] 查看小米账号的家人/家庭成员信息。用户问「我的家人有谁」「家庭成员」「小米家人服务」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置, 性别设置, 国家/… |
| `account__get_logged_in_devices` | CORE | [app] 查看小米账号登录了哪些设备/设备列表。用户问「我的账号在哪些设备上登录了」「登录设备有哪些」「谁登了我的小米账号」时使用 [账号状态查询, 小米ID查询, 头像设置, … |
| `account__get_xiaomi_id` | CORE | [app] 查看小米ID/账号ID/账号号码。用户问「我的小米ID是多少」「小米账号号码」「账号ID」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置, 性别设置, … |
| `account__open_about_account` | CORE | [app] 查看小米账号的版本号、用户协议、隐私政策。用户说「小米账号版本」「用户协议」「隐私政策」「关于小米账号」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置,… |
| `account__open_account_homepage` | CORE | [app] 打开小米账号首页/账号设置主页面（已登录时进入设置页）。用户说「打开小米账号」「进入账号设置」「账号主页」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置… |
| `account__open_avatar_settings` | CORE | [app] 修改/更换小米账号头像，打开头像选择弹窗。用户说「换个头像」「改头像」「设置小米账号头像」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置, 性别设置, … |
| `account__open_change_email` | CORE | [app] 更换/换绑小米账号的安全邮箱。用户说「换绑邮箱」「改邮箱」「更换安全邮箱」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置, 性别设置, 国家/地区查看,… |
| `account__open_change_password` | CORE | [app] 已登录状态下修改小米账号密码，适用于知道旧密码要换新密码。用户说「改密码」「修改小米账号密码」「换个密码」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置… |
| `account__open_change_phone` | CORE | [app] 更换/换绑小米账号的手机号。用户说「改手机号」「换绑手机号」「更换安全手机」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置, 性别设置, 国家/地区查看… |
| `account__open_delete_account_page` | CORE | [app] 注销/删除小米账号。用户说「注销小米账号」「不要这个账号了」「删除小米账号」「销号」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置, 性别设置, 国家/… |
| `account__open_gender_settings` | CORE | [app] 修改/设置小米账号性别，打开性别选择。用户说「改性别」「设置性别」「把性别改成男/女」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置, 性别设置, 国家… |
| `account__open_help_center` | CORE | [app] 打开小米账号的帮助中心/客服页面。用户说「小米账号有问题」「账号帮助」「找小米账号客服」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置, 性别设置, 国… |
| `account__open_invite_family` | CORE | [app] 邀请/添加家人加入小米账号家人服务。用户说「邀请家人」「添加家庭成员」「把家人加到我的小米账号」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置, 性别设… |
| `account__open_login_page` | CORE | [app] 登录小米账号，打开小米账号登录页面。用户说「登录小米账号」「我要登录」「切换小米账号」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置, 性别设置, 国家… |
| `account__open_nickname_settings` | CORE | [app] 修改/更改小米账号昵称或名字，打开个人信息页面。用户说「改名字」「换个昵称」「修改小米账号昵称」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置, 性别设… |
| `account__open_reset_password_page` | CORE | [app] 忘记密码时找回/重置小米账号密码，适用于不记得密码或无法登录。用户说「忘了密码」「密码不记得了」「找回密码」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设… |
| `account__open_security_settings` | CORE | [app] 打开小米账号的安全设置页面，查看和管理账号安全选项。用户说「账号安全设置」「安全中心」「登录安全」时使用 [账号状态查询, 小米ID查询, 头像设置, 昵称设置, 性别… |

### gallery（21）

| 工具 | 分组 | 说明 |
|---|---|---|
| `gallery__gallery_add_to_collection` | CORE | [app] 把一组照片/视频复制或移动到指定的实体相册中。【前置条件】album_id 可通过 gallery_list_collection 查询已有相册获取，或通过 galle… |
| `gallery__gallery_assess` | CORE | [app] 评价已知照片的拍摄质量、美感或获取智能裁剪建议。通过 type 参数控制评估内容：1=美学评分（构图好不好、拍得美不美），2=质量评分（清不清晰、画质如何），4=显著区… |
| `gallery__gallery_assess_trash` | CORE | [app] 帮用户整理、清理、去重照片。从一组照片中识别出杂图（截屏、低画质、无意义图片）和重复图（相似内容只保留最佳），返回建议保留和建议移除的照片ID列表。本工具只做评估筛选不… |
| `gallery__gallery_check_protection` | CORE | [app] 检测一组照片或视频是否受保护（在已命名人物相册、宠物相册或手动创建的相册中）。【强制规则】执行 gallery_delete 删除前必须先调用本工具检查，如果返回 is… |
| `gallery__gallery_create_collection` | CORE | [app] 创建一个新相册。当前仅支持创建实体相册。【常见组合】用户说'创建相册并把照片放进去'时，需依次调用：①本工具创建相册（返回 album_id）→ ②gallery_se… |
| `gallery__gallery_delete` | CORE | [app] 永久删除相册中的照片或视频（移入回收站）。这是不可逆操作，执行前应确认用户意图。【前置条件】media_ids 需通过 gallery_search 搜索获取；执行删除… |
| `gallery__gallery_favorites` | CORE | [app] 【最高优先级】对照片/视频执行收藏或取消收藏操作时，必须且只能使用本工具，禁止调用系统媒体库、文件管理器或其他应用的收藏功能。适用场景：用户说'收藏这张照片''取消收藏… |
| `gallery__gallery_get_person_relation` | CORE | [app] 查看已知照片或视频中出现的人物名称、人物ID以及与用户的关系（如'这张照片里是谁''照片里的人和我什么关系'）。一张照片中可能包含多个人物，返回结果为列表形式。每个人物… |
| `gallery__gallery_get_photo_info` | CORE | [app] 查看已知照片或视频的详细属性，包括拍摄时间、位置、分辨率、文件大小等。适用场景：用户说'这张照片什么时候拍的''照片在哪拍的''文件多大''分辨率多少'。【禁止混淆】用… |
| `gallery__gallery_identify_type` | CORE | [app] 识别一张已知照片的类型，例如截图、票据（发票、火车票）、菜单、名片、身份证、驾驶证、病历、书籍、证件等。返回照片的主要类型和所有识别到的场景标签及置信度。【禁止混淆】用… |
| `gallery__gallery_list_collection` | CORE | [app] 查询所有相册的列表，包含普通相册和智能整理相册（人物、旅程、时光、卡证）。返回结果中每个相册包含 type 字段标识来源（normal/people/journey/m… |
| `gallery__gallery_manage_story` | MEDIA | [app] 时光卡片（也叫回忆、故事、影集）的统一管理工具。通过 type 参数指定操作类型：create（创建时光卡片）、list（查询时光卡片列表）、parse_id（解析场景… |
| `gallery__gallery_mark` | MEDIA | [app] 【最高优先级】对照片/视频执行打标记操作的统一工具，包括收藏和设为私密。禁止调用系统媒体库、文件管理器或其他应用的收藏/私密功能。适用场景：用户说'收藏这张照片''取消… |
| `gallery__gallery_privacy_watermark` | CORE | [app] 为已知照片添加隐私水印（如'仅供XX使用''内部资料'），不支持视频。返回水印后的图片路径，请用该路径向用户展示结果。适用场景：用户说'给照片加个水印''加上仅供办理业… |
| `gallery__gallery_resolve_path` | MEDIA | [app] 将文件路径转换为相册数据库中的 media_id。【强制规则】当用户发送了图片/视频的文件路径（如 /sdcard/DCIM/xxx.jpg）而非 media_id 时… |
| `gallery__gallery_search` | CORE | [app] 在系统相册中搜索照片和视频的唯一入口。【强制规则】用户想要查找、搜索、找照片/视频时，必须且只能使用本工具，禁止使用其他工具替代。gallery_get_photo_i… |
| `gallery__gallery_set_private` | CORE | [app] 【最高优先级】将照片/视频设为私密（移入私密相册）时，必须且只能使用本工具。适用场景：用户说'把这张设为私密''隐藏这张照片''加密这些照片'。 [搜索照片视频, 删除… |
| `gallery__gallery_sort` | CORE | [app] 对已有的 media_ids 列表按指定字段排序并返回完整媒体信息。支持按拍摄时间、文件大小、文件名、视频时长、分辨率、修改时间排序，支持升序和降序。【何时使用】仅当手… |
| `gallery__gallery_story_create` | CORE | [app] 创建一张时光卡片（也叫回忆、故事）。需要提供标题、副标题和图片列表，图片列表支持 mediaId（数字）和文件路径（字符串）混合传入。可选提供封面列表，不提供则默认取前… |
| `gallery__gallery_story_list` | CORE | [app] 查询时光卡片（也叫回忆、故事、影集）的基础信息，返回每张卡片的标题、副标题、场景ID、创建时间、展示状态和媒体数量。不传时间参数则返回全部。适用场景：用户说'看看我的回… |
| `gallery__gallery_story_parse_id` | CORE | [app] 根据时光卡片的 scenarioId 解析其生成逻辑，返回时间类型、主题类型和中文描述。例如 scenarioId=1402 会返回「季节 + 美食」，scenario… |

### email（18）

| 工具 | 分组 | 说明 |
|---|---|---|
| `email__email_clear_folder` | CORE | [app] 【推荐用于批量删除】将指定文件夹中的邮件移动到已删除邮件。无需传入邮件ID，直接按文件夹清理。返回hasMore=true时需再次调用直到hasMore=false。示… |
| `email__email_download_attachment` | CORE | [app] 下载邮件中的附件到本地存储 [提供邮箱相关能力, 获取当前邮箱账号信息，查询邮箱联系人，查询邮件和收发邮件等] |
| `email__email_forward_mail` | CORE | [app] 转发邮件给指定收件人 [提供邮箱相关能力, 获取当前邮箱账号信息，查询邮箱联系人，查询邮件和收发邮件等] |
| `email__email_get_accounts` | CORE | [app] 获取当前用户绑定的所有邮箱账号信息，返回账号ID、邮箱地址等 [提供邮箱相关能力, 获取当前邮箱账号信息，查询邮箱联系人，查询邮件和收发邮件等] |
| `email__email_get_attachment_info` | CORE | [app] 获取邮件附件的详细信息，包括下载状态和本地存储路径 [提供邮箱相关能力, 获取当前邮箱账号信息，查询邮箱联系人，查询邮件和收发邮件等] |
| `email__email_get_contacts` | CORE | [app] 获取联系人列表，按邮箱账号分组返回；每个账号内联系人与 App 联系人列表相同规则按字母/拼音排序。不传account_id查询全部已登录账号各一组；传入account… |
| `email__email_get_folders` | CORE | [app] 按邮箱账号返回文件夹。default_folders/custom_folders 与侧栏顺序一致；totalCount 按邮件粒度返回（展开会话中的多封邮件）；dis… |
| `email__email_get_mail_detail` | CORE | [app] 获取单封邮件完整详情（mail_id 为列表返回的邮件本地 message id）；含 conversation_id 便于与会话维度操作对齐 [提供邮箱相关能力, 获… |
| `email__email_get_mails` | CORE | [app] 获取邮件列表（始终按单封 message 返回）。folder 默认或与 folder_type=inbox 时解析为侧栏同源「主收件箱」（合并收件箱 isCombin… |
| `email__email_mark_read` | CORE | [app] 修改邮件的已读状态（邮件级别，非会话级别）。单次上限50封，返回hasMore=true时需重新调用email_get_mails获取最新ID后继续操作 [提供邮箱相关… |
| `email__email_mark_starred` | CORE | [app] 修改邮件的星标状态（邮件级别）。单次上限50封，返回hasMore=true时需重新调用email_get_mails获取最新ID后继续操作 [提供邮箱相关能力, 获取… |
| `email__email_move_mail` | CORE | [app] 移动邮件到目标文件夹。可移动单封或整个会话。单次上限50封，返回hasMore=true时需重新调用email_get_mails获取最新ID后继续操作。清空整个文件夹… |
| `email__email_open_app` | CORE | [app] 打开邮箱应用 [提供邮箱相关能力, 获取当前邮箱账号信息，查询邮箱联系人，查询邮件和收发邮件等] |
| `email__email_open_mail` | CORE | [app] 打开邮箱应用并跳转到指定邮件的详情页 [提供邮箱相关能力, 获取当前邮箱账号信息，查询邮箱联系人，查询邮件和收发邮件等] |
| `email__email_reply_mail` | CORE | [app] 回复邮件，支持回复单人或回复全部 [提供邮箱相关能力, 获取当前邮箱账号信息，查询邮箱联系人，查询邮件和收发邮件等] |
| `email__email_save_draft` | CORE | [app] 将邮件保存到草稿箱，支持附件 [提供邮箱相关能力, 获取当前邮箱账号信息，查询邮箱联系人，查询邮件和收发邮件等] |
| `email__email_send_mail` | CORE | [app] 发送新邮件，支持上传附件。【绝对禁止】未经用户明确确认直接调用。【必须】先展示完整邮件信息，等待用户下一轮对话确认后才可调用。 [提供邮箱相关能力, 获取当前邮箱账号信… |
| `email__email_sync_mails` | CORE | [app] 主动同步新邮件，从邮件服务器拉取最新邮件到本地 [提供邮箱相关能力, 获取当前邮箱账号信息，查询邮箱联系人，查询邮件和收发邮件等] |

### voiceassist（15）

| 工具 | 分组 | 说明 |
|---|---|---|
| `voiceassist__controlApp` | CORE | [app] GUI Agent技能在小米手机上，只负责**第三方App内部的UI操作任务**。当用户想在受支持的 App 中执行具体操作时使用本技能，例如：在美团点外卖、在淘宝搜商… |
| `voiceassist__control_car_by_phone` | CORE | [app] 小米汽车手机控车专用工具。把备车、车内降温、空调、车窗、车锁、座椅、前后备箱、充电口、哨兵模式、车辆电量、续航、充电状态、车内温度和停车位置等车辆意图转换为 versi… |
| `voiceassist__favorite` | CORE | [app] 本工具适用于屏幕内容收藏及收藏内容查询全场景，支持3种操作类型：1.触发收藏：用户明确要求收藏当前屏幕可见图文、网页或App链接时调用，自动截屏并智能解析提取抖音、小红… |
| `voiceassist__get_memory_data` | CORE | [app] 已废弃：优先使用 read_memory 搜索或查询用户已有的本地记忆和云端收藏数据。本工具仅为兼容旧版调用保留，用于检索用户在本机保存的个人记忆数据，返回结构化记忆列… |
| `voiceassist__image_to_text` | CORE | [app] 根据图片和文字问题进行图生文问答。image 为相册中图片的本地路径 URL（例如 content:// 或 file:// 开头的路径），query 为与图片相关的问… |
| `voiceassist__nlp_query` | CORE | [app] 将自然语言 query 直接交给小爱语义引擎理解，返回按能力类型分组的结构化 JSON（music/menstruation/weather/time/alarm/to… |
| `voiceassist__play_media_tool` | CORE | [app] 播放音乐、电台、视频。调用顺序上推荐与 search_media_tool 绑定使用：先调用 search_media_tool 联网搜索并获取资源列表，再调用本工具进… |
| `voiceassist__read_memory` | CORE | [app] 搜索或查询用户已有的本地记忆和云端收藏数据。用户询问已记住、已保存、已收藏的信息时优先调用本工具；本工具会同时查询本地记忆与收藏列表并合并返回。 [操控手机App完成指… |
| `voiceassist__search_media_tool` | CORE | [app] 根据用户自然语言意图，联网搜索音乐、电台（包括广播、有声书、相声、讲故事等）、视频等媒体资源。本工具只负责搜索，不会触发任何播放行为。当用户意图中包含“播放”类需求，或… |
| `voiceassist__search_mi_knowledge` | CORE | [app] 检索小米官方知识库，获取小米品牌商品的权威规格参数、功能说明及客服知识。当用户询问以下内容时调用：①小米手机、平板、电视、耳机、家电等3C产品的参数配置（如屏幕尺寸、电… |
| `voiceassist__take_photo_recognize` | CORE | [app] 使用相机进行拍照识别任务。当用户需要通过相机完成拍照翻译或拍照问答（识别文字、花草、物体等）时调用，根据 mode 打开对应功能标签页。 [操控手机App完成指定任务,… |
| `voiceassist__text_to_image` | CORE | [app] 根据文字描述生成图片。query 为期望生成的图片描述,例如「画一只在草地上奔跑的柴犬，油画风格」。调用会阻塞直到生成完成或失败,并返回图片真实路径或错误信息。 [操控… |
| `voiceassist__trans_image` | CORE | [app] 传入图片Uri（content://，通常由FileProvider生成）和目标/源语言code，自动上传图片获取imageId并完成图片翻译，返回识别的原文/译文（可… |
| `voiceassist__trans_text` | CORE | [app] 传入原始文本和目标语言，将自动完成翻译并返回翻译后，目标语言和源语言传入语种code [操控手机App完成指定任务,检索小米官方知识库,检索记忆数据,检索收藏列表数据,… |
| `voiceassist__write_memory` | CORE | [app] 当用户明确要求记住、记录或保存信息到记忆时调用本工具。 [操控手机App完成指定任务,检索小米官方知识库,检索记忆数据,检索收藏列表数据,检索收藏详情数据,触发收藏,文… |

### securitycenter（13）

| 工具 | 分组 | 说明 |
|---|---|---|
| `securitycenter__manage_autostart` | CORE | [app] 管理应用自启动权限。当用户说'哪些应用自启动了'、'关掉微信自启动'时调用。action=list 查询自启动列表，action=toggle 切换开关。返回应用列表或… |
| `securitycenter__manage_chain_launch` | CORE | [app] 管理链式启动（应用间互相唤醒）。当用户说'哪些应用互相唤醒'、'阻止XX唤醒YY'、'XX应用唤醒了哪些应用'时调用。action=list 查询所有应用链式启动状态，… |
| `securitycenter__manage_memory` | CORE | [app] 管理内存占用和清理。当用户说'内存不够了'、'手机卡了'、'清理内存'时调用。action=query 查询内存占用排行和可清理进程，action=clean 执行清理… |
| `securitycenter__manage_permission` | CORE | [app] 管理应用权限，包括查询权限使用记录、查询应用权限及修改权限授权。当用户说'哪些应用用了定位'、'禁止XX访问相册'、'查看权限使用记录'、‘XX应用申请了哪些权限’、'… |
| `securitycenter__power_extreme_endurance_query` | CORE | [app] 查询设备极限续航模式的当前状态（是否可用、是否已开启）。极限续航是指在低电量时关闭非必要功能，降低功耗，延长设备可用时间的模式。 适用场景： 当用户表达查询极限续航状态… |
| `securitycenter__power_extreme_endurance_switch` | CORE | [app] 设置（开启或关闭）设备的极限续航模式。 适用场景： 当用户明确表达希望改变极限续航状态时调用此工具，例如用户说"开启极限续航"、"打开极限续航"、"关闭极限续航"、"关… |
| `securitycenter__power_mode_query` | CORE | [app] 查询电池模式。当用户说‘当前/查询电池模式’时，返回当前的电池模式给到用户 [内存管理，自启动管理，链式启动管理, 病毒扫描, 权限管理, 省电与电池] |
| `securitycenter__power_mode_switch` | CORE | [app] 修改电池模式。当用户说‘{开启/打开/关闭}{省电模式/均衡模式/性能模式/狂暴模式/超级省电模式}' [内存管理，自启动管理，链式启动管理, 病毒扫描, 权限管理, … |
| `securitycenter__power_wired_charge_mode_query` | CORE | [app] 查询有线充电模式，当用户说‘当前的有线模式’，‘查询有线充电模式’ [内存管理，自启动管理，链式启动管理, 病毒扫描, 权限管理, 省电与电池] |
| `securitycenter__power_wired_charge_mode_switch` | CORE | [app] 修改有线充电模式,当用户说'{打开/开启/关闭}有线{标准/极速/低电疾充}模式' [内存管理，自启动管理，链式启动管理, 病毒扫描, 权限管理, 省电与电池] |
| `securitycenter__power_wireless_charge_mode_query` | CORE | [app] 查询设备的无线充电模式。当用户说“当前的无线充电模式”、“查询无线充电模式”时调用。不适用于修改无线充电模式（应使用 power_wireless_charge_mod… |
| `securitycenter__power_wireless_charge_mode_switch` | CORE | [app] 修改无线充电模式,当用户说'{打开/开启/关闭}无线{标准/静音/极速模式}' [内存管理，自启动管理，链式启动管理, 病毒扫描, 权限管理, 省电与电池] |
| `securitycenter__scan_security` | CORE | [app] 执行病毒扫描和风险处理。当用户说'查杀病毒'、'扫描一下手机'、'卸载病毒'、'删除病毒apk'、'忽略病毒'时调用。action=scan 扫描已安装应用和未安装的a… |

### calendar（9）

| 工具 | 分组 | 说明 |
|---|---|---|
| `calendar__create_anniversary` | CORE | [app] 创建纪念日。纪念日为全天事件，每年自动重复提醒。纪念日日期不能晚于今天 [创建日程, 创建生日, 创建纪念日, 创建倒数日, 修改日程, 删除日程, 查询日程] |
| `calendar__create_birthday` | CORE | [app] 创建生日提醒。生日为全天事件，每年自动重复提醒 [创建日程, 创建生日, 创建纪念日, 创建倒数日, 修改日程, 删除日程, 查询日程] |
| `calendar__create_countdown` | CORE | [app] 创建倒数日。倒数日为全天事件，支持一次性、每月重复、每年重复。目标日期不能早于今天 [创建日程, 创建生日, 创建纪念日, 创建倒数日, 修改日程, 删除日程, 查询日… |
| `calendar__create_event` | CORE | [app] 创建日程，支持普通日程和重复日程 [创建日程, 创建生日, 创建纪念日, 创建倒数日, 修改日程, 删除日程, 查询日程] |
| `calendar__delete_event` | CORE | [app] 删除日程。根据日程ID删除指定日程 [创建日程, 创建生日, 创建纪念日, 创建倒数日, 修改日程, 删除日程, 查询日程] |
| `calendar__query_events` | CORE | [app] 查询日程。根据时间范围查询日程列表，返回日程的ID、标题、时间、地点等信息 [创建日程, 创建生日, 创建纪念日, 创建倒数日, 修改日程, 删除日程, 查询日程] |
| `calendar__query_festivals` | CORE | [app] 查询国内节日。支持按年月过滤返回所有节日，或按节日名称查询指定节日信息 [创建日程, 创建生日, 创建纪念日, 创建倒数日, 修改日程, 删除日程, 查询日程] |
| `calendar__query_legal_holidays` | CORE | [app] 查询国内法定节假日。支持按年月过滤返回所有法定节假日，或按节日名称查询指定法定节假日信息 [创建日程, 创建生日, 创建纪念日, 创建倒数日, 修改日程, 删除日程, … |
| `calendar__update_event` | CORE | [app] 修改日程。仅支持修改本地日程，只读日历的日程不可修改 [创建日程, 创建生日, 创建纪念日, 创建倒数日, 修改日程, 删除日程, 查询日程] |

### contacts（9）

| 工具 | 分组 | 说明 |
|---|---|---|
| `contacts__call_log_delete` | CORE | [app] 按 ID 删除单条通话记录。需先通过 call_log_list 查询获取 ID。当用户说"删除通话记录"、"删掉这条通话"时调用。不适用于查询通话记录（用 call_… |
| `contacts__call_log_list` | CORE | [app] 查询和列出通话记录，支持按号码、联系人、类型和时间范围筛选。当用户说"查看通话记录"、"最近的来电"、"查一下某某的通话记录"时调用。不适用于删除通话记录（用 call… |
| `contacts__contacts_auto_merge_duplicate` | CORE | [app] 自动合并手机中完全重复的联系人，并检测疑似重复项。当用户说“合并重复联系人”、“整理联系人”时调用。不适用于手动编辑单个联系人（用 manage_contacts）。返… |
| `contacts__contacts_open_merge_page` | CORE | [app] 打开手动合并重复联系人的界面，让用户自行确认和选择要合并的疑似重复项。仅在用户明确同意后调用（通常紧跟 contacts_auto_merge_duplicate 返回… |
| `contacts__manage_contacts` | CORE | [app] 管理联系人：添加、修改或删除。操作类型：create（创建新联系人，name 必填，phone/email/company/title/nickname 可选，返回 c… |
| `contacts__open_import_export_contacts_page` | CORE | [app] 打开联系人导入/导出页面，让用户自行完成联系人的批量导入或导出操作。当用户说"导入联系人"、"导出联系人"时调用。不适用于管理 SIM 卡联系人（SIM 卡管理用 op… |
| `contacts__open_sim_contact_manage_page` | CORE | [app] 打开指定卡槽的 SIM 卡联系人管理页面。参数 slotNumber 为卡槽编号，仅支持 1（卡槽1）或 2（卡槽2）。当用户说"管理SIM卡联系人"、"打开SIM卡管… |
| `contacts__search_contacts` | CORE | [app] 搜索联系人。支持按姓名、拼音全拼、拼音首字母、电话号码、邮箱、昵称、公司名称、职位搜索。例如搜索"zs"可匹配"张三"，搜索"xiaomi"可匹配公司为"小米科技"的联… |
| `contacts__search_phone_location` | CORE | [app] 查询电话号码的归属地和运营商信息。当用户说'查询号码归属地'、'这个号码是哪里的'、'这个号码的运营商是什么'时调用。不适用于查询来电的国家码信息（来电国家码用 pho… |

### cloudservice（8）

| 工具 | 分组 | 说明 |
|---|---|---|
| `cloudservice__cloud_data_protection` | CORE | [app] 跳转到端到端加密（数据保护）设置页面 [云同步开关, 同步状态, 手动同步, 闲时同步, 备份管理, 云空间查询, 会员管理, 数据保护设置] |
| `cloudservice__cloud_member_info` | CORE | [app] 查看是否是云服务会员、会员等级 [云同步开关, 同步状态, 手动同步, 闲时同步, 备份管理, 云空间查询, 会员管理, 数据保护设置] |
| `cloudservice__cloud_member_purchase` | CORE | [app] 跳转到云空间会员购买/扩容页面 [云同步开关, 同步状态, 手动同步, 闲时同步, 备份管理, 云空间查询, 会员管理, 数据保护设置] |
| `cloudservice__cloud_storage_usage` | CORE | [app] 查看云空间总量、已用量、剩余空间 [云同步开关, 同步状态, 手动同步, 闲时同步, 备份管理, 云空间查询, 会员管理, 数据保护设置] |
| `cloudservice__cloud_sync_idle` | CORE | [app] 开启或关闭闲时同步（息屏充电时自动同步） [云同步开关, 同步状态, 手动同步, 闲时同步, 备份管理, 云空间查询, 会员管理, 数据保护设置] |
| `cloudservice__cloud_sync_status` | CORE | [app] 查询指定业务的上次同步时间 [云同步开关, 同步状态, 手动同步, 闲时同步, 备份管理, 云空间查询, 会员管理, 数据保护设置] |
| `cloudservice__cloud_sync_switch` | CORE | [app] 开启或关闭指定业务的云同步开关。注意：如已开启端到端加密，部分业务需在设置页面操作 [云同步开关, 同步状态, 手动同步, 闲时同步, 备份管理, 云空间查询, 会员管… |
| `cloudservice__cloud_sync_trigger` | CORE | [app] 触发所有已开启业务的数据立即同步，或暂停同步 [云同步开关, 同步状态, 手动同步, 闲时同步, 备份管理, 云空间查询, 会员管理, 数据保护设置] |

### service（8）

| 工具 | 分组 | 说明 |
|---|---|---|
| `service__milink_cast_settings` | CORE | [app] 管理投屏开关与隐私投屏开关（设置层面的开/关，不是发起投屏的实际操作）。action=get_status 查询指定开关的当前状态，需传 target；action=s… |
| `service__milink_connectivity_settings` | CORE | [app] 管理互联互通设置开关。查询/打开/关闭互联服务时调用。action=get_status 查询指定开关的当前状态，需传 target；action=set 设置指定开关… |
| `service__milink_desktop_device_switch` | CORE | [app] 管理妙享桌面设备开关。查询/打开/关闭妙享桌面功能时调用。action=list 列出所有支持妙享桌面的互联设备（包含设备名称、开关状态）,无需任何参数。action=… |
| `service__milink_desktop_position` | CORE | [app] 管理妙享桌面默认位置设置（仅 PAD 设备）。控制 PAD 显示手机桌面的默认位置（左侧/右侧）。action=query 查询当前妙享桌面默认位置,无需任何参数。ac… |
| `service__milink_manage_device_control` | CORE | [app] 设备控制工具,支持响铃查找、电量查询和移动数据/热点共享。action=ring 响铃查找:device_type 必填,device_name 可选(未传时按 dev… |
| `service__milink_manage_screen_media` | CORE | [app] 跨设备屏幕/媒体流转播控工具。direction 以当前设备为视角:push=当前设备推到远端,pull=远端拉到当前设备。action=device_capacity… |
| `service__milink_manage_tv_control` | CORE | [app] 电视遥控工具,管理局域网内电视设备的发现与控制。action=discover_control 扫描并返回可遥控电视列表(含名称、支持能力、在线状态),无需任何参数。a… |
| `service__milink_unlock_switch` | CORE | [app] 管理跨端解锁开关。查询/打开/关闭跨端解锁功能时调用。action=list 列出所有支持跨端解锁的互联设备（包含设备名称、开关状态、是否依赖妙享桌面）,无需任何参数。… |

### xiaomi_appstore（8）

| 工具 | 分组 | 说明 |
|---|---|---|
| `xiaomi_appstore__app_download` | CORE | [mcp] [xiaomi_appstore] 当用户有下载、更新某款应用或游戏的需求时，调用此工具。此工具会根据指定应用或游戏的包名，精准跳转至应用商店的对应详情页，支持升级与安… |
| `xiaomi_appstore__app_search` | CORE | [mcp] [xiaomi_appstore] 当用户有搜索应用或游戏的需求时，调用此工具。此工具会根据搜索的应用的名称或者关键词，调起应用商店搜索结果页。工具返回的是Androi… |
| `xiaomi_appstore__app_update` | CORE | [mcp] [xiaomi_appstore] 当用户有更新应用或游戏的需求时，调用此工具。此工具会调起应用商店升级页。工具返回的是Android系统的deeplink，使用工具打… |
| `xiaomi_appstore__game_open` | CORE | [mcp] [xiaomi_appstore] 当用户表达想打开某款小游戏或者玩某款小游戏时，调用此工具。此工具会根据用户提供的小游戏名称，跳转至应用商店搜索结果页。工具返回的是A… |
| `xiaomi_appstore__game_recommend` | CORE | [mcp] [xiaomi_appstore] 获取小游戏推荐或者发现小游戏，跳转至应用商店搜索结果页。工具返回的是Android系统的deeplink，使用工具打开返回的deep… |
| `xiaomi_appstore__netroam_mcp_oversea_traffic_buy` | CORE | [mcp] [xiaomi_appstore] 当用户表达想购买出境流量、海外流量或全球上网套餐时调用此工具。工具返回的是Android系统的deeplink，使用工具打开返回的d… |
| `xiaomi_appstore__open_game_guide` | CORE | [mcp] [xiaomi_appstore] 返回游戏攻略站页面的deeplink。根据游戏名称模糊查询，返回可以打开对应游戏攻略站页面的deeplink。若找到攻略站则返回攻略… |
| `xiaomi_appstore__open_game_welfare` | CORE | [mcp] [xiaomi_appstore] 返回游戏福利页面的deeplink。根据游戏名称模糊查询，返回可以打开对应游戏福利页面的deeplink。若找到游戏则返回对应福利页… |

### bugreport（7）

| 工具 | 分组 | 说明 |
|---|---|---|
| `bugreport__add_reply` | CORE | [app] 当用户想对已提交的反馈补充信息、追问进度或回复客服时，调用此工具。调用前应先通过 get_feedback_list 确认目标 feedbackId，并优先选择 can… |
| `bugreport__get_description_guide` | CORE | [app] 当用户描述设备问题或要提交反馈时，调用此工具获取描述引导模板。返回提交反馈时需要用户提供的关键信息模板，调用方应根据模板中的引导项逐一追问用户，补充完整的问题描述。应在… |
| `bugreport__get_domains` | CORE | [app] 当用户提到系统问题、功能建议或使用疑问，且尚未获取领域列表时，调用此工具获取 BugReport 支持的问题领域列表，用于后续按领域查询标签 |
| `bugreport__get_feedback_list` | CORE | [app] 当用户想查看自己已提交的反馈、了解当前本地已同步的处理进度、或在追加回复前需要确定目标反馈时，调用此工具获取反馈列表。返回的每条记录包含 feedbackId、titl… |
| `bugreport__get_tags` | CORE | [app] 当已经确定候选领域，但尚未获取该领域下标签列表时，调用此工具获取该领域下可选标签，用于后续提交反馈 |
| `bugreport__open_feedback_detail` | CORE | [app] 当用户从 get_feedback_list 选择某条反馈并希望查看详情时，调用此工具打开该反馈详情页。本工具只接受 get_feedback_list 返回的 fee… |
| `bugreport__submit_feedback` | CORE | [app] 当用户遇到设备问题或提出改进建议时，通过此工具提交反馈。用户可以把相关图片直接发到对话里作为附件一并提交（最多 3 张），调用前应询问用户是否有相关图片。只有当用户明确… |

### mms（6）

| 工具 | 分组 | 说明 |
|---|---|---|
| `mms__mms_delete_sms` | CORE | [app] 删除消息，支持SMS/MMS/RCS。两种模式：1)按ID删除：传ids参数；2)批量删除：传delete_all=true，可配合phone_number/start… |
| `mms__mms_mark_all_as_read` | CORE | [app] 一键将所有短信/彩信/融合消息标记为已读。当用户说'全部已读'、'把所有短信标为已读'时调用。同时更新底层sms/mms/rms表、上层BugleDatabase会话未… |
| `mms__mms_query_contact_name` | CORE | [app] 通过电话号码查询商户名称。当需要显示号码对应的商户名时调用。查黄页返回商户名(source=yellowpage)。返回JSON含found/name/source字段… |
| `mms__mms_read_sms` | CORE | [app] 读取手机消息，支持SMS/MMS/RCS。支持按收件箱/已发送/全部查询，可按手机号、关键字、SIM卡槽、日期范围筛选。当用户说'短信'时默认查全部类型(sms+mms… |
| `mms__mms_send_sms` | CORE | [app] 发送短信。支持长短信自动分段发送，支持双卡选择。 [读取短信/彩信/5G消息，发送短信（支持双卡），删除短信/彩信/5G消息，黄页联系人查询，短信全部标为已读，开关短信… |
| `mms__mms_toggle_setting` | CORE | [app] 开关短信设置项或跳转设置页面。支持的setting_key：smart_sms(短信智能识别开关)、collapse_verification(折叠验证码短信开关)、h… |

### settings（6）

| 工具 | 分组 | 说明 |
|---|---|---|
| `settings__continuity_settings` | CORE | [app] 管理折叠屏合盖连续性设置。当用户说合盖设置、合盖继续使用、合盖息屏、合盖锁屏、折叠屏连续性、翻盖后应用继续、查询连续性应用列表、开启或关闭某个应用的连续性时调用。 [深… |
| `settings__dark_mode_settings` | CORE | [app] 管理深色模式（也叫夜间模式、暗色模式、浅色模式、亮色模式）设置。当用户说开启/关闭深色模式、切换到浅色模式、调成浅色/亮色、屏幕太暗想换回浅色、开启/关闭自动切换深色模… |
| `settings__manage_connectivity` | CORE | [app] 管理飞行模式设置。当用户说开飞行模式、关飞行模式、查飞行模式状态时调用。不适用于WiFi、蓝牙、移动数据等其他连接设置。与phone_network_settings的… |
| `settings__manage_datetime` | CORE | [app] 管理时间格式设置。当用户说开24小时制、关24小时制、切换12小时制、查时间格式时调用。不适用于闹钟、日历、自动设置时间等非时间格式设置。示例：1)开24小时制：act… |
| `settings__manage_display` | CORE | [app] 管理屏幕显示设置，包括亮度和分辨率。当用户说调亮度、亮一点、暗一点、开自动亮度、关自动亮度、查分辨率、切换分辨率、打开分辨率设置时调用。不适用于刷新率、色彩风格、护眼模… |
| `settings__manage_sound` | CORE | [app] 管理声音与触感设置。当用户说调音量、开静音、关触感、小爱音量、通知音量时调用。不适用于屏幕亮度、显示设置、无障碍功能等非声音触感设置。示例：1)开静音：action=s… |

### soundrecorder（6）

| 工具 | 分组 | 说明 |
|---|---|---|
| `soundrecorder__check_before_delete` | CORE | [app] 删除录音文件前的预检查，查询该录音的云端备份状态和数据库 ID。当用户说"删除录音"、"删掉这个录音"时，必须先调用此工具，再调用 delete_recording 执… |
| `soundrecorder__delete_recording` | CORE | [app] 执行删除录音文件。必须先调用 check_before_delete，将返回的 confirm_message 展示给用户，并在用户明确确认后才能调用本工具。禁止在用户… |
| `soundrecorder__get_transcription` | CORE | [app] 获取录音机录音文件的转录文字内容。当用户说"这个录音讲了什么"、"帮我整理下刚才的会议"、"录音摘要"、"总结一下这个录音"、"语音转文字"时调用。仅适用于录音机 ap… |
| `soundrecorder__manage_recording` | CORE | [app] 管理录音机的音频录制：开始、停止、暂停、继续、查询录音状态。当用户说"开始录音"、"停止录音"、"暂停录音"、"继续录音"、"在录音吗"时调用。仅适用于录音机 app … |
| `soundrecorder__play_audio_file` | CORE | [app] 播放指定的录音文件，打开录音机播放界面。当用户说"播放这个录音"、"听一下这个文件"时调用。支持 mp3/wav/aac/amr/ogg/flac 等格式。仅用于播放已… |
| `soundrecorder__search_recordings` | CORE | [app] 搜索录音机中的录音文件，按关键词匹配录音名称和备注。当用户说"搜索录音"、"找一下之前的会议录音"、"有没有叫xxx的录音"时调用。支持按录音类型筛选（普通录音、通话录… |

### telecom（5）

| 工具 | 分组 | 说明 |
|---|---|---|
| `telecom__phone_call_action` | CORE | [app] 接听/拒接/挂断当前通话或来电。来电时可以拒接（拒接时可以短信回复），通话中可挂断。当用户说"接电话"、"挂掉"、"拒接并发短信说在开会"时调用。返回操作是否成功和当前… |
| `telecom__phone_hold_call` | CORE | [app] 保持或恢复当前通话。仅在通话中可调用。当用户说"先保持一下"、"恢复通话"时调用。返回当前保持状态（held/active）。不适用于接听/拒接/挂断通话（用 phon… |
| `telecom__phone_make_call` | CORE | [app] 支持语音、视频和 IP 三种类型。单卡双卡不已默认拨号卡打直接拨出，双卡无默认时拉起拨号卡选择页面。当用户说"打电话给张三"、"视频打给妈妈"时调用。若用户给的是联系人… |
| `telecom__phone_set_audio_route` | CORE | [app] 设置通话音频输出通路（扬声器/听筒/有线耳机/蓝牙耳机）。仅在通话中可调用。当用户说"开免提"、"切到听筒"、"用耳机接"时调用。返回当前音频通路。不适用于非通话状态下… |
| `telecom__phone_set_mute` | CORE | [app] 设置通话静音/取消静音状态。当用户说"静音"、"取消静音"时调用。返回当前静音状态（true/false）。不适用于切换音频输出设备（切扬声器/耳机用 phone_se… |

### Wind_MCP（4）

| 工具 | 分组 | 说明 |
|---|---|---|
| `Wind_MCP__fin_rag_search` | CORE | [mcp] [Wind_MCP] 依据用户的自然语言问句，结合其设定的过滤条件，从金融数据库中召回匹配的金融文件 |
| `Wind_MCP__natural_language_get_large_financial_data` | CORE | [mcp] [Wind_MCP] Query Wind data using natural language and receive the relevant full data… |
| `Wind_MCP__query_financial_data` | CORE | [mcp] [Wind_MCP] 对金融指标进行聚合、计算及跨品种的数据处理。处理任务包括：从单个公司数据汇总行业或板块级别的合计值（如某行业的总市场份额）、计算多实体间的加权平均… |
| `Wind_MCP__search_financial_docs` | CORE | [mcp] [Wind_MCP] 依据用户的自然语言问句，结合其设定的过滤条件，从金融数据库中召回匹配的金融文件 |

### deskclock（4）

| 工具 | 分组 | 说明 |
|---|---|---|
| `deskclock__deskclock_add_alarm` | CORE | [app] 新增一个闹钟。当用户说"帮我设个X点的闹钟"、"明天早上X点叫醒我"、"定个闹钟"时调用。必须提供 hour 和 minutes，其余参数可选。未传 daysOfWee… |
| `deskclock__deskclock_delete_alarm` | CORE | [app] 永久删除一个闹钟。当用户明确说"删除"、"取消这个闹钟"时调用。若用户只是说"关掉"或"关闭"或"不想响了"，应使用 deskclock_update_alarm 将 … |
| `deskclock__deskclock_query_alarms` | CORE | [app] 查询设备上所有已设置的闹钟。当用户询问"有哪些闹钟"、"几点有闹钟"、"帮我看看闹钟"时调用。返回闹钟列表，每条包含 id、hour、minutes、enabled、d… |
| `deskclock__deskclock_update_alarm` | CORE | [app] 修改已有闹钟的属性。当用户说"把X点的闹钟改成Y点"、"开启/关闭那个闹钟"、"修改闹钟的重复规则"时调用。必须先通过 deskclock_query_alarms 获… |

### phone（4）

| 工具 | 分组 | 说明 |
|---|---|---|
| `phone__phone_call_recording` | CORE | [app] 开始/暂停/恢复/停止通话录音。仅在通话进行中可操作。当用户说"录一下这个电话"、"停止录音"时调用。能力由录音机提供，开关由电话设置管理。返回录音状态和文件信息。不适… |
| `phone__phone_get_caller_info` | CORE | [app] 获取当前来电/通话中的号码的国家码信息。仅在来电时可调用，无法根据号码静态查询。主动查号码归属地请用联系人 App 的 search_phone_location。返回… |
| `phone__phone_network_settings` | CORE | [app] 查询当前移动网络和无线网络设置，包括 SIM 卡当前网络信息、默认拨号卡/上网卡、移动数据开关、智能双卡/智能切换上网卡开关、智能回拨开关/智能互转开关。当用户说"切换… |
| `phone__phone_settings` | CORE | [app] 查询和管理电话设置。category 包含：call（来电/去电，拨打/接听/挂断/拒接/短信回复等）、record（录音，通话自动录音/通话录音设置等）、locati… |

### JDJR_MCP_BASE_SERVICE（3）

| 工具 | 分组 | 说明 |
|---|---|---|
| `JDJR_MCP_BASE_SERVICE__query-gold` | CORE | [mcp] [京东金融MCP基础服务] 查询黄金实时行情，支持上海黄金交易所、伦敦金、期货黄金等，数据来源为“京东金融” |
| `JDJR_MCP_BASE_SERVICE__query-news` | CORE | [mcp] [京东金融MCP基础服务] 查询京东金融资讯，支持任意关键字相关的资讯新闻，数据来源为“京东金融” |
| `JDJR_MCP_BASE_SERVICE__query-stock` | CORE | [mcp] [京东金融MCP基础服务] 查询股票实时行情、分时走势、K线数据等，支持A股(SZ/SH)、港股(HK)、美股(US)、贵金属(SGE)，数据来源为“京东金融” |

### kugou-music（3）

| 工具 | 分组 | 说明 |
|---|---|---|
| `kugou-music__open_play_song_page` | CORE | [mcp] [酷狗音乐] 通过 Scheme 打开酷狗播放页并播放指定歌曲。调用前必须先调用 search_music，并使用其返回的 hash、filename、album_au… |
| `kugou-music__open_search_page` | CORE | [mcp] [酷狗音乐] 打开酷狗 App 的搜索页 |
| `kugou-music__search_music` | CORE | [mcp] [酷狗音乐] 搜索酷狗音乐内容，支持歌曲名、歌手名、专辑名、歌词片段等关键词搜索 |

### kugou-music-mcp（3）

| 工具 | 分组 | 说明 |
|---|---|---|
| `kugou-music-mcp__open_play_song_page` | CORE | [mcp] [酷狗音乐] 通过 Scheme 打开酷狗播放页并播放指定歌曲。调用前必须先调用 search_music，并使用其返回的 hash、filename、album_au… |
| `kugou-music-mcp__open_search_page` | CORE | [mcp] [酷狗音乐] 打开酷狗 App 的搜索页 |
| `kugou-music-mcp__search_music` | CORE | [mcp] [酷狗音乐] 搜索酷狗音乐内容，支持歌曲名、歌手名、专辑名、歌词片段等关键词搜索 |

### printspooler（3）

| 工具 | 分组 | 说明 |
|---|---|---|
| `printspooler__confirm_print` | CORE | [app] 确认并执行打印任务 [打印文件, 确认打印] |
| `printspooler__get_available_printers` | CORE | [app] 获取当前在线可用的打印机列表 [打印文件, 确认打印] |
| `printspooler__prepare_print` | CORE | [app] 准备打印任务，校验文件和参数，返回确认信息，当用户说含“打印”的话时调用 [打印文件, 确认打印] |

### screenrecorder（3）

| 工具 | 分组 | 说明 |
|---|---|---|
| `screenrecorder__screenrecorder_get_status` | CORE | [app] 查询当前是否正在录制屏幕。当用户询问"录屏了吗"、"现在在录屏吗"时调用，或在调用 start/stop 前主动查询以避免状态冲突。返回包含录制状态的对象。 [开始屏幕… |
| `screenrecorder__screenrecorder_start_recording` | CORE | [app] 开始屏幕录制。当用户要求开始录屏、录制屏幕时调用。录制在后台进行，不会弹出额外界面。调用前建议先用 screenrecorder_get_status 确认当前未在录制… |
| `screenrecorder__screenrecorder_stop_recording` | CORE | [app] 停止屏幕录制并自动保存视频到相册。当用户要求停止录屏、结束录制、保存录屏时调用。若当前未在录制，调用可能返回错误。 [开始屏幕录制, 停止屏幕录制并保存视频, 查询当前… |

### sdksample（3）

| 工具 | 分组 | 说明 |
|---|---|---|
| `sdksample__sdk_demo_fortune` | CORE | [app] SDK演示应用的专属工具，生成今日运势报告，包含幸运数字、幸运颜色和运势建议。当用户想看运势、算命、问今天运气时调用此工具。 [今日运势, 藏头诗, 密码生成] |
| `sdksample__sdk_demo_password` | CORE | [app] SDK演示应用的专属工具，生成随机安全密码。当用户需要生成密码、创建口令、要一个随机密码时调用此工具。 [今日运势, 藏头诗, 密码生成] |
| `sdksample__sdk_demo_poem` | CORE | [app] SDK演示应用的专属工具，根据给定文字生成一首藏头诗，每行诗以给定文字的一个字开头。当用户想写藏头诗、玩文字游戏时调用此工具。 [今日运势, 藏头诗, 密码生成] |

### tsmclient（3）

| 工具 | 分组 | 说明 |
|---|---|---|
| `tsmclient__tsm_action` | CORE | [app] 跳转到智能卡相关页面，如开通/删除/修改各类卡片，移出交通卡，退卡，桌面快捷方式，云备份设置。当用户说'开通公交卡'、'删除门禁卡'、'设置桌面快捷方式'时调用。注意：… |
| `tsmclient__tsm_cards` | CORE | [app] 查询智能卡信息，包括已开通卡片、默认刷卡卡片、云端备份卡片、云备份状态、智能选卡开关状态、默认卡开关状态。当用户询问'某种类型卡（交通卡、门禁卡）'是多个行为，查询已开… |
| `tsmclient__tsm_settings` | CORE | [app] 查询或修改智能卡相关设置，如双击电源键唤起支付、简洁刷卡模式、双击HOME唤醒刷卡页。当用户说'开启双击支付'、'关闭简洁刷卡'、'查看刷卡设置'时调用。返回JSON对… |

### xiaomi-ice-content-search（3）

| 工具 | 分组 | 说明 |
|---|---|---|
| `xiaomi-ice-content-search__search_film_and_tv_content` | CORE | [mcp] [xiaomi-ice-content-search]      搜索影视综资源内容。     当用户想要查找电影、电视剧、综艺等长视频内容时使用此工具。     :p… |
| `xiaomi-ice-content-search__search_novel` | CORE | [mcp] [xiaomi-ice-content-search]      搜索小说内容。     当用户想要查找小说内容时使用此工具。     :param query: 用户… |
| `xiaomi-ice-content-search__search_short_video` | CORE | [mcp] [xiaomi-ice-content-search]      搜索短视频内容。     当用户想要查找短视频内容时使用此工具。     :param query: … |

### yellowpage（3）

| 工具 | 分组 | 说明 |
|---|---|---|
| `yellowpage__manage_antispam_settings` | CORE | [app] 查询和管理智能识别陌生号码（反骚扰）的设置开关，包括智能识别总开关和快捷标记开关。当用户说"打开号码识别"、"关闭号码识别"、"查看号码识别设置状态"、"打开快捷标记"… |
| `yellowpage__mark_number` | CORE | [app] 将电话号码标记为骚扰电话或取消已有标记。当用户说"标记这个号码为骚扰电话"、"这是诈骗电话帮我标记"、"取消这个号码的标记"时调用。action 为 mark 时必须提… |
| `yellowpage__query_number` | CORE | [app] 查询电话号码的黄页信息和骚扰标记信息。当用户说"识别一下这个号码"、"识别这个电话是谁的"、"查一下这个号码信息"、"这个号码是不是骚扰电话"时调用。返回 JSON 含… |

### HimaAudioServer（2）

| 工具 | 分组 | 说明 |
|---|---|---|
| `HimaAudioServer__hubSearch` | MEDIA | [mcp] [喜马拉雅音频内容服务] 在喜马拉雅平台做统一检索：一次调用同时返回与意图相关的专辑列表与声音列表，以及便于展示的摘要信息（如更宜优先展示的列表类型 topConten… |
| `HimaAudioServer__playContent` | MEDIA | [mcp] [喜马拉雅音频内容服务] 生成单个内容的播放deeplink。 根据参数type区分播放对象是专辑还是声音 返回：播放deeplink字符串。 请使用工具打开返回的de… |

### mx-mcp（2）

| 工具 | 分组 | 说明 |
|---|---|---|
| `mx-mcp__aitrading_jump` | CORE |  |
| `mx-mcp__aitrading_select_stock` | CORE | [mcp] [东方财富妙想MCP] 智能选股工具，当用户咨询股票筛选、选股策略、条件选股、行业选股、技术面选股、基本面选股及相关投资分析问题时，调用该工具执行智能选股逻辑，返回符合… |

### scanner（2）

| 工具 | 分组 | 说明 |
|---|---|---|
| `scanner__open_scan` | CORE | [app] 使用相机进行扫描二维码和扫名片任务。当用户需要通过相机完成扫描二维码和扫名片时调用，根据 mode 打开对应功能标签页。 [扫码，扫名片] |
| `scanner__scan_qr_image` | CORE | [app] 识别图片或照片中的二维码进行扫码，传入图片的绝对路径，例如：/storage/emulated/0/DCIM/Camera/IMG_20260317_172741.jp… |

### weather2（2）

| 工具 | 分组 | 说明 |
|---|---|---|
| `weather2__weather_get_city_alert` | CORE | [app] 查询指定城市的气象预警信息，包括预警标题、级别、类型、详情和防御指南。如果没有预警则返回空列表 [查询当前天气, 查询天气预报] |
| `weather2__weather_get_city_weather` | CORE | [app] 查询指定城市的实时天气和多日预报信息，包括温度、天气类型、湿度、风力、日出日落等 [查询当前天气, 查询天气预报] |

### cleanmaster（1）

| 工具 | 分组 | 说明 |
|---|---|---|
| `cleanmaster__manage_cleaner` | CORE | [app] 查询垃圾文件、扫描手机垃圾、清理手机垃圾的专用工具。当用户想要查询垃圾文件、查看垃圾大小、扫描缓存、清理垃圾，必须使用此工具，不要使用 search_files 或 l… |

### connectivity（1）

| 工具 | 分组 | 说明 |
|---|---|---|
| `connectivity__manage_file_transfer` | CORE | [app] 管理跨设备文件传输（小米互传，仅支持一对一传输）。支持发现附近设备、发送文件、取消传输和控制互传开关。当用户说"把文件发到电脑"、"传照片给手机"、"搜一下附近设备"时… |

### home（1）

| 工具 | 分组 | 说明 |
|---|---|---|
| `home__home_settings` | CORE | [app] 小米桌面设置工具，支持查询和修改桌面设置项。当用户说开启无字模式、关闭图标文字、隐藏应用名称、仅小部件无字、锁定桌面布局、固定桌面、自动填充空位、双击锁屏、切换经典模式… |

### misettings（1）

| 工具 | 分组 | 说明 |
|---|---|---|
| `misettings__manage_refresh_rate` | CORE | [app] 管理屏幕刷新率设置。当用户提到刷新率、帧率、Hz相关操作时调用，包括：'屏幕刷新率多少'、'把刷新率调到120'、'开启智能刷新率'、'打开刷新率页面'、'刷新率设置'… |

### mishop（1）

| 工具 | 分组 | 说明 |
|---|---|---|
| `mishop__mishop_search_agent` | CORE | [mcp] [小米商城] 小米商城智能购物助手，支持商品搜索、对比推荐、评价查询、优惠查询等。当用户有购物相关需求时调用此工具，如：搜索商品、比较商品、查看评价、了解优惠活动等。 |

### misound（1）

| 工具 | 分组 | 说明 |
|---|---|---|
| `misound__misound_settings` | CORE | [app] 音质音效设置。包含沉浸声开关，用于开启或关闭沉浸声功能。 [音质音效设置, 包含沉浸声开关] |

## 进程内 App 工具（UIAgentServiceForOSBot）

`assets/app_tools.json` 声明的方法，直接在小爱进程内调用，见 `docs/08-internal-api.md`。

| 工具名 | 方法 | 参数 | 说明 |
|---|---|---|---|
| `nlp_query` | `nlpQuery` | 1 | 将自然语言 query 直接交给小爱语义引擎理解，返回小爱端解析后的精简 JSON，按能力类型分组（music/menstruation/time/alarm/stock/car_… |
| `search_mi_knowledge` | `searchMiKnowledge` | 3 | 检索小米官方知识库，获取小米品牌商品的权威规格参数、功能说明及客服知识。当用户询问以下内容时调用：①小米手机、平板、电视、耳机、家电等3C产品的参数配置（如屏幕尺寸、电池容量、处理… |
| `favorite` | `favorite` | 5 | 本工具适用于屏幕内容收藏及收藏内容查询全场景，支持3种操作类型：1.触发收藏：用户明确要求收藏当前屏幕可见图文、网页或App链接时调用，自动截屏并智能解析提取抖音、小红书、B站等平… |
| `write_memory` | `writeMemory` | 1 | 当用户明确要求记住、记录或保存信息到记忆时调用本工具。 |
| `read_memory` | `readMemory` | 3 | 搜索或查询用户已有的本地记忆和云端收藏数据。用户询问已记住、已保存、已收藏的信息时优先调用本工具；本工具会同时查询本地记忆与收藏列表并合并返回。 |
| `get_memory_data` | `getMemoryData` | 3 | 已废弃：优先使用 read_memory 搜索或查询用户已有的本地记忆和云端收藏数据。本工具仅为兼容旧版调用保留，用于检索用户在本机保存的个人记忆数据，返回结构化记忆列表。当用户出… |
| `text_to_image` | `textToImage` | 1 | 根据文字描述生成图片。query 为期望生成的图片描述,例如「画一只在草地上奔跑的柴犬，油画风格」。调用会阻塞直到生成完成或失败,并返回图片真实路径或错误信息。 |
| `image_to_text` | `imageToText` | 2 | 根据图片和文字问题进行图生文问答。image 为相册中图片的本地路径 URL（例如 content:// 或 file:// 开头的路径），query 为与图片相关的问题，例如「图… |
| `trans_text` | `translateText` | 3 | 传入原始文本和目标语言，将自动完成翻译并返回翻译后，目标语言和源语言传入语种code |
| `take_photo_recognize` | `takePhotoRecognize` | 1 | 使用相机进行拍照识别任务。当用户需要通过相机完成拍照翻译或拍照问答（识别文字、花草、物体等）时调用，根据 mode 打开对应功能标签页。 |
| `trans_image` | `translateImage` | 3 | 传入图片Uri（content://，通常由FileProvider生成）和目标/源语言code，自动上传图片获取imageId并完成图片翻译，返回识别的原文/译文（可能包含fin… |
| `show_instruction` | `showInstruction` | 1 | 把 nlp_query 截获的小爱指令渲染成对话卡片。token 必须来自 nlp_query 返回的 render_token。当 nlp_query 返回的 JSON 带 re… |
| `search_media_tool` | `searchMedia` | 10 | 根据用户自然语言意图，联网搜索音乐、电台（包括广播、有声书、相声、讲故事等）、视频等媒体资源。本工具只负责搜索，不会触发任何播放行为。当用户意图中包含“播放”类需求，或者语义上已经… |
| `play_media_tool` | `playMedia` | 8 | 播放音乐、电台、视频。调用顺序上推荐与 search_media_tool 绑定使用：先调用 search_media_tool 联网搜索并获取资源列表，再调用本工具进行实际播放。… |
| `settings_access` | `settingsAccess` | 3 | 读取、修改或列出小爱设置项（方言切换等），支持 get（读取）、set（修改）和 list（列出可选值）操作。当前支持 key=dialect（方言切换）。输入示例: {"acti… |
| `get_screen_context` | `getScreenContext` | 0 | 获取当前屏幕上下文：截图、前台应用信息、屏幕类型，并按屏幕类型附带内容——文档场景返回 doc_uri（文件路径或 content:// URI），网页场景返回 web_text（… |
| `get_current_package` | `getCurrentPackage` | 0 | 快速获取当前前台应用的包名（仅查询 ActivityManager，不截图）。返回格式 {"foreground_package":"...","foreground_activi… |
| `exit_xiaoai` | `exitXiaoAi` | 0 | 退出/关闭超级小爱：关闭当前的小爱主页面（TabHostMainActivity）或悬浮窗页面。当用户表达"退出""退出超级小爱""关闭小爱""收起悬浮窗""关闭悬浮窗"等希望关掉… |

## Agent 定义（assets/agents/）

| id | 名称 | 模式 | 最大深度 | 最大轮次 |
|---|---|---|---|---|
| `com.android.camera` | 相机助手 | sub | None | 15 |
| `com.xiaomi.lyrabridge-watch` | 手表助手 | sub | 3 | 20 |
| `com.xiaomi.type-correction-v2` | 小米输入法智能纠错 V2 | sub | 1 | 1 |
| `com.xiaomi.type-edit` | 小米输入法语音指令编辑 | sub | 1 | 1 |
| `com.xiaomi.type-reply` | 小米输入法灵动回复 | sub | 1 | 1 |
| `com.xiaomi.type-translate` | 小米输入法翻译 | sub | 1 | 1 |
| `com.xiaomi.type` | 小米输入法智能纠错 | sub | 1 | 1 |
| `osbot.chat` | 好友消息助手 | sub | 1 | 5 |
| `com.miui.notes-edit_qa` | edit_qa | sub | 3 | 12 |
| `com.miui.notes-fast_pipeline` | fast_pipeline | sub | 1 | 1 |
| `com.miui.notes-import_vlm` | import_vlm | sub | 1 | 1 |
| `com.miui.notes-smart_search` | smart_search | sub | 3 | 10 |
| `com.miui.notes-tag_classify` | tag_classify | sub | 1 | 1 |
| `com.miui.notes-task` | task | sub | 3 | 30 |
| `com.miui.notes-title_generate` | title_generate | sub | 1 | 1 |
| `com.miui.notes-voice_creative` | voice_creative | sub | 1 | 1 |
| `osbot.group_chat` | 群聊助手 | sub | 1 | 8 |
| `osbot.main` | 超级小爱 | main | 3 | 30 |
| `osbot.overlayassistant` | 浮窗助手 | main | 3 | 30 |
| `osbot.taiyi` | 端侧小爱 | sub | 1 | 4 |
