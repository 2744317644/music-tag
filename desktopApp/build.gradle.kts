import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm("jvm") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.okio)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.okhttp)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.tagger.desktop.EntryPointKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "NeteaseTagger"
            packageVersion = "1.0.0"
            description = "网易云音乐标签刮削器"
            vendor = "NeteaseTagger"
            windows {
                menu = true
                shortcut = true
                dirChooser = true
                upgradeUuid = "0d1f8f2a-5b1e-4a6c-9c3a-7f2e1b9d4c11"
            }
        }
    }
}

/**
 * 免安装的 fat jar：把所有依赖打进一个 jar，`java -jar` 直接运行。
 * 打包 exe/msi 需要 WiX（要访问 GitHub），网络不通时用这个。
 */
val fatJar by tasks.registering(Jar::class) {
    group = "distribution"
    description = "把所有依赖打成一个可执行 jar"
    archiveFileName.set("NeteaseTagger.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "app.tagger.desktop.EntryPointKt"
    }
    // 本项目自己的 class
    from(sourceSets.getByName("jvmMain").output)
    // 运行期依赖（含 shared 模块）
    val runtimeClasspath = configurations.getByName("jvmRuntimeClasspath")
    from(runtimeClasspath.map { if (it.isDirectory) it else zipTree(it) })
    // 排除签名文件，避免打包后出现 "Invalid signature file"
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    dependsOn("compileKotlinJvm", "jvmProcessResources")
}

tasks.register("portableJar") {
    group = "distribution"
    description = "生成可执行 fat jar"
    dependsOn(fatJar)
}
