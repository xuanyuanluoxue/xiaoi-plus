# 08 内部 API：小爱对外暴露的三条通道

> 证据级别：静态（Manifest xmltree + dexdump）+ 实测（真机请求采样）｜适用版本：8.2.70.8421
>
> 本文是本子项目最重要的一篇：它回答「外部 Agent 到底能用什么方式驱动小爱」。
> 我们自己的模块（8771 MCP）此前是**反射摸黑**做的，而小爱其实有官方通道。

## 1. 总览

小爱同时存在三条互不相同的对外通道，用途和权限模型都不一样：

| 通道 | 形态 | 谁在用 | 权限 | 状态 |
|---|---|---|---|---|
| A. 进程内 App 工具 | `UIAgentServiceForOSBot` 的 18 个方法 | 小爱自己的 Agent | 进程内，无 IPC | **可用（需在进程内）** |
| B. 导出 AIDL 服务 | 5 个 `exported=true` 的 Service | 外部 App / 外部 Agent | `com.aios.osbot.permission.EXTERNAL_AGENT`（signature\|privileged） | 官方支持，第三方 App 拿不到权限 |
| C. MCP 客户端 | `mcp/mcp_servers.json` + `mcp` 工具 | 用户自己配的外部 MCP 服务 | 无（用户配置） | **完全开放** |

三条通道的能力并不重叠：A 是「小爱的能力」，B 是「把自己当成外部 Agent 接进来」，
C 是「让小爱来调你的工具」。本项目要走的是 **B（暴露给小爱）** 与 **C（被小爱调用）**，
以及 **A（在进程内直接取能力）**。

## 2. 通道 A：`UIAgentServiceForOSBot` 的 18 个方法

`assets/app_tools.json` 完整声明了这套接口（`class` 字段固定为
`com.xiaomi.voiceassistant.UIAgentServiceForOSBot`），由小爱自己的 Agent 通过
`apptool` 工具调用。

| # | 工具名 | 方法 | 参数 | 能力 |
|---|---|---|---|---|
| 1 | `nlp_query` | `nlpQuery` | query | 把小爱语义引擎的解析结果（music/alarm/weather/todo…）以 JSON 返回，可带 `render_token` |
| 2 | `show_instruction` | `showInstruction` | token | 用 `render_token` 渲染原生卡片 |
| 3 | `search_mi_knowledge` | `searchMiKnowledge` | query, scene, query_intent | 小米官方知识库（3C 参数、汽车、客服知识） |
| 4 | `favorite` | `favorite` | type, pageNumber, pageSize, keywords, favoriteId | 收藏：触发/列表/详情 |
| 5 | `write_memory` | `writeMemory` | query | 写入记忆（原始指令交给记忆子系统） |
| 6 | `read_memory` | `readMemory` | keywords, page, page_size | 本地记忆 + 云端收藏合并检索 |
| 7 | `get_memory_data` | `getMemoryData` | offset, limit, keywords | 已废弃，兼容旧调用 |
| 8 | `text_to_image` | `textToImage` | query | 文生图，返回真实文件路径 |
| 9 | `image_to_text` | `imageToText` | image, query | 图生文问答（content:// 或 file://） |
| 10 | `trans_text` | `translateText` | from, to, text | 文本翻译 |
| 11 | `trans_image` | `translateImage` | image_uri, from, to | 图片翻译（返回原文/译文） |
| 12 | `take_photo_recognize` | `takePhotoRecognize` | mode | 拍照翻译 / 拍照问答 |
| 13 | `search_media_tool` | `searchMedia` | 10 个可选参数 | 音乐/电台/视频搜索（只搜不播） |
| 14 | `play_media_tool` | `playMedia` | 8 个可选参数 | 播放（与 searchMedia 配对） |
| 15 | `settings_access` | `settingsAccess` | action, key, value | 读写小爱设置（当前仅 `dialect`） |
| 16 | `get_screen_context` | `getScreenContext` | — | 截图 + 前台应用 + 文档/网页/图片内容 |
| 17 | `get_current_package` | `getCurrentPackage` | — | 只查前台包名，不截图 |
| 18 | `exit_xiaoai` | `exitXiaoAi` | — | 关闭小爱主页/悬浮窗 |

