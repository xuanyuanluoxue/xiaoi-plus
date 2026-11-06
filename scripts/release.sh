#!/usr/bin/env bash
# 一键发版：干净检查 → 构建 → 签名/包名/指纹校验 → dist/xiaoi-plus-<version>.apk → sha256 → Release Notes 草稿
#
# 用法：
#   scripts/release.sh                 正式发版
#   scripts/release.sh --allow-dirty   允许已跟踪文件有未提交改动（本地试跑）
#   scripts/release.sh --allow-new-key 没有 keystore 时允许自动生成（签名会变，别用于正式版）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ALLOW_DIRTY=0
ALLOW_NEW_KEY=0
for arg in "$@"; do
  case "$arg" in
    --allow-dirty) ALLOW_DIRTY=1 ;;
    --allow-new-key) ALLOW_NEW_KEY=1 ;;
    -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$arg" >&2; exit 2 ;;
  esac
done

die() { echo "错误：$*" >&2; exit 1; }

command -v git >/dev/null || die "需要 git"
command -v zip >/dev/null || die "需要 zip（sudo apt install zip）"
[ -f build.sh ] || die "不在仓库根目录"

VERSION="$(scripts/version.sh name)"
CODE="$(scripts/version.sh code)"
TAG="v$VERSION"
APK="build/xiaoi-plus.apk"
DIST="dist"
OUT="$DIST/xiaoi-plus-$VERSION.apk"

echo "==> 版本 v$VERSION ($CODE)"

# 1) 工作区检查（只看已跟踪文件；dist/ 等未跟踪产物不阻塞）
if [ "$ALLOW_DIRTY" -eq 0 ]; then
  if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    git status --short --untracked-files=no >&2
    die "工作区不干净，先提交或加 --allow-dirty 试跑"
  fi
  echo "==> 工作区干净"
else
  echo "==> 跳过工作区检查（--allow-dirty）"
fi

# 2) tag 检查，避免覆盖已发布版本
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  die "tag $TAG 已存在；发新版本请先改 app/src/main/AndroidManifest.xml 的 android:versionName/android:versionCode"
fi

# 3) 签名密钥检查（build.sh 缺密钥会自动生成新密钥，正式版不能这样）
if [ ! -f keystore/xiaoi.keystore ] && [ "$ALLOW_NEW_KEY" -eq 0 ]; then
  die "缺少 keystore/xiaoi.keystore。请从备份恢复（见 docs/release.md），或加 --allow-new-key 仅做本地验证"
fi

# 4) 构建
echo "==> 构建"
./build.sh
[ -f "$APK" ] || die "构建结束但找不到 $APK"

# 5) 发布门禁：签名（固定指纹）/ 包名 / versionCode；失败不归档、不生成 notes
echo "==> 校验 APK（签名 / 包名 / versionCode / 指纹）"
if [ "$ALLOW_NEW_KEY" -eq 1 ]; then
  echo "警告：--allow-new-key 允许临时密钥，跳过固定指纹比对；该产物不能用于正式发布。" >&2
  scripts/verify-apk.sh "$APK" --allow-any-signer || die "APK 校验失败，不归档"
  SIGN_NOTE="已签名 + 包名 + versionCode（临时密钥，未比对固定指纹）"
else
  scripts/verify-apk.sh "$APK" || die "APK 校验失败（未签名 / 包名或版本不符 / 签名指纹不匹配），不归档"
  SIGN_NOTE="已签名 + 包名 + versionCode + 证书指纹"
fi

# 6) 归档
mkdir -p "$DIST"
cp -f "$APK" "$OUT"
( cd "$DIST" && sha256sum "xiaoi-plus-$VERSION.apk" > "xiaoi-plus-$VERSION.apk.sha256" )
scripts/release-notes.sh "$VERSION" "$OUT" > "$DIST/release-notes-$VERSION.md"

SHA="$(cut -d' ' -f1 "$DIST/xiaoi-plus-$VERSION.apk.sha256")"

echo
echo "==> 完成"
echo "APK      : $OUT ($(du -h "$OUT" | cut -f1))"
echo "签名校验 : 通过（$SIGN_NOTE）"
echo "SHA-256  : $SHA"
echo "校验文件 : $DIST/xiaoi-plus-$VERSION.apk.sha256"
echo "Notes    : $DIST/release-notes-$VERSION.md"
echo
echo "下一步："
echo "  git tag -a $TAG -m \"$TAG\""
echo "  git push origin $TAG"
echo "  # 推送 tag 后 GitHub Actions 会自动构建；只有 signed=true（正式密钥）才建 Release"
echo "  # 手动发布：gh release create $TAG $OUT --notes-file $DIST/release-notes-$VERSION.md"
