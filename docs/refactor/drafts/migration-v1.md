# 迁移与发布：`0.11.0-beta.4` → `0.12.0`

> 状态：**规划中，未执行**。本文描述升级/降级、schema 迁移、令牌与客户端兼容、发布门禁。
> 设计依据：[architecture.md](architecture.md)。工作包：[workpackages.md](workpackages.md)。

## 1. 版本策略

| 阶段 | versionName | versionCode | 内容 | 发布形态 |
|---|---|---|---|---|
| 基线 | `0.11.0-beta.4` | 15 | 现状 | 已发布 prerelease |
| 重构候选 | `0.12.0-beta.1` | 16 | 控制面/配置/状态重构；`/config` 下线；schema v3 | prerelease |
| 候选修复 | `0.12.0-beta.N` | 17+ | 只修 beta 发现的问题；不扩范围 | prerelease |
| 稳定 | `0.12.0` | 递增 | 去掉 `-beta`；文档/门禁全绿 | 正式 Release |

规则：

- `versionCode` 只增不减；每次发版 +1。
- 唯一来源仍是 `app/src/main/AndroidManifest.xml`；不改 `app/build.gradle.kts` 的构建角色。
- `0.12.0` 的破坏性变更只有一条：`POST /config` 返回 `410 Gone`；必须在 Release Notes 顶部写明。
- beta 期间允许调整内部类名，但 schema v3 与 HMAC 协议字段一旦发布即冻结；改动必须升 minor。

**为什么先 beta**：控制面依赖 Binder/Provider 的 ROM 行为，必须在真机验证；直接发
`0.12.0` 会把「Provider 被 MIUI 拦截」变成全量用户的首次配置失败。

---

## 2. `config.json` schema 迁移

### 2.1 v2 → v3 差异

| 字段 | v2 | v3 | 迁移规则 |
|---|---|---|---|
| `version` | `2` | `3` | 升为 3 |
| `rev` | 无 | 整数 ≥1 | 设为 `1` |
| `updatedAt` | 无 | epoch ms | 设为文件 mtime；不可用则 `now` |
| `enabled` | 有 | 有 | 原样 |
| `marker` | 有 | 有 | 原样 |
| `markerTemplate` | 有 | 有 | 原样 |
| `activeProviderId/activeModelId` | 有 | 有 | `normalize` 修正悬空值 |
| `mcp.enabled/lan/token` | 有 | 有 | 原样 |
| `mcp.tokenSetAt` | 无 | epoch ms | `token` 非空设 `now`，否则 `0` |
| `providers[]` | 有 | 有 | 原样；补 `extra` |
| `themeMode` | 已废弃 | 不写回 | 读取时丢弃（不能进 `extra`） |
| 其他未知字段 | 顶层保留 | 顶层 + `mcp` + provider + model 四层保留 | 原样写回 |

### 2.2 迁移算法（伪代码）

```text
load():
  if !config.json.exists():
      if config.json.bak.exists(): return RECOVERABLE_FROM_BAK
      return FIRST_RUN

  bytes = read(config.json)
  map = parseJson(bytes)
  if parse fails:
      copyTo("config.json.corrupt.<ts>")
      return CORRUPT            // 绝不写默认值

  if map.version > 3: return UNSUPPORTED
  migrated = false
  if map.version < 3:
      map = migrateV2toV3(map)  // 纯函数
      migrated = true

  cfg = normalize(map)
  if !validateStructure(cfg): return CORRUPT
  cfg.hash = sha256(canonicalJson(cfg))

  if migrated:
      if !writeWithBackup(cfg):
          return MIGRATION_FAILED   // 原文件保持不动
  return VALID(cfg)
```

关键点：

1. **先校验再写**。迁移写失败时，原文件必须逐字节不变（测试用哈希断言）。
2. `validateStructure` 只检查「能否安全使用」：JSON 对象、providers 是数组、字段类型正确。
   `validateBusiness`（启用但没有 key 等）不阻塞加载，只影响 `enabled` 的生效判定。
3. `canonicalJson`：固定字段顺序、无空白、未知字段按 key 字典序；用于 `hash`，不用于落盘
   （落盘仍用可读的紧凑顺序）。
4. `themeMode` 必须在迁移时显式丢弃，不能进 `extra`，否则会与 prefs 形成双源。

### 2.3 原子写与备份

