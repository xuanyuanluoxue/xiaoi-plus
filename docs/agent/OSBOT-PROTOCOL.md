# OSBOT 协议侦察报告（只读）

- 目标：摸清「让小爱执行内置技能（先做创建待办）」的协议层，供手机端做 CLI/skill。
- 范围：`/home/chatxavier/code/xiaoi-plus`、worktree `liquid-nav`、小爱 APK 8.2.65.4714 的反编译产物、`tmp/osbot/xa` 探针源码。
- 约束：未 adb、未碰手机、未改业务代码、未编译；所有结论来自源码/grep/反编译。
- 关键结论：**待办没有 `todo.*` 这种 OsbotRequest；它是内置工具 `notes__todo_create`。走小爱 agent 时用 `conversation.send` / `conversation.send_streaming`；要确定性执行，直接调 Notes 的 `IAppToolProvider.tools/call`。**

---

## 0. 结论速览

| 问题 | 结论 |
| --- | --- |
| Q1 8770 facade 是模块自己的代码吗？ | **不是。** 8770 的 HTTP server、`/call` 白名单、`/methods` 都是独立探针 Xposed 模块 `xavier.xa` 的代码；`plus.xiaoi.module` 只是 hook 到了小爱的 `OsbotFacadeImpl` 实例。模块自己的 8770 是 `AgentHttpServer`（6 工具动作面），被占用时顺延到 8772。 |
| Q2 能直接拿 `OsbotFacadeImpl` 调 `dispatch` 吗？ | **实例能拿到**（ctor hook 已保存到 `Bridge.sFacade`），但 `dispatch` 是 **private**；应调 public 的 `execute(OsbotRequest, Continuation)`。缺的是：请求构造、`Continuation`/Flow 收集、以及 8772→8771 的进程路由。 |
| Q3 创建待办的最小请求是什么？ | **没有 `todo.create` 这个 OsbotRequest。** 待办工具是 `notes__todo_create`（agent 侧注册名）/ `todo_create`（Notes provider 线上名），参数 `{title, content}`。走 agent 的最小请求是 `conversation.send_streaming{agent_id:"osbot.main", text:"创建待办：…"}`；确定性执行则直接 JSON-RPC `tools/call`。 |

---

## 1. 三问结论与证据

### Q1：8770 的 facade 是不是模块自己的代码？白名单在哪？能否加白/绕过？

**结论：不是模块代码，是独立探针模块 `xavier.xa` 的 8770 HTTP 外壳。**

证据链：

1. `plus.xiaoi.module` 源码里 **没有** `resolveTaskMode`、没有 `/call` 白名单。全仓库 grep `resolveTaskMode` 只命中反编译产物和 `xa` 探针；模块源码里与 facade 相关的只有：
   - `app/src/main/java/plus/xiaoi/module/Hook.java:478-500`：`hookFacade()` 反射找 `com.aios.osbot.facade.OsbotFacadeImpl`，hook 所有构造器，构造完成后 `Bridge.install(cl, param.thisObject)`。
   - `app/src/main/java/plus/xiaoi/module/Bridge.java:152-155`：`sFacade = facade`；`:209-210` 只暴露 `hasFacade()` 给探活；`:91` 只把 `facadeReady()` 报给 8771。
2. 真正监听 8770 并实现 `/call`、`/methods` 的是 `tmp/osbot/xa/src/sources/xavier/xa/Hook.java`：
   - `:30` `PORT = 8770`；`:366-372` `ServerSocket` 绑定 `127.0.0.1:8770`。
   - `:94-105` `callMethod(String method, String arg)` 的白名单：
     ```java
     if (!"resolveTaskMode".equals(str)) {
         return "ERR method not whitelisted: " + str;
     }
     Method m = sFacade.getClass().getDeclaredMethod(str, String.class);
     ```
     只放行 `(String)->String` 的 `resolveTaskMode`；`dispatch` 签名不匹配，即使加白也调不到。
   - `:280-300` `listMethods()` 用 `getDeclaredMethods()` 列出非 synthetic 方法；`OsbotFacadeImpl` 正好有 **18 个**非 synthetic 方法（dexdump 计数：`dispatch/dispatchAsr/dispatchChatSession/dispatchEvent/dispatchMiClawEvents/dispatchMiClawGetEvents/dispatchMiClawGetSession/dispatchMiClawUploadFile/dispatchObserveBusinessEvent/dispatchVoiceProcessFlow/resolveTaskMode/toMiClawSessionEvent/toSessionInfo/execute/startAsr/subscribe/subscribeWithFd/uploadStream`），与手机侧看到的 18 个反射方法吻合。
   - `:448-455` ctor hook 抓 `sFacade`，然后 `startServerOnce()` + `bindCli()`。
