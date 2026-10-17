package app.tagger.core.tags

import app.tagger.core.Crypto

import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path

/**
 * 标签引擎的公共部分：格式探测、字段映射、读写结果。
 * 三种格式（ID3v2 / Vorbis / MP4）都实现同一套接口，保证三端行为一致。
 */
data class TagField(
    val key: String,
    val label: String,
    val queryKinds: List<FieldQuery>,
    val defaultKind: FieldQuery,
    val preferKeyword: List<String>,
    val description: String,
)

/** 查询维度：直接复用 core 里的定义，避免出现两套枚举。 */
typealias FieldQuery = app.tagger.core.QueryKind

/** 读写用到的字段规范。 */
object Fields {
    val order = listOf(
        "title", "artist", "album", "album_artist", "year", "track", "disc",
        "genre", "composer", "copyright", "lyrics",
    )

    private val specs = listOf(
        TagField("title", "标题", listOf(FieldQuery.Song, FieldQuery.Album, FieldQuery.Artist, FieldQuery.Playlist), FieldQuery.Song, listOf("title", "filename"), "用歌名搜索，取命中的歌曲标题"),
        TagField("artist", "艺术家", listOf(FieldQuery.Song, FieldQuery.Album, FieldQuery.Artist), FieldQuery.Song, listOf("artist", "title", "filename"), "用歌手名或歌名搜索，取演唱者"),
        TagField("album", "专辑", listOf(FieldQuery.Album, FieldQuery.Song, FieldQuery.Artist), FieldQuery.Album, listOf("album", "title", "filename"), "用专辑名搜索，取专辑标题"),
        TagField("album_artist", "专辑艺术家", listOf(FieldQuery.Album, FieldQuery.Song, FieldQuery.Artist), FieldQuery.Album, listOf("album_artist", "artist", "album", "filename"), "取专辑的署名艺术家"),
        TagField("year", "发行日期", listOf(FieldQuery.Album, FieldQuery.Song), FieldQuery.Album, listOf("album", "title", "filename"), "按专辑取发行年份"),
        TagField("track", "音轨号", listOf(FieldQuery.Album), FieldQuery.Album, listOf("album", "filename"), "按专辑曲目顺序取音轨号"),
        TagField("disc", "碟号", listOf(FieldQuery.Album), FieldQuery.Album, listOf("album", "filename"), "按专辑取碟号"),
        TagField("genre", "风格", listOf(FieldQuery.Album), FieldQuery.Album, listOf("album", "artist", "filename"), "网易云多数专辑没有风格数据"),
        TagField("composer", "作曲", listOf(FieldQuery.Song), FieldQuery.Song, listOf("title", "filename"), "单曲详情里的作曲者"),
        TagField("copyright", "版权 / 厂牌", listOf(FieldQuery.Song, FieldQuery.Album), FieldQuery.Song, listOf("title", "album", "filename"), "取唱片公司信息"),
        TagField("lyrics", "歌词", listOf(FieldQuery.Song, FieldQuery.Album, FieldQuery.Artist, FieldQuery.Playlist), FieldQuery.Song, listOf("title", "artist", "filename"), "搜索歌曲后取其歌词"),
    )

    fun all(): List<TagField> = specs
    fun get(key: String): TagField? = specs.firstOrNull { it.key == key }
    fun label(key: String): String = get(key)?.label ?: key
}

/** 各格式对封面 / 写入能力的声明。 */
data class FormatCaps(
    val write: Boolean,
    val cover: Boolean,
)

/** 一个音频文件的完整标签快照。 */
data class TagSnapshot(
    val path: String,
    val format: String,
    val durationSeconds: Double,
    val bitrate: Int,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val tags: Map<String, String>,
    val hasCover: Boolean,
    val coverMime: String,
    val coverSize: Int,
    val coverMd5: String,
    val writable: Boolean,
    val canEmbedCover: Boolean,
    val error: String = "",
) {
    fun get(key: String): String = tags[key] ?: ""
}

data class TagWriteResult(
    val ok: Boolean,
    val changes: List<TagChange>,
    val error: String = "",
    val backupPath: String = "",
    val notes: List<String> = emptyList(),
)

data class TagChange(
    val field: String,
    val old: String,
    val new: String,
)

/**
 * 格式无关的标签读写入口。
 * 内部按扩展名分派到 ID3v2 / Vorbis / MP4 实现。
 */
object TagEngine {

    val audioExtensions = setOf(
        ".mp3", ".flac", ".m4a", ".mp4", ".m4b", ".ogg", ".oga", ".opus",
    )

    fun isAudioFile(path: String): Boolean {
        val lower = path.lowercase()
        return audioExtensions.any { lower.endsWith(it) }
    }

