#!/usr/bin/env bash
# 解出 APK 里的 assets（工具目录/Agent/提示词/MCP 配置），供 catalog.py 生成目录。
set -uo pipefail
source "$(dirname "$0")/env.sh"

V="$(xiaoi_version)"
OUT="$(rev_dir)"
APK="$OUT/voiceassist-$V-base.apk"

[ -s "$APK" ] || { echo "✗ 先跑 pull-apk.sh" >&2; exit 1; }

mkdir -p "$OUT/assets"
( cd "$OUT/assets" && unzip -o -q "$APK" \
    'assets/agents/*' 'assets/app_tools.json' 'assets/tool_enriched.json' \
    'assets/mcp/*' 'assets/authorization/*' 'assets/memory-topic/*' \
    'assets/prompts/*' 'assets/tools/*' 'assets/security/*' 'assets/router/*' \
    2>/dev/null )

echo "✓ assets → $OUT/assets（$(find "$OUT/assets" -type f | wc -l) 个文件）"
echo "提示：接下来跑 python3 reverse/scripts/catalog.py --version $V"
