# 真机 P0 矩阵（xiaoi-plus）

发布日期基线：2026-11-06，`0.12.0-beta.1`（versionCode 16）。
设备以 `adb devices` 为准；以下命令假设已设置：

```bash
ADBX="adb"          # WSL 下可用 Windows adb.exe 的绝对路径
TOKEN_FILE="$HOME/.config/xiaoi/token"
DEV="$ADBX -s <serial>"
```

不要 `echo` token / apiKey。所有下载与截图放：

```text
/sdcard/Download/xiaoi-plus-dev/YYYY-MM-DD/{screenshots,ui-dumps,logs}/
```

## 1. 升级不丢配置

```bash
# 升级前
mkdir -p ~/xiaoi-plus-dev/artifacts/configs/$(date +%F)
$DEV exec-out 'su -c "cat /data/data/plus.xiaoi.module/files/config.json"' \
  > ~/xiaoi-plus-dev/artifacts/configs/$(date +%F)/pre-upgrade-config.json
chmod 600 ~/xiaoi-plus-dev/artifacts/configs/$(date +%F)/pre-upgrade-config.json
sha256sum ~/xiaoi-plus-dev/artifacts/configs/$(date +%F)/pre-upgrade-config.json

# 覆盖安装（同签名，versionCode 必须递增）
$DEV install -r /path/to/xiaoi-plus-<version>.apk
$DEV shell dumpsys package plus.xiaoi.module | grep -E 'versionName|versionCode'

# 升级后
$DEV exec-out 'su -c "cat /data/data/plus.xiaoi.module/files/config.json"' \
  > /tmp/post-upgrade-config.json
cmp -s ~/xiaoi-plus-dev/artifacts/configs/$(date +%F)/pre-upgrade-config.json /tmp/post-upgrade-config.json \
  && echo CONFIG_UNCHANGED || echo CONFIG_CHANGED
```

通过：versionCode 为新值，`CONFIG_UNCHANGED`；若发生过 UI 保存，rev 变化但
provider/model/baseUrl/apiKey/token 不变。

## 2. 协议门禁

```bash
$DEV forward tcp:18771 tcp:8771
TOKEN=$(cat "$TOKEN_FILE")

curl --noproxy '*' -s -o /dev/null -w '%{http_code}\n' \
  http://127.0.0.1:18771/health -H "Authorization: Bearer $TOKEN"      # 200
curl --noproxy '*' -s -o /dev/null -w '%{http_code}\n' \
  http://127.0.0.1:18771/health                                       # 401
curl --noproxy '*' -s -o /dev/null -w '%{http_code}\n' -X POST \
  http://127.0.0.1:18771/config -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"enabled":true}'           # 410
```

MCP initialize 必须返回 `result.serverInfo.name = xiaoi-plus`。

远程写拒绝：

```bash
curl --noproxy '*' -s -X POST http://127.0.0.1:18771/mcp \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
       "name":"xiaoai_switch_model",
       "arguments":{"model":"<current>","baseUrl":"http://evil.example"}}}'
```

通过：`isError=true`，文案包含“端点和密钥只能在手机 App 设置”。

## 3. 监听与关闭即停

```bash
$DEV shell 'su -c "ss -ltnp | grep -E \":877[1-5]\" || echo none"'
```

- 正常：只有一个监听（通常 8771）。
- App 关闭 MCP 并保存后：`none`。
- 重启小爱进程后仍为 `none`（配置保持关闭）。
- 重新开启并保存后：8771 恢复。

UI 路径：配置 → MCP 接口开关 → 保存。滚动时从屏幕中部滑动，避免从顶部拉出通知栏。

## 4. 状态

- 配置页顶部 D3：`已应用 · 小爱已加载当前配置`。
- 主页 D4：`已确认` / `小爱正在使用 <model>`。
- 校验模型只改变 D2（`可用`），不得把 D4 改成“已确认”。
- MCP 关闭时日志页仍显示控制面日志（不要求开 MCP）。

已知限制：MCP 关闭时 D3/D4 以数据面 leader 为准，主页显示“端点可用 / 运行态尚未确认”，
配置页显示“等待小爱连接后应用”。这是保守口径，不是数据面异常。

## 5. 签名

```bash
SDK="$HOME/code/逆向/tools/sdk" ./scripts/verify-apk.sh <apk>
```

必须命中：

```text
d7dd34b2e654562954c991730ba6ba88955ea1fcce9e13ae949f74b8b213d19b
```

Release 资产下载后必须再验一次 sha256 与指纹。

## 6. 未重跑项

以下项在 0.12.0-beta.1 发布时由前序工作包真机验证，发布包未重复跑；
正式版前应补：

- 控制面拒绝第三方 UID；
- leader 进程被杀后的 10s 接管；
- LAN 弱 token 回退；
- 配置损坏恢复（legacy 恢复 / 重建默认）；
- 杀小爱 30s 后 D4 退回未确认（系统可能自动拉起小爱，需先阻止自启）。