    fun capsFor(path: String): FormatCaps {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".mp3") -> FormatCaps(write = true, cover = true)
            lower.endsWith(".flac") -> FormatCaps(write = true, cover = true)
            lower.endsWith(".m4a") || lower.endsWith(".mp4") || lower.endsWith(".m4b") -> FormatCaps(write = true, cover = true)
            lower.endsWith(".ogg") || lower.endsWith(".oga") || lower.endsWith(".opus") -> FormatCaps(write = true, cover = false)
            else -> FormatCaps(write = false, cover = false)
        }
    }

    fun read(fileSystem: FileSystem, path: Path): TagSnapshot {
        val name = path.name
        val caps = capsFor(name)
        val lower = name.lowercase()
        return try {
            when {
                lower.endsWith(".mp3") -> Id3v2.read(fileSystem, path)
                lower.endsWith(".flac") -> Vorbis.readFlac(fileSystem, path)
                lower.endsWith(".ogg") || lower.endsWith(".oga") || lower.endsWith(".opus") ->
                    Vorbis.readOgg(fileSystem, path)
                lower.endsWith(".m4a") || lower.endsWith(".mp4") || lower.endsWith(".m4b") ->
                    Mp4Tags.read(fileSystem, path)
                else -> errorSnapshot(path, "暂不支持该格式：$name")
            }.let { snapshot ->
                snapshot.copy(
                    writable = caps.write && snapshot.error.isEmpty(),
                    canEmbedCover = caps.cover,
                )
            }
        } catch (ex: Exception) {
            errorSnapshot(path, "解析失败: ${ex.message}")
        }
    }

    /**
     * 写入标签。只写 spec 里出现的字段，其余保持原样。
     * dryRun=true 时只计算差异，不落盘。
     */
    fun write(
        fileSystem: FileSystem,
        path: Path,
        spec: Map<String, String>,
        cover: ByteArray? = null,
        coverMime: String = "",
        dryRun: Boolean = false,
        backup: Boolean = true,
        backupDirectory: Path? = null,
    ): TagWriteResult {
        val name = path.name
        val lower = name.lowercase()
        val caps = capsFor(name)
        if (!caps.write) {
            return TagWriteResult(false, emptyList(), "暂不支持写入 $name 的格式")
        }

        val before = read(fileSystem, path)
        if (before.error.isNotEmpty()) {
            return TagWriteResult(false, emptyList(), before.error)
        }

        val notes = mutableListOf<String>()
        var effectiveCover = cover
        if (effectiveCover != null && !caps.cover) {
            notes += "$name 不支持内嵌封面，已跳过封面"
            effectiveCover = null
        }

        val changes = buildChanges(before, spec, effectiveCover, coverMime)

        if (changes.isEmpty()) {
            return TagWriteResult(true, emptyList(), notes = notes)
        }
        if (dryRun) {
            return TagWriteResult(true, changes, notes = notes)
        }

        // 备份
        var backupPath = ""
        if (backup) {
            try {
                val dir = backupDirectory ?: path.parent ?: error("无法确定备份目录")
                val backupDir = dir / ".tagger_backup"
                fileSystem.createDirectories(backupDir)
                val stamp = timestamp()
                val base = name.substringBeforeLast('.', name)
                val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
                var target = backupDir / "$base.$stamp$ext"
                var index = 1
                while (fileSystem.exists(target)) {
                    target = backupDir / "$base.$stamp.$index$ext"
                    index++
                }
                fileSystem.copy(path, target)
                backupPath = target.toString()
            } catch (ex: Exception) {
                return TagWriteResult(false, emptyList(), "备份失败，已中止写入: ${ex.message}")
            }
        }

        try {
            val normalizedSpec = spec.mapValues { (_, value) -> value.trim() }
            when {
                lower.endsWith(".mp3") -> Id3v2.write(fileSystem, path, normalizedSpec, effectiveCover, coverMime)
                lower.endsWith(".flac") -> Vorbis.writeFlac(fileSystem, path, normalizedSpec, effectiveCover, coverMime)
                lower.endsWith(".ogg") || lower.endsWith(".oga") || lower.endsWith(".opus") ->
                    Vorbis.writeOgg(fileSystem, path, normalizedSpec)
                else -> Mp4Tags.write(fileSystem, path, normalizedSpec, effectiveCover, coverMime)
            }
            // 回读校验
            val after = read(fileSystem, path)
            val mismatches = mutableListOf<String>()
            for ((key, value) in normalizedSpec) {
                if (value.isEmpty()) continue
                val actual = after.get(key)
                if (!equivalent(key, actual, value)) {
                    mismatches += "$key: 期望 $value 实际 $actual"
                }
            }
            if (effectiveCover != null && after.hasCover) {
                // 封面写入成功即可
            }
            if (mismatches.isNotEmpty()) {
                return TagWriteResult(false, changes, "写入后回读不一致: " + mismatches.joinToString("; "), backupPath, notes)
            }
            return TagWriteResult(true, changes, backupPath = backupPath, notes = notes)
        } catch (ex: Exception) {
            return TagWriteResult(false, changes, "${ex::class.simpleName}: ${ex.message}", backupPath, notes)
        }
    }

    fun readCover(fileSystem: FileSystem, path: Path): Pair<ByteArray, String>? {
        val lower = path.name.lowercase()
        return try {
            when {
                lower.endsWith(".mp3") -> Id3v2.readCover(fileSystem, path)
                lower.endsWith(".flac") -> Vorbis.readFlacCover(fileSystem, path)
                lower.endsWith(".ogg") || lower.endsWith(".oga") || lower.endsWith(".opus") -> null
                else -> Mp4Tags.readCover(fileSystem, path)
            }
        } catch (ex: Exception) {
            null
        }
    }

    private fun buildChanges(
        before: TagSnapshot,
        spec: Map<String, String>,
        cover: ByteArray?,
        coverMime: String,
    ): List<TagChange> {
        val changes = mutableListOf<TagChange>()
        for ((key, raw) in spec) {
            val value = raw.trim()
            val old = before.get(key)
            if (old != value) {
                changes += TagChange(key, old, value)
            }
        }
        if (cover != null) {
            val md5 = Crypto.md5(cover).toHexString()
            if (md5 != before.coverMd5) {
                changes += TagChange(
                    "cover",
                    if (before.hasCover) "${before.coverMime} ${before.coverSize} B" else "",
                    "$coverMime ${cover.size} B",
                )
            }
        }
        return changes
    }

    private fun equivalent(key: String, actual: String, expect: String): Boolean {
        if (actual == expect) return true
        return when (key) {
            "track", "disc" -> actual.substringBefore('/').trim() == expect.substringBefore('/').trim()
            "year" -> actual.startsWith(expect) || expect.startsWith(actual)
            "artist", "album_artist" ->
                actual.replace('／', '/').replace('、', '/').replace(';', '/').trim() ==
                    expect.replace('／', '/').replace('、', '/').replace(';', '/').trim()
            "lyrics" -> actual.replace("\r\n", "\n").trim() == expect.replace("\r\n", "\n").trim()
            else -> false
        }
    }

    private fun errorSnapshot(path: Path, message: String) = TagSnapshot(
        path = path.toString(),
        format = "未知",
        durationSeconds = 0.0,
        bitrate = 0,
        sampleRate = 0,
        channels = 0,
        bitsPerSample = 0,
        tags = emptyMap(),
        hasCover = false,
        coverMime = "",
        coverSize = 0,
        coverMd5 = "",
        writable = false,
        canEmbedCover = false,
        error = message,
    )
    @OptIn(kotlin.time.ExperimentalTime::class)
    internal fun timestamp(): String {
        // 依赖标准库的 UTC 时间，格式 yyyyMMdd-HHmmss
        val now = kotlin.time.Clock.System.now()
        val seconds = now.epochSeconds
        val days = seconds / 86400
        val secondOfDay = (seconds % 86400).toInt()
        val hour = secondOfDay / 3600
        val minute = (secondOfDay % 3600) / 60
        val second = secondOfDay % 60
        val (year, month, day) = civilFromDays(days)
        return "$year${month.toString().padStart(2, '0')}${day.toString().padStart(2, '0')}-" +
            "${hour.toString().padStart(2, '0')}${minute.toString().padStart(2, '0')}${second.toString().padStart(2, '0')}"
    }

    /** 由 Unix 天数换算民用历（Howard Hinnant 算法）。 */
    internal fun civilFromDays(epochDays: Long): Triple<Int, Int, Int> {
        var z = epochDays + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return Triple(year.toInt(), m.toInt(), d.toInt())
    }
}

