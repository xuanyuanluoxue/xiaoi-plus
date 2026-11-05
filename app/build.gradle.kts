// ⚠️ 本文件不参与构建：真实构建入口是根目录 build.sh（javac + d8 + aapt2 + zipalign + apksigner，无 Gradle）。
// 这里只保留 Android Studio 打开仓库时需要的最小元数据。
// - 版本号唯一来源：app/src/main/AndroidManifest.xml 的 android:versionCode / android:versionName
//   （scripts/version.sh 与 CI tag 校验都读 manifest，不读这里）。
// - Xposed API 以源码桩编译（libs/xposed-stub-src），不走 maven 依赖。
plugins { id("com.android.application") }

android {
    namespace = "plus.xiaoi.module"
    compileSdk = 35

    defaultConfig {
        applicationId = "plus.xiaoi.module"
        // 与 AndroidManifest 的 uses-sdk 严格对齐（scripts/check-consistency.sh 会校验）：
        // 26 保住 127.0.0.1 明文 HTTP 推送与旧版窗口行为
        minSdk = 26
        targetSdk = 26
        // versionCode / versionName 故意不在此声明，避免与 manifest 漂移
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // 与 build.sh 的 -source/-target 1.8 一致
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
