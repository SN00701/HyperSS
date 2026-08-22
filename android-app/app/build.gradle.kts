import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val versionProps = Properties().apply {
    val f = file("${rootProject.projectDir}/version.properties")
    if (f.exists()) load(f.inputStream())
}
val versionMajor = versionProps.getProperty("major", "0").toInt()
val versionMinor = versionProps.getProperty("minor", "0").toInt()
val versionPatch = versionProps.getProperty("patch", "1").toInt()
val versionSuffix = versionProps.getProperty("suffix", "beta")
val hyperssVersionName = "%d.%d.%d%s".format(versionMajor, versionMinor, versionPatch, versionSuffix)
val hyperssVersionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch

/** 从 local.properties 的 sdk.dir 下查找 NDK 根目录（取版本号最高的那个）。 */
fun findNdkRoot(sdkDir: String?): String? {
    if (sdkDir.isNullOrBlank()) return null
    val ndkRoot = file(sdkDir).resolve("ndk")
    if (!ndkRoot.isDirectory) return null
    return ndkRoot.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { it ->
            val version = it.name.substringBefore("-").toIntOrNull()
            version?.let { v -> v to it.absolutePath }
        }
        ?.maxByOrNull { it.first }
        ?.second
        ?: ndkRoot.listFiles()?.firstOrNull()?.absolutePath
}

android {
    namespace = "com.hyperss.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hyperss.app"
        minSdk = 33
        targetSdk = 37
        versionCode = hyperssVersionCode
        versionName = hyperssVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // 仅打包 64 位 ABI。
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        // 正式签名：优先使用 CI 注入的密钥（release.jks + 环境变量），本地未配置时回退 debug 签名，
        // 保证开源环境下 release APK 也可直接安装。真实密钥严禁入库（见 .gitignore）。
        create("release") {
            val releaseKeystore = rootProject.file("release.jks")
            if (releaseKeystore.exists() &&
                System.getenv("KEYSTORE_PASSWORD") != null &&
                System.getenv("KEY_ALIAS") != null
            ) {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            } else {
                storeFile = signingConfigs.getByName("debug").storeFile
                storePassword = signingConfigs.getByName("debug").storePassword
                keyAlias = signingConfigs.getByName("debug").keyAlias
                keyPassword = signingConfigs.getByName("debug").keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets {
        // UniFFI 生成的 Kotlin 绑定直接作为源码参与编译。
        named("main") {
            kotlin.directories.add("${rootProject.projectDir}/core-bindings")
        }
        // cargo-ndk 交叉编译产物输出到 jniLibs（arm64-v8a / x86_64，仅 64 位）。
        named("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // UniFFI 生成的 Kotlin 绑定基于 JNA 与原生库通信。
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    // miuix（MIUI/HyperOS 风格的 Compose 组件库）。
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-squircle:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.3")
}

// 交叉编译 Rust 核心库（需 cargo-ndk，见 docs 第 12 章）。
val cargoBuildAndroid = tasks.register<Exec>("cargoBuildAndroid") {
    workingDir = file("${rootProject.projectDir}/../rust-core")
    val localProps = Properties().apply {
        val f = file("${rootProject.projectDir}/local.properties")
        if (f.exists()) load(f.inputStream())
    }
    val sdkDir = localProps.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    findNdkRoot(sdkDir)?.let { environment("ANDROID_NDK_HOME", it) }
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", "${project.projectDir}/src/main/jniLibs",
        "build", "-p", "hyperss-core", "--release",
    )
}

tasks.named("preBuild") { dependsOn(cargoBuildAndroid) }