internal fun ByteArray.toHexString(): String = joinToString("") { byte ->
    val v = byte.toInt() and 0xFF
    v.toString(16).padStart(2, '0')
}

internal fun Buffer.toByteArray(): ByteArray = this.readByteArray()

internal fun ByteArray.mime(): String {
    if (size >= 8 && this[0] == 0x89.toByte() && this[1] == 0x50.toByte()) return "image/png"
    if (size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) return "image/jpeg"
    if (size >= 12 && this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte()) return "image/webp"
    if (size >= 3 && this[0] == 'G'.code.toByte()) return "image/gif"
    return "image/jpeg"
}

/** okio 的 source 没有 skip，用 read 丢弃实现。 */
internal fun skipSource(source: okio.Source, offset: Long) {
    var remaining = offset
    val sink = okio.Buffer()
    while (remaining > 0) {
        val read = source.read(sink, remaining)
        if (read <= 0) break
        remaining -= read
        sink.clear()
    }
}

internal fun ByteArray.safeSlice(from: Int, until: Int): ByteArray {
    if (from >= size || until <= from) return ByteArray(0)
    return copyOfRange(from, minOf(until, size))
}

/** 按 ISO-8859-1 逐字节解码（MP4 原子名用）。 */
internal fun ByteArray.toLatin1(): String = buildString(size) {
    for (b in this@toLatin1) append((b.toInt() and 0xFF).toChar())
}