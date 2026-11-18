#!/usr/bin/env bash
# 桌面单元测试：javac + java，零依赖（无 JUnit / 无 Gradle / 无 android.jar）。
# 只编译纯 JVM 层（Json / Http / Mcp / Logs / agent 契约）+ Xposed 桩 + 测试源码；
# 编译能通过本身就证明这几个类没有 android 引用。
# 真机行为回归见 scripts/contract-test.sh。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/tests/.build"

rm -rf "$OUT"; mkdir -p "$OUT"

# 先单编 domain（空 classpath）：证明 domain 不依赖 application / android / Xposed。
mkdir -p "$OUT/domain-only"
javac -nowarn -source 1.8 -target 1.8 -d "$OUT/domain-only" \
    $(find "$ROOT/domain/src/main/java" -name '*.java')

javac -nowarn -source 1.8 -target 1.8 -d "$OUT" \
    "$ROOT/libs/xposed-stub-src/robv/android/xposed/XposedBridge.java" \
    "$ROOT/libs/xposed-stub-src/robv/android/xposed/XC_MethodHook.java" \
    $(find "$ROOT/domain/src/main/java" "$ROOT/application/src/main/java" "$ROOT/infra/src/main/java" -name '*.java') \
    "$ROOT/app/src/main/java/plus/xiaoi/module/Logs.java" \
    $(find "$ROOT/tests/src" -name '*.java')

java --add-modules jdk.httpserver -cp "$OUT" plus.xiaoi.module.TestMain
