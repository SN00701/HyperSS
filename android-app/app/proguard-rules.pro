# Rust / UniFFI 生成的绑定代码经 JNI 调用，禁止混淆。
-keep class uniffi.hyperss_core.** { *; }
-keepnames class uniffi.hyperss_core.** { *; }
-dontwarn uniffi.hyperss_core.**

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**

# 保留反射使用的配置实体
-keep class com.hyperss.app.util.ConfigKeys { *; }