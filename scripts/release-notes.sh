#!/usr/bin/env bash
# 生成 Release Notes 草稿：从 CHANGELOG.md 抽取对应版本的小节，
# 补上版本号、校验和、签名指纹与安装说明。
# 用法：scripts/release-notes.sh <version> <apk>
set -euo pipefail

VERSION="${1:?用法: release-notes.sh <version> <apk>}"
APK="${2:?用法: release-notes.sh <version> <apk>}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${SDK:-$HOME/Android/Sdk}"
BT="$SDK/android-14"

[ -f "$APK" ] || { echo "APK 不存在：$APK" >&2; exit 1; }

SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
SIZE="$(du -h "$APK" | cut -f1)"
CODE="$("$ROOT/scripts/version.sh" code)"
DATE="$(date -u +%Y-%m-%d)"

FINGERPRINT=""
if [ -x "$BT/apksigner" ]; then
  FINGERPRINT="$("$BT/apksigner" verify --print-certs "$APK" 2>/dev/null \
    | sed -n 's/.*SHA-256 digest: //p' | head -1 || true)"
fi

BODY="$(
  awk -v v="$VERSION" '
    $0 ~ "^## " v "([^0-9.]|$)" { on = 1; print; next }
    on && /^## / { exit }
    on { print }
  ' "$ROOT/CHANGELOG.md"
)"

if [ -z "$BODY" ]; then
  BODY="## $VERSION"
  echo "提示：CHANGELOG.md 里没有 $VERSION 小节，已使用空模板" >&2
fi

cat <<EOF
$BODY

---

- 版本：\`$VERSION\`（versionCode $CODE）
- 构建日期（UTC）：$DATE
- 文件：\`$(basename "$APK")\`（$SIZE）
- SHA-256：\`$SHA\`
$([ -n "$FINGERPRINT" ] && echo "- 签名证书 SHA-256：\`$FINGERPRINT\`")

### 安装

\`\`\`bash
adb install -r $(basename "$APK")
\`\`\`

校验下载：

\`\`\`bash
sha256sum -c $(basename "$APK").sha256
\`\`\`

> 0.x 预览版：配置格式、能力桥接口与 hook 目标都可能在不发大版本的情况下变化。
> 仅支持 Android 12+ + LSPosed，且小爱版本需在兼容性矩阵白名单内。
EOF
