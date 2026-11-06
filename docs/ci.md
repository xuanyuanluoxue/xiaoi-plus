# CI 与本地门禁

> 一句话：**GitHub 托管 CI 管构建，网关机本地管设备**。
> 仓库是公开的，不把自建 runner 注册上来（供应链风险），设备契约用本地脚本。

## 1. GitHub 托管 CI（`.github/workflows/build.yml`）

每次 push / PR / tag 自动运行：

1. 装 Android SDK（`scripts/ci-sdk.sh`，带 actions/cache）
2. **JVM 单测**（`tests/run.sh`，测试红则不构建）
3. `scripts/check-consistency.sh`（版本/SDK/R/线程/HTTP/Toast/死资源）
4. `build.sh` 构建
5. 无 `KEYSTORE_PASSWORD` 时 `build.sh` 产出未签名 APK；有 `KEYSTORE_BASE64` 时由
   `scripts/ci-sign.sh` 重新签名，否则警告 + 临时签名
6. `scripts/verify-apk.sh` 校验：`signed=true` 必须命中
   `scripts/expected-signer.sha256`；`signed=false` 只校验已签名 + 包名 + versionCode
7. 产物与 Release Notes 上传 artifact
8. **tag 构建只有 `signed=true` 才创建 Release**。无正式密钥的 tag 仍产出 artifact，
   但跳过 Release 并打 `::warning::`/`::notice::`；
   版本号含 `-`（如 `0.12.0-beta.1`）自动标为 prerelease

## 2. 本地全量门禁（`scripts/check-all.sh`）

需要设备与 aoci 的检查只能在本机跑：

```bash
scripts/check-all.sh              # 单测 + 认知检查 + 真机契约
scripts/check-all.sh --no-device  # 跳过设备契约
```

门禁含义：

| 层 | 内容 | 失败即中止 |
|---|---|---|
| 工程一致性 | `scripts/check-consistency.sh`（版本/SDK/R/线程/HTTP/Toast/死资源） | ✅ |
| JVM 单测 | `tests/run.sh`（851 断言） | ✅ |
| APK 签名门禁 | `scripts/verify-apk.sh`（已签名/包名/versionCode/固定指纹；`--self-test` 覆盖负向用例） | ✅ |
| 认知检查 | `aoci check`（漂移/待策展/字典/格式/草稿五净） | ✅ |
| 真机契约 | `scripts/contract-test.sh --check`（45 断言，会重启小爱） | ✅ beta.4 基线；0.12 P0 矩阵待主 Agent 补跑 |

CI 与本地共用的发布门禁：

| 场景 | 校验 | 结果 |
|---|---|---|
| 有正式密钥（`signed=true`） | `verify-apk.sh` 命中 `scripts/expected-signer.sha256` | 可建 Release |
| 临时密钥 / 无 secrets（`signed=false`） | 只校验已签名 + 包名 + versionCode | 只出 artifact，**不建 Release** |
| 未签名 / 包名或 versionCode 错 | `verify-apk.sh` exit 1 | job 失败 |
| 指纹不符 | `verify-apk.sh` exit 1 | job 失败（tag 也不发 Release） |

## 3. pre-push hook（轻量、每次推送自动跑）

```bash
git config core.hooksPath .githooks   # 每台克隆执行一次
```

推送前自动跑「工程一致性 + JVM 单测 + aoci check」，失败则拒绝推送。设备契约较重（会重启小爱），不放进 hook。

## 4. 桥 token 的存放

契约测试的 token 优先取 `XIAOI_TOKEN` 环境变量；未设置时回退读本机文件：

```bash
mkdir -p ~/.config/xiaoi
printf '%s\n' '<你的桥 token>' > ~/.config/xiaoi/token
chmod 600 ~/.config/xiaoi/token
```

该文件不进仓库；自建 runner 也用它，避免把 token 传到 GitHub secrets。

## 5. 可选：自建 runner 跑设备契约

`.github/workflows/contract.yml` 是给**有自己 runner** 的人用的模板：仅 `workflow_dispatch`
手动触发，`runs-on: [self-hosted, xiaoi-device]`。注册步骤：

```bash
# 1) 在目标机器下载 runner（版本以 GitHub 实际最新为准）
mkdir -p ~/actions-runner-xiaoi && cd ~/actions-runner-xiaoi
curl -fL -o runner.tar.gz https://github.com/actions/runner/releases/download/vX.Y.Z/actions-runner-linux-x64-X.Y.Z.tar.gz
tar xzf runner.tar.gz
# 2) 注册（registration-token 用 gh 或仓库设置页生成，一次性）
./config.sh --url https://github.com/<owner>/<repo> --token <REG_TOKEN> \
  --name xiaoi-gateway --labels xiaoi-device --unattended
# 3) 装成系统服务
sudo ./svc.sh install "$USER" && sudo ./svc.sh start
```

> ⚠️ **公开仓库不要这样做**，除非你完全理解风险：fork PR 的 workflow 可能在你的
> runner 上执行任意代码。本仓库的推荐路径是第 2 节的本地门禁。