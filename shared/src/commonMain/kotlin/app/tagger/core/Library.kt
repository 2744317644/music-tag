package app.tagger.core

import app.tagger.core.tags.FieldQuery
import app.tagger.core.tags.TagEngine
import app.tagger.core.tags.TagSnapshot
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * 曲库扫描 + 匹配打分。
 * 全部在 commonMain，三端共享。
 */
object Library {

    /** 递归扫描目录，返回音频文件列表（不读标签，快）。 */
    fun listAudioFiles(fileSystem: FileSystem, root: Path, recursive: Boolean = true): List<Path> {
        val result = mutableListOf<Path>()

        fun walk(directory: Path, depth: Int) {
            if (depth > 12) return
            val children = try {
                fileSystem.list(directory)
            } catch (ex: Exception) {
                return
            }
            for (child in children.sortedBy { it.name.lowercase() }) {
                val metadata = try {
                    fileSystem.metadataOrNull(child)
                } catch (ex: Exception) {
                    null
                } ?: continue
                if (metadata.isDirectory) {
                    if (recursive && !child.name.startsWith(".")) walk(child, depth + 1)
                } else if (TagEngine.isAudioFile(child.name)) {
                    result += child
                }
            }
        }

        walk(root, 0)
        return result
    }

    /** 读取一个文件的标签快照，转成曲库条目。 */
    fun toTrack(fileSystem: FileSystem, path: Path, root: Path?, snapshot: TagSnapshot): AudioTrack {
        val relativeDir = try {
            val base = root ?: path.parent ?: path
            val parent = path.parent ?: base
            if (parent == base) "" else parent.toString().removePrefix(base.toString()).trim('/', '\\')
        } catch (ex: Exception) {
            ""
        }
        val size = try {
            fileSystem.metadata(path).size ?: 0L
        } catch (ex: Exception) {
            0L
        }
        return AudioTrack(
            path = path.toString(),
            fileName = path.name,
            relDir = relativeDir,
            ext = path.name.substringAfterLast('.', "").lowercase(),
            format = snapshot.format,
            size = size,
            durationSeconds = snapshot.durationSeconds,
            bitrate = snapshot.bitrate,
            sampleRate = snapshot.sampleRate,
            channels = snapshot.channels,
            bitsPerSample = snapshot.bitsPerSample,
            writable = snapshot.writable,
            canEmbedCover = snapshot.canEmbedCover,
            tags = AudioTags(
                title = snapshot.get("title"),
                artist = snapshot.get("artist"),
                album = snapshot.get("album"),
                albumArtist = snapshot.get("album_artist"),
                track = snapshot.get("track"),
                disc = snapshot.get("disc"),
                year = snapshot.get("year"),
                genre = snapshot.get("genre"),
                composer = snapshot.get("composer"),
                copyright = snapshot.get("copyright"),
                comment = snapshot.get("comment"),
                lyrics = snapshot.get("lyrics"),
                hasCover = snapshot.hasCover,
                coverMime = snapshot.coverMime,
                coverSize = snapshot.coverSize,
                coverMd5 = snapshot.coverMd5,
            ),
            error = snapshot.error,
        )
    }
}

/** 文件名解析。 */
object FileNames {

    private val noise = Regex(
        "\\b(?:hires|hi-res|hr|flac|ape|wav|mp3|320k|320kbps|192k|128k|vbr|lossless|ost|soundtrack|official|mv|live|version|remaster(?:ed)?|explicit|clean|cd|cd1|disc\\s*\\d+|dvd|web|webrip|vinyl)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val trackPrefix = Regex("^\\s*(?:\\[\\s*\\d+\\s*\\]|\\(\\s*\\d+\\s*\\)|\\d{1,3}\\s*[-._、]\\s*|\\d{1,3}\\s+(?=\\D))")
    private val bracket = Regex("[\\[\\(\\{【（]([^\\[\\]\\(\\)\\{\\}【】（）]*)[\\]\\)\\}】）]")
    private val separator = Regex("\\s*[-–_|/·]\\s*")

    data class Parsed(val stem: String, val artist: String, val title: String, val clean: String)

    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var t = text.lowercase()
            .replace('’', '\'').replace('“', '"').replace('”', '"')
        t = t.replace(bracket) { match ->
            val inner = match.groupValues[1]
            if (inner.isNotEmpty() && inner.all { it.isDigit() }) match.value else " "
        }
        t = Regex("[\\s\\u3000]+").replace(t, " ")
        t = Regex("[!！?？。，,;；:：'\"`~@#$%^&*+=<>]").replace(t, "")
        t = noise.replace(t, " ")
        return Regex("\\s+").replace(t, " ").trim()
    }

    fun parse(fileName: String): Parsed {
        val stem = fileName.substringBeforeLast('.', fileName)
        val stripped = trackPrefix.replace(stem, "").trim()
        val clean = Regex("\\s+").replace(bracket.replace(stripped, " "), " ").trim()
        val parts = separator.split(clean).filter { it.isNotBlank() }.map { it.trim() }
        val artist: String
        val title: String
        when {
            parts.size >= 3 -> {
                artist = parts.first()
                title = parts.last()
            }
            parts.size == 2 -> {
                artist = parts[0]
                title = parts[1]
            }
            parts.size == 1 -> {
                artist = ""
                title = parts[0]
            }
            else -> {
                artist = ""
                title = clean
            }
        }
        return Parsed(stripped, artist, title, clean)
    }
}