```text
writeWithBackup(next):
  tmp = config.json.tmp
  write(tmp, canonicalReadableJson(next)); fsync(tmp)
  if config.json.exists():
      replace(config.json -> config.json.bak)   // 旧版本保留
  rename(tmp -> config.json)
  fsync(dir) best effort
  return true
```

恢复规则：

| 主文件 | `.bak` | 行为 |
|---|---|---|
| 合法 | 任意 | 使用主文件 |
| 损坏/缺失 | 合法 | 状态 `RECOVERABLE_FROM_BAK`；**不自动替换**，UI 让用户确认 |
| 损坏 | 损坏 | `CORRUPT`；只读；提供导出诊断副本 |
| 合法但 rev 比 `revFloor` 小 | 任意 | 接受文件（用户显式回滚），下次写 `revFloor+1` |

**为什么备份不自动恢复**：自动恢复会静默丢弃用户刚用 `adb push` 的新配置；
「损坏」本身已经是异常，必须让用户看到并选择。

### 2.4 回滚矩阵

| 回滚动作 | 结果 | 注意 |
|---|---|---|
| 0.12 → 0.11（`adb install -d`） | 0.11 能读 v3：`version` 保留 3，`rev` 作为未知字段保留；旧代码忽略 `rev` | 必须强制重启小爱；旧代码会恢复「扫描端口 + 发 Bearer」的旧行为 |
| 0.12 写出的 v3 被 0.11 再保存 | `version` 仍为 3，`rev` 原样保留（未知字段），但 `rev` 不再递增 | 再升回 0.12 时 `revFloor` 取历史最大，避免 rev 回退 |
| 恢复 `config.json.bak` | 回到上一版配置 | 需重启小爱或等 60 s 轮询 |
| 恢复 v2 备份 | 0.12 会再次迁移，`rev` 重置为 1 | 建议先删 `.bak`，避免旧备份覆盖新文件 |
| 删除 `config.json` | 0.12 进入 `FIRST_RUN`，不自动覆盖 target 缓存 | 用户需重新配对/配置 |

**必须保留的降级资产**（WP2/WP3 实现）：

- 模块 prefs 继续写 legacy key：`base_url/api_key/model/marker/marker_tpl/enabled/bridge/mcp_lan/token`。
- 桥继续写 target prefs 的同一组 legacy key（值与当前生效配置一致）。
- 0.11 的 `Config.forTarget()` 能读这些 key；这是降级可用的唯一保证。

---

## 3. prefs key 迁移

### 3.1 模块 prefs（App 进程）

| key | 0.11 | 0.12 | 处理 |
|---|---|---|---|
| `base_url/api_key/model/marker/marker_tpl/enabled/bridge/mcp_lan/token` | 使用 | **只写不读**（legacy 镜像） | 保留到 0.13；读取以 config.json 为准 |
| `sync_pending` | 使用 | 忽略 | 0.12 不读；0.13 删除 |
| `theme_mode` | 使用 | 使用 | 不变 |
| `probe_fp/probe_result/probe_at` | 使用 | 使用 | 仅 D2；TTL 仍 10 分钟 |
| `cfg_rev/cfg_hash/cfg_updated_at` | 无 | 新增 | 缓存/诊断；不作事实源 |
| `bridge_last_state` | 无 | 新增 | JSON 快照（不含 token） |
| `logs_local_seq` | 无 | 新增 | DiagnosticsStore 游标 |
| `rev_floor` | 无 | 新增 | 历史最大 rev |

### 3.2 target prefs（voiceassist 进程）

| key | 0.11 | 0.12 | 处理 |
|---|---|---|---|
| legacy 九项 | 使用 | 桥写入，供降级/兜底 | 值必须与当前 `RuntimeConfig` 一致 |
| `cfg_rev` | 无 | 新增 | 缓存版本 |
| `cfg_hash` | 无 | 新增 | 缓存内容哈希 |
| `cfg_json` | 无 | 新增 | 最近一次完整配置（含密钥）——仅 target 私有 |
| `bridge_instance` | 无 | 新增 | 进程启动时的 instanceId |
| `safe_mode` | 无 | 新增 | 崩溃熔断标记 |

**迁移动作**：0.12 首次启动时，如果 target prefs 只有 legacy key、没有 `cfg_json`，
桥从 Provider 拉一次并写入 `cfg_json/cfg_rev`；如果 Provider 不可用，则用 legacy key
组装一个 `rev=0` 的临时快照，**不写回 config.json**，只用于继续重定向。

### 3.3 MIUI 重定向

