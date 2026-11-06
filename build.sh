#!/usr/bin/env bash
# 无 Gradle 构建：javac + d8 + aapt2 + apksigner
#
# 顺序很关键：aapt2 先编译/链接并生成 R.java，再编译 Java。
# 这样业务代码可以引用 R.id / R.color / R.style，资源改名在编译期就会报错，
# 不再依赖运行时 getIdentifier（字符串写错只会在真机上崩）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${SDK:-$HOME/Android/Sdk}"
BT="$SDK/android-14"
AJ="$SDK/android-35/android.jar"
OUT="$ROOT/build"
APK="$OUT/xiaoi-plus-unsigned.apk"
RES="$ROOT/app/src/main/res"

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "[1/6] 编译 Xposed 桩"
javac -nowarn -source 1.8 -target 1.8 -cp "$AJ" -d "$OUT/classes" \
  $(find "$ROOT/libs/xposed-stub-src" -name "*.java")

echo "[2/6] 编译资源并生成 R"
"$BT/aapt2" compile --dir "$RES" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$APK" --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  -I "$AJ" -A "$ROOT/app/src/main/assets" -R "$OUT/res.zip" --java "$OUT/gen"

echo "[3/6] 编译模块（含 R）"
javac -nowarn -source 1.8 -target 1.8 -cp "$AJ:$OUT/classes" -d "$OUT/classes" \
  $(find "$ROOT/app/src/main/java" "$OUT/gen" -name "*.java")

echo "[4/6] dex"
"$BT/d8" --lib "$AJ" --release --output "$OUT/dex" \
  $(find "$OUT/classes/plus" -name "*.class")

echo "[5/6] 打包"
(cd "$OUT/dex" && zip -q "$APK" classes.dex)
"$BT/zipalign" -f 4 "$APK" "$OUT/xiaoi-plus-aligned.apk"

echo "[6/6] 签名"
KS="$ROOT/keystore/xiaoi.keystore"
KP="${KEYSTORE_PASSWORD:-}"
if [ -z "$KP" ] && [ -f "$HOME/.config/xiaoi/keystore_pass" ]; then
  KP="$(cat "$HOME/.config/xiaoi/keystore_pass")"
fi
if [ -z "$KP" ]; then
  # CI 的 Build 步骤不注入签名口令，正式签名由后面的 ci-sign.sh 完成；
  # 本地缺少口令时也产出未签名 APK，方便先验证编译。
  echo "未设置签名口令，跳过签名（CI 会重新签）"
  cp "$OUT/xiaoi-plus-aligned.apk" "$ROOT/build/xiaoi-plus.apk"
  echo "OK (unsigned): $ROOT/build/xiaoi-plus.apk"
  exit 0
fi
mkdir -p "$ROOT/keystore"
if [ ! -f "$KS" ]; then
  # 有口令却没有 keystore，说明密钥被删/没恢复。静默生成新证书会产出
  # “已签名但不能覆盖安装正式版”的包，必须显式允许才能继续。
  if [ "${XIAOI_ALLOW_NEW_KEY:-0}" != "1" ]; then
    echo "错误：有签名口令，但找不到 $KS" >&2
    echo "正式发版请先从备份恢复 keystore；仅本地编译验证可设 XIAOI_ALLOW_NEW_KEY=1（会生成新证书，不能覆盖安装正式版）" >&2
    exit 1
  fi
  echo "警告：已生成新的签名证书（XIAOI_ALLOW_NEW_KEY=1）；该 APK 不能覆盖安装正式版" >&2
  keytool -genkeypair -keystore "$KS" -storepass "$KP" -keypass "$KP" \
    -alias xiaoi -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=XiaoAi Plus, O=OpenSource, C=CN" >/dev/null 2>&1
fi
printf '%s\n%s\n' "$KP" "$KP" > "$OUT/kpass.txt"
"$BT/apksigner" sign --ks "$KS" --ks-pass "file:$OUT/kpass.txt" --key-pass "file:$OUT/kpass.txt" \
  --out "$ROOT/build/xiaoi-plus.apk" "$OUT/xiaoi-plus-aligned.apk"
echo "OK: $ROOT/build/xiaoi-plus.apk"