3. 模块自己的 8770 是另一个东西：`app/src/main/java/plus/xiaoi/module/agent/AgentHttpServer.java:34-36` `DEFAULT_PORT=8770, PORT_MIN=8770, PORT_MAX=8775`；`:119-140` 依次尝试 8770~8775。若 8770 已被 xa 探针占用、8771 已被模块 MCP 占用，动作面自然落到 **8772**。这解释了「8770=facade、8772=模块动作面」同时存在。
4. 反编译里也能确认 `OsbotFacadeImpl.dispatch` 的访问标志是 **private**（dexdump `access=0x0012`），jadx 源码把它误标成 `public`；`execute` 才是 public（dexdump `access=0x0001`）。`resolveTaskMode` 也是 private，xa 用 `getDeclaredMethod + setAccessible` 才能调。

**能否加白/绕过？**

- 只改 `callMethod` 的一行白名单**不够**：`/call` 只接受一个 String 参数，无法构造 `OsbotRequest`，也无法传 `Continuation`。要支持 `dispatch`/`execute`，得给 xa 加一个新的 `/dispatch` endpoint（读 JSON、构造请求、传 Continuation），不是加白一行的事。
- xa 是**独立探针 APK**，源码在 `tmp/osbot/xa`，不在 `plus.xiaoi.module` 仓库里；改它要重新编译/安装探针，不是改模块。
- **更好的绕过**：模块已经拿到 `sFacade`，直接在模块内调 `execute`（见 Q2）；或者干脆绕过 facade，直接调 Notes 的 apptool（见 Q3）。两条路都不需要动 8770 的 `/call` 白名单。

---

### Q2：模块跑在小爱进程里，能否直接拿 `OsbotFacadeImpl` 调 `dispatch(OsbotRequest)`？现有 hook 点在哪、还缺什么？

**结论：能拿到实例；但不要直接调 `dispatch`（private），应调 public 的 `execute(request, Continuation)`。当前只“抓到了实例”，还没有任何调用封装。**

证据链：

1. `Hook.handleLoadPackage()` 只对 `com.miui.voiceassist` 生效（`Hook.java:17,44-46`），因此 hook 代码运行在小爱进程的 ClassLoader 里。
2. `Hook.java:478-500` 对 `OsbotFacadeImpl` 所有构造器挂 `afterHookedMethod`；实例化后 `Bridge.install(cl, thisObject)`；`Bridge.java:152-155` 保存到静态 `sFacade`。这是目前唯一的 facade 实例来源。
3. `Bridge.java:82` `private static volatile Object sFacade`；`:209-210` `hasFacade()`；`:91` `facadeReady()`。**没有任何地方调用 `dispatch`/`execute`/`subscribe`**。
4. `Bridge.java:356-397` 现有的数据面 hook 只有 `ConnectionContext.getOwnedSessions` 和 `CliCommandService.onBind`；`:886-888` 的 `callCli()` 走的是 `ICliCommandService.executeCommand(String)`，与 facade 无关。
5. 反编译确认：
   - `OsbotFacadeImpl.execute(OsbotRequest, Continuation)` 是 public，内部 `dispatch(request, continuation)` 并 catch 异常返回 `OsbotResponse.Error`（`out_impl/.../OsbotFacadeImpl.java:712-810`）。
   - `dispatch` 是 private（dexdump access `0x0012`），且是 Kotlin suspend；直接反射要 `getDeclaredMethod` + `setAccessible(true)`。
   - `subscribe(OsbotRequest)` 是 public，返回 `z82.i<OsbotResponse>`（混淆后的 Kotlin Flow），用于 `SendStreaming` 这类流式请求。
6. 进程边界很重要：
   - 8771 的 `Bridge`/MCP 跑在**小爱进程**（Hook 注入），所以那里能直接看到 `sFacade`，也能以 voiceassist 的 UID 调 Notes apptool。
   - 8772 的 `AgentService`/`AgentHttpServer` 跑在**模块自己的 App 进程**，那里 `sFacade == null`，而且不是系统 UID，调 Notes 的 `IAppToolProvider` 会被 `verifyCallerOrDebug()` 拒绝。
   - 因此 facade/skill 能力应挂在 **8771**（或从 8772 通过控制面转发到 8771），不要指望 8772 直接调。