- 不在 target 进程读模块 prefs（删除 `XSharedPreferences` 分支）。
- App 进程读自己的 prefs 仍可能被重定向，但这只影响主题/诊断缓存，不影响配置。
- 诊断字段：`prefsPath`、`prefsRedirected`、`providerAvailable`、`lastPullSource`。
- 迁移不尝试修复路径（普通应用无权限）。

---

## 4. Token 与 MCP 客户端兼容

### 4.1 旧 Token

- 0.11 的 token 原样保留在 `mcp.token`；0.12 首次启动直接使用。
- 长度 < 32 hex 的旧 token：可用，但 UI 标「弱令牌，建议轮换」；不强制。
- token 为空且 `mcp.enabled=true`：0.12 视为非法，保存时拒绝；首次打开开关自动生成。
- 轮换：写 `rev+1` + `notifyChange`；桥立即清空会话；旧 token 立即 401。
  **不再有** beta.4 的「新→旧→上次成功」多 token 回退。

### 4.2 旧 MCP 客户端兼容期

| 客户端行为 | 0.12.0-beta.1 | 0.12.0 | 0.13.0 |
|---|---|---|---|
| `POST /mcp` + Bearer | ✅ 不变 | ✅ | ✅ |
| `initialize` / `2025-03-26` | ✅ | ✅ | ✅ |
| `tools/list` 8 个工具 | ✅ | ✅ | 可增不可删 |
| `xiaoai_switch_model` | ✅（写路径改为 Provider） | ✅ | ✅ |
| `GET /health` + Bearer | ✅ 精确旧格式 | ✅ | 可改 JSON（需 major） |
| `POST /config` + Bearer | ❌ `410 Gone` | ❌ | ❌ |
| 无 Token 访问任何数据端点 | 401 | 401 | 401 |

`/config` 的 410 响应体建议：

```json
{"ok":false,"error":"config_endpoint_removed",
 "hint":"配置请在小爱同学+ App 中修改；脚本请用 MCP 工具 xiaoai_switch_model"}
```

**为什么不做兼容期**：`/config` 是第二个写者，保留它就无法保证单一事实源；而且它正是
审计里「保存时收到完整配置（含 apiKey）」的载体。用 410 + 明确 hint 比静默保留更安全。

### 4.3 版本偏斜

| 场景 | 检测 | 处理 |
|---|---|---|
| 升级 APK 后未重启小爱 | 注册表无 `proto=2`；`/hello` 404 | UI「请重启小爱以完成升级」；不发送 Bearer |
| 降级 APK 后未重启小爱 | 0.12 桥仍在，0.11 App 扫描端口 | 旧 App 自己会走旧流程；文档要求降级后必须重启小爱 |
| App 与桥 schema 不一致 | `rev` 缺失 / `cfg_json` 缺失 | 桥按 legacy 组装临时快照；日志 `schema_skew` |

---

## 5. 升级 / 降级步骤

### 5.1 升级（用户）

1. 在 App 内「导出备份（含密钥）」或 `adb pull` 当前 `config.json` 到电脑。
2. 记录当前签名指纹：`apksigner verify --print-certs xiaoi-plus-0.11.0-beta.4.apk`。
3. 覆盖安装 `0.12.0-beta.1`（同包名、同签名）。
4. **强制停止并重新打开小爱**（或重启手机），让新桥代码加载。
5. 打开 App，确认：
   - 主页显示「运行中」或「已保存 · 等待小爱」；
   - 配置页显示 `已应用 rev N`；
   - MCP 开关状态与升级前一致。
6. 若提示「桥版本过旧」，重复第 4 步。
7. 若提示「控制面不可用」，把 App 加入电池白名单后重试；仍失败则用岛配对/ADB。

### 5.2 降级（用户）

1. `adb install -d xiaoi-plus-0.11.0-beta.4.apk`（必须同签名）。
2. 强制停止并重新打开小爱。
3. 若配置异常：`adb push config.json.bak` 到 `Android/data/plus.xiaoi.module/files/config.json`，
   再打开 App 让旧代码应用。
4. 明确告知：0.11 会恢复「扫描端口 + Bearer」的旧行为，安全性低于 0.12。

### 5.3 开发者升级检查

```bash
# 1) 迁移前保存样本
adb pull /storage/emulated/0/Android/data/plus.xiaoi.module/files/config.json /tmp/v2.json

# 2) 安装
adb install -r build/xiaoi-plus.apk

# 3) 重启目标进程
adb shell am force-stop com.miui.voiceassist

# 4) 看迁移结果
adb shell run-as com.miui.voiceassist cat shared_prefs/xiaoi_plus.xml  # 可能受 SELinux 限制
adb logcat -d | grep xiaoi+

# 5) 确认没有旧监听
adb shell ss -ltn | grep 877 || echo "no bridge"
```

