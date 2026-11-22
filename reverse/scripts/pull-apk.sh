#!/usr/bin/env bash
# 从设备拉「正在运行」的那份 APK（不是出厂 priv-app）。
set -uo pipefail
source "$(dirname "$0")/env.sh"

V="$(xiaoi_version)"
OUT="$(rev_dir)"
mkdir -p "$OUT"
APK="$OUT/voiceassist-$V-base.apk"

echo "设备      : $DEVICE"
echo "运行版本  : $V"
echo "输出      : $APK"

if [ -s "$APK" ]; then
  echo "已存在，跳过（删除后重跑可重新拉取）"
  exit 0
fi

# 优先 pm path（需要 root 读 /data/app）
PKG_PATH="$(adb -s "$DEVICE" shell "pm path com.miui.voiceassist" | tr -d '\r' | sed -n 's/^package://p' | head -1)"
if [ -z "$PKG_PATH" ]; then
  echo "✗ 拿不到 APK 路径；确认设备已授权并 root 可用" >&2
  exit 1
fi
echo "APK 路径  : $PKG_PATH"

# cat 比 pull 稳：绕过 /data 的目录权限
adb -s "$DEVICE" shell "cat '$PKG_PATH'" > "$APK"

SIZE=$(stat -c %s "$APK" 2>/dev/null || echo 0)
if [ "$SIZE" -lt 1000000 ]; then
  echo "✗ 拉取结果只有 ${SIZE} 字节，可能没权限" >&2
  exit 1
fi
echo "✓ 完成：$(du -h "$APK" | cut -f1)"
