package app.tagger.core

import platform.Foundation.NSHomeDirectory

actual object Platform {
    actual val name: String = "iOS"
    actual val pathSeparator: String = "/"

    actual fun appDataDirectory(appName: String): String {
        // iOS 沙盒里用 Documents 目录
        val home = NSHomeDirectory()
        return "$home/Documents/$appName"
    }
}
