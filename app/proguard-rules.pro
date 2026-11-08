# P0: R8 关闭；P3 打开后必须在此保留 Xposed 入口与反射目标。
-keep class plus.xiaoi.module.Hook { *; }
-keep class plus.xiaoi.module.agent.** { *; }
-keep class plus.xiaoi.module.core.** { *; }
-keep class plus.xiaoi.module.control.** { *; }
-keepclassmembers class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keepattributes *Annotation*
-dontwarn de.robv.android.xposed.**
