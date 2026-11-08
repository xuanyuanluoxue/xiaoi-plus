# P0 报告：Gradle + Kotlin + Compose + Miuix 构建验证

> 日期：2026-11-07
> 分支：`feat/ui-p0`（从 `main@8fc6eec` 创建）
> 工作区：`~/xiaoi-plus-dev/worktrees/ui-p0`
> 范围：只做 P0 构建/依赖/Spike 验证，不重写页面，不改业务逻辑。
> **2026-11-08 更新：真机补测完成，P0 结论 Go for P1（见 §14）。**

---

## 1. 环境结果

| 项 | 结果 | 说明 |
| --- | --- | --- |
| JDK | **21.0.12.1**（`/usr/lib/jvm/java-21-openjdk-amd64`） | x-cmd 无 `openjdk-21` 包，改用 Ubuntu 包 `openjdk-21-jdk-headless` |
| Gradle | **9.9.0**（wrapper 固定） | 从 Miuix 仓库拷贝 wrapper；distribution 由华为云镜像预置到 `~/.gradle/wrapper/dists` |
| AGP | **9.5.1** | AGP 9 内置 Kotlin，不能再应用 `org.jetbrains.kotlin.android` |
| Kotlin | **2.4.21** | 由 AGP 9 内置 Kotlin 提供；Compose 编译器插件同版本 |
| Compose Multiplatform | **1.12.2** | `compose.runtime/foundation/ui` |
| Miuix | **0.9.4** | `top.yukonga.miuix.kmp:miuix-ui` + `miuix-preference` |
| Android SDK | `~/Android/Sdk` | cmdline-tools 19.0、platform-tools 37.0.1 |
| platform | **android-37.0** | `compileSdk { release(37) { minorApiLevel = 0 } }` |
| build-tools | **37.0.0** | Gradle 默认使用 |
| minSdk / targetSdk | **26 / 26** | targetSdk 未上调 |
| package | `plus.xiaoi.module` | 未改包名 |

**JDK 21 安装命令（实际执行）**

```bash
sudo apt-get install -y openjdk-21-jdk-headless
java -version   # openjdk 21.0.12.1
```

**SDK 安装命令（实际执行）**

```bash
SDK_ROOT="$HOME/Android/Sdk"
curl -L -o commandlinetools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip commandlinetools.zip -d cmdline-tools-tmp
mv cmdline-tools-tmp/cmdline-tools "$SDK_ROOT/cmdline-tools/latest"
yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_ROOT" \
  "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
```

> `android-37.0` 与 `build-tools;37.0.0` 均真实存在，无需退到 35/36。
> 未使用 `/mnt/c/...` 的 Windows SDK。

---

## 2. Gradle 工程

新增/修改：

```text
settings.gradle.kts                 # 阿里云镜像优先，官方源兜底
build.gradle.kts                    # 插件版本声明
gradle/libs.versions.toml           # 版本唯一来源
gradle/wrapper/*                    # Gradle 9.9.0
gradlew / gradlew.bat
app/build.gradle.kts                # 单 :app；compileSdk 37.0；签名读取现有 keystore
app/proguard-rules.pro              # P3 用；P0 暂不启用 R8
app/src/main/java/plus/xiaoi/module/ui/spike/SpikeActivity.kt
```

关键点：

- `libs/xposed-stub-src` 通过自定义 `compileXposedStub` + `xposedStubJar` 任务编译成 jar，
  再以 `compileOnly` 引入；桩类**不进入 APK**。
- 版本号继续从 `AndroidManifest.xml` 读取（`versionCode=18`、`versionName=0.13.0-agent.2`），
  保证 `scripts/version.sh` / `verify-apk.sh` 仍以 Manifest 为单一来源。
- Manifest 删除 `package=` 和 `<uses-sdk>`（AGP 9 禁止）；namespace 在 Gradle 中声明。
- 7 个 `res/values/*.xml` 的 `<add-resource>` 已全部删除。
- `build.sh` 保留未删；P0 以 Gradle 为准。
- `local.properties`、`keystore/`、`build/`、`.gradle/` 均未提交（已有 `.gitignore`）。
- 代理只写入 `~/.gradle/gradle.properties`，未提交仓库。

**签名**：复用 `keystore/xiaoi.keystore` 与 `~/.config/xiaoi/keystore_pass`，
alias `xiaoi`，证书 SHA-256 与 `scripts/expected-signer.sha256` 一致。

---

## 3. 构建结果

实际执行：

```bash
./gradlew clean :app:assembleDebug :app:assembleRelease
```

结果：**BUILD SUCCESSFUL**。

