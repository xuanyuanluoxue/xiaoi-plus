#!/usr/bin/env bash
# 用 CI secret 里的正式 keystore 重新签名 build.sh 的对齐产物。
# 用法：scripts/ci-sign.sh <aligned.apk> <out.apk>
# 环境变量：KEYSTORE_FILE、KEYSTORE_PASSWORD、KEYSTORE_ALIAS（默认 xiaoi）、
#           KEYSTORE_KEY_PASSWORD（可选，默认同 KEYSTORE_PASSWORD）、SDK
set -euo pipefail

IN="${1:?用法: ci-sign.sh <aligned.apk> <out.apk>}"
OUT="${2:?用法: ci-sign.sh <aligned.apk> <out.apk>}"

# build-tools 布局：优先 $SDK/android-14（旧 build.sh 布局），否则找 $ANDROID_HOME /
# $ANDROID_SDK_ROOT / $HOME/Android/Sdk 下最新的 build-tools（Gradle 9 / AGP 9 布局）。
BT=""
for root in "${SDK:-}" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
  [ -n "$root" ] || continue
  if [ -x "$root/android-14/apksigner" ]; then
    BT="$root/android-14"
    break
  fi
  for d in $(ls -d "$root"/build-tools/* 2>/dev/null | sort -V -r); do
    if [ -x "$d/apksigner" ]; then
      BT="$d"
      break 2
    fi
  done
done
[ -n "$BT" ] || { echo "找不到 apksigner（检查 SDK / ANDROID_HOME 的 build-tools）" >&2; exit 1; }

: "${KEYSTORE_FILE:?缺少 KEYSTORE_FILE}"
: "${KEYSTORE_PASSWORD:?缺少 KEYSTORE_PASSWORD}"
ALIAS="${KEYSTORE_ALIAS:-xiaoi}"
export KEYSTORE_KEY_PASSWORD="${KEYSTORE_KEY_PASSWORD:-$KEYSTORE_PASSWORD}"

[ -f "$IN" ] || { echo "输入 APK 不存在：$IN" >&2; exit 1; }
[ -f "$KEYSTORE_FILE" ] || { echo "keystore 不存在：$KEYSTORE_FILE" >&2; exit 1; }

"$BT/apksigner" sign \
  --ks "$KEYSTORE_FILE" \
  --ks-key-alias "$ALIAS" \
  --ks-pass "env:KEYSTORE_PASSWORD" \
  --key-pass "env:KEYSTORE_KEY_PASSWORD" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --out "$OUT" "$IN"

"$BT/apksigner" verify --print-certs "$OUT"
echo "已签名：$OUT"
