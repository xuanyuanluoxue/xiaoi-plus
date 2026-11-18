#!/usr/bin/env bash
# 工程一致性门禁：把"同一件事只有一种做法"变成可执行的检查。
#
# 覆盖：
#   1) 版本号 / minSdk / targetSdk 在 manifest 与 Gradle 元数据之间一致
#   2) 业务代码不再使用运行时 getIdentifier（资源改名必须编译期报错）
#   3) 后台任务/HTTP 客户端没有各自为政
#   4) 分层边界：domain/application 不依赖 Android，UI 不直连配置仓库
#   4.1) Hook/控制面通过进程宿主端口解耦（WP2）
#   5) layout / drawable 没有无人引用的死文件
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

GRADLE_MIN=""
GRADLE_TARGET=""
if [ -f "$GRADLE" ]; then
    GRADLE_MIN="$(sed -n 's/^[[:space:]]*minSdk[[:space:]]*=[[:space:]]*\([0-9]*\).*/\1/p' "$GRADLE" | head -1)"
    GRADLE_TARGET="$(sed -n 's/^[[:space:]]*targetSdk[[:space:]]*=[[:space:]]*\([0-9]*\).*/\1/p' "$GRADLE" | head -1)"
fi

# AGP 9 起禁止在 Manifest 写 uses-sdk；Gradle 是唯一来源，但仍必须显式声明。
if [ -n "$MANIFEST_MIN" ] || [ -n "$MANIFEST_TARGET" ]; then
    [ -n "$MANIFEST_MIN" ] && pass "manifest minSdk=$MANIFEST_MIN" || fail "manifest 缺少 minSdkVersion"
    [ -n "$MANIFEST_TARGET" ] && pass "manifest targetSdk=$MANIFEST_TARGET" || fail "manifest 缺少 targetSdkVersion（必须显式声明，不能靠默认值）"
    [ -n "$GRADLE_MIN" ] && [ "$GRADLE_MIN" = "$MANIFEST_MIN" ] \
        && pass "Gradle minSdk 与 manifest 一致（$GRADLE_MIN）" \
        || fail "Gradle minSdk=$GRADLE_MIN 与 manifest=$MANIFEST_MIN 不一致"
    [ -n "$GRADLE_TARGET" ] && [ "$GRADLE_TARGET" = "$MANIFEST_TARGET" ] \
        && pass "Gradle targetSdk 与 manifest 一致（$GRADLE_TARGET）" \
        || fail "Gradle targetSdk=$GRADLE_TARGET 与 manifest=$MANIFEST_TARGET 不一致"
else
    [ -n "$GRADLE_MIN" ] && pass "Gradle minSdk=$GRADLE_MIN（AGP 9 单一来源）" \
        || fail "Gradle 缺少 minSdk（必须显式声明，不能靠默认值）"
    [ -n "$GRADLE_TARGET" ] && pass "Gradle targetSdk=$GRADLE_TARGET（AGP 9 单一来源）" \
        || fail "Gradle 缺少 targetSdk（必须显式声明，不能靠默认值）"
    # minSdk 仍为 26；targetSdk 34 用于预测性返回（33–36 可接受，35+ 会强制 edge-to-edge）。
    [ "$GRADLE_MIN" = "26" ] && [ "$GRADLE_TARGET" -ge 33 ] && [ "$GRADLE_TARGET" -le 36 ] \
        || fail "minSdk 必须为 26、targetSdk 需在 33–36（实际 $GRADLE_MIN/$GRADLE_TARGET）"
fi

