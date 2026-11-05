#!/usr/bin/env bash
# adb 输入法（ADBKeyboard）开关。仅在需要 adb 输入文本时切过去，用完切回。
#
# 用法：
#   scripts/ime.sh on            切到 ADBKeyboard（并记住原输入法）
#   scripts/ime.sh off           切回原输入法
#   scripts/ime.sh status        当前输入法 / 是否已安装
#   scripts/ime.sh type "文本"    用 ADBKeyboard 广播输入（含中文；需先 on）
#   scripts/ime.sh enter         发送回车
#
# 设备：默认取 adb devices 的第一台；可用 ANDROID_SERIAL 覆盖。
set -euo pipefail

ADB_IME="com.android.adbkeyboard/.AdbIME"
PREV_FILE="$HOME/.config/xiaoi/ime_prev"
SERIAL="${ANDROID_SERIAL:-$(adb devices | awk '$2=="device"{print $1; exit}')}"
[ -n "$SERIAL" ] || { echo "错误：没有已连接设备" >&2; exit 1; }
ADB="adb -s $SERIAL"

mkdir -p "$(dirname "$PREV_FILE")"

case "${1:-status}" in
  on)
    CUR="$($ADB shell settings get secure default_input_method | tr -d '\r')"
    if [ "$CUR" != "$ADB_IME" ]; then
      printf '%s\n' "$CUR" > "$PREV_FILE"
    fi
    $ADB shell ime enable "$ADB_IME" >/dev/null
    $ADB shell ime set "$ADB_IME" >/dev/null
    echo "已切换到 ADBKeyboard（原输入法：$(cat "$PREV_FILE" 2>/dev/null || echo 未知)）"
    ;;
  off)
    if [ -f "$PREV_FILE" ]; then
      PREV="$(cat "$PREV_FILE")"
      [ -n "$PREV" ] || PREV="$($ADB shell settings get secure default_input_method | tr -d '\r')"
      $ADB shell ime set "$PREV" >/dev/null
      echo "已切回：$PREV"
    else
      echo "没有记录原输入法；用系统设置手动切换即可" >&2
    fi
    ;;
  type)
    MSG="${2:?用法: ime.sh type \"文本\"}"
    $ADB shell am broadcast -a ADB_INPUT_TEXT --es msg "$MSG" >/dev/null
    echo "已输入：$MSG"
    ;;
  enter)
    $ADB shell am broadcast -a ADB_INPUT_CODE --ei code 66 >/dev/null
    echo "已发送回车"
    ;;
  status)
    CUR="$($ADB shell settings get secure default_input_method | tr -d '\r')"
    echo "当前输入法：$CUR"
    if $ADB shell ime list -a -s 2>/dev/null | grep -q "$ADB_IME"; then
      echo "ADBKeyboard：已安装"
    else
      echo "ADBKeyboard：未安装"
    fi
    ;;
  *)
    echo "用法：$0 [on|off|status|type <文本>|enter]" >&2
    exit 2
    ;;
esac
