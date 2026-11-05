# 兼容性矩阵

模块对版本做**精确匹配**（`SUPPORTED_VERSIONS` 里写死的 `versionName`）。
不在列表里的版本一律 no-op：不 hook、不改请求、不报错，小爱按原样工作。

| 小爱版本 | 状态 | 已适配功能 | 混淆名 |
|---|---|---|---|
| 8.2.65.4714 | ✅ 已验证 | 模型重定向 / 请求头标识 | 配置 `sm.a`、请求 `qm.j`、Json `s92.b#d` |
| 其他版本 | ⏸ 未适配 | —（自动跳过） | — |

查看当前版本：

```bash
adb shell dumpsys package com.miui.voiceassist | grep -m1 versionName
```

## 契约测试

装完模块后问小爱一句话，检查：

- 日志里是否有 `模型标识已改走请求头 X-Model-Provider-Id=xiaoi-plus`
- 没有 → 看日志：

  ```bash
  adb logcat -s VectorLegacyBridge | grep "xiaoi+"
  ```

| 日志 | 结论 |
|---|---|
| `已启用 model=...` | 模块正常工作 |
| `配置为空，跳过` | 设置页没保存，或端点/模型名为空 |
| `版本不在白名单: ...` | 该版本未适配，按下面清单适配 |
| `配置 hook 失败` / `provider 标识 hook 失败` | 混淆名变了，按下面清单适配 |

## 如何适配新版本

小爱每次发版都可能重命名混淆类。**只需要定位三个东西**，改完重编译即可。
整个过程大约 15 分钟。

### 前置：把 APK 拉下来反编译

```bash
# 1) 找 APK 路径（需要 root）
adb shell su -c 'pm path com.miui.voiceassist'
# 输出形如：package:/data/app/~~AbC==/com.miui.voiceassist-XyZ==/base.apk

# 2) 用上一步的路径拉出来（cat 比 pull 稳，绕过 /data 权限）
adb shell su -c 'cat /data/app/~~AbC==/com.miui.voiceassist-XyZ==/base.apk' > xiaoi-base.apk

# 3) 反编译成 Java
jadx -d /tmp/xiaoi-dec xiaoi-base.apk
```

没有 `jadx` 就用 `apktool d -f xiaoi-base.apk -o /tmp/xiaoi-smali`，
然后把下面的 `.java` 换成 `.smali`、`grep` 关键字不变。

### 步骤 1：找「配置对象」

特征：一个类，提供三个返回 `String` 的 getter，分别给**端点、密钥、模型**。

```bash
grep -rn --include=*.java "getBaseUrl\|getApiKey" /tmp/xiaoi-dec/sources | head
```

getter 名也被混淆时，改搜字符串常量（配置键名通常保留）：

```bash
grep -rn --include=*.java '"api_key"\|"base_url"\|"apiKey"' /tmp/xiaoi-dec/sources | head
```

命中后往上翻，确认：这个类被 `SharedPreferences` 或某个配置构造器实例化，
且三个 getter 的返回值被拼进网络请求。记下**完整类名**（当前是 `sm.a`）。

### 步骤 2：找「请求构造」

特征：方法名里含 `buildResponsesRequest`（Kotlin/Retrofit 生成的名字一般不会被混淆掉），
返回值是 JSON 元素，JSON 里有 `instructions`、`input`、`model` 字段。

```bash
grep -rn --include=*.java "buildResponsesRequest" /tmp/xiaoi-dec/sources | head
```

记下方法所在的**完整类名**（当前是 `qm.j`）。
模块只按方法名包含 `buildResponsesRequest` 来匹配，所以类名对就行。

### 步骤 3：找「JSON 解析器单例」

特征：`kotlinx.serialization.json.Json` 的静态实例，模块用它把改过的 JSON 字符串转回原类型。

```bash
grep -rn --include=*.java "parseToJsonElement" /tmp/xiaoi-dec/sources | head
```

或直接找持有 `Json` 静态字段的类：

```bash
grep -rn --include=*.java "static.*Json " /tmp/xiaoi-dec/sources | head
```

记下**类名 + 静态字段名**（当前是 `s92.b` 的字段 `d`）。

### 收尾：改代码、重编译、验证

在 `app/src/main/java/plus/xiaoi/module/Hook.java` 改四个地方：

| 位置 | 改什么 |
|---|---|
| `SUPPORTED_VERSIONS` | 加入新的 `versionName`（保留旧版本，便于回退） |
| `hookConfigObject` 里的 `Class.forName("sm.a", ...)` | 换成步骤 1 的类名 |
| `hookRequestBuilder` 里的 `Class.forName("qm.j", ...)` | 换成步骤 2 的类名 |
| `hookRequestBuilder` 里的 `Class.forName("s92.b")` 与 `getField("d")` | 换成步骤 3 的类名与字段名 |

然后：

```bash
./build.sh
adb install -r build/xiaoi-plus.apk
adb shell am force-stop com.miui.voiceassist
adb logcat -c && adb logcat -s VectorLegacyBridge | grep "xiaoi+"
```

看到 `配置 hook 完成` 和 `provider 标识 hook 完成`，再问一句小爱，确认请求头标识日志出现。

### 提交适配

PR 里请附上：小爱 `versionName`、三个混淆名、`adb logcat` 的关键几行，
以及本文件矩阵里新增的一行。**只提交自己验证过的版本**。

## 已知边界

- 只适配 `com.miui.voiceassist`（超级小爱），不覆盖旧版「小爱同学」其他包名
- 只处理 `provider=openai` 的 Responses API 路径；其他 provider 不改写
- 标识靠请求头实现，不依赖模型是否遵守提示词
- 能力桥为实验性、默认关闭，适配新版本时无需关心；它依赖小爱内部 CLI 的 Facade 类，
  该类一变只是桥用不了（日志写「能力桥 hook 失败」），重定向与标记不受影响