| 产物 | 路径 | 体积 |
| --- | --- | --- |
| debug | `app/build/outputs/apk/debug/app-debug.apk` | **14,145,527 B（13.5 MiB）** |
| release | `app/build/outputs/apk/release/app-release.apk` | **7,705,934 B（7.35 MiB）** |

归档副本：

```text
~/xiaoi-plus-dev/artifacts/2026-11-07/apk/xiaoi-plus-0.13.0-agent.2-p0-release.apk
~/xiaoi-plus-dev/artifacts/2026-11-07/apk/xiaoi-plus-0.13.0-agent.2-p0-debug.apk
```

`aapt2 dump badging`：

```text
package: name='plus.xiaoi.module' versionCode='18' versionName='0.13.0-agent.2'
compileSdkVersion='37' compileSdkVersionCodename='17'
minSdkVersion:'26'
targetSdkVersion:'26'
```

`scripts/verify-apk.sh`：**PASS**

```text
签名         : 已签名
包名         : plus.xiaoi.module
versionCode  : 18
证书 SHA-256 : d7dd34b2e654562954c991730ba6ba88955ea1fcce9e13ae949f74b8b213d19b
结论         : PASS
```

---

## 4. 安装与 Spike 验证

### 4.1 真机：blocked

```text
adb devices                          -> 空
adb connect 192.168.0.118:5555       -> 超时
ping 192.168.0.118                   -> 100% packet loss
tcp 192.168.0.118:5555               -> 连接失败
Windows adb.exe devices              -> 空
```

结论：**没有可用真机**。覆盖安装、LSPosed 注入、小爱模型重定向均未在真机验证。

### 4.2 模拟器替代（AOSP ATD API 37 x86_64）

为避免“只编译不看”，临时安装并启动：

```text
emulator + system-images;android-37.0;aosp_atd;x86_64
```

结果：

- release APK `adb install` 成功；再次 `adb install -r` 成功（同签名、同 versionCode 覆盖）。
- `am start -W plus.xiaoi.module/.HomeActivity`：冷启动 **231 ms**（另一次 344 ms，模拟器数据，不代表真机）。
- 首页正常渲染：`主页 / 未配置 / v0.13.0-agent.2 / 今日三方 — / 校验模型 / 切换模型 / 超级岛 / 当前模型 / 接口地址`。
- logcat 无 `FATAL EXCEPTION`、无 `plus.xiaoi.module` 崩溃。
- API 37 首次启动会出现系统“此应用为旧版 Android 构建”的兼容提示（targetSdk=26 导致），
  点 OK 后正常。这是 P0 已知体验问题，不是崩溃。

**SpikeActivity（debug-only 临时导出）**

为让 `adb shell am start` 能打开非导出 Activity，测试时临时增加
`app/src/debug/AndroidManifest.xml` 把 `SpikeActivity` 覆盖为 `exported=true`；
验证后已删除该文件，最终 debug/release APK 均为 `exported=false`。

验证结果：

- `am start -W plus.xiaoi.module/.ui.spike.SpikeActivity`：`Status: ok`，COLD 1273 ms。
- UI 层级确认 Miuix 组件全部渲染：
  `Miuix Hello`、`示例开关`、`整行可点，验证 Miuix Switch`、`输入框`、`EditText xiaoi-plus`、
  `当前值：xiaoi-plus`、`按钮`、`点我：0`。
- 点击开关后文案变为 `开关=关`；点击按钮后变为 `点我：1`。
- `cmd uimode night yes` 后重开，文案为 `主题=深色`；恢复 `night no`。
- 无崩溃。

**截图状态：blocked**

模拟器 `adb shell screencap` 触发 `Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma`，
`adb emu screenrecord screenshot` 只能得到全黑图（软件 GPU 限制）。因此没有可用的像素截图；
使用了 `uiautomator dump` 作为渲染证据，保存在：

```text
~/xiaoi-plus-dev/artifacts/2026-11-07/screenshots/release-home-ui.xml
~/xiaoi-plus-dev/artifacts/2026-11-07/screenshots/spike-miuix-light-ui.xml
~/xiaoi-plus-dev/artifacts/2026-11-07/screenshots/spike-miuix-dark-ui.xml
~/xiaoi-plus-dev/artifacts/2026-11-07/screenshots/spike-miuix-interaction-ui.xml
```

---

## 5. LSPosed 注入

**结论：blocked（未真机验证）。**

已做的静态检查（release APK）：

