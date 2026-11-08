# A8 · Build/Release 报告（0.14.0 / R8）

> 日期：2026-11-08
> 分支：`feat/ui-release`（基于 `main@c59954d`）
> Worktree：`~/xiaoi-plus-dev/worktrees/ui-release`
> 范围：版本号、release R8/资源收缩、体积、CI、构建门禁；不改业务代码与 `res/**`

## 1. 结论

| 项 | 结果 |
| --- | --- |
| versionName / versionCode | **0.14.0 / 19**（只改 `AndroidManifest.xml`） |
| release R8 | **开启** `isMinifyEnabled` + `isShrinkResources` |
| release 体积 | **8,721,766 B → 1,657,323 B（8.4 MiB → 1.6 MiB，-81.0%）** |
| 6MB 目标 | **达标**（1.66MB，余量约 4.3MB） |
| R8 missing class | **0**（无 `missing_rules.txt`，构建无相关 warning） |
| 签名指纹 | `d7dd34b2e654562954c991730ba6ba88955ea1fcce9e13ae949f74b8b213d19b`（未变） |
| 单测 / 一致性 / APK 校验 | 全部 PASS |
| 真机 R8 回归 | ✅ 已补测（见 §7.1） |
| CI | workflow 已切到 Gradle/JDK 21/SDK 37；本地等价命令全绿，GitHub 实跑需 push 后确认（A8 不 push） |

产物：
`~/xiaoi-plus-dev/artifacts/2026-11-08/apk/xiaoi-plus-0.14.0-release.apk`
SHA-256：`83037c2df9abb01db5150905446192d287c3b7655476da8016df73043db6fede`
构建日志：`~/xiaoi-plus-dev/artifacts/2026-11-08/logs/assembleRelease-r8.log`

## 2. 版本号

- `app/src/main/AndroidManifest.xml`：`versionCode 18 → 19`，`versionName 0.13.0-agent.2 → 0.14.0`。
- `app/build.gradle.kts` 仍从 Manifest 读取，`scripts/version.sh` 自动跟随，无需第二处修改。
- `docs/ui/BUILD.md` 的示例版本同步为 `0.14.0`；CHANGELOG 按分工未动。

## 3. R8 规则（`app/proguard-rules.pro`）

原则：**只 keep 运行时按名字/反射访问的入口，不整包 keep，不使用 `-dontobfuscate`**。

| 规则 | 覆盖对象 |
| --- | --- |
| `-keep class plus.xiaoi.module.Hook { *; }` | `assets/xposed_init` 声明的 LSPosed 入口，反射实例化 |
| `-keepclassmembers class * implements IXposedHookLoadPackage { handleLoadPackage }` | 入口接口方法签名 |
| `-keep class plus.xiaoi.module.control.BridgeControlService { *; }` | `ControlKeys.SERVICE_CLASS` 字符串 + 显式 `ComponentName` 绑定 |
| `-keepclassmembers ActionDescriptor / ActionRequest / ActionResult / ActionRisk { public <fields>; }` | Agent 协议对象公开字段名（当前手写 Json，无反射；保留字段契约） |
| `-dontwarn de.robv.android.xposed.**` | Xposed API 为 `compileOnly`，运行时由 LSPosed 提供 |
| `-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable` | 注解/内部类信息与可读崩溃栈 |

排查记录：

- `grep -rn "Class.forName|getDeclaredMethod|getMethod(" app/src/main/java`：反射目标都是
  小爱进程内的 `sm.*` / `qm.*` / `com.aios.*` / `android.*`，不属于本 APK；
  本项目唯一按字符串访问的类是 `plus.xiaoi.module.control.BridgeControlService`，已 keep。
- Manifest 组件（Activity/Service/Receiver/Provider）由 AGP 内置规则保留；
  `xposed_init` 是 asset，不受 `shrinkResources` 影响。
- Compose / Miuix / AndroidX / Kotlin 协程规则由 AAR consumer rules 提供，未重复添加。
- 未使用 `-dontobfuscate`，未按包 keep 业务代码。

## 4. 体积与 APK 内容

同一提交、同一签名配置下实测：

