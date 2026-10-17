package app.tagger.core

import android.content.Context
import java.io.File

/**
 * Android 端的 Context 需要由宿主（Activity / Application）注入。
 * 在 Application.onCreate() 里调用 [init] 一次即可。
 */
object AndroidContext {
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    internal fun require(): Context =
        appContext ?: error("请先在 Application 或 Activity 中调用 AndroidContext.init(context)")
}

actual object Platform {
    actual val name: String = "Android (${android.os.Build.VERSION.RELEASE})"
    actual val pathSeparator: String = File.separator

    actual fun appDataDirectory(appName: String): String {
        val context = AndroidContext.require()
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, appName).absolutePath
    }
}
