# 构建规范（Gradle / Compose / Miuix）

> 状态：**生效**（0.14 P0 起）
> 关联：[PLAN.md](PLAN.md)、[CONVENTIONS.md](CONVENTIONS.md)。
> 目标：一条命令可复现地产出可覆盖安装的签名 APK，并保留现有 JVM 测试与门禁。

## 1. 环境要求

| 项 | 要求 | 当前环境（2026-11-08） |
| --- | --- | --- |
| JDK | 21（AGP 9.5 要求） | 已装 `java-21-openjdk`，构建时显式 `JAVA_HOME` |
| Android SDK | platforms;android-37.0、build-tools;37.0.0、platform-tools | Linux SDK 已装；`local.properties` 指向 `~/Android/Sdk` |
| Gradle | 9.9.0（wrapper 固定） | `gradlew` 可用，distributionUrl 固定 9.9.0 |
| 网络 | 可访问 Maven Central / Google Maven | `settings.gradle.kts` 配阿里云镜像，官方源兜底 |
| 设备 | 有线/无线 ADB（注入/截图验证） | 2026-11-08 白天可用（31106e85），晚间断开；真机回归见 A8 |

### 1.1 安装 JDK 21

优先用 x-cmd：

```bash
. ~/.x-cmd.root/X
x env use openjdk-21
java -version
```

或使用系统包管理器安装 `openjdk-21-jdk`。构建时显式：

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew ...
```

### 1.2 安装 Linux Android SDK

不要直接让 Gradle 用 `/mnt/c/...` 的 Windows SDK（路径/可执行文件/文件锁都可能出问题）。安装到 Linux 目录：

```bash
SDK_ROOT="$HOME/Android/Sdk"
mkdir -p "$SDK_ROOT/cmdline-tools"
# 下载 commandlinetools-linux 最新 zip（Google 官方），解压为 cmdline-tools/latest
# 然后：
yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_ROOT" \
  "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
```

`local.properties`：

```properties
sdk.dir=/home/chatxavier/Android/Sdk
```

> 如果 `android-37.0` 不存在，按 sdkmanager 列表选择可用的 37 平台；Miuix AAR 要求 `minCompileSdk=37`，不能用 35。

### 1.3 Gradle wrapper

从 Miuix 仓库拷贝 wrapper，避免先装 Gradle：

```bash
cp -r /home/chatxavier/code/github/miuix/gradle/wrapper gradle/
cp /home/chatxavier/code/github/miuix/gradlew .
cp /home/chatxavier/code/github/miuix/gradlew.bat .
# gradle/wrapper/gradle-wrapper.properties 已固定 9.9.0：
#   distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-9.9.0-bin.zip
#   distributionSha256Sum=0b00b0ff…
# services.gradle.org 会 307 到 GitHub Releases；GitHub CDN 曾持续 504，
# 因此改用镜像并用固定 SHA-256 校验完整性。
chmod +x gradlew
```

### 1.4 代理

`gradle.properties`（仅本机，不提交）：

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7897
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7897
```

或：

```bash
GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897" ./gradlew ...
```

## 2. 工程结构

```text
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/plus/xiaoi/module/...
app/src/main/res/...
app/src/main/assets/xposed_init
tests/run.sh
scripts/check-consistency.sh
scripts/verify-apk.sh
keystore/xiaoi.keystore
```

- 单 `:app` 模块；不拆 `:core`。
- `libs/xposed-stub-src` 用 `compileOnly files(...)`。
- `tests/run.sh` 保持独立，不依赖 Gradle。

## 3. 版本与依赖

| 项 | 值 |
| --- | --- |
| Gradle | 9.9.0 |
| AGP | 9.5.1 |
| Kotlin | 2.4.21 |
| Compose Multiplatform | 1.12.2 |
| Miuix | 0.9.4（`miuix-ui`、`miuix-preference`、`miuix-icons`） |
| compileSdk | 37 |
| minSdk / targetSdk | 26 / 26 |
| Java/Kotlin target | 11（Compose 1.12 要求） |
| versionName / versionCode | 0.14.0 / 19（`AndroidManifest.xml` 单一来源） |
| release R8 | `isMinifyEnabled = true`、`isShrinkResources = true` |