| 产物 | 字节 | 说明 |
| --- | --- | --- |
| release（R8 前） | 8,721,766 | P0 基线 |
| release（R8 后） | **1,657,323** | 本分支产物 |
| debug（不 R8） | 15,718,702 | 仅本地调试，含 Compose tooling |

R8 后 APK 组成（解压/压缩字节）：

| 条目 | 未压缩 | 压缩后 | 说明 |
| --- | --- | --- | --- |
| `classes.dex` | 3,031,536 | ~1,410,250 | 约 85% 体积；2,956 类 / 16,228 方法 |
| `resources.arsc` | 95,264 | 95,264 | 资源表 |
| `res/*.xml` | 79,324 | 22,370 | 资源名已被 AGP 短名化，共 35 个 XML |
| `lib/*` | 37,392 | 37,392 | `libandroidx.graphics.path.so` 四 ABI |
| `META-INF/*` | 41,184 | 14,827 | 版本元数据/签名 |
| `kotlin/*` + DebugProbes | 55,735 | 13,202 | Kotlin/coroutines 元数据 |
| `assets/*` | 7,897 | 7,899 | 含 `assets/xposed_init`（23 B） |
| `AndroidManifest.xml` | 10,312 | 2,672 | versionCode=19 |

- 资源收缩后 `res/` 仅 35 条；`string/app_name`、`mipmap/ic_launcher`、
  `mipmap/ic_launcher_round`、`style/Theme.XiaoiPlus` 均保留。
- 未为体积删除任何功能；若后续超 6MB，建议先看
  `app/build/outputs/mapping/release/{mapping.txt,usage.txt,resources.txt}` 与
  `unzip -lv` 的条目分布，再决定是否引入按需拆分。

## 5. CI 变更（`.github/workflows/build.yml`）

- **JDK 17 → 21**（temurin），匹配 AGP 9.5.1。
- 新增 `android-actions/setup-android@v3`，安装
  `platform-tools platforms;android-37.0 build-tools;37.0.0`，替换旧的
  `scripts/ci-sdk.sh` + `build.sh`（javac/d8）路径。
- 构建改为 Gradle wrapper 9.9.0：`assembleDebug` + `assembleRelease`。
- 缓存 `~/.gradle/caches`、`~/.gradle/wrapper`，key 取
  `libs.versions.toml` + wrapper 属性 + `*.gradle.kts` 哈希。
- 签名沿用现有 secrets：`KEYSTORE_BASE64` 解码到 `keystore/xiaoi.keystore`，
  `KEYSTORE_PASSWORD` / `KEYSTORE_ALIAS` / `KEYSTORE_KEY_PASSWORD`
  映射到 Gradle 的 `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`；
  构建结束删除解码后的 keystore。无密钥时临时证书签名，只出 artifact、不建 Release。
- 门禁顺序：`tests/run.sh` → `check-consistency.sh` → debug/release 构建 →
  `verify-apk.sh`（正式密钥固定指纹；临时密钥 `--allow-any-signer`）。
- `scripts/ci-sign.sh` 的 build-tools 查找改为兼容
  `$SDK/android-14`、`$ANDROID_HOME`、`$ANDROID_SDK_ROOT`、`$HOME/Android/Sdk`
  下的最新 `build-tools/*`。
- `contract.yml` 保持手动触发的真机契约，不引入新 secret。
- `scripts/ci-sdk.sh` 是 `build.sh` 的旧 SDK 布局脚本，本次未删除，避免破坏本地手工构建。

## 6. 门禁结果

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 单测 | `./tests/run.sh` | PASS（含 NavStack 49 项，全部通过） |
| 一致性 | `./scripts/check-consistency.sh` | PASS（SDK/R/线程/HTTP/Toast/死资源） |
| release 构建 | `JAVA_HOME=... ./gradlew clean :app:assembleRelease --offline` | PASS（R8 无 missing class） |
| debug 构建 | `JAVA_HOME=... ./gradlew :app:assembleDebug --offline` | PASS |
| APK 校验 | `SDK=$HOME/Android/Sdk ./scripts/verify-apk.sh ...` | PASS（包名/19/指纹一致） |
| CI YAML | `python3 -c yaml.safe_load` | 语法 PASS（Actions 实跑待 push） |

