package app.tagger.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.tagger.core.AppSettings
import app.tagger.core.FileSystemProvider
import app.tagger.ui.FolderPicker
import app.tagger.ui.HttpClientFactory
import app.tagger.ui.TaggerApp
import app.tagger.ui.TaggerState
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import javax.swing.JFileChooser

/** 桌面入口（Windows / macOS / Linux）。界面与业务都在 shared 里。 */
fun main() {
    HttpClientFactory.install { OkHttp.create() }

    application {
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val scale = detectDisplayScale()
        val logicalWidth = bounds.width / scale
        val logicalHeight = bounds.height / scale
        val width = (bounds.width / scale * 0.95).coerceIn(860.0, 1280.0)
        val height = (bounds.height / scale * 0.95).coerceIn(560.0, 900.0)
        println(
            "屏幕(逻辑) ${logicalWidth.toInt()}x${logicalHeight.toInt()}dp · 缩放 ${"%.2f".format(scale)} · " +
                "窗口 ${width.toInt()}x${height.toInt()}dp",
        )

        val windowState = rememberWindowState(
            width = width.dp,
            height = height.dp,
            placement = WindowPlacement.Floating,
        )
        val state = remember {
            TaggerState(
                fileSystem = FileSystemProvider.system,
                folderPicker = DesktopFolderPicker(),
            )
        }

        Window(
            onCloseRequest = {
                AppSettings.save(FileSystemProvider.system, state.currentSettings())
                exitApplication()
            },
            title = "music-tag",
            state = windowState,
        ) {
            window.minimumSize = Dimension(820, 560)
            // 有上次的目录就自动扫描一次，省一次点击
            LaunchedEffect(Unit) {
                if (state.state.value.directory.isNotBlank()) state.scan()
            }
            TaggerApp(state = state)
        }
    }
}

/** 桌面端显示缩放：Windows 高 DPI 下 Compose 的 dp 与 AWT 的像素不一致。 */
private fun detectDisplayScale(): Double {
    val dpi = try {
        Toolkit.getDefaultToolkit().screenResolution
    } catch (ex: Exception) {
        96
    }
    return if (dpi in 48..480) dpi / 96.0 else 1.0
}

/** 用 Swing 的目录选择器实现桌面端的「选择目录」。 */
private class DesktopFolderPicker : FolderPicker {
    override suspend fun pickFolder(currentDirectory: String): String? = withContext(Dispatchers.IO) {
        val chooser = JFileChooser(
            if (currentDirectory.isNotBlank()) java.io.File(currentDirectory) else null,
        )
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "选择音乐目录"
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    }
}

