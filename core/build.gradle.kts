plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // 不用 toolchain：Windows 离线构建不应为了 Java 8 去下载 JDK。
    options.release.set(8)
}
