# Rust / UniFFI 生成的绑定代码经 JNI 调用，禁止混淆。
-keep class uniffi.hyperss_core.** { *; }
-keepnames class uniffi.hyperss_core.** { *; }
-dontwarn uniffi.hyperss_core.**

# JNA 通过 JNI 按名称反射访问内部字段（如 Pointer.peer），混淆会导致
# UnsatisfiedLinkError: Can't obtain peer field ID（JNA 官方要求全量保留）。
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn com.sun.jna.**

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**

# 保留反射使用的配置实体
-keep class com.hyperss.app.util.ConfigKeys { *; }