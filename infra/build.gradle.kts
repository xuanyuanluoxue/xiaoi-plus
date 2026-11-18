plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    // 端口定义在 :application（ConfigStorePort），实现在本模块：方向固定 infra -> application。
    implementation(project(":domain"))
    implementation(project(":application"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(8)
}
