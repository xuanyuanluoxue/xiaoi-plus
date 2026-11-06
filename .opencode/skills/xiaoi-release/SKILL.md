---
name: xiaoi-release
description: 发布 xiaoi-plus（LSPosed 模块 + 设置 App）的 beta 或正式版：改版本号与 CHANGELOG、跑单测/构建/一致性/APK 签名门禁、真机 P0、打 tag、确认 GitHub Release 与资产签名。用户提到发版、release、tag、beta、版本号、verify-apk 时使用。
---

# xiaoi-plus 发布流程

## 先读

1. `AGENTS.md`（构建、线程、资源、测试产物约定）
2. `docs/release.md`（签名与发版）
3. `docs/ci.md`（CI 门禁）
4. `CHANGELOG.md` 顶部当前版本
5. `app/src/main/AndroidManifest.xml` 的 `versionName` / `versionCode`

## 硬规则

- 版本号只改 `app/src/main/AndroidManifest.xml`，不改 `app/build.gradle.kts`。
- 正式包必须与上一版同签名。固定指纹在 `scripts/expected-signer.sha256`：
  `d7dd34b2e654562954c991730ba6ba88955ea1fcce9e13ae949f74b8b213d19b`
- 不允许静默生成新 keystore。`build.sh` 在“有口令但无 keystore”时会失败；
  只有本地编译验证才设 `XIAOI_ALLOW_NEW_KEY=1`，该包不能发布。
- 未签名、错包名、versionCode 不符、指纹不符的 APK 不得归档、不得发 Release。
- 测试产物写 `~/xiaoi-plus-dev/artifacts/YYYY-MM-DD/`；手机写
  `/sdcard/Download/xiaoi-plus-dev/YYYY-MM-DD/{screenshots,ui-dumps,logs}/`；
  含密钥的配置 `chmod 600`，用完删除。
- 不把 token、apiKey、keystore 口令写进日志、提交或对话。

## 1. 发布前检查

```bash
git status --short                  # 必须干净（dist/ 等未跟踪产物除外）
git log --oneline -5
git fetch origin && git status -sb  # 确认与 origin/main 同步
```

确认上一版 tag：

```bash
git tag -l 'v*' | sort -V | tail -5
```

## 2. 版本与 CHANGELOG

1. 改 manifest：
   - `versionCode` 只能加 1；
   - `versionName` 用 SemVer，beta 形如 `0.12.0-beta.1`。
2. 在 `CHANGELOG.md` 顶部加同名小节，必须包含：
   - 本版重点；
   - 破坏性变更（协议/配置路径/权限）；
   - 升级方式；
   - 降级警告（如果降级会重新开启能力或读不到配置）。
3. 同步 README 当前测试版链接、`HANDOVER.md`、`docs/refactor/PLAN.md` 状态。

## 3. 本机质量门禁

```bash
# SDK 按本机实际路径；本机常用：
export SDK="$HOME/code/逆向/tools/sdk"

./tests/run.sh
SDK="$SDK" ./build.sh
./scripts/check-consistency.sh
SDK="$SDK" ./scripts/verify-apk.sh build/xiaoi-plus.apk
SDK="$SDK" ./scripts/verify-apk.sh --self-test build/xiaoi-plus.apk
```

全绿标准：

- 单测全部通过（当前基线 664 断言）；
- `build.sh` 打出 `versionName` / `versionCode` 对应的已签名 APK；
- 一致性检查通过；
- `verify-apk.sh`：包名 `plus.xiaoi.module`、versionCode 与 manifest 一致、指纹命中固定值；
- `--self-test` 4/4：正式签名通过、未签名被拒、临时密钥因指纹不符被拒、临时文件已清理。

## 4. 真机 P0

清单与命令见 `references/p0-matrix.md`。最低要求：

- 上一版直接覆盖安装，`config.json` 哈希不变，provider/model/key/token 不丢；
- `/health` 带 token 200、无 token 401；
- `POST /config` 返回 410；
- MCP `initialize` 正常；
- 远程 MCP 写 `baseUrl` / `apiKey` / `bridge` / `lan` 被拒绝；
- MCP 关闭后 8771~8775 无监听，重启小爱仍关闭；
- 配置页 D3“已应用”、主页 D4“已确认”；
- 签名指纹与上一版一致。

