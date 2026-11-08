#!/usr/bin/env bash
# 桌面单元测试：javac + java，零依赖（无 JUnit / 无 Gradle / 无 android.jar）。
# 只编译纯 JVM 层（Json / Http / Mcp / Logs / agent 契约）+ Xposed 桩 + 测试源码；
# 编译能通过本身就证明这几个类没有 android 引用。
# 真机行为回归见 scripts/contract-test.sh。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/tests/.build"

rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -source 1.8 -target 1.8 -d "$OUT" \
    "$ROOT/libs/xposed-stub-src/robv/android/xposed/XposedBridge.java" \
    "$ROOT/libs/xposed-stub-src/robv/android/xposed/XC_MethodHook.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/Json.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/AgentValues.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/AlarmTimes.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/ActionRisk.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/AgentError.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/ActionRequest.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/ActionResult.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/ActionExecutor.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/ActionDescriptor.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/Actions.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/ActionRegistry.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/CanonicalArgs.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/ConfirmationManager.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/IdempotencyStore.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/AuditLog.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/NotifyHost.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/AskHost.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/BridgeAskHost.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/FallbackExecutor.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/RootCommands.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/AgentMcp.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/AgentHttpServer.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/AgentLanPolicy.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/agent/AgentRuntimeStatus.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/Http.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/Mcp.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/AppConfig.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/ConfigCodec.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/Logs.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/ModelValidator.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/UsageCounter.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/ThemeMode.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/AppStatus.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/StatusResolver.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/Hash.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/BridgeClient.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/Lan.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/core/ControlKeys.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/core/ConfigStore.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/core/BridgePolicy.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/core/ErrorCatalog.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/core/BridgeEndpoint.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/core/BridgeStatus.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/core/StateModel.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/core/RateLimiter.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/core/PermissionSnapshot.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/core/IssueResolver.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/core/CapabilityRegistry.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/core/ActivityFeed.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/core/ActivityHub.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/core/IssueInputFactory.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/core/NavStack.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/bridge/LeaderElection.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/control/UidGuard.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/control/InstanceRecord.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/control/BridgeRegistry.java" \
    "$ROOT/app/src/main/java/plus/xiaoi/module/control/ControlLogRing.java" \
    $(find "$ROOT/tests/src" -name '*.java')

java --add-modules jdk.httpserver -cp "$OUT" plus.xiaoi.module.TestMain
