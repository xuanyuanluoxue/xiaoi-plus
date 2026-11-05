# 远程调试手机（小爱同学plus）

> 前提：手机与网关在同一 tailnet（或同一局域网），把下面的 `<PHONE_IP>` 换成你自己的手机地址。
> 所有命令在网关 WSL 里执行；手机侧不需要 USB。

## 1. 连接

```bash
adb devices -l
# <PHONE_IP>:5555  device  product:<codename> model:<model>
```

掉线时重连：

```bash
adb disconnect
adb connect <PHONE_IP>:5555
```

## 2. 安装与重启

```bash
./build.sh                      # 产物 build/xiaoi-plus.apk
adb install -r build/xiaoi-plus.apk
adb shell am force-stop com.miui.voiceassist
adb shell "monkey -p com.miui.voiceassist -c android.intent.category.LAUNCHER 1"
```

> 模块 APK 更新后，**必须重启小爱**，否则作用域内进程仍跑旧 dex。
> 系统会弹「模块已更新，请强行停止并重新打开其作用域内的应用」，就是这个原因。

## 3. 看模块日志

```bash
adb logcat -c
adb logcat -s VectorLegacyBridge:I | grep "xiaoi+"
```

| 日志 | 含义 |
|---|---|
| `已启用 model=...` | 配置读到 |
| `配置为空，跳过` | 没读到配置 |
| `provider 标识 hook 完成` | 请求头标识已装 |
| `模型标识已改走请求头` | 首次请求已加 `X-Model-Provider-Id` |
| `超级岛诊断: island=true protocol=3 canShowFocus=true` | 岛能力正常 |
| `版本不在白名单` | 小爱升级了，需重新适配 |

## 4. 本机桥与 MCP

默认只绑手机自己的 `127.0.0.1:8771~8775`，网关做端口转发：

```bash
adb forward tcp:8771 tcp:8771
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:8771/health
# ok facade=true port=8771
```

也可以在设置页打开「允许局域网访问」（Token 非空）后直接走内网，不需要 adb：

```bash
curl -s -H "Authorization: Bearer <token>" http://<PHONE_IP>:8771/health
```

MCP 探测（两种接入都把 URL 换成对应地址）：

```bash
curl -s -X POST http://127.0.0.1:8771/mcp \
  -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}'
```

> `adb forward` 的生命周期跟着 adb server；adb 重启后要重跑，MCP 会表现为超时。
> 局域网模式不受 adb 影响，但端口可能在重绑后落到 8772~8775，客户端应按设置页显示的地址填。

## 5. root 操作

`adb shell su` 在部分 KernelSU/Magisk 环境不可用（没装 su 兼容层）。接一个 root shell 的 MCP，或在 `adb shell` 里直接执行：

```
<root-exec>      {"cmd": "...", "timeout": 30}
<root-read-file> {"path": "/data/...", "max_bytes": 262144}
<root-sys-info>  {}
```

## 6. 截图

```bash
adb shell screencap -p /sdcard/s.png
adb pull /sdcard/s.png /tmp/s.png
```

大图在部分查看器里会解码失败，先缩到 720 宽。

## 7. 写小爱/模块 prefs 的坑

用 root 覆盖 `shared_prefs/*.xml` 后，**必须补 SELinux 类别**，否则应用读不到（表现：配置为空、主题不生效）：

```bash
F=/data/user/0/plus.xiaoi.module/shared_prefs/xiaoi_plus.xml
chown <uid>:<gid> $F && chmod 600 $F
chcon u:object_r:app_data_file:s0:c512,c768 $F
```

## 8. 常见故障

| 现象 | 处理 |
|---|---|
| `unauthorized` | token 不对，或请求没带 `Authorization` |
| 连不上 8771 | 先 `adb forward`；或换 8772（多进程时端口会错开） |
| 模块不生效 | 小爱没重启；或 LSPosed 作用域没勾 `com.miui.voiceassist` |
| 配置为空 | prefs 写失败 / SELinux 标签不对 |
| 岛不折叠成胶囊 | 缺 RemoteViews，属已知限制 |
| `install` 很快 | 增量安装，属正常；用 `dumpsys package | grep versionName` 核对 |