**还缺什么：**

1. **实例/ClassLoader 访问器**：`sFacade`、`sLoader`、`Hook.sCl` 都是 private；需要在 `Bridge` 加 `facade()`/`classLoader()`（或包内可见）供新类使用。
2. **请求工厂**：模块没有 `OsbotRequest` 的编译期依赖，只能反射构造或走 kotlinx.serialization。`Conversation.Send` 构造器是 7 参：`(agentId, text, chatId, images, metadata, billing, inputFiles)`；`SendStreaming` 是 11 参，注意顺序是 `(agentId, chatId, text, images, timeoutMs, metadata, attachments, conversation, billing, inputFiles, clientCapabilities)`。
3. **Continuation**：`execute` 是 suspend；要么实现 `kotlin.coroutines.Continuation`（`getContext()` 返回 `EmptyCoroutineContext.INSTANCE`，`resumeWith` 里放结果 + CountDownLatch），要么用 `runBlocking`。`dispatch` 内部没有强制主线程检查，但 agent 执行可能回到主线程；不要在主线程阻塞等待。
4. **流式结果**：`Conversation.Send` 的 `t.e()` 是 **fire-and-forget**：它把任务丢到 `executeInBackground` 后立刻返回 `OsbotResponse.Ok`（`out_conv/.../t.java:629-654`）。要拿最终回复/卡片，必须 `SendStreaming` + `subscribe` 收集 Flow，或走 CLI `agent.submit`（`CliCommandService.executeCommand` 内部 `runBlocking`，已经帮你收完流）。
5. **进程路由**：若手机端只打 8772，需要在 8772 加一个代理动作，经 `BridgeControlService`/控制面转到 8771；或者手机端直接打 8771 的新 MCP 工具。
6. **错误/超时/幂等**：facade 的 `execute` 会把异常包成 `OsbotResponse.Error`，但反射层还要处理 `InvocationTargetException`、协程取消、超时；写操作还要幂等键（模块现有 `IdempotencyStore` 可复用）。

---

### Q3：创建一条待办的最小请求：字段、构造、Continuation、method 字符串

**结论：待办不是 OsbotRequest 的一个 method；它是内置工具 `notes__todo_create`。分三层看：**

| 层 | 最小“method” | 参数 | 结果 |
| --- | --- | --- | --- |
| 用户/agent 层 | `conversation.send` 或 `conversation.send_streaming` | `agent_id`, `text` | agent 自己决定调 `cli`/`notes` 工具 |
| 内置技能层 | `notes__todo_create`（agent 注册名）/ `todo_create`（provider 线上名） | `{title, content}`，`content` 是 `- [ ] xxx` 多行 | Notes 返回 `noteId` 等 |
| Notes AppFunction 回退 | `com.miui.notes/todoCreate` | `{title, content}` | 同上 |

**证据：**

1. `OsbotRequest` 的 sealed 子类清单（`out_req/.../OsbotRequest.java:25429` 的 serializer 列表）里只有 `Access/Agent/Asr/Card/ChatSession/Consent/Conversation/DataOrigin/Debug/Device/Event/File/Mcp/MiClaw/RemoteControl/Settings/Skill/Sts/Timer/Tool/Tts/Voice`，**没有 Todo，也没有 notes**。所以不存在 `todo.create` 这种 OsbotRequest。
2. 待办是 notes 内置技能：
   - `assets/bash-notes.js:90` `"todo_create": "todoCreate"`。
   - `:150-165` `callApi("todo_create")` 依次尝试 `notes__notes_todo_create`、`notes__note_todo_create`、`notes__todo_create`，都没有才回退 `app_func` `com.miui.notes/todoCreate`。
   - `:723-733` `notes create ... --type todo` 分支：`callApi("todo_create", {title, content: normalizeTodoContent(content)})`。
   - `:496-520` 技能帮助文本：`notes create <标题> "<待办项markdown>" --type todo`，`content` 只允许 `- [ ] 纯文本` 每行一项。
   - `assets/todo.js` 是待办卡片渲染器（`render/update`），不是执行入口。
