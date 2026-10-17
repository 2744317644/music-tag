buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // 放到 classpath 上，shared 模块里才能按需 apply com.android.library
        // （只在装了 Android SDK 时才真正启用，见 settings.gradle.kts）
        classpath("com.android.tools.build:gradle:8.5.2")
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
}
