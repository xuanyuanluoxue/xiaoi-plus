# 发版流程

本项目没有 Gradle，构建由 `build.sh` 完成（javac + d8 + aapt2 + zipalign + apksigner）。
发版额外做三件事：定版本、用固定密钥签名、把产物和校验和挂到 GitHub Release。

## 1. 版本号

唯一来源是 `app/src/main/AndroidManifest.xml`：

```xml
android:versionCode="7"     <!-- 整数，每次发版 +1，只能增 -->
android:versionName="0.7.0" <!-- 展示用，SemVer -->
```

`build.sh` 不读这两个字段，它们只用于制品命名、tag 校验和 Release Notes。
`scripts/version.sh` 是脚本侧的统一读取入口（manifest 优先）。
`app/build.gradle.kts` 不参与构建，只是给 IDE 看的元数据壳（见其文件头注释）。

规则：

| 变更 | 版本 | 示例 |
|---|---|---|
| 修 bug、文档 | patch | `0.1.0` → `0.1.1` |
| 加功能、兼容新小爱版本 | minor | `0.1.1` → `0.2.0` |
| 改配置格式、桥协议、hook 方式 | minor + 在 CHANGELOG 标「不兼容」 | `0.2.0` |

0.x 阶段不承诺接口稳定；破坏性改动也走 minor，并在 Release Notes 顶部写明。

发版前：

1. 改 AndroidManifest 的 `android:versionCode`（+1）和 `android:versionName`
2. 在 `CHANGELOG.md` 顶部加 `## <version> — YYYY-MM-DD` 小节
3. 提交，确保 `git status` 中没有已跟踪文件的改动

## 2. 签名

### 密钥在哪

- 本地：`keystore/xiaoi.keystore`，别名 `xiaoi`（`.gitignore` 已排除，**绝不提交**）
- CI：`secrets.KEYSTORE_BASE64` + `secrets.KEYSTORE_PASSWORD`，别名放 `secrets.KEYSTORE_ALIAS`（默认 `xiaoi`）

### 首次生成（只做一次）

```bash
mkdir -p keystore
keytool -genkeypair -keystore keystore/xiaoi.keystore -alias xiaoi \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=XiaoAi Plus, O=OpenSource, C=CN"
```

口令自己定，别用仓库里的示例值。本地构建从 `KEYSTORE_PASSWORD` 或
`~/.config/xiaoi/keystore_pass` 读取，不写进源码。生成后**立刻离线备份**
（密码管理器 + 仓库外的备份盘）。
密钥丢失＝无法覆盖安装，只能换包名或让所有用户卸载重装。

### 配置到 GitHub

```bash
base64 -w0 keystore/xiaoi.keystore > /tmp/ks.b64
gh secret set KEYSTORE_BASE64 < /tmp/ks.b64
gh secret set KEYSTORE_PASSWORD    # 交互输入
gh secret set KEYSTORE_ALIAS --body xiaoi
rm /tmp/ks.b64
```

`KEYSTORE_KEY_PASSWORD` 可选。PKCS12 keystore 的 key 口令与 store 口令相同，
通常不需要单独设置。**口令轮换后**要同步更新 `KEYSTORE_PASSWORD`（以及
`KEYSTORE_KEY_PASSWORD`，如果设置过），否则 CI 签名会失败。

### 没有密钥时

工作流不会失败：检测不到 `KEYSTORE_BASE64` 就打 `::warning::`，用 CI 临时密钥签名并继续产出 artifact。
这种包**只能测试，不能覆盖安装正式版**，Release Notes 会自动加警告；即使推的是 tag，
**也不会创建 GitHub Release**（只上传 artifact，并输出跳过 Release 的 warning/notice）。
本地 `build.sh` 缺少签名口令时只产出**未签名** APK（用于编译验证），不会假装签名成功。
本地有口令但 `keystore/xiaoi.keystore` 缺失时，`build.sh` 会直接失败，不再静默生成新证书；
仅本地验证可设 `XIAOI_ALLOW_NEW_KEY=1`（`release.sh --allow-new-key` 会自动设置），
生成的包不能覆盖安装正式版。

## 3. 本地一键发版

```bash
scripts/release.sh
```

它依次做：

1. 检查已跟踪文件是否干净（未跟踪的 `dist/` 不阻塞）；不干净就退出
2. 检查 `v<version>` tag 是否已存在（存在即拒绝，避免覆盖已发布版本）
3. 检查 `keystore/xiaoi.keystore` 是否存在（缺失即拒绝，防止静默换签名）
4. 跑 `./build.sh`
5. 跑 `scripts/verify-apk.sh build/xiaoi-plus.apk`：校验已签名、包名、versionCode 与固定证书指纹；
   **失败直接退出，不归档、不生成 notes**
6. 复制为 `dist/xiaoi-plus-<version>.apk`，生成 `.sha256`
7. 从 CHANGELOG 抽对应小节，生成 `dist/release-notes-<version>.md`（含大小、SHA-256、证书指纹、安装命令）

本地试跑（允许脏工作区 / 允许临时密钥）：

```bash
scripts/release.sh --allow-dirty --allow-new-key
```

`--allow-new-key` 只跳过指纹比对（仍要求已签名 + 包名 + versionCode 正确），
产物只能测试，不能发布。

### 签名指纹校验（`scripts/verify-apk.sh`）

固定指纹存在 `scripts/expected-signer.sha256`：

```text
d7dd34b2e654562954c991730ba6ba88955ea1fcce9e13ae949f74b8b213d19b
```

这是 0.11.0-beta.4 ~ 0.12.0 的正式签名证书（X.509）SHA-256，**只是公钥指纹，
不含私钥或口令**。私钥仍在 `keystore/xiaoi.keystore` 与 CI secrets。

