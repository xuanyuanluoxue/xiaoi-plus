# 0.14.1 版本说明（启动入口修复）

> 日期：2026-11-08
> 目标：`0.14.1` / versionCode 20
> 状态：**已发布**
> 上一版：[0.14.0](RELEASE-0.14.md)

## 1. 为什么发 0.14.1

0.14.0 的 Compose + Miuix 页面已经完成，但 `AndroidManifest.xml` 仍把

```xml
<action android:name="android.intent.action.MAIN" />
<category android:name="android.intent.category.LAUNCHER" />
```

挂在旧 `HomeActivity` 上，`MainActivity` 还是 `exported=false`。因此：

- 从桌面图标进入 → 旧 XML 首页（看起来“UI 没变”）
- Compose 新 UI 只能通过 `adb am start ... --es route Home` 打开

这是发布配置遗漏，不是 UI 没合并。0.14.1 只修启动入口，不改页面。

## 2. 修复内容

| 项 | 0.14.0 | 0.14.1 |
| --- | --- | --- |
| launcher | `HomeActivity` | `MainActivity` |
| `MainActivity.exported` | false | true |
| `HomeActivity.exported` | true | false |
| 旧首页入口 | 桌面图标 | 我的 › 兼容模式 |
| 一致性门禁 | 无 launcher 检查 | 启动入口不是 `.MainActivity` 直接失败 |

## 3. 验证

| 检查 | 结果 |
| --- | --- |
| `cmd package resolve-activity --brief plus.xiaoi.module` | `plus.xiaoi.module/.MainActivity` |
| 桌面图标（`monkey -p ... LAUNCHER`） | 落在 Compose 首页，显示四维状态卡 |
| `check-consistency.sh` | launcher / exported 检查通过，7+2 项全绿 |
| `verify-apk.sh` | versionCode 20；签名指纹 `d7dd34b2…d19b` |
| 覆盖安装 | 配置 SHA 不变；8771 正常 |

## 4. 已知限制

与 0.14.0 相同，见 [0.14.0 §4](RELEASE-0.14.md#4-已知限制)。重点仍是：

- 8770 与小米自带本地服务同端口，可能 `EADDRINUSE`；8771 不受影响。
- 日志域筛选、Connect BottomSheet、底栏图标风格等转 0.15。

## 5. 资产

- `xiaoi-plus-0.14.1.apk`
- `xiaoi-plus-0.14.1.apk.sha256`
