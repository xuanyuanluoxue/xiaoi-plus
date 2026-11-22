#!/usr/bin/env bash
# 只解 dex，不 dump。全量 dex 交给 dexindex.py 建索引。
set -uo pipefail
source "$(dirname "$0")/env.sh"

V="$(xiaoi_version)"
OUT="$(rev_dir)"
APK="$OUT/voiceassist-$V-base.apk"
DEX="$OUT/dex"

[ -s "$APK" ] || { echo "✗ 先跑 pull-apk.sh" >&2; exit 1; }
mkdir -p "$DEX"

echo "解包 classes*.dex → $DEX"
( cd "$DEX" && unzip -o -j "$APK" 'classes*.dex' >/dev/null )
N=$(ls "$DEX"/classes*.dex 2>/dev/null | wc -l)
SIZE=$(du -sm "$DEX" | cut -f1)
echo "✓ $N 个 dex，共 ${SIZE} MB"
