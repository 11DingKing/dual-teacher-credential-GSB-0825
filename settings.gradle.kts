plugins {
    // 当本机没有 JDK 21 时，允许 Gradle 自动下载匹配的 toolchain
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "dual-teacher-credential"
