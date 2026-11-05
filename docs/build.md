# 从零构建

本项目**不用 Gradle**。`build.sh` 直接调用 `javac` + `d8` + `aapt2` + `zipalign` + `apksigner`
走完「编译 → dex → 打包 → 对齐 → 签名」，产出可直接安装的 APK。

## 1. 依赖

| 依赖 | 用途 | 说明 |
|---|---|---|
| Linux / WSL2 + bash | 执行脚本 | Windows 原生 CMD 不行 |
| JDK 17（含 `javac`、`keytool`） | 编译 Java、生成签名密钥 | 更高版本也能用，只是多几条 obsolete 警告 |
| Android SDK build-tools | `d8` / `aapt2` / `zipalign` / `apksigner` | 本仓库默认用 `android-14` 目录（即 build-tools 35） |
| `android.jar` | 编译期 Android API | 本仓库默认用 `android-35/android.jar` |
| `zip` | 把 `classes.dex` 塞进 APK | 一般系统自带 |

默认 SDK 路径是 `$HOME/Android/Sdk`（可用环境变量 `SDK` 覆盖），目录结构：

```
tools/sdk/
├── android-14/        # build-tools：d8、aapt2、apksigner、zipalign ...
└── android-35/
    └── android.jar
```

换机器时用环境变量覆盖，不必改脚本：

```bash
export SDK=/你的/sdk/路径
```

构建前自检：

```bash
ls "$SDK/android-14"/{d8,aapt2,apksigner,zipalign}
ls "$SDK/android-35/android.jar"
javac -version
```

## 2. 构建

```bash
git clone <本仓库地址> xiaoi-plus
cd xiaoi-plus
chmod +x build.sh
./build.sh
```

成功时最后一行是：

```
OK: /path/to/xiaoi-plus/build/xiaoi-plus.apk
```

## 3. 产物

| 文件 | 说明 |
|---|---|
| `build/xiaoi-plus.apk` | **已签名**，用这个安装 |
| `build/xiaoi-plus-aligned.apk` | 已 zipalign、未签名 |
| `build/xiaoi-plus-unsigned.apk` | aapt2 打包 + dex 的原始件 |
| `keystore/xiaoi.keystore` | 首次构建自动生成的测试密钥（口令从 `KEYSTORE_PASSWORD` 或 `~/.config/xiaoi/keystore_pass` 读取，别名 `xiaoi`）；在 `build/` 之外，重跑构建不会丢 |
| `build/kpass.txt` | 给 `apksigner` 用的临时口令文件（构建结束可删，已被 .gitignore 忽略） |

安装 / 覆盖安装：

```bash
adb install -r build/xiaoi-plus.apk
```

## 4. 脚本每一步在做什么

| 步骤 | 命令 | 作用 |
|---|---|---|
| 1/6 | `javac` | 编译 `libs/xposed-stub-src`：Xposed API 的空壳桩，只用于编译 |
| 2/6 | `aapt2 compile` + `link --java` | 编译资源、按清单链接，并**先生成 `R.java`** |
| 3/6 | `javac` | 编译 `app/src/main/java` + `build/gen`（含 R），classpath 带上桩 |
| 4/6 | `d8` | 把 class 转成 `classes.dex` |
| 5/6 | `zip` + `zipalign` | 塞入 dex、4 字节对齐 |
| 6/6 | `apksigner` | 签名并产出 `build/xiaoi-plus.apk` |

> **顺序不能换**：R 必须在业务代码之前生成，否则业务代码只能用运行时的
> `getIdentifier`（字符串写错只在真机崩）。`scripts/check-consistency.sh`
> 会拦住回退到 `getIdentifier` 的改动。

`assets/xposed_init` 里只有一行 `plus.xiaoi.module.Hook`，这是 LSPosed 找入口类的依据。
删掉它，APK 能装但模块不会加载。

## 5. 常见报错

**`error: package android.content does not exist` / `android.jar` 找不到**

SDK 路径不对。确认 `$SDK/android-35/android.jar` 真实存在，或导出正确的 `SDK`。

**`aapt2: error: AndroidManifest.xml:1: error: <manifest> requires a package attribute`**

`aapt2 link` 要求清单里有 `package`。本仓库的清单已带 `package="plus.xiaoi.module"`；
如果你是复制骨架时删掉了，补回去即可。

**`Warning: Type 'de.robv.android.xposed.XC_MethodHook' was not found ...`（d8 阶段）**

**可以忽略。** Xposed 桩只在编译期存在，运行时这些类由 LSPosed 注入。
同样，`javac` 的 `source value 8 is obsolete` 也属于噪音。

**`./build.sh: Permission denied`**

```bash
chmod +x build.sh
```

**`zip: command not found`**

```bash
sudo apt install zip
```

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / 签名不一致**

`build.sh` 每次都会 `rm -rf build/`，但密钥在 `keystore/xiaoi.keystore`，只会在**缺失时**自动生成。
密钥一旦丢失，下次构建会换新密钥，签名随之改变，无法覆盖安装。开发阶段直接卸载旧版：

```bash
adb uninstall plus.xiaoi.module
adb install build/xiaoi-plus.apk
```

想保持同一签名（例如保留配置、做升级测试）：备份 `keystore/xiaoi.keystore` 到仓库外，
换机器时放回同一路径，脚本检测到文件存在就会复用。**不要把这个密钥提交进 git**
（`.gitignore` 已排除 `*.keystore`）。正式发版的密钥管理与流程见 [release.md](release.md)。

**装上了但 LSPosed 里看不到模块**

- 确认装的是 `build/xiaoi-plus.apk`（不是 unsigned）
- LSPosed 管理器里下拉刷新
- `adb shell dumpsys package plus.xiaoi.module | grep -i xposed` 应能看到 `xposedmodule` 元数据

**小爱里没效果**

不是构建问题，看 [兼容性与适配](compatibility.md) 与 [安全与回滚](security.md)。
