#!/usr/bin/env bash
# 发布门禁：校验 APK 已签名、包名/versionCode 正确、签名证书指纹与固定值一致。
#
# 用法：
#   scripts/verify-apk.sh <apk> [--expect <sha256>] [--keystore <file>]
#                          [--package <pkg>] [--version-code <n>] [--skip-version]
#                          [--allow-any-signer]
#   scripts/verify-apk.sh --self-test <signed-apk>
#
# 指纹来源优先级：
#   --expect > 环境变量 XIAOI_SIGNER_SHA256 > --keystore + KEYSTORE_PASSWORD > scripts/expected-signer.sha256
#
# 说明：
#   - apksigner/aapt2 从 $SDK/android-14 找，找不到再回退 $ANDROID_HOME / $HOME/Android/Sdk
#     的 build-tools/*，最后回退 PATH；仍找不到就明确报错。
#   - --allow-any-signer 只跳过指纹比对，仍强制校验「已签名 + 包名 + versionCode」；
#     仅用于临时密钥构建（不能发布）。
#   - 不会打印 keystore 口令；临时文件写到 ~/xiaoi-plus-dev/tmp/ 或 mktemp。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PIN_FILE="$ROOT/scripts/expected-signer.sha256"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
DEFAULT_PACKAGE="plus.xiaoi.module"

APK=""
EXPECT=""
EXPECT_SET=0
KEYSTORE=""
PKG="$DEFAULT_PACKAGE"
VCODE=""
SKIP_VERSION=0
ALLOW_ANY=0
SELFTEST=""

usage() {
  sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-2}"
}

die() { echo "错误：$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --expect)
      [ $# -ge 2 ] || die "--expect 缺少参数"
      EXPECT="$2"
      EXPECT_SET=1
      shift 2
      ;;
    --expect=*)
      EXPECT="${1#--expect=}"
      EXPECT_SET=1
      shift
      ;;
    --keystore)
      [ $# -ge 2 ] || die "--keystore 缺少参数"
      KEYSTORE="$2"
      shift 2
      ;;
    --keystore=*)
      KEYSTORE="${1#--keystore=}"
      shift
      ;;
    --package)
      [ $# -ge 2 ] || die "--package 缺少参数"
      PKG="$2"
      shift 2
      ;;
    --package=*)
      PKG="${1#--package=}"
      shift
      ;;
    --version-code)
      [ $# -ge 2 ] || die "--version-code 缺少参数"
      VCODE="$2"
      shift 2
      ;;
    --version-code=*)
      VCODE="${1#--version-code=}"
      shift
      ;;
    --skip-version) SKIP_VERSION=1; shift ;;
    --allow-any-signer) ALLOW_ANY=1; shift ;;
    --self-test)
      [ $# -ge 2 ] || die "--self-test 缺少 <signed-apk>"
      SELFTEST="$2"
      shift 2
      ;;
    --self-test=*)
      SELFTEST="${1#--self-test=}"
      shift
      ;;
    -h|--help) usage 0 ;;
    --) shift; break ;;
    -*) die "未知参数：$1（--help 查看用法）" ;;
    *)
      [ -z "$APK" ] || die "只能指定一个 APK（多余参数：$1）"
      APK="$1"
      shift
      ;;
  esac
