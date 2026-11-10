# 真机验收记录 2026-11-10

设备：M332BF / Android 17 / API 37 / MIUI，LSPosed 注入正常。
安装方式：`scp` 拉到 `/sdcard/Download/DSH-Go/` → `pm install -r`（不用 adb push，跨 tailscale 易断）。

## 通过

| 项 | 证据 |
|---|---|
| 8772 动作面恢复 | `ss` 见 8772 监听；`xiaoai_action device.info` 返回 `M332BF / Android 17 / 电池` |
| `/health` 报实际端口 | `ok agent=true port=8772 bind=0.0.0.0`（修前是 8770） |
| 玻璃底栏折射 | 滚动截图见内容在胶囊下被模糊（`GlassBottomBar` + miuix-blur） |
| 左右滑动切 tab（跟手） | `HorizontalPager`，用户确认跟手；tab 与 pager 双向同步 |
| 底栏抬高避开手势条 | 底栏加 `navigationBarsPadding()`，与小白条不再重叠 |
| 底部留白 | 四个主页底部 112dp，最后一项能滚出底栏 |
| 协议层创建待办 | `xiaoai_todo_create` → `{"success":true,"noteId":139}`；DB 双重验证（`ai_agentic_db.documents` + `todo_mappings` 命中） |

## 未通过 / 待重做

| 项 | 现象 |
|---|---|
| 顶栏（自研版） | 有内容压字、两层、鬼影等问题；已决定迁移 Miuix `TopAppBar`，见 [TOPBAR.md](TOPBAR.md) |

## 回归注意

模块每次 `pm install -r` 后，**8771/8772 会变成「等待」**：小爱进程里还是旧 hook，
需要 `am force-stop com.miui.voiceassist` 让它重启重载，才恢复。