3. `notes__todo_create` 这个名字在 APK 里也有硬编码交叉验证：
   - `classes23.dex` 常量池：`nj.m.d = "notes__todo_create"`（`code23.txt:1498062`）。
   - `classes27.dex` 的 `yq.c.<clinit>` 把它放进 notes 工具名集合（`dump27.txt` 偏移 `40645c`），与 `notes__note_create` 等并列。
   - `StandardAppToolProvider$discover$2` 的命名规则是 `registered = appLabel + "__" + AppToolDefinition.getName()`，然后 `toolToOriginalName[registered] = originalName`（`dump27.txt` 偏移 `220e30-220e96`）。因此 `notes__todo_create` 的线上原始名就是 **`todo_create`**。
4. apptool 线上协议：
   - `out_apptool/.../AppToolProviderService.java:24-70`：`handleRequest(String)` 解析 JSON，`method == "tools/call"` 时取 `params.name` + `params.arguments`，调 `AppTool.execute`，返回 `{"jsonrpc":"2.0","id":...,"result":{"content":[{"type":"text","text":"..."}],"isError":...}}`。
   - `out_apptool27/.../StandardAppToolProvider.java:68` `ACTION_TOOL_PROVIDER = "com.aios.osbot.action.APP_TOOL_PROVIDER"`；`:611` `queryIntentServices` 发现 provider；`:642-659` `execute` 把 agent 注册名映射回 `toolToOriginalName`，再 `executeIpc(package, originalName, args)`。
   - `executeIpc` 构造 `JsonRpcRequest("2.0", id, "tools/call", {"name": originalName, "arguments": args})`（`dump27.txt` 偏移 `221bfc` 附近）。
   - `verifyCallerOrDebug()` 要求调用者系统签名或目标 App debuggable（`AppToolProviderService.java:117-146`）；voiceassist 是系统应用，理论上可调，**未真机验证**。
5. 走 agent 的最小请求：
   - `CliSessionHandler.handleOpen` 的默认 `agentId = "osbot.main"`（`code23.txt` 偏移 `535052`，字符串 `osbot.main`）。
   - `CliAgentHandler.handleSubmit` 支持 `agent.submit`，必填 `sessionId` + `text`；它拿 session 的 `agentId`，构造 `Conversation.SendStreaming(agentId, null, text, 其余默认, Billing("miclaw-bvt","pass",false), ...)`（`code23.txt` 偏移 `559561` 起，构造在 `0x1a1`；`chatId` 传 0/null）。
   - `session.open` 本身也是 CLI handler；`agent.submit` 是自定义 handler，所以它的 params 用 camelCase `sessionId`；而走 facade 的 method 用序列化名 snake_case。
6. `Conversation.Send` 的 `dispatch` 行为：`t.e(Send)` 只做参数校验 + `executeInBackground`，然后立即 `OsbotResponse.Ok`；所以**不要**用 `conversation.send` 等最终结果，它不会给你。要结果用 `send_streaming` + `subscribe`，或 CLI。

---

## 2. OsbotRequest 结构表

### 2.1 通用信封

`OsbotRequest` 是 Kotlin `@Serializable` sealed interface；JSON 用 `type` 作为 discriminator，值是各子类的 `@SerialName`：

```json
{"type":"conversation.send","agent_id":"osbot.main","text":"..."}
```

CLI 层（`CliCodec`）则是：

```json
{"id":"1","method":"conversation.send","params":{"agent_id":"osbot.main","text":"..."}}
```

- `CliCodec.toOsbotRequest` 把 `method` 塞进 `type`，`params` 原样放入，然后 `json.decodeFromJsonElement(OsbotRequest.serializer(), ...)`（`out23/.../CliCodec.java:407-500`）。
- `params` 的 key 必须是**序列化名**（snake_case）；`flatToOsbotRequest` 那条路径才会做 snake→camel（`:200-233`）。
- Json 配置：`ignoreUnknownKeys=true`、`encodeDefaults=false`、`classDiscriminator="type"`（`CliCodec.java:70-90`）。

### 2.2 关键子类

| type | 类 | 用途 | 待办相关？ |
| --- | --- | --- | --- |
| `conversation.send` | `Conversation.Send` | 向 agent 发一条消息；**fire-and-forget**，立即返回 Ok | 可让 agent 调 notes 工具，但不等待结果 |
| `conversation.send_streaming` | `Conversation.SendStreaming` | 向 agent 发消息并订阅流式事件 | agent 路线的正确选择 |
| `conversation.subscribe` | `Conversation.Subscribe` | 订阅已有会话事件 | 配合 `send` 拿结果 |
| `chat_session.create` | `ChatSession.Create` | 新建会话 | 需要 chatId 时用 |
| `agent.list` | `Agent.List` | 列 agent（`enabled_only` 可选） | 找 agentId |
| `timer.*` | `Timer.*` | 闹钟/定时器，不是待办 | 否 |
| `device.send_task` | `Device.SendTask` | 跨设备任务，不是待办 | 否 |
| `skill.*` | `Skill.*` | 技能包管理（安装/删除/读取），不是执行内置技能 | 否 |
| `tool.list_categorized` | `Tool.ListCategorized` | 只列工具，不能调用 | 否 |

