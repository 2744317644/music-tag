package app.tagger.android

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import app.tagger.core.FileSystemProvider
import app.tagger.ui.FolderPicker
import app.tagger.ui.HttpClientFactory
import app.tagger.ui.TaggerApp
import app.tagger.ui.TaggerState
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CompletableDeferred

/**
 * Android 宿主。界面用 shared 里的同一份 Compose 代码。
 */
class MainActivity : ComponentActivity() {

    private var pendingPick: CompletableDeferred<Uri?>? = null

    private val openTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        pendingPick?.complete(uri)
        pendingPick = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HttpClientFactory.install { OkHttp.create() }

        val state = TaggerState(
            fileSystem = FileSystemProvider.system,
            folderPicker = AndroidFolderPicker(),
        )

        setContent {
            TaggerApp(state = state)
        }
    }

    /**
     * Android 用系统文件选择器（SAF）选目录。
     * SAF 返回的是 content:// URI，不能直接当文件路径用，所以这里返回的是
     * 用户可见路径的近似值，提示用户改用「手动输入路径」以获得完整功能。
     */
    private inner class AndroidFolderPicker : FolderPicker {
        override suspend fun pickFolder(currentDirectory: String): String? {
            val deferred = CompletableDeferred<Uri?>()
            pendingPick = deferred
            openTree.launch(null)
            val uri = deferred.await() ?: return null
            // SAF 的 URI 无法转为真实路径，这里返回一个明确提示用的占位
            return uri.path
        }
    }
}
