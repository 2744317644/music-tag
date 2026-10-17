package app.tagger.core.tags

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.Path
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 标签引擎端到端测试：用 ffmpeg 生成真实音频 → 写入标签 + 封面 → 回读校验 → 幂等。
 * 只跑在 JVM 上（需要 ffmpeg 与真实文件系统）。
 */
class TagEngineTest {

    private val fs: FileSystem = FileSystem.SYSTEM

    private val spec = mapOf(
        "title" to "标题 Title",
        "artist" to "艺术家 A/B",
        "album" to "专辑 Album",
        "album_artist" to "专辑艺术家",
        "track" to "3/12",
        "disc" to "1/2",
        "year" to "2024",
        "genre" to "Pop",
        "composer" to "作曲人",
        "lyrics" to "[00:01.00]第一行歌词\n[00:05.00]第二行歌词",
    )

    /** 1x1 PNG。 */
    private val png1px: ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==",
    )

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "tagger-tags-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun makeAudio(target: File, kind: String): Boolean {
        val codec = when (kind) {
            "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", "128k")
            "flac" -> listOf("-c:a", "flac")
            "m4a" -> listOf("-c:a", "aac", "-b:a", "96k")
            "ogg" -> listOf("-c:a", "libvorbis", "-q:a", "2")
            else -> return false
        }
        return try {
            val process = ProcessBuilder(
                listOf(
                    "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                ) + codec + listOf(target.absolutePath),
            ).redirectErrorStream(true).start()
            process.waitFor(60, TimeUnit.SECONDS)
            target.exists() && target.length() > 100
        } catch (ex: Exception) {
            false
        }
    }

    private fun roundtrip(kind: String) {
        val dir = tempDir()
        try {
            val file = File(dir, "test.$kind")
            if (!makeAudio(file, kind)) {
                println("跳过 $kind：ffmpeg 不可用")
                return
            }
            val path: Path = file.absolutePath.toPath()
            val caps = TagEngine.capsFor(file.name)

            // 1) 写入
            val result = TagEngine.write(fs, path, spec, png1px, "image/png", backup = false)
            assertTrue(result.ok, "$kind 写入失败：${result.error}")
            assertTrue(result.changes.isNotEmpty(), "$kind 应有改动")

            // 2) 回读
            val snapshot = TagEngine.read(fs, path)
            assertEquals("", snapshot.error, "$kind 回读错误")
            for (key in listOf("title", "artist", "album", "album_artist", "year", "genre", "composer")) {
                assertEquals(spec[key], snapshot.get(key), "$kind 字段 $key 回读不一致")
            }
            assertTrue(snapshot.get("track").startsWith("3"), "$kind 音轨号：${snapshot.get("track")}")
            assertTrue(snapshot.get("lyrics").contains("第一行歌词"), "$kind 歌词：${snapshot.get("lyrics").take(20)}")
            if (caps.cover) {
                assertTrue(snapshot.hasCover, "$kind 应写入封面")
                val cover = TagEngine.readCover(fs, path)
                assertTrue(cover != null && cover.first.contentEquals(png1px), "$kind 封面字节应一致")
            }
            println("$kind: 格式=${snapshot.format} 时长=${"%.2f".format(snapshot.durationSeconds)}s 封面=${snapshot.coverSize}B")

            // 3) 幂等：再算一次差异应为空
            val again = TagEngine.write(fs, path, spec, png1px, "image/png", dryRun = true, backup = false)
            assertTrue(again.ok, "$kind 幂等检查失败：${again.error}")
            assertTrue(again.changes.isEmpty(), "$kind 重复写入应无差异：${again.changes}")

            // 4) 预演不得改动文件
            val before = file.readBytes()
            val dry = TagEngine.write(fs, path, mapOf("title" to "预演标题"), dryRun = true, backup = false)
            assertTrue(dry.ok && dry.changes.isNotEmpty(), "$kind 预演应报告改动")
            assertTrue(before.contentEquals(file.readBytes()), "$kind 预演不应改动文件")

            // 5) 只改指定字段，不清空其它字段
            val single = TagEngine.write(fs, path, mapOf("title" to "只改标题"), backup = false)
            assertTrue(single.ok, "$kind 单字段写入失败：${single.error}")
            val after = TagEngine.read(fs, path)
            assertEquals("只改标题", after.get("title"), "$kind 标题应更新")
            assertEquals(spec["album"], after.get("album"), "$kind 其它字段应保留")
            assertTrue(after.durationSeconds > 0.0, "$kind 应能读出时长")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mp3Roundtrip() = roundtrip("mp3")

    @Test
    fun flacRoundtrip() = roundtrip("flac")

    @Test
    fun m4aRoundtrip() = roundtrip("m4a")

    @Test
    fun oggReadsTags() {
        val dir = tempDir()
        try {
            val file = File(dir, "test.ogg")
            if (!makeAudio(file, "ogg")) {
                println("跳过 ogg：ffmpeg 不可用")
                return
            }
            val path = file.absolutePath.toPath()
            val snapshot = TagEngine.read(fs, path)
            assertEquals("", snapshot.error)
            assertTrue(snapshot.writable, "OGG 应可写")
            assertTrue(!snapshot.canEmbedCover, "OGG 不支持内嵌封面")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun backupIsCreated() {
        val dir = tempDir()
        try {
            val file = File(dir, "backup.mp3")
            if (!makeAudio(file, "mp3")) return
            val path = file.absolutePath.toPath()
            val result = TagEngine.write(fs, path, mapOf("title" to "备份测试"), backup = true)
            assertTrue(result.ok, result.error)
            assertTrue(result.backupPath.isNotEmpty(), "应产生备份路径")
            assertTrue(File(result.backupPath).exists(), "备份文件应存在：${result.backupPath}")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unsupportedFormatIsRejected() {
        val dir = tempDir()
        try {
            val file = File(dir, "fake.ape")
            file.writeBytes(ByteArray(64))
            val result = TagEngine.write(fs, file.absolutePath.toPath(), mapOf("title" to "x"))
            assertTrue(!result.ok && result.error.isNotEmpty(), "不支持的格式应拒绝写入")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun brokenFileReportsError() {
        val dir = tempDir()
        try {
            val file = File(dir, "broken.flac")
            file.writeBytes(byteArrayOf(0x66, 0x4C, 0x61, 0x43, 0, 0, 0, 0))
            val snapshot = TagEngine.read(fs, file.absolutePath.toPath())
            assertTrue(snapshot.error.isNotEmpty(), "损坏文件应报错")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun civilDateConversionIsCorrect() {
        // 1970-01-01 是第 0 天
        assertEquals(Triple(1970, 1, 1), TagEngine.civilFromDays(0))
        // 2024-02-29（闰日）
        assertEquals(Triple(2024, 2, 29), TagEngine.civilFromDays(19782))
        assertEquals(Triple(2000, 1, 1), TagEngine.civilFromDays(10957))
    }
}