要点：

- 这 18 个方法**没有一个是「让模型说话」**，全是「能力」。想驱动对话要走通道 B。
- `nlpQuery` 是最有价值的一个：它把「小爱对一句话的理解」直接吐成结构化 JSON，
  等于免费拿到小爱的 NLU。
- `writeMemory` / `readMemory` 与我们 P2 记忆功能重叠——**优先复用**，不要自己再写一套。
- 全部方法都在小爱进程内，只要我们的 hook 在进程里，就能直接拿到服务对象，
  **不需要 IPC、不需要权限**。

## 3. 通道 B：导出的 AIDL 服务

Manifest 里 `exported=true` 且带 `com.aios.osbot.permission.EXTERNAL_AGENT`
（`protectionLevel=0x12` = signature|privileged）的服务：

| Service | action | 接口 | 用途 |
|---|---|---|---|
| `com.aios.osbot.external.ExternalAgentService` | `com.aios.osbot.action.EXTERNAL_AGENT` | `IExternalAgentService`（interface_version=2） | 开/关会话、提交一轮请求、流式回调 |
| `com.aios.osbot.external.CliCommandService` | `com.aios.osbot.action.CLI_COMMAND` | `ICliCommandService` | 执行 CLI 命令、流式执行、上传流 |
| `com.aios.osbot.external.PendingIntentJumpService` | `PENDING_INTENT_JUMP` | — | 执行完跳回宿主 App |
| `com.aios.osbot.external.ExternalAsrService` | `EXTERNAL_ASR` | `IAsrCallback` | 外部送音频做 ASR |
| `com.aios.osbot.event.OSbotMessengerService` | `MESSENGER` | — | 事件/消息通道 |
| `com.xiaomi.voiceassistant.UIAgentServiceForOSBot` | `APP_TOOL_PROVIDER` | `IAppToolProvider` | 反方向：App 向小爱提供工具（权限 `BIND_APP_TOOL`） |

还有两个「App 提供工具」的配套：

```
com.aios.apptoolsdk.AppToolProviderService     App 侧实现，AIDL 暴露自研工具
com.aios.apptoolsdk.AnnotatedToolService       注解式实现（@Tool / @Param）
com.aios.osbot.action.APP_EVENT                 事件回传广播
```

### 3.1 接口签名（dexdump 实测）

```java
// IExternalAgentService
String openSession(String sessionId, boolean tts);
void   closeSession(String sessionId);
void   submit(String sessionId, String text, List<Attachment> attachments,
              IExternalAgentCallback cb);
boolean isFloatWindowVisible();
void   closeFloatWindow();

// IExternalAgentCallback —— 流式回调
void onTextDelta(String sessionId, String delta);
void onReasoningDelta(String sessionId, String delta);
void onToolEvent(String sessionId, String event);
void onTtsEvent(String sessionId, String event);
void onComplete(String sessionId, String result, List attachments);
void onError(String sessionId, String message);

// ICliCommandService
String executeCommand(String command);
String executeStreaming(String command, ICliCommandCallback cb);
String executeStreamingWithFd(String command, ParcelFileDescriptor fd,
                              ICliCommandCallback cb);
String openUploadStream(String name, ICliCommandCallback cb);
void   sendUploadChunk(String handle, String chunk);
void   endUploadStream(String handle);
void   cancelSubscription(String handle);

// ICliCommandCallback
void onResponse(String chunk, boolean done);
void onError(String message);
```

> 这套接口与小爱自己的 `cli` 工具（`executeCommand`）共用一套 CLI 词表，
> 也就是 `assets/prompts/tools/cli.txt` 里那份。

## 4. 通道 C：MCP

小爱自己是 **MCP 客户端**：

| 位置 | 内容 |
|---|---|
| `assets/mcp/README.md` | 给用户/AI 看的配置说明（含安全规则） |
| `assets/mcp/enterprise_mcp_servers.json` | 企业 MCP：`gateway_mode=true`，URL 走占位符 |
| `mcp/mcp_servers.json`（运行时，私有目录） | 个人 MCP 服务：`{name, url, enabled, headers}` |
| 工具 `mcp` | 「MCP 工具网关」，先 `help <provider>` 再调用 |
| 工具 `reload_mcp_config` | 写完配置后热加载 |

