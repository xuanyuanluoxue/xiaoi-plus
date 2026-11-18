# AGENTS.md

本仓库是一个只在本人设备生效的 LSPosed 模块（`plus.xiaoi.module`）+ 设置 App。

## 构建与测试

- 构建（Windows 原生，当前主环境）：
  `gradlew.bat :domain:compileJava :application:compileJava :infra:compileJava :app:assembleRelease`
  → `app/build/outputs/apk/release/app-release.apk`
- 构建（Linux / WSL）：`./gradlew :domain:compileJava :application:compileJava :infra:compileJava :app:assembleRelease`
  （0.16.0 起 **Gradle 是唯一构建入口**；旧的 `build.sh` 已删除）
- 单测：`bash tests/run.sh`（纯 JVM，无需设备；Windows 用 Git Bash/WSL）
- 门禁：`bash scripts/check-consistency.sh`（版本/SDK/资源引用/线程/提示/分层）
- 分层：`bash scripts/check-layering.sh --strict`（基线见 `scripts/layering-baseline.tsv`）
- 真机契约（可选）：`bash scripts/contract-test.sh --check`
- 下一阶段优化计划：`docs/refactor/LAYERING-2.md`（路线索引 `docs/refactor/NEXT.md`）

## 模块

- `:domain` 模型/策略/协议 DTO/端口，无 I/O、无 Android、无 Xposed
- `:application` 用例/编排 + 端口定义，零 I/O（禁 `java.io|net|nio`），依赖 `:domain`
- `:infra` I/O 实现（`Http`/`ConfigStore`/`BridgeClient`/`ModelValidator`/`LeaderElection`/`Lan`/`BridgeAskHost`/`BridgeEndpoint`），依赖 `:application` + `:domain`，禁 Android/Xposed
- `:platform` Android 适配器（`ConfigRepository`/`Bridge`/控制面/`AgentService`（单端口，不监听）/执行器/通知/`PermissionProbe`），依赖 `:infra` + `:application` + `:domain`，**禁 Compose/Miuix/ui**；namespace 为 `plus.xiaoi.module.platform`
- `:ui` Compose 表现层（20 路由 / 玻璃底栏 / StateHolder），只依赖 `:application` + `:domain`，**禁 infra/platform**；namespace 为 `plus.xiaoi.module.ui`
- `:app` 壳：`AndroidManifest` + `res/` + `MainActivity.kt` + `Hook.java`，依赖全部下层

## 改动约定

- 资源一律走 `R.*`，禁止 `getIdentifier`
- 后台任务一律走 `AppExecutors`，禁止裸 `new Thread`
- 提示统一 `MainActivity.message()` → Compose `SnackbarHost`，禁止系统 Toast
- 新增 drawable 必须被引用，否则一致性门禁失败
- 版本号只改 `app/src/main/AndroidManifest.xml` 的 `versionName`/`versionCode`

## 产物与临时文件（一律写在项目内）

**除了依赖与工具调用（JDK / Android SDK / adb / aoci 等外部程序），所有文件都落在
仓库目录内**：不写 `%TEMP%`、用户 home、`~/xiaoi-plus-dev`、`/tmp`。

| 内容 | 位置 |
|---|---|
| 构建产物、契约日志、发布 APK | `artifacts/YYYY-MM-DD/{apk,logs,configs,releases}/` |
| UI 截图（`scripts/ui-screenshot.sh`） | `artifacts/ui/{tmp,review}/YYYY-MM-DD/`（`--clean` 清 7 天前） |
| 一次性脚本 / 中间文件 / `mktemp` 父目录 | `tmp/`（脚本先 `mkdir -p <repo>/tmp`，再 `mktemp -d <repo>/tmp/...`） |
| 令牌、keystore 口令等凭据 | `.secrets/`（如 `.secrets/xiaoi-token`；**绝不入库**） |
| 单测产物 | `tests/.build/`（`run.sh` 每次重建） |

- `artifacts/`、`tmp/`、`.secrets/`、`dist/` 已在 `.gitignore`。
- 手机端只放临时文件（`/data/local/tmp` 或用完即删的 `/sdcard` 目录）；
  截图/配置用 `adb exec-out` / `adb pull` 直接落到 `artifacts/`，不在手机留产物。
- 含 `apiKey` / `mcp.token` 的配置与备份用完即删；Windows 盘上的 `chmod` 不生效，
  凭据文件依赖仓库目录本身的 ACL。

## 根目录纪律

仓库根**只放**：构建入口（`settings/build.gradle.kts`、`gradle*`、`gradle.properties`）、
文档（`README.md` / `CHANGELOG.md` / `ARCHIVE.md`）、`AGENTS.md`、`LICENSE`，
以及固定目录（`app/ domain/ application/ infra/ platform/ ui/ docs/ scripts/ tests/ skill/ libs/`）。

新增文件必须落到对应目录：

| 内容 | 去处 |
|---|---|
| 工具脚本 | `scripts/`（并在 `AGENTS.md` 或 `docs/` 登记） |
| 设计/交接/调研 | `docs/`（历史归档放 `docs/refactor/drafts/`） |
| 临时验证、中间文件、截图 | `tmp/`（临时）或 `artifacts/`（需要留档） |
| 一次性任务书 | **不要入库**；用完合并进 `docs/` 或删除 |

**禁止**在根目录新建 `*.md` / `*.sh` / `*.txt` / `*.ps1` / `test.*` / `notes.*`；
也不要把 `aoci*`、`opencode.json`、`local.properties`、`keystore/`、
`artifacts/`、`tmp/`、`.secrets/` 提交进来（都已在 `.gitignore`）。

## 文档

改动行为时同步更新 `README.md`、`docs/` 与 `CHANGELOG.md`。
