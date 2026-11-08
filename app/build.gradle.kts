import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// 版本号单一来源：AndroidManifest.xml。scripts/version.sh 与 verify-apk.sh 也读这里。
val manifestFile = file("src/main/AndroidManifest.xml")
fun manifestAttr(name: String): String =
    Regex("""android:$name="([^"]+)"""")
        .find(manifestFile.readText())
        ?.groupValues
        ?.get(1)
        ?: error("AndroidManifest.xml 缺少 android:$name")

val keystoreFile = rootProject.file("keystore/xiaoi.keystore")
val keystorePass: String? = System.getenv("KEYSTORE_PASSWORD")
    ?: System.getenv("KEYSTORE_PASS")
    ?: File(System.getProperty("user.home"), ".config/xiaoi/keystore_pass")
        .takeIf { it.isFile }
        ?.readText()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
val hasReleaseSigning = keystoreFile.isFile && !keystorePass.isNullOrEmpty()

android {
    namespace = "plus.xiaoi.module"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "plus.xiaoi.module"
        minSdk = 26
        targetSdk = 26
        versionCode = manifestAttr("versionCode").toInt()
        versionName = manifestAttr("versionName")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = keystoreFile
                storePassword = keystorePass
                keyAlias = System.getenv("KEY_ALIAS") ?: "xiaoi"
                keyPassword = System.getenv("KEY_PASSWORD") ?: keystorePass
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // P3：R8 代码收缩 + 资源收缩。keep 规则见 app/proguard-rules.pro；
            // 规则必须覆盖 assets/xposed_init、LSPosed 反射入口与字符串加载的组件名。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Compose 1.12.2 的 Android 产物是 JVM 11 字节码；AGP 9 要求 Java/Kotlin target 一致。
    // Java 源码仍不使用 Java 11+ 语言特性；minSdk 26 由 D8 desugar。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    lint {
        // 本模块不是 Play 分发应用；targetSdk=26 是既定约束，其他 lint 仍照常执行。
        disable += "ExpiredTargetSdkVersion"
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
}

kotlin {
    compilerOptions {
        // Compose 1.12.2 要求 JVM 11；计划文档的 1.8 目标无法编译。
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// Xposed 桩只用于编译，不进入 APK。
val xposedStubClasses = layout.buildDirectory.dir("xposed-stub/classes")
val xposedStubJar = tasks.register<Jar>("xposedStubJar") {
    archiveFileName.set("xposed-stub.jar")
    destinationDirectory.set(layout.buildDirectory.dir("xposed-stub"))
    from(xposedStubClasses)
}
val compileXposedStub = tasks.register<JavaCompile>("compileXposedStub") {
    source(fileTree("$rootDir/libs/xposed-stub-src") { include("**/*.java") })
    classpath = files(androidComponents.sdkComponents.bootClasspath.get())
    destinationDirectory.set(xposedStubClasses)
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
}
xposedStubJar.configure { dependsOn(compileXposedStub) }
tasks.withType<JavaCompile>().configureEach {
    if (name != "compileXposedStub") dependsOn(xposedStubJar)
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    debugImplementation(libs.compose.ui.tooling)

    compileOnly(files(xposedStubJar.flatMap { it.archiveFile }))
}
