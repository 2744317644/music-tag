rootProject.name = "music-tag"

pluginManagement {
    plugins {
        kotlin("multiplatform") version "2.1.21"
        kotlin("plugin.serialization") version "2.1.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
        id("org.jetbrains.compose") version "1.8.2"
        id("com.android.application") version "8.5.2"
        id("com.android.library") version "8.5.2"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

include(":shared")
include(":desktopApp")

/**
 * Android 模块只在装了 Android SDK 时纳入构建。
 * 这样在没装 SDK 的机器上（比如只做桌面版）`./gradlew` 不会因为
 * "SDK location not found" 直接失败。
 */
val androidEnabled = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()

if (androidEnabled) {
    include(":androidApp")
}
