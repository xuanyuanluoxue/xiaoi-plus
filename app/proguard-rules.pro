# ============================================================================
# xiaoi-plus release R8 规则
#
# 原则：只保留“运行时按名字/反射访问”的入口，不做整包 keep，也不使用
# -dontobfuscate。Compose / Miuix / AndroidX / Kotlin 的 keep 规则由各自
# AAR 的 consumer-rules.pro 提供，本文件只补本项目缺失的部分。
#
# 排查依据：
#   grep -rn "Class.forName|getDeclaredMethod|getMethod(" app/src/main/java
#   cat app/src/main/assets/xposed_init
# ============================================================================

# ---------------------------------------------------------------- 1. Xposed / LSPosed
# assets/xposed_init 里只声明 plus.xiaoi.module.Hook；LSPosed 通过
# Class.forName("plus.xiaoi.module.Hook") 实例化，类名、默认构造器和
# handleLoadPackage 的签名都不能被 R8 改写/内联。
-keep class plus.xiaoi.module.Hook { *; }
-keepclassmembers class * implements de.robv.android.xposed.IXposedHookLoadPackage {
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}

# Xposed API 是 compileOnly，运行时由 LSPosed 提供，不在 APK 内。
-dontwarn de.robv.android.xposed.**

# ---------------------------------------------------------------- 2. 字符串加载的组件
# ControlKeys.SERVICE_CLASS = "plus.xiaoi.module.control.BridgeControlService"，
# BridgeControlClient 用显式 ComponentName 绑定。服务虽在 Manifest 中声明，
# 这里仍显式 keep 类名，避免 R8 只依赖 Manifest 隐式规则。
-keep class plus.xiaoi.module.control.BridgeControlService { *; }

# ---------------------------------------------------------------- 3. 协议对象的公开字段
# Mcp / AgentMcp / ActionDescriptor 当前用手写 Json 逐字段序列化，没有反射；
# 这些 public 成员是 Agent 协议契约。保留字段名，避免将来改成反射/JSON 库时
# 出现“字段被改名”的静默故障（不保留方法体，不影响收缩）。
-keepclassmembers class plus.xiaoi.module.agent.ActionDescriptor { public <fields>; }
-keepclassmembers class plus.xiaoi.module.agent.ActionRequest { public <fields>; }
-keepclassmembers class plus.xiaoi.module.agent.ActionResult { public <fields>; }
-keepclassmembers class plus.xiaoi.module.agent.ActionRisk { public <fields>; }

# ---------------------------------------------------------------- 4. 调试信息
# 保留注解/泛型/内部类信息；SourceFile + LineNumberTable 让真机崩溃栈可读。
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# ---------------------------------------------------------------- 5. 已知可选依赖
# 不引入额外依赖；若某些 AAR 引用桌面粉末类，只静默缺失告警，不改变行为。
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
