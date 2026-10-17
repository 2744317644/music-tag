package app.tagger.core

import okio.FileSystem

/**
 * 文件系统入口：okio 的 FileSystem.SYSTEM 在 JVM / Android / iOS 上都可用，
 * 这里包一层，方便测试时替换成假文件系统。
 */
object FileSystemProvider {
    var system: FileSystem = FileSystem.SYSTEM
        set(value) {
            field = value
        }
}