## 5. 本地发版

```bash
git status --short                  # 必须干净
SDK="$HOME/code/逆向/tools/sdk" ./scripts/release.sh
```

`release.sh` 会：

1. 检查工作区、tag 是否已存在、keystore 是否存在；
2. 构建；
3. 调 `scripts/verify-apk.sh`，失败则在归档前退出；
4. 生成 `dist/xiaoi-plus-<version>.apk`、`.sha256`、Release Notes。

检查 `dist/release-notes-<version>.md`：版本、SHA-256、签名指纹、破坏性变更、降级警告、QQ 群。

## 6. tag 与 CI

```bash
git tag -a v<version> -m "v<version>"
git push origin main
git push origin v<version>
```

注意：

- 本机环境变量可能残留失效代理（如 `HTTPS_PROXY=127.0.0.1:7897`），导致
  `gnutls_handshake() failed`。先确认代理端口是否在监听；没监听就用
  `env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy git push ...`。
- tag 必须与 manifest 的 `versionName` 完全一致，CI 会强校验。
- CI 只有在 `signed=true`（配置了正式 keystore secrets）时才创建 Release；
  没有正式密钥时只上传 artifact，并输出跳过 Release 的 warning/notice。
- 版本含 `-` 会自动标为 prerelease。

## 7. 发布后确认

```bash
gh run list -R xuanyuanluoxue/xiaoi-plus --limit 3
gh run watch <run-id> -R xuanyuanluoxue/xiaoi-plus --exit-status
gh release view v<version> -R xuanyuanluoxue/xiaoi-plus \
  --json tagName,isPrerelease,isDraft,assets,url
```

下载 Release 资产复验：

```bash
D="$HOME/xiaoi-plus-dev/tmp/release-check"
mkdir -p "$D"
gh release download v<version> -R xuanyuanluoxue/xiaoi-plus -D "$D" --clobber
( cd "$D" && sha256sum -c xiaoi-plus-<version>.apk.sha256 )
SDK="$HOME/code/逆向/tools/sdk" ./scripts/verify-apk.sh "$D/xiaoi-plus-<version>.apk"
```

再覆盖安装一次 Release 资产，确认 `versionCode` 与启动/状态正常。

归档：

- `~/xiaoi-plus-dev/artifacts/releases/YYYY-MM-DD/`
- `~/xiaoi-plus-dev/artifacts/apk/YYYY-MM-DD/`
- 真机截图/UI dump/log 同样归档
- 更新 `HANDOVER.md` 为“已发布”，`docs/refactor/PLAN.md` 状态同步
- 删除 `/tmp/opencode` 与手机根目录的临时文件

## 失败处理

| 现象 | 处理 |
|---|---|
| `verify-apk.sh` 指纹不符 | 停止发布；确认 keystore 是否被替换，恢复正式 keystore 后重跑 |
| 未签名 / 包名或 versionCode 不符 | 停止归档；检查 `build.sh`、manifest、`SDK` |
| CI tag 构建未创建 Release | 看 `signed` 输出；缺 `KEYSTORE_*` secrets 时只发 artifact |
| push 报 TLS / gnutls | 检查代理端口；没监听就清掉代理环境变量直连 |
| 设备升级后配置丢失 | 立即停止发布；用 `~/xiaoi-plus-dev/artifacts/configs/` 备份恢复并排查迁移 |
| 单测/一致性失败 | 不发布；修好回到本流程第 3 步 |

## 常用路径

- APK：`build/xiaoi-plus.apk`
- 发布产物：`dist/`
- 签名：`keystore/xiaoi.keystore`（绝不提交）
- 固定指纹：`scripts/expected-signer.sha256`
- 签名门禁：`scripts/verify-apk.sh`
- 发布脚本：`scripts/release.sh`
- CI：`.github/workflows/build.yml`
- 真机 P0：`references/p0-matrix.md`