---

## 6. 真机回归清单（P0）

> 每条都要记录：命令、预期、实际、证据（日志/截图路径）。失败必须归因到 WP。

### 6.1 安装与迁移

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| P0-01 | 覆盖升级不丢配置 | 从 beta.4 覆盖安装 | provider/model/key/token 全在；`rev` 出现；无崩溃 |
| P0-02 | v2 文件迁移 | `adb push` v2 样本后打开 App | 文件变 v3；未知字段保留；hook 生效 |
| P0-03 | 损坏文件 | 写入非法 JSON 后打开 App | 状态「配置损坏」；原文件字节不变；可导出诊断 |
| P0-04 | 备份恢复 | `.bak` 合法、主文件损坏 | 提示恢复；确认后恢复；不自动覆盖 |
| P0-05 | 签名一致 | `apksigner verify --print-certs` | 指纹与 beta.4 相同 |

### 6.2 生命周期

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| P0-06 | MCP 关闭即停 | 关闭 MCP → 保存 | 2 s 内 `ss` 无 877x；连接 refused |
| P0-07 | 重启保持关闭 | 关闭 MCP → 重启小爱 | 仍无监听 |
| P0-08 | MCP 开启 loopback | 开 MCP + token | 仅 127.0.0.1 一个端口；`/health` 200 |
| P0-09 | LAN 开启 | 开 LAN + token | 绑 0.0.0.0；LAN `/mcp` 200；`/config` 410；`/island` 从 LAN 403 |
| P0-10 | 无 token | 清空 token + 开 MCP | 拒绝保存或自动生成；绝不空 token 监听 |
| P0-11 | 端口被占 | 先占 8771 再启动小爱 | 桥用 8772；注册表端口与实际一致 |
| P0-12 | 假端口 | 占 8773 返回 `ok facade=true port=8773` | App 不发送 Bearer；握手失败；不泄露 token |
| P0-13 | 多进程 | 触发 `:core` / `:voice_trigger` | 全机仍只有一个 877x；hook 状态可见 |

### 6.3 配置与状态

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| P0-14 | 桥离线保存 | 杀小爱 → 改配置 → 保存 | 成功；显示「已保存 rev N，等待小爱」；无「令牌不一致」 |
| P0-15 | 外部编辑生效 | `adb push` 合法文件 | ≤2 s（或 60 s）应用；状态更新 |
| P0-16 | 暂停可编辑 | 暂停 → 改 provider/model/key | 输入可编辑；保存成功；校验按钮禁用并说明 |
| P0-17 | 校验不伪造 | 点「校验模型」成功 | 只显示「端点可用」；不显示「已确认/运行中」 |
| P0-18 | 令牌轮换 | 生成新 token → 保存 | 旧 token 立即 401；新 token 200；无死锁 |
| P0-19 | MCP 关闭看日志 | 关 MCP → 打开日志页 | 有日志；不提示「去开 MCP」 |
| P0-20 | Provider 不可用 | force-stop App → 重启小爱 | 桥用 target 缓存继续重定向；状态标「控制面不可用」 |
| P0-21 | 首次配置退出 | 新装/清数据 → 只填端点 → 稍后继续 | 保存草稿；主页有完成清单；无错误弹窗 |
| P0-22 | 导入确认 | 导入文件 → 取消；再导入 → 确认 | 取消不写文件；确认前展示 diff；确认后 `.bak` 正确 |

### 6.4 安全与回归

| # | 场景 | 预期 |
|---|---|---|
| P0-23 | 日志脱敏 | 日志/错误/导出不含 apiKey/token 原文 |
| P0-24 | 导出脱敏 | 默认 `***`；含密钥导出需二次确认 |
| P0-25 | 模型重定向回归 | 主对话仍走第三方端点；暂停后回官方 |
| P0-26 | 调用量统计 | `/stats` 结构正确；MCP 关闭时主页数字仍更新（Provider） |
| P0-27 | MIUI 重定向诊断 | 日志出现 `prefsRedirected=true`，且不影响配置 |

---

## 7. 发布门禁

### 7.1 CI（`.github/workflows/build.yml`）

必须顺序执行：

