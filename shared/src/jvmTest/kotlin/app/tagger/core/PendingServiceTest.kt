package app.tagger.core

import app.tagger.core.tags.TagEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 核心承诺的测试：**没有手动勾选的条目，文件一个字节都不会变。**
 *
 * 这是整个产品最关键的行为约束（用户明确要求"手动确认标签内容再写入"），
 * 所以必须有测试钉住，不能只靠界面自觉。
 */
class PendingServiceTest {

    private val fs: FileSystem = FileSystem.SYSTEM

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "tagger-pending-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    /**
     * 生成一个结构合法的 MP3。
     *
     * CI 上没有 ffmpeg，所以不能依赖它；这里优先用 ffmpeg 生成真实音频，
     * 不可用时手写 "ID3v2.3 空标签 + 3 个完整 MPEG-1 Layer III 帧"。
     * 注意必须写**完整帧长**（128kbps/44.1kHz = 417 字节/帧），
     * 只写半个帧会让引擎判定为损坏文件，测试就测不到写入逻辑了。
     */
    private fun makeMp3(target: File): Boolean {
        if (System.getenv("TAGGER_TEST_FORCE_FALLBACK") == null) {
            val ok = try {
                val process = ProcessBuilder(
                    listOf(
                        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                        "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                        "-c:a", "libmp3lame", "-b:a", "128k",
                        target.absolutePath,
                    ),
                ).redirectErrorStream(true).start()
                process.waitFor(60, TimeUnit.SECONDS)
                target.exists() && target.length() > 100
            } catch (ex: Exception) {
                false
            }
            if (ok) return true
        }
        return writeMinimalMp3(target)
    }

    /** 纯 Kotlin 生成最小可解析 MP3（不依赖外部工具）。 */
    private fun writeMinimalMp3(target: File): Boolean = try {
        val frame = ByteArray(417)
        // FF FB 90 00：MPEG-1 Layer III · 128kbps · 44.1kHz · 无填充
        frame[0] = 0xFF.toByte()
        frame[1] = 0xFB.toByte()
        frame[2] = 0x90.toByte()
        frame[3] = 0x00
        val out = java.io.ByteArrayOutputStream()
        // ID3v2.3 头，标签长度为 0
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 0))
        repeat(3) { out.write(frame) }
        target.writeBytes(out.toByteArray())
        true
    } catch (ex: Exception) {
        false
    }

    private fun newService(dir: File): PendingService {
        val client = NeteaseClient(HttpClient(OkHttp.create()))
        val settings = AppSettings(backup = false, writeCover = false)
        return PendingService(fs, client, RunLog(), settings)
    }

    @Test
    fun stagingDoesNotTouchFile() {
        val dir = tempDir()
        try {
            val file = File(dir, "a.mp3")
            assertTrue(makeMp3(file), "需要能生成测试音频")
            val before = file.readBytes()

            val service = newService(dir)
            val staged = service.stageFieldDecisions(
                listOf(FieldDecision(path = file.absolutePath, fieldKey = "title", value = "稻香")),
            )

            // 生成清单阶段绝不能碰文件
            assertTrue(file.readBytes().contentEquals(before), "生成清单时文件被修改了")
            assertEquals(1, staged.proposals.size)
            assertEquals(1, staged.summary.changeCount)
            assertTrue(staged.proposals.first().confirmed, "默认应为已勾选，交由用户取消")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unconfirmedIsNeverWritten() = runBlocking {
        val dir = tempDir()
        try {
            val file = File(dir, "b.mp3")
            assertTrue(makeMp3(file))
            val before = file.readBytes()

            val service = newService(dir)
            val staged = service.stageFieldDecisions(
                listOf(FieldDecision(path = file.absolutePath, fieldKey = "title", value = "稻香")),
            )
            // 全部取消勾选
            val result = service.commit(staged.selectNone())

            assertTrue(file.readBytes().contentEquals(before), "未勾选却写入了文件")
            assertEquals(0, result.summary.written)
            assertEquals(1, result.summary.skipped)
            assertEquals(0, result.summary.failed)
            assertEquals("未勾选", result.items.first().reason)
            assertTrue(file.readBytes().contentEquals(before))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun confirmedIsWritten() = runBlocking {
        val dir = tempDir()
        try {
            val file = File(dir, "c.mp3")
            assertTrue(makeMp3(file))
            val before = file.readBytes()

            val service = newService(dir)
            val staged = service.stageFieldDecisions(
                listOf(
                    FieldDecision(path = file.absolutePath, fieldKey = "title", value = "稻香"),
                    FieldDecision(path = file.absolutePath, fieldKey = "artist", value = "周杰伦"),
                ),
            )
            val result = service.commit(staged)

            assertEquals(1, result.summary.written, "勾选后应写入 1 个文件")
            assertEquals(0, result.summary.skipped)
            assertEquals(0, result.summary.failed)
            assertFalse(file.readBytes().contentEquals(before), "写入后文件应当变化")

            // 回读确认真的写进去了
            val snapshot = TagEngine.read(fs, file.absolutePath.toPath())
            assertEquals("稻香", snapshot.get("title"))
            assertEquals("周杰伦", snapshot.get("artist"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun dryRunLeavesFileUntouched() = runBlocking {
        val dir = tempDir()
        try {
            val file = File(dir, "d.mp3")
            assertTrue(makeMp3(file))
            val before = file.readBytes()

            val service = newService(dir)
            val staged = service.stageFieldDecisions(
                listOf(FieldDecision(path = file.absolutePath, fieldKey = "album", value = "魔杰座")),
            )
            val result = service.commit(staged, dryRun = true)

            assertTrue(result.summary.dryRun)
            assertTrue(file.readBytes().contentEquals(before), "预演不应修改文件")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun partialSelectionWritesOnlySelected() = runBlocking {
        val dir = tempDir()
        try {
            val first = File(dir, "e1.mp3")
            val second = File(dir, "e2.mp3")
            assertTrue(makeMp3(first))
            assertTrue(makeMp3(second))
            val secondBefore = second.readBytes()

            val service = newService(dir)
            val staged = service.stageFieldDecisions(
                listOf(
                    FieldDecision(path = first.absolutePath, fieldKey = "title", value = "晴天"),
                    FieldDecision(path = second.absolutePath, fieldKey = "title", value = "夜曲"),
                ),
            )
            // 只保留第一个
            val onlyFirst = staged.withConfirmation(
                mapOf(
                    staged.proposals[0].id to true,
                    staged.proposals[1].id to false,
                ),
            )
            val result = service.commit(onlyFirst)

            assertEquals(1, result.summary.written)
            assertEquals(1, result.summary.skipped)
            assertEquals("晴天", TagEngine.read(fs, first.absolutePath.toPath()).get("title"))
            assertTrue(second.readBytes().contentEquals(secondBefore), "未勾选的文件被改动了")
        } finally {
            dir.deleteRecursively()
        }
    }
}
