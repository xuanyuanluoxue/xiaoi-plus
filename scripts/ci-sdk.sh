#!/usr/bin/env bash
# 在 CI（或新机器）上准备 build.sh 需要的 SDK 布局：
#   $SDK/android-14/{aapt2,d8,zipalign,apksigner,...}   build-tools 35
#   $SDK/android-35/android.jar                        platform 35
# 已就绪时直接退出，可安全配合 actions/cache 使用。
set -euo pipefail

SDK="${SDK:-$PWD/.android-sdk}"
BT_URL="${BT_URL:-https://dl.google.com/android/repository/build-tools_r35_linux.zip}"
PLATFORM_URL="${PLATFORM_URL:-https://dl.google.com/android/repository/platform-35_r02.zip}"

if [ -x "$SDK/android-14/aapt2" ] && [ -f "$SDK/android-35/android.jar" ]; then
  echo "SDK 已就绪：$SDK"
  exit 0
fi

mkdir -p "$SDK"
cd "$SDK"

if [ ! -x "$SDK/android-14/aapt2" ]; then
  echo "下载 build-tools 35 ..."
  curl -fL --retry 3 --retry-delay 2 -o build-tools.zip "$BT_URL"
  unzip -q -o build-tools.zip
  rm -f build-tools.zip
fi

if [ ! -f "$SDK/android-35/android.jar" ]; then
  echo "下载 platform 35 ..."
  curl -fL --retry 3 --retry-delay 2 -o platform-35.zip "$PLATFORM_URL"
  unzip -q -o platform-35.zip
  rm -f platform-35.zip
fi

# 不同批次压缩包的顶层目录名可能不同，统一成 build.sh 期望的 android-14 / android-35。
if [ ! -x "$SDK/android-14/aapt2" ]; then
  BT_DIR="$(find "$SDK" -maxdepth 2 -type f -name aapt2 -printf '%h\n' | head -1)"
  [ -n "$BT_DIR" ] || { echo "解压后找不到 aapt2" >&2; exit 1; }
  ln -sfn "$BT_DIR" "$SDK/android-14"
fi

if [ ! -f "$SDK/android-35/android.jar" ]; then
  JAR="$(find "$SDK" -maxdepth 3 -type f -name android.jar | head -1)"
  [ -n "$JAR" ] || { echo "解压后找不到 android.jar" >&2; exit 1; }
  ln -sfn "$(dirname "$JAR")" "$SDK/android-35"
fi

[ -x "$SDK/android-14/d8" ] || { echo "build-tools 缺少 d8" >&2; exit 1; }
[ -f "$SDK/android-35/android.jar" ] || { echo "platform 缺少 android.jar" >&2; exit 1; }
echo "SDK 就绪：$SDK"
