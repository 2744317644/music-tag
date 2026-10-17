package app.tagger.ios

import androidx.compose.ui.window.ComposeUIViewController
import app.tagger.core.FileSystemProvider
import app.tagger.ui.FolderPicker
import app.tagger.ui.HttpClientFactory
import app.tagger.ui.TaggerApp
import app.tagger.ui.TaggerState
import io.ktor.client.engine.darwin.Darwin
import platform.UIKit.UIViewController

/**
 * iOS 入口：把共享 Compose 界面包装成 UIViewController。
 * Swift 侧通过 MainViewControllerKt.MainViewController() 调用。
 *
 * iOS 沙盒不允许随意扫描任意外部目录，实际使用应接
 * UIDocumentPickerViewController 选目录；这里先用手输路径，
 * 保证三端代码路径完整、可编译。
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    TaggerApp(state = IosAppHolder.state)
}

/** 单例状态：避免 Compose 重组时反复创建网络客户端。 */
private object IosAppHolder {
    val state: TaggerState by lazy {
        HttpClientFactory.install { Darwin.create { } }
        TaggerState(
            fileSystem = FileSystemProvider.system,
            folderPicker = object : FolderPicker {
                override suspend fun pickFolder(currentDirectory: String): String? = null
            },
        )
    }
}
