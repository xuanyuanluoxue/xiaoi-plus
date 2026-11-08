#!/usr/bin/env bash
# UI 截图脚本：截图直接写到 PC（adb exec-out），不在手机留文件。
#
# 默认输出：~/xiaoi-plus-dev/xiaoi-plus-ui/tmp/YYYY-MM-DD/
# 归档输出：--review 复制到 ~/xiaoi-plus-dev/xiaoi-plus-ui/review/YYYY-MM-DD/
# 清理：    --clean 删除 tmp 下 7 天前的目录
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UI_ROOT="${XIAOI_UI_ARTIFACTS:-$HOME/xiaoi-plus-dev/xiaoi-plus-ui}"
TMP_ROOT="$UI_ROOT/tmp"
OUT="${XIAOI_SCREENSHOT_OUT:-$TMP_ROOT/$(date +%F)}"
SERIAL="${ANDROID_SERIAL:-}"
ROUTE=""
NAME=""
DUMP=0
REVIEW=0
CLEAN=0

usage() {
    cat <<'EOF'
用法: scripts/ui-screenshot.sh [选项]

选项:
  --serial <serial>   指定设备；默认取第一个 device
  --route <name>      启动 MainActivity 并打开 Home/Xiaoai/Access/Me/Logs/About
  --name <name>       文件名（默认 ui-HHMMSS）
  --out <dir>         输出目录（默认 xiaoi-plus-dev/xiaoi-plus-ui/tmp/日期）
  --dump              同时抓 uiautomator XML（设备临时文件会删除）
  --review            抓完复制到 xiaoi-plus-dev/xiaoi-plus-ui/review/日期
  --clean             删除 tmp 下 7 天前的目录后退出
  -h, --help          显示帮助

环境变量:
  ADB                 自定义 adb 路径
  ANDROID_SERIAL      默认设备
  XIAOI_UI_ARTIFACTS  项目产物根目录（默认 ~/xiaoi-plus-dev/xiaoi-plus-ui）
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --route) ROUTE="$2"; shift 2 ;;
        --name) NAME="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --dump) DUMP=1; shift ;;
        --review) REVIEW=1; shift ;;
        --clean) CLEAN=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "未知参数: $1" >&2; usage >&2; exit 2 ;;
    esac
done

if [ "$CLEAN" = "1" ]; then
    mkdir -p "$TMP_ROOT"
    find "$TMP_ROOT" -mindepth 1 -maxdepth 1 -type d -mtime +7 -print -exec rm -rf {} +
    echo "已清理 $TMP_ROOT 下 7 天前的目录"
    exit 0
fi

if [ -z "${ADB:-}" ]; then
    if command -v adb >/dev/null 2>&1; then
        ADB="$(command -v adb)"
    elif [ -x "/mnt/c/Users/Chatxavier/AppData/Local/Android/Sdk/platform-tools/adb.exe" ]; then
        ADB="/mnt/c/Users/Chatxavier/AppData/Local/Android/Sdk/platform-tools/adb.exe"
    else
        echo "找不到 adb；请设置 ADB=/path/to/adb" >&2
        exit 1
    fi
fi

if [ -z "$SERIAL" ]; then
    SERIAL="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}' | tr -d '\r')"
fi
[ -n "$SERIAL" ] || { echo "没有可用设备；先连接 ADB" >&2; exit 1; }

if [ -n "$ROUTE" ]; then
    "$ADB" -s "$SERIAL" shell am force-stop plus.xiaoi.module >/dev/null 2>&1 || true
    "$ADB" -s "$SERIAL" shell am start -W -n plus.xiaoi.module/.MainActivity \
        --es route "$ROUTE" >/dev/null
    sleep 2
fi

mkdir -p "$OUT"
NAME="${NAME:-ui-$(date +%H%M%S)}"
PNG="$OUT/$NAME.png"
XML="$OUT/$NAME.xml"

# exec-out 是二进制安全的；截图不落手机。
"$ADB" -s "$SERIAL" exec-out screencap -p > "$PNG"
if [ ! -s "$PNG" ] || [ "$(head -c 8 "$PNG" | od -An -tx1 | tr -d ' \n')" != "89504e470d0a1a0a" ]; then
    echo "截图失败或不是 PNG: $PNG" >&2
    rm -f "$PNG"
    exit 1
fi
echo "截图: $PNG"

if [ "$DUMP" = "1" ]; then
    DEV_TMP="/data/local/tmp/xiaoi-ui-$$.xml"
    cleanup() { "$ADB" -s "$SERIAL" shell rm -f "$DEV_TMP" >/dev/null 2>&1 || true; }
    trap cleanup EXIT
    "$ADB" -s "$SERIAL" shell uiautomator dump "$DEV_TMP" >/dev/null
    "$ADB" -s "$SERIAL" exec-out cat "$DEV_TMP" > "$XML"
    cleanup
    trap - EXIT
    echo "UI dump: $XML"
fi

if [ "$REVIEW" = "1" ]; then
    RDIR="$UI_ROOT/review/$(date +%F)"
    mkdir -p "$RDIR"
    cp -f "$PNG" "$RDIR/"
    [ -f "$XML" ] && cp -f "$XML" "$RDIR/"
    echo "已归档: $RDIR"
fi

echo "提示: 默认输出在 tmp/，可用 scripts/ui-screenshot.sh --clean 清理 7 天前目录"