手动校验：

```bash
# 默认用仓库内固定指纹 + manifest 里的 versionCode
scripts/verify-apk.sh build/xiaoi-plus.apk

# 显式指定指纹 / 包名 / versionCode
scripts/verify-apk.sh dist/xiaoi-plus-0.12.0-beta.1.apk \
  --expect d7dd34b2e654562954c991730ba6ba88955ea1fcce9e13ae949f74b8b213d19b

# 从 keystore 反查指纹（口令只通过环境变量，不会打印）
KEYSTORE_PASSWORD='***' scripts/verify-apk.sh build/xiaoi-plus.apk \
  --keystore keystore/xiaoi.keystore

# 只校验已签名/包名/versionCode（临时密钥构建用，不能发布）
scripts/verify-apk.sh build/xiaoi-plus.apk --allow-any-signer

# 门禁自测：正式签名通过、未签名被拒、临时密钥因指纹不符被拒
scripts/verify-apk.sh --self-test build/xiaoi-plus.apk
```

指纹来源优先级：`--expect` > 环境变量 `XIAOI_SIGNER_SHA256` > `--keystore` >
`scripts/expected-signer.sha256`。比较前会统一小写并去掉冒号/空白。

**指纹不一致时**：

1. 先确认是不是用了临时密钥或别的 keystore：`apksigner verify --print-certs <apk>` 看实际指纹；
2. 不要改 `expected-signer.sha256` 去“凑”当前指纹——那等于放弃防换签名；
3. 真换了正式证书：同步更新固定指纹文件 + 在 CHANGELOG/Release Notes 显著说明，
   用户必须卸载重装（覆盖安装会因签名不符失败）；
4. `apksigner verify` 报 `DOES NOT VERIFY` / `Missing META-INF`：产物未签名，重新用正式密钥构建。

## 4. 打 tag 与发布

```bash
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0
```

推送 tag 后 `.github/workflows/build.yml` 会：

1. 下载 build-tools 35 与 platform 35（`dl.google.com`），命中缓存则跳过
2. 校验 tag 与 `versionName` 一致（不一致直接失败）
3. 跑 JVM 单测与 `scripts/check-consistency.sh`
4. `./build.sh`
5. 有 `KEYSTORE_BASE64` 就重新签名（`scripts/ci-sign.sh`），没有则警告 + 临时签名
6. 跑 `scripts/verify-apk.sh`：
   - `signed=true`：必须命中 `scripts/expected-signer.sha256`，否则 job 失败；
   - `signed=false`：只校验已签名 + 包名 + versionCode，并打 `::warning::`
7. 产出 `dist/xiaoi-plus-<version>.apk` + `.sha256` + notes，上传 artifact
8. **只有 `startsWith(github.ref, 'refs/tags/v') && signed=true` 才创建 Release**；
   没有正式密钥的 tag 构建仍上传 artifact，但跳过 Release 并输出 warning/notice
9. Release 附带 APK 与校验和；版本号含 `-`（如 `0.12.0-beta.1`）标为 prerelease

不想推送 tag 也可以手动发：

```bash
gh release create v0.1.0 dist/xiaoi-plus-0.1.0.apk \
  dist/xiaoi-plus-0.1.0.apk.sha256 \
  --notes-file dist/release-notes-0.1.0.md
```

PR 和普通分支推送只跑构建 + 上传 artifact，不建 Release。

## 5. 发布后验证

```bash
# 校验下载完整性
sha256sum -c xiaoi-plus-0.1.0.apk.sha256

# 确认签名证书与固定指纹一致（已签名 + 包名 + versionCode + 指纹）
scripts/verify-apk.sh xiaoi-plus-0.12.0-beta.1.apk

# 真机覆盖安装
adb install -r xiaoi-plus-0.1.0.apk
```

签名指纹应等于 `dist/release-notes-<version>.md` 里记录的值；
若与上一版不同，先停下查原因，不要发布。

## 6. 回滚

**不要删除或移动已发布的 tag，也不要重签同一个版本号。**

- Release 内容有问题（notes、附件错）：编辑/重传附件即可，APK 本身不动
- APK 有严重 bug：
  1. `git revert` 问题提交，`versionCode` +1，发一个更高版本（如 `0.1.2`）
  2. 旧 Release 保留，在 notes 顶部加「已知问题，请用 0.1.2」，或把旧 Release 标为 prerelease
  3. 需要立即止损时用 `gh release edit v0.1.1 --draft` 暂时隐藏，而不是删除
- 用户侧降级：`adb install -d xiaoi-plus-<旧版本>.apk`（要求同签名；系统可能仍拒绝，必要时卸载重装，配置会丢）

## 7. 发版检查清单

- [ ] `app/src/main/AndroidManifest.xml` 的 `android:versionCode` 已 +1，`android:versionName` 已更新
- [ ] `CHANGELOG.md` 有对应版本小节，破坏性改动已标注
- [ ] 工作区干净，改动已提交
- [ ] `keystore/xiaoi.keystore` 可用，且与 `scripts/expected-signer.sha256` 一致
- [ ] `scripts/verify-apk.sh build/xiaoi-plus.apk` 通过；`--self-test` 通过
- [ ] `scripts/release.sh` 跑通，`dist/` 里 APK、`.sha256`、notes 齐全
- [ ] tag 名 = `v` + `versionName`
- [ ] 推送 tag 后 Actions 绿，Release 附件可下载
- [ ] 真机覆盖安装 + 日志出现 `模型标识已改走请求头 X-Model-Provider-Id=xiaoi-plus`（回复正文不带标记）
