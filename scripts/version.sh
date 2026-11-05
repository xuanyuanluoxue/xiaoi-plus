#!/usr/bin/env bash
# 版本号唯一来源：app/src/main/AndroidManifest.xml 的 android:versionName / android:versionCode。
# app/build.gradle.kts 仅作回退（该文件不参与构建，见其文件头注释）。
# 用法：scripts/version.sh [name|code|json|print]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE="$ROOT/app/build.gradle.kts"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"

# 唯一来源优先取 AndroidManifest.xml（本项目的实际构建入口）
if [ -f "$MANIFEST" ]; then
  NAME="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)"
  CODE="$(sed -n 's/.*android:versionCode="\([0-9][0-9]*\)".*/\1/p' "$MANIFEST" | head -1)"
fi

# 回退到 gradle 文件
if [ -z "${NAME:-}" ] && [ -f "$GRADLE" ]; then
  NAME="$(sed -n 's/.*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$GRADLE" | head -1)"
  CODE="$(sed -n 's/.*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$GRADLE" | head -1)"
fi

[ -n "${NAME:-}" ] || { echo "未找到 versionName（manifest/gradle）" >&2; exit 1; }
[ -n "${CODE:-}" ] || { echo "未找到 versionCode（manifest/gradle）" >&2; exit 1; }

case "${1:-print}" in
  name) echo "$NAME" ;;
  code) echo "$CODE" ;;
  json) printf '{"name":"%s","code":%s}\n' "$NAME" "$CODE" ;;
  print) echo "v$NAME ($CODE)" ;;
  *) echo "用法: $0 [name|code|json|print]" >&2; exit 2 ;;
esac
