#!/usr/bin/env bash
# 生成当前版本的证据快照：manifest 组件/权限 + dex 概况 + 关键类是否还在。
set -uo pipefail
source "$(dirname "$0")/env.sh"

V="$(xiaoi_version)"
OUT="$(rev_dir)"
APK="$OUT/voiceassist-$V-base.apk"
REPORT="$OUT/report-$V.md"
mkdir -p "$OUT"

[ -s "$APK" ] || { echo "✗ 先跑 pull-apk.sh" >&2; exit 1; }

{
  echo "# 证据快照 $V"
  echo
  echo "生成时间：$(date '+%Y-%m-%d %H:%M:%S')"
  echo
  echo "## 包信息"
  echo '```'
  "$AAPT2" dump badging "$APK" 2>/dev/null | head -20
  echo '```'
  echo
  echo "## 组件（activity/service/receiver/provider）"
  echo '```'
  "$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null \
    | grep -E "E: (activity|service|receiver|provider)" | sed 's/^ *//' | sort -u | head -60
  echo '```'
  echo
  echo "## dex 概况"
  echo '```'
  ls -l "$OUT/dex"/classes*.dex 2>/dev/null | awk '{print $5, $9}' | head -60
  echo '```'
  echo
  echo "## 关键类是否仍在（静态）"
  echo '```'
  for token in getBaseUrl getApiKey getModelProviderId buildOpenAIRequest \
               buildResponsesRequest streamCompletion getSupportsVision; do
    n=$(python3 "$RE_ROOT/reverse/scripts/scan.py" classes --contains "$token" --limit 5 2>/dev/null | grep -c "  $token" || true)
    printf "%-24s 命中 %s 个方法\n" "$token" "$n"
  done
  echo '```'
} > "$REPORT"

echo "✓ 报告：$REPORT"
