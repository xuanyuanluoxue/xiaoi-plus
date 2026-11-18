import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.android.library)
}

/**
 * Android 适配器层（WP10）。
 *
 * 这里放所有「必须认识 Android 才能干活」的实现：配置读写、SharedPreferences 镜像、
 * 文件监听、通知、执行器、控制面 Binder、8770 服务、8771 桥与 Hook 宿主实现。
 * 端口定义在 `:domain` / `:application`，本模块只做实现。
 *
 * 禁止：Compose / Miuix / `plus.xiaoi.module.ui.*`（门禁 platform_no_compose / platform_no_ui）。
 */
android {
    // 与 :app 不同 namespace：AGP 要求模块间 namespace 唯一，R 生成在 .platform 包下。
    namespace = "plus.xiaoi.module.platform"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // 库模块不单独出 lint 报告；最终由 :app 的 lintVital 把关。
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Xposed 编译桩：`:platform`（Bridge / Config / Logs）与 `:app`（Hook）都需要。
// 桩只用于编译，不进入 APK；`XC_LoadPackage` 引用 android.content.pm.ApplicationInfo，
// 所以必须拿 SDK bootClasspath 才能编译。
val xposedStubClasses = layout.buildDirectory.dir("xposed-stub/classes")
val compileXposedStub = tasks.register<JavaCompile>("compileXposedStub") {
    source(fileTree("$rootDir/libs/xposed-stub-src") { include("**/*.java") })
    classpath = files(androidComponents.sdkComponents.bootClasspath.get())
    destinationDirectory.set(xposedStubClasses)
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
}
val xposedStubJar = tasks.register<Jar>("xposedStubJar") {
    dependsOn(compileXposedStub)
    archiveFileName.set("xposed-stub.jar")
    destinationDirectory.set(layout.buildDirectory.dir("xposed-stub"))
    from(xposedStubClasses)
}
tasks.withType<JavaCompile>().configureEach {
    if (name != "compileXposedStub") dependsOn(xposedStubJar)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":infra"))

    compileOnly(files(xposedStubJar.flatMap { it.archiveFile }))
}