### 2.3 `conversation.send` 字段

构造器：`Send(String agentId, String text, String chatId, List<ImageContent> images, JsonObject metadata, Billing billing, List<InputFileContent> inputFiles)`

| 字段 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `agent_id` | String | **是** | 无 | 根 agent 用 `"osbot.main"` |
| `text` | String | **是** | 无 | 给 agent 的话 |
| `chat_id` | String? | 否 | null | 不传则新建/由 agent 决定 |
| `images` | List<ImageContent> | 否 | `[]` | 图片内容 |
| `metadata` | JsonObject? | 否 | null | 透传元数据 |
| `billing` | Billing? | 否 | null | CLI 传 `Billing("miclaw-bvt","pass",false)` |
| `input_files` | List<InputFileContent> | 否 | `[]` | 本地文件不能用（跨进程限制） |

序列化描述符顺序：`agent_id`(必填), `text`(必填), `chat_id`, `images`, `metadata`, `billing`, `input_files`（`OsbotRequest.java:9647-9654`）。

### 2.4 `conversation.send_streaming` 字段

构造器：`SendStreaming(String agentId, String chatId, String text, List<ImageContent> images, Long timeoutMs, JsonObject metadata, List<EventAttachment> attachments, List<ConversationMessage> conversation, Billing billing, List<InputFileContent> inputFiles, Set<StreamingClientCapability> clientCapabilities)`

| 字段 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `agent_id` | String | **是** | 无 | `"osbot.main"` |
| `chat_id` | String? | 否 | null | 不传则新建 |
| `text` | String | **是** | 无 | 给 agent 的话 |
| `images` | List<ImageContent> | 否 | `[]` |  |
| `timeout_ms` | Long? | 否 | null |  |
| `metadata` | JsonObject? | 否 | null |  |
| `attachments` | List<EventAttachment> | 否 | `[]` |  |
| `conversation` | List<ConversationMessage>? | 否 | null | 历史消息 |
| `billing` | Billing? | 否 | null | CLI 传 `miclaw-bvt/pass/false` |
| `input_files` | List<InputFileContent> | 否 | `[]` | 跨进程限制 |
| `client_capabilities` | Set<StreamingClientCapability> | 否 | `[]` |  |

注意构造器顺序是 `agentId, chatId, text`，不是 `agentId, text, chatId`（`OsbotRequest.java:10344`）。

### 2.5 嵌套类型

| 类型 | 字段 | 说明 |
| --- | --- | --- |
| `Billing` | `bizId: String?`, `featureId: String?`, `billable: Boolean` | CLI 用 `Billing("miclaw-bvt","pass",false)` |
| `ImageContent` | 图片内容 | 未展开 |
| `InputFileContent` | 本地文件内容 | `CliCodec` 明确拒绝跨进程本地文件 |
| `EventAttachment` | 事件附件 | 未展开 |
| `ConversationMessage` | 历史消息 | 未展开 |
| `StreamingClientCapability` | 流式能力枚举 | 未展开 |

### 2.6 构造方式

- **反射直接构造**（推荐，无编译依赖）：
  ```java
  Class<?> c = Class.forName("com.aios.osbot.facade.OsbotRequest$Conversation$SendStreaming", false, cl);
  Constructor<?> k = c.getConstructor(String.class, String.class, String.class,
      List.class, Long.class, JsonObject.class, List.class, List.class,
      Billing.class, List.class, Set.class);
  Object req = k.newInstance("osbot.main", null, text, emptyList(), null, null,
      emptyList(), null, billing, emptyList(), emptySet());
  ```
- **JSON 反序列化**：用 app 内的 `s92.b` Json + `OsbotRequest.Companion.serializer()`，和 `CliCodec` 一致；需要拿到 app 的 Json 实例（`s92.q.Json$default`）或直接反射 `OsbotRequest.Companion.serializer()`。
- **不要**试图 `new OsbotRequest.Conversation.Send(...)` 编译期引用：模块没有小爱 APK 的 compile classpath。

### 2.7 Continuation

