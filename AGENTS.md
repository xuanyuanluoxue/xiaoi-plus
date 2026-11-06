# AGENTS.md

本仓库是一个只在本人设备生效的 LSPosed 模块（`plus.xiaoi.module`）+ 设置 App。

## 构建与测试

- 构建：`SDK=/path/to/android-sdk ./build.sh` → `build/xiaoi-plus.apk`
- 单测：`./tests/run.sh`（纯 JVM，无需设备）
- 门禁：`scripts/check-consistency.sh`（版本/SDK/资源引用/线程与提示规范）
- 真机契约（可选）：`XIAOI_TOKEN=<token> scripts/contract-test.sh`

## 改动约定

- 资源一律走 `R.*`，禁止 `getIdentifier`
- 后台任务一律走 `AppExecutors`，禁止裸 `new Thread`
- 提示统一 `BaseActivity.showMessage()`，禁止系统 Toast
- 新增 drawable 必须被引用，否则一致性门禁失败
- 版本号只改 `app/src/main/AndroidManifest.xml` 的 `versionName`/`versionCode`

## 测试产物

- 真机截图、测试 APK、配置 fixture、构建/契约日志统一放
  `~/xiaoi-plus-dev/artifacts/YYYY-MM-DD/`（screenshots/apk/configs/logs/releases/notes）。
- 临时文件先写 `~/xiaoi-plus-dev/tmp/`，不要落在仓库根、用户 home 根或 `/tmp/opencode` 根。
- 设备端真机产物统一写 `/sdcard/Download/xiaoi-plus-dev/YYYY-MM-DD/{screenshots,ui-dumps,logs}/`，
  不要写 `/sdcard` 根目录；配置类文件测试后立即 pull 到 PC 并删除。
- 含 `apiKey` / `mcp.token` 的配置、备份、`.corrupt` 文件必须 `chmod 600`，用完清理。
- 目录约定与清理脚本见 `~/xiaoi-plus-dev/README.md`、`~/xiaoi-plus-dev/clean.sh`。

## 文档

改动行为时同步更新 `README.md`、`docs/` 与 `CHANGELOG.md`。
