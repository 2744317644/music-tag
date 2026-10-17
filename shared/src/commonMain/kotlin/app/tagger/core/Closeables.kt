package app.tagger.core

import okio.Closeable

/**
 * okio 的 [Closeable] 在 Kotlin/Native 上不是 AutoCloseable，
 * 所以 JVM 的 `use { }` 扩展在 iOS 目标里编译不过（会报 receiver type mismatch）。
 * 这里给一个三端通用的等价实现。
 */
internal inline fun <T : Closeable, R> T.useCloseable(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        close()
    }
}