# ---------------------------------------------------------------- 1.1) 启动入口
# 0.14 起 Compose MainActivity 必须是唯一 LAUNCHER；旧 XML 首页不能抢图标。
LAUNCHER_ACTIVITY="$(grep -B12 'android.intent.category.LAUNCHER' "$MANIFEST" 2>/dev/null \
    | sed -n 's/.*android:name="\(\.[^"]*\)".*/\1/p' | tail -1)"
if [ "$LAUNCHER_ACTIVITY" = ".MainActivity" ]; then
    pass "launcher 指向 .MainActivity"
else
    fail "launcher 必须是 .MainActivity（实际 '${LAUNCHER_ACTIVITY:-未找到}'）"
fi
if grep -A3 'android:name=".MainActivity"' "$MANIFEST" | grep -q 'android:exported="true"'; then
    pass "MainActivity exported=true"
else
    fail "MainActivity 必须 exported=true（launcher 需要）"
fi

# ---------------------------------------------------------------- 2) 运行时资源查找
HITS="$(grep -rn 'getIdentifier' app/src/main/java ui/src/main/java platform/src/main/java domain/src/main/java application/src/main/java infra/src/main/java 2>/dev/null || true)"
if [ -z "$HITS" ]; then
    pass "业务代码 0 处 getIdentifier（全部走 R）"
else
    fail "仍有 getIdentifier（必须改用 R）："
    echo "$HITS" | sed 's/^/      /'
fi

# ---------------------------------------------------------------- 3) 统一实现路径
THREADS="$(grep -rn 'new Thread(' app/src/main/java ui/src/main/java platform/src/main/java domain/src/main/java application/src/main/java infra/src/main/java 2>/dev/null \
    | grep -v 'AppExecutors.java' | grep -v 'Bridge.java' || true)"
if [ -z "$THREADS" ]; then
    pass "业务代码后台任务统一走 AppExecutors（无裸 new Thread）"
else
    fail "发现裸 new Thread（App 进程请用 AppExecutors.io）："
    echo "$THREADS" | sed 's/^/      /'
fi

HTTP="$(grep -rln 'HttpURLConnection' app/src/main/java ui/src/main/java platform/src/main/java domain/src/main/java application/src/main/java infra/src/main/java 2>/dev/null \
    | grep -v 'BridgeClient.java' | grep -v 'ModelValidator.java' || true)"
if [ -z "$HTTP" ]; then
    pass "HTTP 客户端收敛（桥访问走 BridgeClient，模型探测走 ModelValidator）"
else
    fail "发现额外的 HttpURLConnection 实现："
    echo "$HTTP" | sed 's/^/      /'
fi

TOAST="$(grep -rn 'Toast.makeText' app/src/main/java ui/src/main/java platform/src/main/java domain/src/main/java application/src/main/java infra/src/main/java 2>/dev/null || true)"
if [ -z "$TOAST" ]; then
    pass "提示统一走 Compose Snackbar（无系统 Toast）"
else
    fail "发现系统 Toast（请用 showMessage）："
    echo "$TOAST" | sed 's/^/      /'
fi

# ---------------------------------------------------------------- 4) 分层边界
if LAYER_OUT="$("$ROOT/scripts/check-layering.sh" 2>&1)"; then
    pass "分层边界检查（domain/application/infra/platform/ui）"
else
    fail "分层边界检查失败（见 scripts/check-layering.sh）"
    echo "$LAYER_OUT" | sed 's/^/      /'
fi

# ---------------------------------------------------------------- 4.1) Hook/控制面依赖方向（WP2）
strip_line_comments() { sed -E 's://.*$::' "$1" 2>/dev/null; }

HOOK_FILE="app/src/main/java/plus/xiaoi/module/Hook.java"
CLIENT_FILE="app/src/main/java/plus/xiaoi/module/bridge/BridgeControlClient.java"

HOOK_LEAK=""
if [ -f "$HOOK_FILE" ]; then
    HOOK_LEAK="$(strip_line_comments "$HOOK_FILE" \
        | grep -nE '(^|[^A-Za-z0-9_])(Bridge|Island)\.|plus\.xiaoi\.module\.(Bridge|Island)\.|import[[:space:]]+(plus\.xiaoi\.module\.)?(Bridge|Island)(;|[[:space:]])' \
        || true)"
fi
if [ -z "$HOOK_LEAK" ]; then
    pass "Hook 不依赖 Bridge/Island（只依赖进程宿主端口）"
else
    fail "Hook 仍直接依赖 Bridge/Island（应走 XiaoiProcessHost）："
    echo "$HOOK_LEAK" | sed 's/^/      /'
fi

CLIENT_LEAK=""
if [ -f "$CLIENT_FILE" ]; then
    CLIENT_LEAK="$(strip_line_comments "$CLIENT_FILE" \
        | grep -nE '(^|[^A-Za-z0-9_])(Hook|Bridge|Island)\.|plus\.xiaoi\.module\.(Hook|Bridge|Island)\.|import[[:space:]]+(plus\.xiaoi\.module\.)?(Hook|Bridge|Island)(;|[[:space:]])' \
        || true)"
fi
if [ -z "$CLIENT_LEAK" ]; then
    pass "控制面客户端不依赖 Hook/Bridge/Island（只依赖进程宿主端口）"
else
    fail "BridgeControlClient 仍直接依赖 Hook/Bridge/Island（应走 XiaoiProcessHost）："
    echo "$CLIENT_LEAK" | sed 's/^/      /'
fi

# H3：BridgeLifecycle 只依赖 BridgeRuntime，不直接调用 Bridge
LIFECYCLE_FILE="app/src/main/java/plus/xiaoi/module/bridge/BridgeLifecycle.java"
LIFECYCLE_LEAK=""
if [ -f "$LIFECYCLE_FILE" ]; then
    LIFECYCLE_LEAK="$(strip_line_comments "$LIFECYCLE_FILE" \
        | grep -nE '(^|[^A-Za-z0-9_])Bridge\.|plus\.xiaoi\.module\.Bridge\.|import[[:space:]]+(static[[:space:]]+)?(plus\.xiaoi\.module\.)?Bridge(;|[[:space:]]|\.)' \
        || true)"
fi
if [ -z "$LIFECYCLE_LEAK" ]; then
    pass "BridgeLifecycle 不依赖 Bridge（只依赖 BridgeRuntime 端口）"
else
    fail "BridgeLifecycle 仍直接依赖 Bridge（应走 BridgeRuntime）："
    echo "$LIFECYCLE_LEAK" | sed 's/^/      /'
fi

# ---------------------------------------------------------------- 5) 领域层无进程级可变状态
DOMAIN_STATE=""
for f in \
    core/BridgeEndpoint.java \
    core/LogSettings.java \
    core/BridgeStatus.java \
    agent/AgentRuntimeStatus.java ; do
    [ -e "domain/src/main/java/plus/xiaoi/module/$f" ] && DOMAIN_STATE="$DOMAIN_STATE $f"
done
STATIC_STATE="$(grep -rnE 'static[[:space:]]+volatile' domain/src/main/java 2>/dev/null || true)"
if [ -z "$DOMAIN_STATE" ] && [ -z "$STATIC_STATE" ]; then
    pass "领域层无进程级可变状态（快照留在 domain，持有者在上层）"
else
    fail "领域层出现进程级可变状态：${DOMAIN_STATE}${STATIC_STATE}"
fi

# ---------------------------------------------------------------- 6) 分享 authority 单一来源
AUTH_SRC="$(grep -oE 'AUTHORITY = "[^"]+"' application/src/main/java/plus/xiaoi/module/application/runtime/FileShare.java 2>/dev/null | head -1 | sed 's/.*"\(.*\)"/\1/')"
AUTH_MAN="$(grep -oE 'android:authorities="[^"]+"' app/src/main/AndroidManifest.xml 2>/dev/null | head -1 | sed 's/.*"\(.*\)"/\1/')"
if [ -n "$AUTH_SRC" ] && [ "$AUTH_SRC" = "$AUTH_MAN" ]; then
    pass "分享 authority 单一来源（$AUTH_SRC）"
else
    fail "分享 authority 不一致：FileShare=$AUTH_SRC manifest=$AUTH_MAN"
fi

# ---------------------------------------------------------------- 6) 死资源
dead=0
SRC_DIRS="app/src/main/java ui/src/main/java platform/src/main/java domain/src/main/java application/src/main/java infra/src/main/java"
RES_DIRS="app/src/main/res ui/src/main/res platform/src/main/res"
for res in $RES_DIRS; do
    [ -d "$res" ] || continue
    for f in "$res"/layout/*.xml; do
        [ -e "$f" ] || continue
        n="$(basename "$f" .xml)"
        if ! grep -rqE "R\.layout\.$n|@layout/$n" $SRC_DIRS $RES_DIRS; then
            fail "无人引用的 layout：$n（$res）"
            dead=$((dead+1))
        fi
    done
    for f in "$res"/drawable*/*.xml; do
        [ -e "$f" ] || continue
        n="$(basename "$f" .xml)"
        if ! grep -rqE "R\.drawable\.$n|@drawable/$n" $SRC_DIRS $RES_DIRS; then
            fail "无人引用的 drawable：$n（$res）"
            dead=$((dead+1))
        fi
    done
done
[ "$dead" -eq 0 ] && pass "layout / drawable 无死资源"

echo
if [ "$FAIL" -eq 0 ]; then
    echo "==> 一致性检查通过"
else
    echo "==> 一致性检查失败：$FAIL 项"
    exit 1
fi
