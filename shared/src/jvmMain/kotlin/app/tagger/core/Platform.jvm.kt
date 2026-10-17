package app.tagger.core

import java.io.File

actual object Platform {
    actual val name: String = "Windows/JVM (${System.getProperty("os.name")})"
    actual val pathSeparator: String = File.separator

    actual fun appDataDirectory(appName: String): String {
        val base = System.getenv("APPDATA")
            ?: System.getProperty("user.home")
            ?: "."
        return File(base, appName).absolutePath
    }
}