/** 候选打分。 */
object Matcher {

    /** 归一化编辑距离相似度，带包含关系加成。 */
    fun similarity(a: String?, b: String?): Double {
        val na = FileNames.normalize(a)
        val nb = FileNames.normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        var ratio = levenshteinRatio(na, nb)
        if (na.contains(nb) || nb.contains(na)) {
            val shortLen = minOf(na.length, nb.length)
            val longLen = maxOf(na.length, nb.length)
            ratio = maxOf(ratio, 0.6 + 0.4 * shortLen / longLen)
        }
        return ratio
    }

    fun levenshteinRatio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        val distance = previous[b.length]
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length)
    }

    data class Score(val value: Double, val notes: List<String>)

    fun score(tags: AudioTags, song: SongInfo, fileName: String, durationSeconds: Double = 0.0): Score {
        val parsed = FileNames.parse(fileName)
        val titleRef = tags.title.ifBlank { parsed.title }
        val artistRef = tags.artist.ifBlank { parsed.artist }
        val albumRef = tags.album
        val notes = mutableListOf<String>()

        var titleSim = similarity(titleRef, song.name)
        for (alias in song.alias) titleSim = maxOf(titleSim, similarity(titleRef, alias))

        val artistSim = if (artistRef.isNotBlank() && song.artists.isNotEmpty()) {
            song.artists.maxOf { similarity(artistRef, it) }
        } else {
            notes += "缺少艺术家信息"
            0.0
        }

        val albumSim = if (albumRef.isNotBlank() && song.album.isNotBlank()) similarity(albumRef, song.album) else 0.0

        val durationScore = if (song.durationSeconds > 0 && durationSeconds > 0) {
            val delta = kotlin.math.abs(durationSeconds - song.durationSeconds)
            when {
                delta <= 2 -> 1.0
                delta <= 5 -> 0.75
                delta <= 10 -> 0.4
                else -> {
                    notes += "时长相差 ${delta.toInt()}s"
                    0.0
                }
            }
        } else {
            0.5
        }

        val total = 0.55 * titleSim + 0.25 * artistSim + 0.05 * albumSim + 0.15 * durationScore
        if (titleSim < 0.6) notes += "标题匹配度低"
        if (artistRef.isNotBlank() && artistSim < 0.5) notes += "艺术家不匹配"
        if (tags.title.isBlank() && parsed.artist.isBlank()) notes += "文件名解析信息较少"
        return Score(total.coerceIn(0.0, 1.0), notes)
    }

    fun bestOf(
        tags: AudioTags,
        candidates: List<SongInfo>,
        fileName: String,
        durationSeconds: Double = 0.0,
    ): Pair<SongInfo?, Score> {
        if (candidates.isEmpty()) return null to Score(0.0, listOf("无搜索结果"))
        var best: SongInfo? = null
        var bestScore = Score(0.0, emptyList())
        for (song in candidates) {
            val s = score(tags, song, fileName, durationSeconds)
            if (best == null || s.value > bestScore.value) {
                best = song
                bestScore = s
            }
        }
        return best to bestScore
    }
}

/** 歌词合并（原文 + 翻译）。 */
object Lyrics {

    private val lrcLine = Regex("^((?:\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\])+)(.*)$")
    private val tagPattern = Regex("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\]")

    fun merge(original: String, translated: String): String {
        if (translated.isBlank()) return original.trim()
        val map = mutableMapOf<String, String>()
        for (line in translated.split('\n')) {
            val match = lrcLine.matchEntire(line.trim()) ?: continue
            val text = match.groupValues[2].trim()
            if (text.isEmpty()) continue
            for (tag in tagPattern.findAll(match.groupValues[1])) map[tag.value] = text
        }
        if (map.isEmpty()) return original.trim()

        val output = mutableListOf<String>()
        for (line in original.split('\n')) {
            output += line.trimEnd()
            val match = lrcLine.matchEntire(line.trim()) ?: continue
            for (tag in tagPattern.findAll(match.groupValues[1])) {
                map[tag.value]?.let { output += it }
            }
        }
        return output.joinToString("\n").trim()
    }
}

/** 字段展示名与查询方式（界面用）。 */
object FieldCatalog {

    fun label(key: String): String = app.tagger.core.tags.Fields.label(key)

    fun queriesFor(key: String): List<FieldQuery> =
        app.tagger.core.tags.Fields.get(key)?.queryKinds ?: listOf(FieldQuery.Song)

    fun defaultQuery(key: String): FieldQuery =
        app.tagger.core.tags.Fields.get(key)?.defaultKind ?: FieldQuery.Song

    fun keywordFor(tags: AudioTags, fileName: String, fieldKey: String, explicit: String = ""): String {
        if (explicit.isNotBlank()) return explicit.trim()
        val spec = app.tagger.core.tags.Fields.get(fieldKey)
        val parsed = FileNames.parse(fileName)
        for (source in spec?.preferKeyword ?: listOf("title", "filename")) {
            val value = when (source) {
                "filename" -> parsed.title
                "album" -> tags.album
                else -> tags.get(source)
            }
            if (value.isNotBlank() && isUsableKeyword(value)) return value.trim()
        }
        return ""
    }

    /** 太短或纯符号的关键词搜不出东西。 */
    fun isUsableKeyword(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val trimmed = value.trim()
        if (trimmed.length < 2) return false
        return trimmed.any { it.isLetterOrDigit() || it.code > 0x2E80 }
    }
}