- `execute` 签名：`Object execute(OsbotRequest, Continuation<? super OsbotResponse>)`。
- `dispatch` 签名：`Object dispatch(OsbotRequest, Continuation<? super OsbotResponse>)`，**private**。
- `subscribe` 签名：`Flow<OsbotResponse> subscribe(OsbotRequest)`。
- 自定义 Continuation：
  ```java
  final class C implements kotlin.coroutines.Continuation<Object> {
      final CountDownLatch latch = new CountDownLatch(1);
      volatile Object value;
      public kotlin.coroutines.CoroutineContext getContext() {
          return kotlin.coroutines.EmptyCoroutineContext.INSTANCE;
      }
      public void resumeWith(Object o) { value = o; latch.countDown(); }
  }
  ```
  调 `execute` 后，若返回值 == `IntrinsicsKt.getCOROUTINE_SUSPENDED()`，`latch.await(timeout)`；否则就是同步返回值。
- 流式：`subscribe` 返回的是混淆后的 `z82.i`（Kotlin Flow），要 `collect(z82.j, Continuation)`；`z82.j.emit(Object, Continuation)` 也要实现。模块是 Java，直接实现较啰嗦；更实际的是走 CLI `agent.submit`（它内部已经 `runBlocking` 收完流），或只调 `execute(Send)` 触发不等结果。

---

## 3. 待办调用伪代码

### 3.1 推荐：直接调 Notes 内置工具（确定性，不过 LLM）

在**小爱进程**（8771 的 Host，或 Xposed hook 内）执行：

```java
// 1) 找 provider：com.aios.osbot.action.APP_TOOL_PROVIDER
Intent q = new Intent("com.aios.osbot.action.APP_TOOL_PROVIDER");
List<ResolveInfo> rs = ctx.getPackageManager().queryIntentServices(q, 128);
ServiceInfo si = null;
for (ResolveInfo r : rs) {
    if ("com.miui.notes".equals(r.serviceInfo.packageName)) { si = r.serviceInfo; break; }
}
if (si == null) throw new IllegalStateException("Notes apptool provider not found");

// 2) bind
Intent i = new Intent().setComponent(new ComponentName(si.packageName, si.name));
ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
// conn.onServiceConnected: provider = IAppToolProvider.Stub.asInterface(binder)

// 3) JSON-RPC tools/call
String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
    + "\"params\":{\"name\":\"todo_create\","
    + "\"arguments\":{\"title\":\"买牛奶\",\"content\":\"- [ ] 买牛奶\"}}}";
String resp = provider.handleRequest(json);

// 4) 解析 result.content[0].text（通常是 JSON 字符串，含 noteId）
```

要点：
- `name` 用 **provider 的原始名** `todo_create`；`notes__todo_create` 是 agent 侧注册名，`StandardAppToolProvider.execute` 会通过 `toolToOriginalName` 映射（`StandardAppToolProvider.java:642-659`）。
- `content` 必须是 `- [ ] xxx` 每行一项；`title` 是待办清单标题（`bash-notes.js:496-520,723-733`）。
- 返回结构：`{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"..."}],"isError":false}}`（`AppToolProviderService.java:70-90`）。
- 调用者必须是系统签名（voiceassist 满足）或 Notes 为 debuggable；模块 App 进程不满足。

### 3.2 走小爱 agent：`conversation.send_streaming` + subscribe

```java
Object billing = newBilling("miclaw-bvt", "pass", false); // 反射
Object req = newSendStreaming("osbot.main", null, "帮我创建待办：买牛奶", billing);
Object flow = facade.getClass().getMethod("subscribe", osbotRequestClass).invoke(facade, req);
// collect flow -> OsbotResponse 事件；或直接走 CLI agent.submit 让 executeCommand 帮你收
```

- `agent_id` 用 `"osbot.main"`（`CliSessionHandler.handleOpen` 默认值）。
- `chat_id` 传 null 即可（CLI `agent.submit` 也是 null）。
- 这条路由会经过 LLM，模型可能调 `cli` → `notes create ... --type todo` → `notes__todo_create`，也可能不调；**不是确定性**。
- 若只求“让小爱动起来”，`conversation.send` 就够；它立刻返回 Ok，后台执行（`t.e`）。

### 3.3 现有 CLI 路径（手机端今天就能用）

```json
{"id":"1","method":"session.open","params":{}}
{"id":"2","method":"agent.submit","params":{"sessionId":"<上一步的 sessionId>","text":"帮我创建待办：买牛奶"}}
```