> `--offline` 首次失败于 `compose-group-mapping:2.4.21` 未缓存；联网拉取一次后
> `clean assembleRelease --offline` 可复现。CI 始终联网，不受影响。

## 7. 真机 R8 回归

开工时 `adb devices` 为空；构建后期设备恢复可见（`31106e85`），但按分工未安装/重启。
主 Agent 在合并后已完成补测，见 §7.1；以下保留原始验证清单。

- LSPosed 激活后 `Hook` 是否被正确加载（R8 改名/裁剪回归）。
- 模型重定向、输出标记、能力桥是否仍生效。
- 8771（MCP）与 8770（Agent 动作面）是否可访问。
- Compose/Miuix 页面在 release 包下的渲染与启动。

设备恢复后的验证清单：

```bash
cd ~/xiaoi-plus-dev/worktrees/ui-release
adb devices
adb install -r ~/xiaoi-plus-dev/artifacts/2026-11-08/apk/xiaoi-plus-0.14.0-release.apk
adb shell dumpsys package plus.xiaoi.module | grep -E "versionName|versionCode"

# LSPosed 中启用模块并勾选 com.miui.voiceassist，然后：
adb shell am force-stop com.miui.voiceassist
adb logcat -c
adb logcat | grep "xiaoi+"
# 预期：出现“已启用 model=...”且无 ClassNotFoundException/NoSuchMethodError

# 8770 / 8771（token 用本机配置，勿写进仓库）
adb forward tcp:18770 tcp:8770
adb forward tcp:18771 tcp:8771
curl -s -H "Authorization: Bearer $XIAOI_TOKEN" http://127.0.0.1:18770/health
curl -s -H "Authorization: Bearer $XIAOI_TOKEN" http://127.0.0.1:18771/mcp

# 真机契约基线（可选）
XIAOI_TOKEN=... ./scripts/contract-test.sh --check

# UI 冒烟
./scripts/ui-screenshot.sh --route Me --name release-me
```

### 7.1 主 Agent 补测结果（2026-11-08，R8 release）

| 项 | 结果 |
| --- | --- |
| 覆盖安装 | Success；`config.json` SHA-256 前后一致 |
| 版本/签名 | `0.14.0` / 19；指纹 `d7dd34b2…d19b`；APK 1.6 MiB |
| UI 启动 | Home / Model / Capabilities / Diagnostics / ConfigManage / Legacy / About 均可打开；无应用级 FATAL |
| LSPosed（R8 包） | `[xiaoi+] 控制面已连接`、`supportsVision=true`、配置热更新；Hook 未被 R8 破坏 |
| 8771 | on-device `/health` 200，`facade=true port=8771` |
| 8770 | 被小米自带 facade 占用（`t:voice_trigger`），App 动作面 `EADDRINUSE`；UI 如实显示“启动失败”，见已知风险 5 |

## 8. 已知风险

1. **资源名短名化**：AGP 9 在 `shrinkResources` 下会重命名资源；项目已无
   `getIdentifier`（一致性门禁保证），但 Miuix 若未来引入按名查找需重新评估。
2. **R8 keep 依赖字符串契约**：新增 `ComponentName`/`Class.forName` 加载的本项目类时，
   必须同步 `proguard-rules.pro`。
3. **CI 实跑未验证**：workflow 已按 JDK 21 / SDK 37 / Gradle 9.9 改写并通过 YAML
   解析，但未 push，GitHub 上的 action 版本与缓存命中需主 Agent 首次 push 后确认。
4. `docs/ci.md` 仍描述旧 `build.sh` 流程；按分工本次只更新
   `docs/ui/BUILD.md`，建议主 Agent 合并时同步 `docs/ci.md`。
5. **8770 端口冲突（已知，非 R8 引入）**：小米「超级小爱」自身监听
   `127.0.0.1:8770`；若它先绑定，`AgentService` 会 `EADDRINUSE`。UI 已如实显示
   “启动失败 + 最后错误”，8771 不受影响。0.15 建议迁到独立端口（如 8780）。