对我们的意义：

- 我们现有的 8771 HTTP MCP 是一个**反向**集成（我们驱动小爱）。
- 想把「我们的能力给小爱用」，只需让小爱连上我们的 MCP endpoint —— 不用 hook。
- 小爱对个人 MCP 的工具返回有明确的不信任约束（README 三段「安全规则」），
  这解释了我们不能靠 MCP 返回值让它读短信/联系人。

## 5. 权限模型与可行路径

```
EXTERNAL_AGENT  protectionLevel = 0x12 = signature | privileged
BIND_APP_TOOL   protectionLevel = 0x02 = signature
```

含义：普通第三方 App **拿不到**这两个权限，所以「外部 App 直接 bindService」这条路
对小爱以外签名的应用是关死的。

我们能走的三条路：

| 路径 | 做法 | 风险 | 推荐度 |
|---|---|---|---|
| 进程内直取 | 我们的 Xposed 代码跑在小爱进程里，直接反射 `ExternalAgentService` 实例或走 Hilt 注入图 | 依赖混淆名；版本升级要重定位 | 中 |
| 进程内调用 A（apptool） | 反射 `UIAgentServiceForOSBot` 的方法 | 同上，但方法名是稳定的英文 camelCase | **高** |
| MCP 反向注册 | 让小爱连我们（配置 `mcp_servers.json`） | 受小爱安全规则限制，能力面窄 | 中 |

> 现有 `Bridge`/`facade` 方案（`xiaoai_submit` / `xiaoai_cli`）相当于自己重造了通道 B，
> 且依赖 `OsbotFacadeImpl` 这种会漂移的类名。**通道 A 的方法名更稳定**，
> 后续新增 MCP 工具优先基于 A 实现。

## 6. 建议的 MCP 工具映射

按「能力唯一、参数简单」优先，把通道 A 逐个暴露成我们的 MCP 工具（与现有
`xiaoai_*` 命名保持一致）：

| 我们的 MCP 工具 | 背后 | 价值 |
|---|---|---|
| `xiaoai_nlp` | `nlpQuery` | 拿到小爱自己的语义解析（闹钟/音乐/天气/待办） |
| `xiaoai_knowledge` | `searchMiKnowledge` | 小米官方知识库检索 |
| `xiaoai_memory_read` / `xiaoai_memory_write` | `readMemory` / `writeMemory` | 复用官方记忆（替代自写 MEMORY.md） |
| `xiaoai_favorite` | `favorite` | 收藏（触发/列表/详情） |
| `xiaoai_image` | `textToImage` / `imageToText` | 文生图 / 图生文 |
| `xiaoai_translate` | `translateText` / `translateImage` | 文本 / 图片翻译 |
| `xiaoai_media` | `searchMedia` + `playMedia` | 媒体搜索与播放 |
| `xiaoai_screen` | `getScreenContext` / `getCurrentPackage` | 屏幕理解 |

代价评估：每个工具都要一次反射调用 + 参数拼装，且**必须在有 facade 的进程内**执行。
实现前先用一次 `xiaoai_cli` 或反射探针确认方法存在。

## 7. 已知未知

| 项目 | 状态 | 验证方法 |
|---|---|---|
| `UIAgentServiceForOSBot` 的反射入口是否稳定 | 未知 | 反射列出方法名，比对 `app_tools.json` |
| `submit` 是否等价于「用户发一句话」 | 推测等价 | 用 `xiaoai_submit` 对比返回结构 |
| 通道 B 能否在进程内绕过权限检查 | 推测可以（同 uid） | 进程内拿 service 实例直接调 |
| `cli` 命令词表全貌 | 部分（`assets/prompts/tools/cli.txt`） | 读该文件并逐个试 |
| `apptool` 与 `ToolRegistry` 的注册关系 | 未知 | 断点/日志看 `register` 调用 |