- `session.open` 默认 `agentId="osbot.main"`。
- `agent.submit` 是自定义 handler，params 用 camelCase `sessionId`/`text`；它构造 `SendStreaming` 并收集流（`CliAgentHandler.handleSubmit`）。
- 8771 已有 `xiaoai_cli`，也可以直接发 `{"id":"1","method":"conversation.send_streaming","params":{"agent_id":"osbot.main","text":"..."}}`；但这仍是 agent 路线，不是确定性待办。
- 这条路径仍然经过 LLM；要确定性，走 3.1。

### 3.4 不建议的路径

- `conversation.send` + `execute` 等结果：`Send` 是 fire-and-forget，`execute` 立刻返回 `Ok`，拿不到待办是否创建成功。
- `/call?method=resolveTaskMode`：只是任务模式解析，和待办无关；且白名单在 xa，不在模块。
- `timer.*`：是定时器/闹钟，不是 Notes 待办。

---

## 4. 下一步要动的文件

| 文件 | 改动 | 目的 |
| --- | --- | --- |
| `app/src/main/java/plus/xiaoi/module/Bridge.java` | 加 `public static Object facade()`、`public static ClassLoader classLoader()`（或包内可见）；可加 `executeOsbot(JSON)` 封装 | 让新类拿到 `sFacade`/ClassLoader，不再 private 反射 |
| 新文件 `app/src/main/java/plus/xiaoi/module/agent/OsbotFacadeClient.java` | 反射构造 `Conversation.Send/SendStreaming`；实现 `Continuation`；调 `execute`/`subscribe`；超时/错误映射 | facade 调用层 |
| 新文件 `app/src/main/java/plus/xiaoi/module/agent/AppToolClient.java` | `queryIntentServices(APP_TOOL_PROVIDER)` + bind `com.miui.notes` + `IAppToolProvider.handleRequest` JSON-RPC；解析 `result.content` | **确定性待办**，不经过 LLM |
| `app/src/main/java/plus/xiaoi/module/Mcp.java` | 8771 增加 `xiaoai_todo_create` 工具（schema: `title` 必填、`content` 必填/或 `items` 数组）；handler 调 `Host.createTodo(...)` | 手机端 CLI/skill 直接调 8771 |
| `app/src/main/java/plus/xiaoi/module/Bridge.java` | `Mcp.Host` 增加 `createTodo(title, content)` 默认实现，转发到 `AppToolClient` | 8771 在 voiceassist 进程执行，UID 正确 |
| `app/src/main/java/plus/xiaoi/module/agent/ActionRegistry.java` / `Actions.java`（可选） | 若手机端坚持打 8772，增加 `todo.create` 动作，经 `BridgeControlService` 转发到 8771 | 8772 进程没有 facade/系统 UID，不能本地做 |
| `app/src/main/java/plus/xiaoi/module/agent/CanonicalArgs.java` | 校验 `title` 非空、`content` 每行 `- [ ]` | 防止 Notes 解析出 0 项 |
| `docs/agent/CAPABILITIES.md` | 把 `notes__todo_create` / `todo_create` / `conversation.send_streaming` 补进能力清单 | 文档同步 |
| `tmp/osbot/xa/src/sources/xavier/xa/Hook.java`（仅探针） | 若要保留 8770 调试口，加 `/dispatch` endpoint，而不是只加白名单 | 8770 目前只能调 `(String)->String` |

推荐落地顺序：
1. `AppToolClient` + 8771 `xiaoai_todo_create`（确定性，最小闭环）。
2. 再补 `OsbotFacadeClient`（通用 agent 调用，能做更多内置技能）。
3. 最后考虑 8772 代理/动作，避免重复实现。

---

## 5. 没验证到的空白