- `assets/xposed_init` 内容 = `plus.xiaoi.module.Hook`，文件存在。
- Manifest `xposedmodule=true`、`xposeddescription`、`xposedminversion=93` 均保留。
- `classes.dex/classes2.dex` 中存在 `Lplus/xiaoi/module/Hook;`。
- `Hook.java` 不引用 `androidx.*` / `compose` / `miuix`；Compose 类不会因 Hook 主动加载。
- 权限集合与迁移前一致（未新增危险权限）；Gradle 仅注入
  `plus.xiaoi.module.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（AndroidX 标准普通权限）。
- `SpikeActivity` 在最终 debug/release APK 中均为 `exported=false`。

未完成：真机覆盖安装、LSPosed 模块激活、小爱进程启动、模型重定向请求头/日志、
Compose 类是否在小爱进程产生冲突。必须等设备恢复后补测。

---

## 6. R8 / 体积

- P0 明确关闭：`isMinifyEnabled=false`、`isShrinkResources=false`。
- release 7.35 MiB（未 R8），超过计划 ≤6 MB；debug 13.5 MiB，超过计划 ≤10 MB。
- P3 必须打开 R8 + shrinkResources，并重点回归：
  - `assets/xposed_init` 不被 shrink 删除；
  - `Hook`、`agent/**`、`core/**`、`control/**` keep；
  - 小爱进程无类冲突/崩溃。

---

## 7. 门禁

| 门禁 | 结果 |
| --- | --- |
| `./gradlew clean :app:assembleDebug :app:assembleRelease` | PASS |
| `./tests/run.sh` | PASS（全部通过，含 117 条幂等持久化断言） |
| `./scripts/check-consistency.sh` | PASS（7 项） |
| `SDK=~/Android/Sdk ./scripts/verify-apk.sh app-release.apk` | PASS |

`check-consistency.sh` 的适配：

- AGP 9 不允许 Manifest 写 `<uses-sdk>`，脚本改为从 `app/build.gradle.kts` 读取并强制
  `minSdk=26`、`targetSdk=26`；若 Manifest 仍有值则继续做双向一致校验。
- 其余检查（getIdentifier、裸 `new Thread`、HTTP 收敛、Toast、死资源）未放宽。

---

## 8. 与计划的偏差

| 项 | 计划 | 实际 | 原因 |
| --- | --- | --- | --- |
| Kotlin/Java target | 1.8 | **11** | Compose 1.12.2 产物是 JVM 11；1.8 无法内联编译 |
| minSdk 来源 | Manifest 或 Gradle | **仅 Gradle** | AGP 9 禁止 Manifest `<uses-sdk>` |
| 依赖源 | Google/Maven Central | **阿里云镜像优先** | 本机到 dl.google.com TLS 不稳定 |
| Gradle distribution | services.gradle.org | 预置到 wrapper 缓存（华为云下载） | 官方下载 TLS 握手失败 |
| release R8 | P3 开 | P0 关 | 按任务要求降低变量 |
| 截图 | 深浅色截图 | UI 层级 XML | 模拟器像素捕获失败 |

---

## 9. 失败与 blocked

1. **真机缺失**：`adb devices` 为空，无线设备不可达。安装、LSPosed、模型重定向、真机冷启动/首帧全部 blocked。
2. **模拟器截图失败**：`screencap` 断言崩溃 / emulator 截图全黑；只用 uiautomator 文本层级验证。
3. **API 37 旧版应用提示**：targetSdk=26 触发系统兼容对话框；P0 不处理，P1 需决定是否接受。
4. **体积超预算**：未开 R8，release 7.35 MiB > 6 MiB，debug 13.5 MiB > 10 MiB。
5. **依赖可复现性**：尚未提交 lockfile / verification-metadata（P3）。

---

## 10. Go / No-Go 建议

**结论：Conditional Go（可进入 P1 页面地基，但不能发布）。**

- Go 的依据：Gradle/AGP/Kotlin/Compose/Miuix 全链路可构建；release 可签名且指纹不变；
  Miuix Hello 在 API 37 上真实渲染并可交互；现有 Home/Java 核心未被破坏；三道门禁全绿。
- 条件（P1 合并前必须完成）：
  1. 真机覆盖安装 0.13.0-agent.2 → 本 APK，确认配置保留。
  2. LSPosed 模块激活，小爱启动不崩，模型重定向仍生效。
  3. 真机冷启动/首帧基线；确认 Compose 未进入小爱注入进程的类冲突。
- 不建议现在发布/打 tag：targetSdk 26、体积、LSPosed 未回归、无真机截图。

---

## 11. 下一步

1. 设备恢复后补 P0.4：真机覆盖安装 + LSPosed + 模型重定向 + 小爱启动日志。
2. P1 前决定 JVM target：维持 11（推荐）或评估降 Compose 版本换回 1.8。
3. P3 打开 R8，补 `verification-metadata`，把 release 压到 ≤6 MB。
4. P1 用正式 `XiaoiTheme` 替换 Spike 的临时颜色映射，并删除 SpikeActivity（或转为 debug 预览）。
5. 真机补深浅色截图矩阵；模拟器不再作为截图依据。

---

## 12. 最大风险

1. **LSPosed/小爱注入未真机验证**：这是 P0 最大未知数；Compose 依赖进入模块 APK 后是否影响
   小爱进程，目前只有静态证据，没有运行时证据。
2. **targetSdk 26 在新系统上的兼容行为**：API 37 已出现“旧版应用”提示；后续系统可能进一步限制。
3. **体积与 R8 回归**：未 R8 release 已 7.35 MiB；开 R8 后必须同时验证 Hook/Xposed 不被裁剪。

---

## 13. 2026-11-08 续跑记录（主 Agent）

- 设备 `31106e85` 曾短暂出现，随后 USB 与无线 ADB 均断开（192.168.0.118:5555 closed）；
  但 `http://192.168.0.118:8770/health` 仍返回 `ok`，说明 App 服务在运行。
- 因此 **真机覆盖安装、LSPosed 注入、模型重定向仍未验证**；模拟器结果只作构建/渲染证据。
- 复跑门禁：`tests/run.sh` 全通过；`check-consistency.sh` 通过；`verify-apk.sh` PASS。
- JVM target 实测：AGP 9 要求 Java/Kotlin target 一致；`Java 1.8 + Kotlin 11` 会直接报
  `Inconsistent JVM targets`。最终 Java/Kotlin 都设为 11，源码仍不使用 Java 11+ 语言特性。
  这是路线 1 的约束变化，需在合并前确认。
- 下一步：设备恢复后补 P0.4（安装、LSPosed、模型重定向、Compose 类冲突检查）。


---

## 14. 2026-11-08 真机补测（P0.4 完成）

设备 `31106e85` 有线 ADB。

| 项 | 结果 |
| --- | --- |
| 覆盖安装 | `adb install -r app-release.apk` 成功；versionCode 18、versionName 0.13.0-agent.2、签名指纹不变 |
| 首页启动 | `am start -W` WARM 156ms；无 FATAL/AndroidRuntime 崩溃 |
| 配置保留 | `xiaoai_config`：model=deepseek-v4.1-flash、baseUrl 不变、bridge=true；测试前 enabled=false |
| Miuix Spike | 真机启动成功；uiautomator 可见 Miuix Hello / 示例开关 / 输入框 / 按钮 |
| 深浅色截图 | `~/xiaoi-plus-dev/artifacts/2026-11-08/screenshots/p0-spike-{light,dark}.png` |
| 首页截图 | `~/xiaoi-plus-dev/artifacts/2026-11-08/screenshots/p0-home.png` |

**深色模式发现**：Spike 的 `MiuixTheme.textStyles.title1` 在深色下仍是黑字，黑底黑字不可读；
P1 的 `XiaoiTheme` 必须显式提供 `onBackground` 文本色（或包裹 `LocalContentColor`），
不能直接依赖 Miuix 默认 Text 颜色。浅色/深色截图已存档。

### LSPosed 注入

- release APK 的 `assets/xposed_init` = `plus.xiaoi.module.Hook`。
- 强制停止并重启小爱后，日志出现：
  - `[xiaoi+] supportsVision=true 已启用`
  - `[xiaoi+] 配置已热更新 model=deepseek-v4.1-flash ...`
  - `[xiaoi+] 控制面已应用配置 ...`
- 未出现 `FATAL EXCEPTION`、`NoClassDefFoundError`、`ClassNotFoundException`；
  Compose/Miuix 类没有破坏小爱注入进程。

### 模型重定向

临时把“启用第三方模型”打开，测试后已恢复 `enabled=false`（开关 checked=false）：

- `xiaoai_submit("只回复 OK")` 返回 `{"data":{"text":"[model=deepseek-v4.1-flash] OK"}}`。
- 日志：`[xiaoi+] 已记录小爱原模型 mimo-omni`、
  `[xiaoi+] 模型标识已改走请求头 X-Model-Provider-Id=xiaoi-plus`、
  超级岛提示“第三方模型 deepseek-v4.1-flash”。
- `xiaoai_stats`：今日 `third=6, official=0`。

### 结论

**P0：Go for P1。**

仍需处理/确认：

1. JVM target 11（Compose 1.12 要求）需要用户确认；若坚持 1.8 只能换 Compose/Miuix 版本。
2. 真机页面级深浅色截图在 P1 正式页面完成后补；P0 只验证了 Spike 与 Home。
3. R8/shrink 与 ≤6MB 目标在 P3；当前 release 7.4MB。
4. 依赖 lockfile/verification-metadata 在 P3 补。