done
[ $# -eq 0 ] || die "多余参数：$*"

# 空 --expect 视为“不固定指纹”（与 --allow-any-signer 等价）
if [ "$EXPECT_SET" -eq 1 ] && [ -z "$(printf '%s' "$EXPECT" | tr -d '[:space:]:')" ]; then
  ALLOW_ANY=1
fi

if [ -z "$APK" ] && [ -z "$SELFTEST" ]; then
  usage 2
fi
if [ -n "$APK" ] && [ -n "$SELFTEST" ]; then
  die "--self-test 已包含 APK 参数，不要再单独传位置参数"
fi

# ---------------------------------------------------------------- SDK / build-tools
APKSIGNER=""
AAPT2=""

try_bt() {
  local dir="$1"
  [ -n "$dir" ] || return 1
  if [ -x "$dir/apksigner" ] && [ -x "$dir/aapt2" ]; then
    APKSIGNER="$dir/apksigner"
    AAPT2="$dir/aapt2"
    return 0
  fi
  return 1
}

try_sdk() {
  local sdk="$1"
  [ -n "$sdk" ] || return 1
  try_bt "$sdk/android-14" && return 0
  local d
  for d in $(ls -d "$sdk"/build-tools/* 2>/dev/null | sort -V -r); do
    try_bt "$d" && return 0
  done
  return 1
}

resolve_tools() {
  if [ -n "${SDK:-}" ] && try_sdk "$SDK"; then return 0; fi
  if [ -n "${ANDROID_HOME:-}" ] && try_sdk "$ANDROID_HOME"; then return 0; fi
  if try_sdk "$HOME/Android/Sdk"; then return 0; fi
  if command -v apksigner >/dev/null 2>&1 && command -v aapt2 >/dev/null 2>&1; then
    APKSIGNER="$(command -v apksigner)"
    AAPT2="$(command -v aapt2)"
    return 0
  fi
  die "找不到 apksigner/aapt2。请设置 SDK=<Android SDK 根目录>（期望 \$SDK/android-14/apksigner 与 aapt2），或安装 Android build-tools 并加入 PATH。"
}

# ---------------------------------------------------------------- 工具函数
norm_sha() {
  printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]:'
}

manifest_code() {
  local code
  code="$(sed -n 's/.*android:versionCode="\([0-9][0-9]*\)".*/\1/p' "$MANIFEST" | head -1)"
  [ -n "$code" ] || die "无法从 $MANIFEST 读取 versionCode"
  printf '%s' "$code"
}

new_tmp_dir() {
  local base="${XIAOI_TMPDIR:-$HOME/xiaoi-plus-dev/tmp}"
  if mkdir -p "$base" 2>/dev/null; then
    mktemp -d "$base/xiaoi-verify.XXXXXX"
  else
    mktemp -d
  fi
}

pin_from_file() {
  [ -f "$PIN_FILE" ] || return 1
  sed 's/#.*//' "$PIN_FILE" | grep -oE '[0-9a-fA-F]{64}' | head -1 || true
}

fingerprint_from_keystore() {
  local ks="$1"
  [ -f "$ks" ] || die "keystore 不存在：$ks"
  : "${KEYSTORE_PASSWORD:?需要 KEYSTORE_PASSWORD 才能从 keystore 提取证书指纹（口令不会被打印）}"
  command -v keytool >/dev/null 2>&1 || die "找不到 keytool（需要 JDK）"

  local tmp pass_out fp
  tmp="$(mktemp "${TMPDIR:-/tmp}/xiaoi-kpass.XXXXXX")"
  chmod 600 "$tmp"
  printf '%s' "$KEYSTORE_PASSWORD" > "$tmp"

  pass_out=""
  if [ -n "${KEYSTORE_ALIAS:-}" ]; then
    pass_out="$(keytool -list -v -keystore "$ks" -storepass:file "$tmp" -alias "$KEYSTORE_ALIAS" 2>/dev/null || true)"
  else
    pass_out="$(keytool -list -v -keystore "$ks" -storepass:file "$tmp" 2>/dev/null || true)"
  fi
  rm -f "$tmp"

  fp="$(printf '%s\n' "$pass_out" | sed -n 's/.*SHA256: *//p' | head -1)"
  [ -n "$fp" ] || die "无法从 keystore 读取证书指纹（检查 KEYSTORE_PASSWORD / KEYSTORE_ALIAS）"
  printf '%s' "$fp"
}

# 输出归一化后的指纹；--allow-any-signer 时输出空串
resolve_expected() {
  if [ "$ALLOW_ANY" -eq 1 ]; then
    echo ""
    return 0
  fi
  if [ "$EXPECT_SET" -eq 1 ]; then
    norm_sha "$EXPECT"
    return 0
  fi
  if [ -n "${XIAOI_SIGNER_SHA256:-}" ]; then
    norm_sha "$XIAOI_SIGNER_SHA256"
    return 0
  fi
  if [ -n "$KEYSTORE" ]; then
    norm_sha "$(fingerprint_from_keystore "$KEYSTORE")"
    return 0
  fi
  local pinned
  pinned="$(pin_from_file)"
  [ -n "$pinned" ] || die "没有可用指纹：请传 --expect、设置 XIAOI_SIGNER_SHA256，或恢复 $PIN_FILE"
  norm_sha "$pinned"
}

# ---------------------------------------------------------------- 核心校验
# 参数：<apk> <expected> <pkg> <versionCode> <skipVersion>
verify_apk() {
  local apk="$1" expected="$2" pkg="$3" vcode="$4" skip_version="$5"
  local fail=0 signer="" sign_out="" sign_rc=0 badging="" apk_pkg="" apk_code=""

  echo "APK          : $apk"

  sign_out="$("$APKSIGNER" verify --print-certs "$apk" 2>&1)" || sign_rc=$?
  if [ "$sign_rc" -eq 0 ]; then
    signer="$(printf '%s\n' "$sign_out" | sed -n 's/.*SHA-256 digest: *//p' | head -1)"
    signer="$(norm_sha "$signer")"
    if [ -z "$signer" ]; then
      fail=$((fail+1))
      echo "签名         : FAIL（apksigner 未输出证书指纹）"
    else
      echo "签名         : 已签名"
    fi
  else
    fail=$((fail+1))
    echo "签名         : FAIL（未签名或签名损坏）"
    printf '%s\n' "$sign_out" | sed -n '1,3p' | sed 's/^/               /'
  fi

  badging="$("$AAPT2" dump badging "$apk" 2>/dev/null || true)"
  apk_pkg="$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
  apk_code="$(printf '%s\n' "$badging" | sed -n "s/^package: .*versionCode='\([0-9][0-9]*\)'.*/\1/p" | head -1)"

  if [ -z "$apk_pkg" ] || [ -z "$apk_code" ]; then
    fail=$((fail+1))
    echo "包信息       : FAIL（aapt2 读不到 package/versionCode）"
  fi

  if [ "$apk_pkg" = "$pkg" ]; then
    echo "包名         : $apk_pkg"
  else
    fail=$((fail+1))
    echo "包名         : FAIL（实际 ${apk_pkg:-未知}，期望 $pkg）"
  fi

  if [ "$skip_version" -eq 1 ]; then
    echo "versionCode  : ${apk_code:-未知}（--skip-version，未比对）"
  elif [ "$apk_code" = "$vcode" ]; then
    echo "versionCode  : $apk_code"
  else
    fail=$((fail+1))
    echo "versionCode  : FAIL（实际 ${apk_code:-未知}，期望 $vcode）"
  fi

  if [ -z "$expected" ]; then
    echo "证书 SHA-256 : ${signer:-未知}（未固定指纹，仅确认已签名）"
  elif [ -z "$signer" ]; then
    fail=$((fail+1))
    echo "证书 SHA-256 : FAIL（无有效签名证书，无法比对指纹）"
  elif [ "$signer" = "$expected" ]; then
    echo "证书 SHA-256 : $signer"
  else
    fail=$((fail+1))
    echo "证书 SHA-256 : FAIL（与固定指纹不一致）"
    echo "               实际: $signer"
    echo "               期望: $expected"
  fi

  echo
  if [ "$fail" -eq 0 ]; then
    echo "结论         : PASS"
    return 0
  fi
  echo "结论         : FAIL（$fail 项）"
  return 1
}

# ---------------------------------------------------------------- 自测（不依赖设备）
self_test() {
  local signed="$1"
  local expected="$2"
  local dir unsigned tmp out rc fails=0

  [ -f "$signed" ] || die "自测 APK 不存在：$signed"
  [ -n "$expected" ] || die "自测必须固定指纹（不要用 --allow-any-signer / 空 --expect）"
  command -v keytool >/dev/null 2>&1 || die "自测需要 keytool（JDK）"

  dir="$(cd "$(dirname "$signed")" && pwd)"
  unsigned="$dir/xiaoi-plus-aligned.apk"
  tmp="$(new_tmp_dir)"
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" EXIT

  echo "==> verify-apk 自测"
  echo "已签名 APK   : $signed"
  echo "未签名 APK   : $unsigned"
  echo "固定指纹     : $expected"
  echo

  # 1) 正式签名 APK 必须通过
  rc=0
  out="$(verify_apk "$signed" "$expected" "$PKG" "$(manifest_code)" "$SKIP_VERSION" 2>&1)" || rc=$?
  if [ "$rc" -eq 0 ]; then
    echo "  PASS [1/4] 正式签名 APK 通过全部校验"
  else
    fails=$((fails+1))
    echo "  FAIL [1/4] 正式签名 APK 未通过"
    printf '%s\n' "$out" | sed 's/^/           /'
  fi

  # 2) 未签名 APK 必须被拒绝
  if [ ! -f "$unsigned" ]; then
    fails=$((fails+1))
    echo "  FAIL [2/4] 找不到未签名产物 $unsigned（先跑 ./build.sh）"
  else
    rc=0
    out="$(verify_apk "$unsigned" "$expected" "$PKG" "$(manifest_code)" "$SKIP_VERSION" 2>&1)" || rc=$?
    if [ "$rc" -ne 0 ] && printf '%s\n' "$out" | grep -q "未签名或签名损坏"; then
      echo "  PASS [2/4] 未签名 APK 被拒绝（签名校验失败）"
    else
      fails=$((fails+1))
      echo "  FAIL [2/4] 未签名 APK 没有被正确拒绝"
      printf '%s\n' "$out" | sed 's/^/           /'
    fi
  fi

  # 3) 临时密钥签名的副本必须因指纹不符被拒绝
  if [ ! -f "$unsigned" ]; then
    fails=$((fails+1))
    echo "  FAIL [3/4] 缺少未签名产物，无法做临时密钥测试"
  else
    local pass="self-test-$RANDOM$RANDOM"
    if ! keytool -genkeypair -keystore "$tmp/other.keystore" \
        -storepass "$pass" -keypass "$pass" -alias other \
        -keyalg RSA -keysize 2048 -validity 1 \
        -dname "CN=XiaoAi Plus Self Test, O=Test, C=CN" >/dev/null 2>&1; then
      fails=$((fails+1))
      echo "  FAIL [3/4] 无法生成临时 keystore"
    elif ! "$APKSIGNER" sign --ks "$tmp/other.keystore" \
        --ks-pass "pass:$pass" --key-pass "pass:$pass" \
        --out "$tmp/other.apk" "$unsigned" >/dev/null 2>&1; then
      fails=$((fails+1))
      echo "  FAIL [3/4] 无法用临时密钥签名副本"
    else
      rc=0
      out="$(verify_apk "$tmp/other.apk" "$expected" "$PKG" "$(manifest_code)" "$SKIP_VERSION" 2>&1)" || rc=$?
      if [ "$rc" -ne 0 ] && printf '%s\n' "$out" | grep -q "证书 SHA-256 : FAIL"; then
        echo "  PASS [3/4] 临时密钥 APK 因指纹不符被拒绝"
      else
        fails=$((fails+1))
        echo "  FAIL [3/4] 临时密钥 APK 没有被指纹校验拒绝"
        printf '%s\n' "$out" | sed 's/^/           /'
      fi
    fi
  fi

  # 4) 临时文件清理
  rm -rf "$tmp"
  trap - EXIT
  if [ ! -e "$tmp" ]; then
    echo "  PASS [4/4] 临时文件已清理"
  else
    fails=$((fails+1))
    echo "  FAIL [4/4] 临时文件未清理：$tmp"
  fi

  echo
  if [ "$fails" -eq 0 ]; then
    echo "==> 自测 PASS"
    return 0
  fi
  echo "==> 自测 FAIL（$fails 项）"
  return 1
}

# ---------------------------------------------------------------- main
resolve_tools

if [ -n "$SELFTEST" ]; then
  if [ "$ALLOW_ANY" -eq 1 ]; then
    die "自测必须固定指纹，不能用 --allow-any-signer 或空 --expect"
  fi
  EXPECTED="$(resolve_expected)"
  self_test "$SELFTEST" "$EXPECTED"
  exit $?
fi

[ -f "$APK" ] || die "APK 不存在：$APK"

if [ "$ALLOW_ANY" -eq 1 ]; then
  echo "警告：未固定签名指纹，只校验已签名/包名/versionCode；该产物不能用于正式发布。" >&2
fi

EXPECTED="$(resolve_expected)"
if [ "$SKIP_VERSION" -eq 0 ]; then
  [ -n "$VCODE" ] || VCODE="$(manifest_code)"
fi

echo "==> APK 校验（签名 / 包名 / 版本 / 指纹）"
verify_apk "$APK" "$EXPECTED" "$PKG" "$VCODE" "$SKIP_VERSION"
