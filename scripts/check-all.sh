#!/usr/bin/env bash
# 本地全量门禁：工程一致性 → JVM 单测 → AOCI 认知漂移检查 → 真机契约（有设备时）。
# 用途：推送前 / 改完代码后一条命令确认"测试 + 认知 + 设备"三层都对齐。
#
# 用法：
#   scripts/check-all.sh              # 全套（无设备时自动跳过契约）
#   scripts/check-all.sh --no-device  # 只跑测试与认知检查
#
# 设备契约需要：adb 已连接设备 + ~/.config/xiaoi/token（或 XIAOI_TOKEN 环境变量）。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NO_DEVICE=0
[ "${1:-}" = "--no-device" ] && NO_DEVICE=1

FAIL=0
step() { echo; echo "==> $*"; }

step "[1/4] 工程一致性（版本/SDK/R/死资源）"
"$ROOT/scripts/check-consistency.sh" || FAIL=1

step "[2/4] JVM 单测"
"$ROOT/tests/run.sh" | tail -6 || FAIL=1

step "[3/4] AOCI 认知检查（漂移 / 待策展 / 字典 / 格式 / 草稿）"
if command -v aoci >/dev/null 2>&1; then
    ( cd "$ROOT" && aoci check ) || FAIL=1
elif [ -x "$HOME/.local/bin/aoci" ]; then
    ( cd "$ROOT" && "$HOME/.local/bin/aoci" check ) || FAIL=1
else
    echo "  · 未安装 aoci，跳过"
fi

step "[4/4] 真机契约（重启小爱 → 日志断言 → MCP 全链路）"
if [ "$NO_DEVICE" -eq 1 ]; then
    echo "  · 按参数跳过"
elif adb devices | awk '$2 == "device"' | grep -q .; then
    "$ROOT/scripts/contract-test.sh" --check || FAIL=1
else
    echo "  · 无已连接设备，跳过"
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "==> 全部门禁通过"
else
    echo "==> 存在失败项，见上方输出"
    exit 1
fi