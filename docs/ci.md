# CI 与本地门禁

> 一句话：**GitHub 托管 CI 管构建，网关机本地管设备**。
> 仓库是公开的，不把自建 runner 注册上来（供应链风险），设备契约用本地脚本。

## 1. GitHub 托管 CI（`.github/workflows/build.yml`）

每次 push / PR / tag 自动运行：

1. 装 Android SDK（`scripts/ci-sdk.sh`，带 actions/cache）
2. **JVM 单测**（`tests/run.sh`，测试红则不构建）
3. `build.sh` 构建
4. 无 `KEYSTORE_PASSWORD` 时 `build.sh` 产出未签名 APK；有 `KEYSTORE_BASE64` 时由
   `scripts/ci-sign.sh` 重新签名，否则警告 + 临时签名
5. 产物与 Release Notes 上传 artifact；tag 构建自动建 Release；
   版本号含 `-`（如 `0.11.0-beta.4`）自动标为 prerelease

## 2. 本地全量门禁（`scripts/check-all.sh`）

需要设备与 aoci 的检查只能在本机跑：

```bash
scripts/check-all.sh              # 单测 + 认知检查 + 真机契约
scripts/check-all.sh --no-device  # 跳过设备契约
```

三层含义：

| 层 | 内容 | 失败即中止 |
|---|---|---|
| 工程一致性 | `scripts/check-consistency.sh`（版本/SDK/R/线程/HTTP/Toast/死资源） | ✅ |
| JVM 单测 | `tests/run.sh`（416 断言） | ✅ |
| 认知检查 | `aoci check`（漂移/待策展/字典/格式/草稿五净） | ✅ |
| 真机契约 | `scripts/contract-test.sh --check`（45 断言，会重启小爱） | ✅ |

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