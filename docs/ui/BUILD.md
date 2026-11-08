# 构建规范（Gradle / Compose / Miuix）

> 状态：**生效**（0.14 P0 起）
> 关联：[PLAN.md](PLAN.md)、[CONVENTIONS.md](CONVENTIONS.md)。
> 目标：一条命令可复现地产出可覆盖安装的签名 APK，并保留现有 JVM 测试与门禁。

## 1. 环境要求

| 项 | 要求 | 当前环境（2026-11-07） |
| --- | --- | --- |
| JDK | 21（AGP 9.5 推荐） | 只有 17，需要补 21 |
| Android SDK | platforms;android-37.0、build-tools;37.0.0、platform-tools | 无 Linux SDK；Windows SDK 有 android-37.0 |
| Gradle | 9.9.0（wrapper 固定） | 未安装；从 Miuix 仓库拷 wrapper |
| 网络 | 可访问 Maven Central / Google Maven | 有 HTTP(S) 代理 127.0.0.1:7897 |
| 设备 | 有线/无线 ADB（P0 注入验证） | 当前 `adb devices` 为空 |

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
# 修改 gradle/wrapper/gradle-wrapper.properties:
# distributionUrl=https\://services.gradle.org/distributions/gradle-9.9.0-bin.zip
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
| Miuix | 0.9.4（`miuix-ui`、`miuix-preference`） |
| compileSdk | 37 |
| minSdk / targetSdk | 26 / 26 |
| Java/Kotlin target | 11（Compose 1.12 要求） |

- 不引入 `miuix-blur`、`miuix-icons`、`miuix-nav`、navigation-compose、DI、图片库。
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
        versionCode = 19
        versionName = "0.14.0-ui.1"
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

- `versionCode`/`versionName` 单一来源：Manifest 或 Gradle，二选一；不要两边各写一套。
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

`proguard-rules.pro` 至少：

```proguard
-keep class plus.xiaoi.module.Hook { *; }
-keep class plus.xiaoi.module.agent.** { *; }
-keep class plus.xiaoi.module.core.** { *; }
-keepclassmembers class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keepattributes *Annotation*
```

- `assets/xposed_init` 不能被 shrink 删除。
- minSdk 26 原生 multidex；AGP 自动处理。
- 目标：release ≤6MB；debug ≤10MB。
- 开启 `shrinkResources`；保留 `assets/licenses/**` 与 `assets/xposed_init`。

## 7. 命令

```bash
# debug
./gradlew :app:assembleDebug

# release（需要签名口令）
KEYSTORE_PASSWORD=... ./gradlew :app:assembleRelease

# 单测（保持现有脚本）
./tests/run.sh

# 门禁
./scripts/check-consistency.sh
SDK="$HOME/Android/Sdk" ./scripts/verify-apk.sh app/build/outputs/apk/release/app-release.apk
```

> 过渡期可同时保留 `build.sh`；P3 删除手工构建，README 只保留 Gradle 命令。

## 8. CI

- JDK 21 + SDK 37 + Gradle 缓存。
- 步骤：`assembleDebug` → `tests/run.sh` → `check-consistency.sh` → `assembleRelease` → `verify-apk.sh`。
- 签名：GitHub Secrets 注入 keystore（base64）与口令；没有密钥时只产出 unsigned artifact，不建 Release。
- 缓存：`~/.gradle/caches`、`~/.gradle/wrapper`；key 用 `gradle/libs.versions.toml` 哈希。
- 保留 `scripts/ci-sdk.sh` 的固定 SDK 思路，改为安装 platforms 37。

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
5. **P0 R8 关闭**：release 仅验证签名与可安装，P3 必须打开 R8/shrink 并回归 Xposed。

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