1. `scripts/check-consistency.sh`（新增，失败即停）
2. `./tests/run.sh`
3. `./build.sh`
4. `scripts/ci-sign.sh`（如有 secrets）
5. `scripts/verify-apk.sh`（新增）
6. 上传 artifact

tag 构建额外：

- tag 名必须等于 `versionName`（已有）；
- `steps.sign.outputs.signed == true` 才允许 `Create GitHub Release`；
- 无正式密钥时：**job 失败**（推荐）或至少 `if:` 跳过 Release 且 artifact 标注
  「临时签名，禁止分发」。当前实现会建 Release，必须修掉。

### 7.2 `scripts/release.sh`

新增步骤：

```text
build.sh
scripts/verify-apk.sh build/xiaoi-plus.apk
  - apksigner verify --print-certs 必须成功
  - 证书 SHA-256 == keystore/xiaoi.keystore 的证书指纹
  - package == plus.xiaoi.module
  - versionName/versionCode == scripts/version.sh
  - 未签名/临时密钥 → 非零退出，不复制到 dist/
sha256sum
release-notes
```

**为什么**：当前 `release.sh` 只检查 keystore 文件存在；`build.sh` 在缺口令时会复制
未签名 APK 到 `build/xiaoi-plus.apk`，脚本仍会把它当发布物归档。这是发布事故面。

### 7.3 发布前人工检查

- [ ] `config.json` schema v3 与 HMAC 协议字段已冻结
- [ ] `docs/refactor/` 四份文档与实现一致
- [ ] `CHANGELOG.md` 标注 `/config` 410
- [ ] 契约基线 diff 已 review（只允许预期变化）
- [ ] P0 清单全绿或有明确豁免
- [ ] 签名指纹与上一版一致
- [ ] Release 为 prerelease（版本号含 `-`）

### 7.4 基线更新策略

| 基线 | 变化 | 处理 |
|---|---|---|
| `health.body` | 不变（仍 `ok facade=... port=...`） | 不改 |
| `config_auth_probe` | 从 400 变 410 | 更新 status/body，注明原因 |
| `mcp_*` | 不变 | 不改 |
| 新增 `/hello`、`/auth/begin` | 新断言 | 新建基线或只做结构断言 |
| `island` | 不变（仍 loopback 免 token） | 不改 |

基线 diff 必须在 PR 描述里逐条解释；禁止「全量重存」掩盖回归。

---

## 8. 回滚与熔断（发布侧）

| 触发 | 动作 |
|---|---|
| beta.1 出现启动崩溃 | 立即把 Release 标 prerelease/隐藏；发 beta.2；必要时指导 `adb install -d` 回 beta.4 |
| Provider 在多数 ROM 不可用 | 热修开关：桥进入 cache-only 模式（仍可重定向，MCP 关闭）；不要求用户降级 |
| HMAC 导致 App 无法读日志 | 关闭 HMAC 兜底，仅用 Provider；记录 `auth_disabled`，不降级到 Bearer |
| 数据面端口无法关闭 | 熔断：`safe_mode=true`，桥不启动任何监听；UI 提示；等待修复版 |
| 配置迁移写坏文件 | 停止写；用 `.bak`/用户备份恢复；发布 hotfix |

**熔断标记**：`target prefs.safe_mode` + 模块 prefs `safe_mode`；任一为 true，`BridgePolicy`
返回 `shouldListen=false`。清除方式：App「诊断 → 退出安全模式」或卸载重装。

---

## 9. 与 beta.4 的迁移差异速查

| 项 | beta.4 | 0.12 |
|---|---|---|
| 配置推送 | HTTP `/config` + 多 token 回退 | Provider pull + notifyChange |
| 端口发现 | 8771~8775 + Bearer `/health` | 注册表 + `/hello` + HMAC |
| Token 轮换 | 新→旧→上次成功 | 写 rev，会话立即失效 |
| MCP 关闭 | 只回 loopback | 关闭全部监听 |
| target prefs | 读配置，缺字段硬编码 true | 缓存，带 rev；缺字段不猜 |
| 日志 | 依赖 MCP `/logs` | Provider 推送 + 本地 Store |
| 运行时确认 | App 探针可伪造 | 仅桥上报 |
| 保存语义 | 文件 + 推送，可能假失败 | 文件成功即成功 |
| 配置损坏 | 静默默认 | 只读 + 备份恢复 |
| 多进程 | 每进程桥 | 主进程单桥 |
| `/config` | 200/401 | 410 |
| schema | v2 | v3（可回滚读） |
