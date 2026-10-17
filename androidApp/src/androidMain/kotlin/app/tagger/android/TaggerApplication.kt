package app.tagger.android

import android.app.Application
import app.tagger.core.AndroidContext

/** 宿主 Application：给共享层注入 Context。 */
class TaggerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidContext.init(this)
    }
}
