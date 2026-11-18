# 项目归档说明（2026-11-17）

> 状态：**归档快照（2026-11-17）；项目已恢复开发**
> 版本：`0.15.0`（versionCode 22）
> 主环境：Windows 原生 `D:\code\Android\xiaoi-plus`
> 归档 tag：`archive-2026-11-17`（仅快照，不是正式 Release）
> 后续开发：[docs/refactor/NEXT.md](docs/refactor/NEXT.md)

## 1. 这是什么

LSPosed 模块 + 设置 App：把小米超级小爱的请求重定向到自己的 OpenAI 兼容端点，
并提供本机/局域网 MCP 能力桥（8771）与设备操作接口（8770）。

## 2. 本次归档包含的架构工作

| WP | 内容 | 状态 |
|---|---|---|
| WP0 | `check-layering` 边界门禁 + 基线 | ✅ |
| WP1a | `ConfigGateway` + UI 注入 + rev 校验 | ✅ |
| WP1b | `ConfigService` + 配置规则上移 + 三个已知坑 | ✅ |
| WP2 | Hook 纯策略 + 死代码清理 + `XiaoiProcessHost` | ✅ |
| WP3 | `Mcp.Hosts` 五端口 + `BridgeRuntime` + `BridgeService` | ✅ |
| WP4 | `AppRuntime` 五端口 + UI 去 `Context` | ✅ |
| WP5 | `:core` 物理模块 | ✅ |
| WP6 | `:domain`（53）+ `:application`（29）拆分 | ✅ |

最终模块：

```
:domain      模型/策略/协议 DTO/端口（纯 JVM，无 I/O）
:application 用例/编排/纯 JVM 适配器
:app         Android / Xposed / UI
```

依赖方向：`:app -> {domain, application}`，`:application -> domain`。

## 3. 已验证

- `bash tests/run.sh`：全绿（含 domain 空 classpath 单编）
- `bash scripts/check-consistency.sh`：全绿
- `bash scripts/check-layering.sh --strict`：通过（基线 11 行）
- Windows `:domain:compileJava` / `:application:compileJava`：通过
- Windows `:app:assembleRelease --offline`：BUILD SUCCESSFUL
- `bash scripts/verify-apk.sh`：PASS（签名/包名/versionCode/固定指纹）
- APK：`app/build/outputs/apk/release/app-release.apk`（约 1.9M）

## 4. 归档时的已知限制（不要当正式发布）

1. **真机 P0 未跑完**：WP1–WP6 的权限矩阵、D2 TTL、8770 启停、配置导入、
   watcher、8771 LAN/leader、控制面端口上报仍需真机验收。
2. ~~git 历史丢失~~ **已解决（2026-11-18）**：原 WSL 仓库 2026-11-17 被异常清空，
   但原仓库副本 `D:\code\Android\xiaoi-plus` 保留了完整的 v0.15.0 历史、
   WP0–WP3 提交、分支与 remote。WP4–WP6 与文档迁移已重放到真实历史上。详见
   [docs/refactor/RECOVERY-2026-11-17.md](docs/refactor/RECOVERY-2026-11-17.md)。
3. 既有产品限制仍有效：输出前缀（markerTemplate）未单点注入；
   官方调用量统计在真机未触发；部分 UI 仍硬编码中文；旧 `ConfigActivity` 保留为过渡入口。
4. `:application` 仍包含纯 JVM I/O 适配器（`Http`、`ConfigStore`、`BridgeClient`、
   `ModelValidator`、`LeaderElection`）。若继续演进，可再拆 `:infra`。

## 5. 如何构建（Windows）

```bat
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"
gradlew.bat :domain:compileJava :application:compileJava :app:assembleRelease --offline
```

脚本用 Git Bash 或 WSL：

```bash
bash tests/run.sh
bash scripts/check-consistency.sh
bash scripts/check-layering.sh --strict
bash scripts/verify-apk.sh app/build/outputs/apk/release/app-release.apk
```

## 6. 备份

- `C:\Users\Chatxavier\code\xiaoi-plus-dev\artifacts\2026-11-17\xiaoi-plus-wp6.bundle`
- `C:\Users\Chatxavier\code\xiaoi-plus-dev\artifacts\2026-11-17\xiaoi-plus-wp6.apk`
- 归档 tag 后建议再生成一份 `xiaoi-plus-archive-2026-11-17.bundle`。

## 7. 如何恢复开发

1. `main` 已建立在真实历史之上（`D:\code\Android\xiaoi-plus`）；
   网络可用时把 `main` 推送到 `origin`（remote URL 目前是 gitclone 镜像，需要先修正）。
2. 真机安装归档 APK，按 `docs/refactor/LAYERING.md` 各 WP 的 P0 清单验收。
3. 若要发正式版：先跑 45 项契约与真机 P0，再按 `docs/release.md` 定版本/tag/签名。
