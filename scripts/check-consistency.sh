#!/usr/bin/env bash
# 工程一致性门禁：把"同一件事只有一种做法"变成可执行的检查。
#
# 覆盖：
#   1) 版本号 / minSdk / targetSdk 在 manifest 与 Gradle 元数据之间一致
#   2) 业务代码不再使用运行时 getIdentifier（资源改名必须编译期报错）
#   3) 后台任务/HTTP 客户端没有各自为政
#   4) layout / drawable 没有无人引用的死文件
#
# 用法：scripts/check-consistency.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
FAIL=0

pass() { echo "  ✓ $1"; }
fail() { FAIL=$((FAIL+1)); echo "  ✗ $1"; }

echo "==> 工程一致性检查"

# ---------------------------------------------------------------- 1) 版本与 SDK
MANIFEST="app/src/main/AndroidManifest.xml"
GRADLE="app/build.gradle.kts"

MANIFEST_MIN="$(sed -n 's/.*android:minSdkVersion="\([0-9]*\)".*/\1/p' "$MANIFEST" | head -1)"
MANIFEST_TARGET="$(sed -n 's/.*android:targetSdkVersion="\([0-9]*\)".*/\1/p' "$MANIFEST" | head -1)"
[ -n "$MANIFEST_MIN" ] && pass "manifest minSdk=$MANIFEST_MIN" || fail "manifest 缺少 minSdkVersion"
[ -n "$MANIFEST_TARGET" ] && pass "manifest targetSdk=$MANIFEST_TARGET" || fail "manifest 缺少 targetSdkVersion（必须显式声明，不能靠默认值）"

if [ -f "$GRADLE" ]; then
    GRADLE_MIN="$(sed -n 's/^[[:space:]]*minSdk[[:space:]]*=[[:space:]]*\([0-9]*\).*/\1/p' "$GRADLE" | head -1)"
    GRADLE_TARGET="$(sed -n 's/^[[:space:]]*targetSdk[[:space:]]*=[[:space:]]*\([0-9]*\).*/\1/p' "$GRADLE" | head -1)"
    [ "$GRADLE_MIN" = "$MANIFEST_MIN" ] \
        && pass "Gradle minSdk 与 manifest 一致（$GRADLE_MIN）" \
        || fail "Gradle minSdk=$GRADLE_MIN 与 manifest=$MANIFEST_MIN 不一致"
    [ "$GRADLE_TARGET" = "$MANIFEST_TARGET" ] \
        && pass "Gradle targetSdk 与 manifest 一致（$GRADLE_TARGET）" \
        || fail "Gradle targetSdk=$GRADLE_TARGET 与 manifest=$MANIFEST_TARGET 不一致"
fi

# ---------------------------------------------------------------- 2) 运行时资源查找
HITS="$(grep -rn 'getIdentifier' app/src/main/java 2>/dev/null || true)"
if [ -z "$HITS" ]; then
    pass "业务代码 0 处 getIdentifier（全部走 R）"
else
    fail "仍有 getIdentifier（必须改用 R）："
    echo "$HITS" | sed 's/^/      /'
fi

# ---------------------------------------------------------------- 3) 统一实现路径
THREADS="$(grep -rn 'new Thread(' app/src/main/java 2>/dev/null \
    | grep -v 'AppExecutors.java' | grep -v 'Bridge.java' || true)"
if [ -z "$THREADS" ]; then
    pass "业务代码后台任务统一走 AppExecutors（无裸 new Thread）"
else
    fail "发现裸 new Thread（App 进程请用 AppExecutors.io）："
    echo "$THREADS" | sed 's/^/      /'
fi

HTTP="$(grep -rln 'HttpURLConnection' app/src/main/java 2>/dev/null \
    | grep -v 'BridgeClient.java' | grep -v 'BridgePusher.java' | grep -v 'ModelValidator.java' || true)"
if [ -z "$HTTP" ]; then
    pass "HTTP 客户端收敛（桥访问走 BridgeClient，模型探测走 ModelValidator）"
else
    fail "发现额外的 HttpURLConnection 实现："
    echo "$HTTP" | sed 's/^/      /'
fi

TOAST="$(grep -rn 'Toast.makeText' app/src/main/java 2>/dev/null || true)"
if [ -z "$TOAST" ]; then
    pass "提示统一走 BaseActivity.showMessage（无系统 Toast）"
else
    fail "发现系统 Toast（请用 showMessage）："
    echo "$TOAST" | sed 's/^/      /'
fi

# ---------------------------------------------------------------- 4) 死资源
dead=0
for f in app/src/main/res/layout/*.xml; do
    [ -e "$f" ] || continue
    n="$(basename "$f" .xml)"
    if ! grep -rqE "R\.layout\.$n|@layout/$n" app/src/main/java app/src/main/res; then
        fail "无人引用的 layout：$n"
        dead=$((dead+1))
    fi
done
for f in app/src/main/res/drawable/*.xml; do
    [ -e "$f" ] || continue
    n="$(basename "$f" .xml)"
    if ! grep -rqE "R\.drawable\.$n|@drawable/$n" app/src/main/java app/src/main/res; then
        fail "无人引用的 drawable：$n"
        dead=$((dead+1))
    fi
done
[ "$dead" -eq 0 ] && pass "layout / drawable 无死资源"

echo
if [ "$FAIL" -eq 0 ]; then
    echo "==> 一致性检查通过"
else
    echo "==> 一致性检查失败：$FAIL 项"
    exit 1
fi
