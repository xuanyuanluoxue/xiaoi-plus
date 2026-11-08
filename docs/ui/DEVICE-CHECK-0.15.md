# 0.15 真机验收清单（UI + 预测性返回）

> 状态：待设备验收
> 基线：`feat/ui-liquid`（未合并 main）
> 设备：Android 17 / 1280×2772 / 520dpi（明天连接 ADB）

## 0. 安装

```bash
cd ~/xiaoi-plus-dev/worktrees/liquid-nav
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n plus.xiaoi.module/.MainActivity
```

覆盖安装不应丢 `config.json`、令牌或主题；如丢配置，立即停止验收并记录。

## 1. 一级页（浅色 / 深色）

每页截图（`scripts/ui-screenshot.sh --review`）：

| 页面 | 检查点 |
| --- | --- |
| 首页 | 大标题 32sp；Hero 四维徽章；绿/黄/红随状态；7 张入口卡；彩色圆 48dp；间距 12dp |
| 小爱 | 开关卡、当前模型、运行态、增强能力；无第二条工具栏 |
| 接入 | 8771/8770 两卡；令牌/连接/能力/工具/权限入口为独立卡 |
| 我的 | 项目介绍/版本、赞助、作者、致谢、外观、配置管理、诊断、日志、关于、许可、危险区 |

- 玻璃底栏：悬浮胶囊、4 tab、选中 pill、图标/标签可读；点击切换正常。
- 内容最后一张卡不被底栏遮挡；Snackbar 在底栏上方。
- 深浅色切换后底栏、卡片、图标颜色对称。

## 2. 二级页（预测性返回）

从首页依次进入：小爱 › 模型、接入 › 端口与服务、我的 › 外观、我的 › 配置管理。

- 顶部返回按钮回到上一页。
- 系统手势返回：从屏幕左/右边缘滑动，页面随进度缩放/淡出；松手完成返回，
  中途取消回弹。
- 一级页左/右滑：非首页 tab 回首页；首页不拦截（交给系统 back-to-home）。
- 脏数据页：模型编辑有未保存修改时，返回先弹保存/放弃确认，不直接退出。

## 3. 玻璃回退

- 外观 › 底栏 › 关闭「玻璃底栏」→ 立即重建为同尺寸实心胶囊。
- 再打开 → 恢复玻璃（API 33+）。
- 系统设置 › 开发者选项 › 动画缩放 0 → 重启 App，底栏应为实心。
- 低版本（API 26–30）无法在本机验证：确保不会因 `miuix-blur` 类加载崩溃；
  日志无 `NoClassDefFoundError` / `RuntimeShader`。

## 4. 回归

```bash
XIAOI_TOKEN=$(cat ~/.config/xiaoi/token) ./scripts/contract-test.sh --check
```

- 8771 `/health`、`/logs`、`/stats`、MCP `tools/list` 正常。
- 8770 `/health`、`actions` 正常（端口冲突时按已知限制记录）。
- 模型重定向仍生效；暂停/恢复、令牌轮换、LAN 开关行为不变。
- `./tests/run.sh`、`./scripts/check-consistency.sh`、`verify-apk.sh` 全绿。

## 5. 记录

截图归档：`~/xiaoi-plus-dev/xiaoi-plus-ui/review/2026-11-09/`
报告：`~/xiaoi-plus-dev/xiaoi-plus-ui/tests/2026-11-09/ui-liquid-acceptance.md`

未通过项按 P0/P1/P2 分级；P0/P1 修完再合并 `main`。