1. **未真机验证 Notes provider**：没有 adb，无法确认 `com.miui.notes` 是否真的声明 `com.aios.osbot.action.APP_TOOL_PROVIDER`、服务类名、`todo_create` 的线上 schema 和返回字段。当前证据来自 `bash-notes.js` + `StandardAppToolProvider` 的通用协议。
2. **未验证系统签名放行**：`AppToolProviderService.verifyCallerOrDebug()` 对 voiceassist UID 的实际结果未跑；理论上系统应用通过。
3. **未真机确认 `todo_create` 的 schema/返回字段**：原始名已由命名规则确认为 `todo_create`，但 Notes 的 `app_tools.json` 是否注册、参数是否只有 `{title, content}`、返回的 `noteId` 字段名，仍需真机 `tools/list` 或实际调用确认。
4. **未验证反射构造**：`Send`/`SendStreaming` 的构造器参数顺序来自反编译；未在真机 `newInstance` 跑过。`billing=null`/`chatId=null` 的兼容性也未验证（CLI 传了 billing）。
5. **未验证 `execute`/`subscribe` 的线程与协程上下文**：`dispatch` 没有显式主线程检查，但 agent 内部可能 `withContext(Dispatchers.Main)`；在 8771 的 HTTP 线程直接 `runBlocking` 可能卡顿或死锁，需要真机压测。
6. **未完整反编译 `OsbotResponse`**：只知道 `Ok`/`Error`/`MiClawSessionEvent` 等，待办卡片的 `OsbotResponse` 具体变体/字段没展开；CLI 的 `flatFromOsbotResponse` 只做了通用映射。
7. **未核对 xa 探针安装包**：`tmp/osbot/xa` 是反编译/重建的源码，不能 100% 确认与手机上安装的 8770 探针是同一版本；但端口、白名单、18 方法三点吻合。
8. **未验证 8772→8771 控制面转发**：`BridgeControlService`/`BridgeControlBinder` 的现有 RPC 是否能承载任意工具调用，没细读；若要 8772 代理，需要补这块。
9. **未验证 `notes create --type todo` 的 CLI 入口**：`bash-notes.js` 是 agent 内置 CLI 的脚本，但模块能否直接触发这条 CLI（不经过 LLM）未找到公开入口；`ICliCommandService` 只接受 JSON-RPC 请求，不支持裸命令。

---

## 6. 证据索引（快速查）

| 结论 | 证据 |
| --- | --- |
| 模块 hook facade 构造器 | `app/src/main/java/plus/xiaoi/module/Hook.java:478-500` |
| 模块保存 facade 实例 | `app/src/main/java/plus/xiaoi/module/Bridge.java:152-155,209-210` |
| 模块 8770~8775 顺延 | `app/src/main/java/plus/xiaoi/module/agent/AgentHttpServer.java:34-36,119-140` |
| xa 探针 8770 server | `tmp/osbot/xa/src/sources/xavier/xa/Hook.java:30,366-372` |
| xa `/call` 白名单 | 同上 `:94-105` |
| xa `/methods` 18 方法 | 同上 `:280-300`；dexdump `OsbotFacadeImpl` 18 个非 synthetic 方法 |
| `dispatch` private / `execute` public | dexdump `classes23.dex`：`dispatch access=0x0012`，`execute access=0x0001` |
| `resolveTaskMode` 私有 | `out_impl/.../OsbotFacadeImpl.java:647`；xa 用 `setAccessible` |
| `Send` fire-and-forget | `out_conv/.../t.java:629-654`（`executeInBackground` 后返回 `Ok`） |
| `agent.submit` → `SendStreaming` | `code23.txt` `CliAgentHandler.handleSubmit` 偏移 `0x1a1`；`miclaw-bvt`/`pass` |
| `session.open` 默认 `osbot.main` | `code23.txt` `CliSessionHandler.handleOpen` 偏移 `0x51` |
| 没有 todo OsbotRequest | `out_req/.../OsbotRequest.java:25429` serializer 列表 |
| `notes__todo_create` | `assets/bash-notes.js:90,150-165,723-733`；`code23.txt:1498062`；`dump27.txt` 偏移 `40645c` |
| apptool JSON-RPC | `out_apptool/.../AppToolProviderService.java:24-70`；`out_apptool27/.../StandardAppToolProvider.java:68,611,642-659` |
| 8771 `xiaoai_submit`/`xiaoai_cli` | `app/src/main/java/plus/xiaoi/module/Mcp.java:208-235,376-395` |
| 8771 CLI 调用 | `app/src/main/java/plus/xiaoi/module/Bridge.java:886-888` |

---

## 7. 一句话给手机端

- **要确定性创建待办**：绑定 `com.miui.notes` 的 `IAppToolProvider`，发 `tools/call`，`name=todo_create`，`arguments={title, content:"- [ ] ..."}`。
- **要通用“让小爱执行技能”**：在 8771 加 `xiaoai_*` 工具，走 `conversation.send_streaming` + `subscribe`；不要用 `conversation.send` 等结果。
- **不要**再往 8770 的 `/call` 白名单里加东西：那里只能调 `(String)->String`，而且不是模块代码。
