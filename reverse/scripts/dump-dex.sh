#!/usr/bin/env bash
# 解出全部 dex 并 dexdump（带缓存，已 dump 过的跳过）。
set -uo pipefail
source "$(dirname "$0")/env.sh"

V="$(xiaoi_version)"
OUT="$(rev_dir)"
APK="$OUT/voiceassist-$V-base.apk"
DEX="$OUT/dex"

[ -s "$APK" ] || { echo "✗ 先跑 pull-apk.sh" >&2; exit 1; }
mkdir -p "$DEX"

echo "解包 dex → $DEX"
( cd "$DEX" && unzip -o -j "$APK" 'classes*.dex' >/dev/null ) || {
  echo "✗ unzip 失败" >&2; exit 1; }

# dexdump 在 Windows 侧，输出是 UTF-16；统一写 .dump.txt
for f in "$DEX"/classes*.dex; do
  base="$(basename "$f" .dex)"
  out="$DEX/$base.dump.txt"
  if [ -s "$out" ]; then
    echo "  跳过 $base（已有 dump）"
    continue
  fi
  echo "  dump $base..."
  "$DEXDUMP" "$f" > "$out" 2>/dev/null || { echo "  ✗ $base 失败" >&2; rm -f "$out"; }
done

COUNT=$(ls "$DEX"/*.dump.txt 2>/dev/null | wc -l)
echo "✓ 完成：$COUNT 个 dump"
echo "提示：PowerShell 直接读这些文件要按 utf-16；python3 请用 encoding='utf-16'"
