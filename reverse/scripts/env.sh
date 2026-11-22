#!/usr/bin/env bash
# 逆向子项目的路径与设备常量。用法：source reverse/scripts/env.sh
set -uo pipefail

# 仓库根（本文件在 reverse/scripts/ 下）
RE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export RE_ROOT

# 大文件目录（不入库）
export RE_BASE="$RE_ROOT/artifacts/reverse"

# 设备（可用 XIAOI_SERIAL 覆盖）
export DEVICE="${XIAOI_SERIAL:-192.168.0.118:5555}"

# Android SDK（用于 dexdump / aapt2）
if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  if [ -d "/mnt/c/Users/Chatxavier/AppData/Local/Android/Sdk" ]; then
    export ANDROID_SDK_ROOT="/mnt/c/Users/Chatxavier/AppData/Local/Android/Sdk"
  fi
fi
export BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_SDK_ROOT/build-tools/37.0.0}"
export DEXDUMP="${DEXDUMP:-$BUILD_TOOLS/dexdump.exe}"
export AAPT2="${AAPT2:-$BUILD_TOOLS/aapt2.exe}"

# 运行中版本号（脚本会读取设备）
xiaoi_version() {
  adb -s "$DEVICE" shell "dumpsys package com.miui.voiceassist | grep -m1 versionName" \
    | tr -d '\r' | sed -E 's/.*versionName=//'
}

# 当前版本的证据目录（与历史材料保持一致：xiaoi-<version>）
rev_dir() {
  local v
  v="$(xiaoi_version)"
  echo "$RE_BASE/xiaoi-${v:-unknown}"
}
