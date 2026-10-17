plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false

    // AGP 在这里统一声明版本并只加载不应用：
    // - shared 模块需要它能被条件 apply（本机没有 Android SDK 时不启用）
    // - androidApp 用 alias 引用时也不会再和 classpath 上的版本冲突
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