- 不引入 `miuix-blur`、`miuix-nav`、navigation-compose、DI、图片库；`miuix-icons` 仅用于底栏/空态矢量图标。
- 依赖版本全部写在 `gradle/libs.versions.toml`；禁止在 `app/build.gradle.kts` 里散落版本号。
- 提交 `gradle/verification-metadata.xml` 或 lockfile（P3 完成）。

## 4. app/build.gradle.kts 要点

```kotlin
android {
    namespace = "plus.xiaoi.module"
    compileSdk = 37
    defaultConfig {
        applicationId = "plus.xiaoi.module"
        minSdk = 26
        targetSdk = 26
        // 单一来源：AndroidManifest.xml（便于 verify-apk / version.sh 读取）
        versionCode = manifestAttr("versionCode").toInt()
        versionName = manifestAttr("versionName")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

- `versionCode`/`versionName` 单一来源：**AndroidManifest.xml**（0.14 起 Gradle 读取）；目标值 `0.14.0` / `19`，由 A8 定版。
- 签名读取 `KEYSTORE_PATH`/`KEYSTORE_PASS`/`KEY_ALIAS`/`KEY_PASSWORD`，与 `scripts/release.sh` 一致。
- `android:debuggable` 只在 debug。

## 5. Manifest 与资源迁移

| 项 | 要求 |
| --- | --- |
| `package=` | 删除；用 `namespace` |
| `<add-resource>` | 全部删除（overlay 遗留） |
| `uses-sdk` | 保留 26/26，或由 Gradle 接管 |
| `assets/xposed_init` | 原样保留 |
| `meta-data xposed*` | 原样保留 |
| `android:exported` | 与现有一致；不新增导出组件 |
| 权限 | 与现有 Manifest 一致；不新增危险权限 |

## 6. R8 / 多 dex / 体积

release 已开启 R8（`isMinifyEnabled = true`）与资源收缩（`isShrinkResources = true`），
debug 保持关闭。规则在 `app/proguard-rules.pro`，原则是**只 keep 运行时按名字访问的入口**：

| 规则 | 原因 |
| --- | --- |
| `-keep class plus.xiaoi.module.Hook { *; }` | `assets/xposed_init` 声明，LSPosed 反射实例化 |
| `-keepclassmembers ... IXposedHookLoadPackage { handleLoadPackage }` | 接口方法签名不能改写 |
| `-keep class plus.xiaoi.module.control.BridgeControlService { *; }` | `ControlKeys.SERVICE_CLASS` 字符串 + 显式 ComponentName 绑定 |
| `-keepclassmembers ActionDescriptor/ActionRequest/ActionResult/ActionRisk { public <fields>; }` | Agent 协议对象的公开字段名 |
| `-dontwarn de.robv.android.xposed.**` | Xposed API 是 compileOnly，运行时由 LSPosed 提供 |
| `-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable` | 注解/内部类与可读崩溃栈 |

未使用 `-dontobfuscate`，也没有整包 keep；Compose / Miuix / AndroidX / Kotlin 的规则由
各自 AAR 的 consumer rules 提供。

实测体积（2026-11-08，同一提交）：

| 产物 | 大小 |
| --- | --- |
| release，R8 前 | 8,721,766 B（8.4 MiB） |
| release，R8 后 | 1,657,323 B（1.6 MiB） |
| debug（不 R8） | 15,718,702 B（15.0 MiB） |

- 目标：release ≤6MB；debug ≤10MB 只是早期预算，当前 debug 含 Compose tooling，仅本地使用。
- 资源收缩后 APK 内 `res/` 只剩 35 个条目；`assets/xposed_init` 必须保留。
- minSdk 26 原生 multidex；AGP 自动处理。
- 目标：release ≤6MB（A8 已实测 1.6 MiB）；debug ≤10MB 只是本地预算。
- 体积回归时先看 `app/build/outputs/mapping/release/`（`mapping.txt`、`usage.txt`、
  `resources.txt`）和 `unzip -l` 的条目分布，不要为了体积删功能。
- 保留 `assets/licenses/**` 与 `assets/xposed_init`；资源收缩不能删这两类资产。

## 7. 命令

```bash
# debug（不 R8）
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug

# release（有 keystore 口令时自动签名）
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  KEYSTORE_PASSWORD=... ./gradlew :app:assembleRelease

# 已有 Gradle 缓存时可离线构建；首次构建需要联网拉 R8/Compose mapping 依赖
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleRelease --offline

# 单测（保持现有脚本）
./tests/run.sh

# 门禁
./scripts/check-consistency.sh
SDK="$HOME/Android/Sdk" ./scripts/verify-apk.sh app/build/outputs/apk/release/app-release.apk
```

产物：`app/build/outputs/apk/release/app-release.apk`（无正式密钥时为
`app-release-unsigned.apk`）。R8 报告在 `app/build/outputs/mapping/release/`。

> 0.14 起 README 只保留 Gradle 命令；`build.sh` 仅覆盖 ≤0.13 的纯 Java 构建，0.14 不再使用。
> CI 已由 A8 切到 JDK 21 + Gradle 9.9；`scripts/release.sh` 按 A8 报告里的流程执行。

## 8. CI

`.github/workflows/build.yml` 与本地命令保持一致：

- JDK 21（temurin）+ `android-actions/setup-android@v3` 安装
  `platforms;android-37.0`、`build-tools;37.0.0`。
- Gradle 由 wrapper 固定 9.9.0；缓存 `~/.gradle/caches` 与 `~/.gradle/wrapper`，
  key 用 `gradle/libs.versions.toml`、wrapper 属性和 `*.gradle.kts` 的哈希。
- 步骤：`tests/run.sh` → `check-consistency.sh` → `assembleDebug` →
  `assembleRelease` → `verify-apk.sh`。
- 签名：GitHub Secrets `KEYSTORE_BASE64` 解码到 `keystore/xiaoi.keystore`，
  配合 `KEYSTORE_PASSWORD` / `KEYSTORE_ALIAS` / `KEYSTORE_KEY_PASSWORD` 让
  Gradle 直接产出正式签名 APK；没有密钥时用临时证书签名，只出 artifact、不建 Release。
- 不新增任何额外 secret；`contract.yml` 仍是手动触发的真机契约。

## 9. P0 验收

- [ ] `./gradlew :app:assembleDebug` 成功。
- [ ] `assembleRelease` 产出签名 APK；包名/versionCode/证书指纹与现有一致。
- [ ] 覆盖安装成功，旧配置保留。
- [ ] LSPosed 激活后模型重定向仍生效。
- [ ] 新增 Miuix Hello 页可打开，深浅色正常。
- [ ] 记录 APK 体积、冷启动、首页首帧；无小爱进程崩溃/类冲突。
- [ ] `tests/run.sh`、`check-consistency.sh`、`verify-apk.sh` 全绿。

## 10. Worktree 与分支

```bash
# 从 main 创建 P0 worktree（不要放 ~/code）
git worktree add "$HOME/xiaoi-plus-dev/worktrees/ui-p0" -b feat/ui-p0
cd "$HOME/xiaoi-plus-dev/worktrees/ui-p0"

# 合并后删除
git worktree remove "$HOME/xiaoi-plus-dev/worktrees/ui-p0"
git branch -d feat/ui-p0
```

- 不在 `~/code` 下创建 `xiaoi-plus-*` 兄弟目录。
- 合并后立即清理；不跨天保留。
- 每个工作包一个分支；主 Agent 负责合并。

## 11. 常见故障

| 现象 | 处理 |
| --- | --- |
| `minCompileSdk=37` 报错 | compileSdk 改为 37，安装 platforms;android-37.0 |
| `Unsupported class file major version 65` | 升级 AGP/D8/JDK 21；确认使用 Gradle 9.9 |
| `Namespace not specified` | 删除 Manifest `package`，加 `namespace` |
| 资源重复定义 | 删除 `colors.xml` 的 `<add-resource>` |
| Hook 不生效 | 检查 R8 keep、`assets/xposed_init`、LSPosed 作用域 |
| 签名不一致 | 复用 `keystore/xiaoi.keystore` 与原口令；不要生成新证书 |
| Gradle 下载超时 | 配置代理；使用 `--offline` 仅在有缓存时 |
| 设备未连接 | P0 可先完成构建；注入测试等设备恢复，记录为 blocked |

## 12. P0 实测修正（2026-11-07）

以下与上文早期假设不同，以本节为准：

1. **jvmTarget = 11，不是 1.8**：Compose Multiplatform 1.12.2 的 Android 产物是
   JVM 11 字节码，`jvmTarget=1.8` 会报 `Cannot inline bytecode built with JVM target 11`。
   `app/build.gradle.kts` 的 Java/Kotlin target 都设为 11；源码仍不使用 Java 11+ 语言特性，
   minSdk 26 由 D8 desugar。P1 若坚持 1.8，需要重新评估 Compose 版本。
2. **AGP 9 不允许 Manifest 写 `<uses-sdk>`**：minSdk/targetSdk 唯一来源改为
   `app/build.gradle.kts`；`scripts/check-consistency.sh` 已适配并从 Gradle 读取。
3. **compileSdk 37.0**：AGP 9 使用 `compileSdk { version = release(37) { minorApiLevel = 0 } }`，
   对应 `platforms;android-37.0`。
4. **依赖镜像**：本机到 `dl.google.com` 的 TLS 不稳定，`settings.gradle.kts` 优先使用
   阿里云 Google/Central 镜像，官方源保留兜底；这不改变依赖版本。
5. **P0 R8 关闭、P3 打开**：0.14 P0 仅验证签名与可安装；A8 已开启
   R8/shrinkResources（1.6MB），keep 规则见 §6，真机注入回归待设备恢复。

## 13. UI 截图与测试产物

```bash
# 默认写 ~/xiaoi-plus-dev/xiaoi-plus-ui/tmp/YYYY-MM-DD/
./scripts/ui-screenshot.sh --route Me --name me-light

# 同时抓 uiautomator XML（设备临时文件会删除）
./scripts/ui-screenshot.sh --route Logs --name logs --dump

# 人工挑选后归档到 review/YYYY-MM-DD/
./scripts/ui-screenshot.sh --route Me --name me-dark --review

# 清理 tmp 下 7 天前的目录
./scripts/ui-screenshot.sh --clean
```

规则：

- 截图只走 `adb exec-out screencap -p`，**不在手机保存 PNG**。
- UI dump 写 `/data/local/tmp/xiaoi-ui-*.xml`，拉回 PC 后立即删除。
- 默认 `tmp/`；`review/` 只放人工确认过的验收图；`tests/` 放日志/XML。
- 含 token/key 的配置不进入截图目录。

## 14. 0.14 实测命令（2026-11-08）

```bash
# 0) JDK 21
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# 1) 构建（P2 验收使用 release）
./gradlew :app:assembleRelease --offline
# 产物：app/build/outputs/apk/release/app-release.apk

# 2) 门禁
./tests/run.sh
./scripts/check-consistency.sh
./scripts/verify-apk.sh app/build/outputs/apk/release/app-release.apk

# 3) 安装并打开指定路由（调试/验收）
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n plus.xiaoi.module/.MainActivity --es route Home
```

19 条路由：`Home`、`Xiaoai`、`Model`、`Access`、`Bridge`、`Token`、`Connect`、
`Capabilities`、`CapabilityDetail:<id>`、`Tools`、`Permissions`、`Me`、`Appearance`、
`ConfigManage`、`Diagnostics`、`Logs`、`About`、`Licenses`、`Legacy`。未知路由回首页。

截图（只写 PC，不在手机存 PNG）：

```bash
# 浅色/深色/1.3 字号，按需换 --route
./scripts/ui-screenshot.sh --route Home --name p3-home-light
./scripts/ui-screenshot.sh --route Me --name p3-me-dark --review
./scripts/ui-screenshot.sh --route Logs --name p3-logs --dump
```

0.14 结果记录：

| 项 | 结果 |
| --- | --- |
| `assembleRelease --offline` | BUILD SUCCESSFUL（P2 验收时 `versionCode=18`、R8 关闭） |
| `tests/run.sh` | 全部通过（NavStack 49/49） |
| `check-consistency.sh` | 7/7 通过 |
| `verify-apk.sh` | PASS；`plus.xiaoi.module`；指纹 `d7dd34b2…d19b` |
| APK 体积 | 8.3 MiB（R8 关闭）；开启 R8 后以 A8 RELEASE-REPORT 为准 |

> 0.14 的 versionName/versionCode 由 A8 改为 `0.14.0` / `19`；若本文与你本地
> Manifest 不一致，以 Manifest 和 A8 报告为准。
