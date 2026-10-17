package app.tagger.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** 统一的标签视图：空字符串表示字段不存在。 */
data class AudioTags(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val track: String = "",
    val disc: String = "",
    val year: String = "",
    val genre: String = "",
    val composer: String = "",
    val copyright: String = "",
    val comment: String = "",
    val lyrics: String = "",
    val hasCover: Boolean = false,
    val coverMime: String = "",
    val coverSize: Int = 0,
    val coverMd5: String = "",
) {
    fun get(key: String): String = when (key) {
        "title" -> title
        "artist" -> artist
        "album" -> album
        "album_artist" -> albumArtist
        "track" -> track
        "disc" -> disc
        "year" -> year
        "genre" -> genre
        "composer" -> composer
        "copyright" -> copyright
        "comment" -> comment
        "lyrics" -> lyrics
        else -> ""
    }

    fun with(key: String, value: String): AudioTags = when (key) {
        "title" -> copy(title = value)
        "artist" -> copy(artist = value)
        "album" -> copy(album = value)
        "album_artist" -> copy(albumArtist = value)
        "track" -> copy(track = value)
        "disc" -> copy(disc = value)
        "year" -> copy(year = value)
        "genre" -> copy(genre = value)
        "composer" -> copy(composer = value)
        "copyright" -> copy(copyright = value)
        "comment" -> copy(comment = value)
        "lyrics" -> copy(lyrics = value)
        else -> this
    }

    fun toMap(): Map<String, String> = mapOf(
        "title" to title,
        "artist" to artist,
        "album" to album,
        "album_artist" to albumArtist,
        "track" to track,
        "disc" to disc,
        "year" to year,
        "genre" to genre,
        "composer" to composer,
        "copyright" to copyright,
        "comment" to comment,
        "lyrics" to lyrics,
    )
}

enum class TrackStatus(val label: String) {
    Pending("未匹配"),
    Matched("已匹配"),
    Ambiguous("待确认"),
    NotFound("未找到"),
    Written("已写入"),
    Error("出错"),
}

/** 曲库里的一个音频文件。 */
data class AudioTrack(
    val path: String,
    val fileName: String,
    val relDir: String = "",
    val ext: String = "",
    val format: String = "",
    val size: Long = 0,
    val modified: Long = 0,
    val durationSeconds: Double = 0.0,
    val bitrate: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitsPerSample: Int = 0,
    val writable: Boolean = true,
    val canEmbedCover: Boolean = true,
    val tags: AudioTags = AudioTags(),
    val error: String = "",
    val status: TrackStatus = TrackStatus.Pending,
    val score: Double = 0.0,
    val query: String = "",
)

/** 网易云歌曲。 */
data class SongInfo(
    val id: Long = 0,
    val name: String = "",
    val alias: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val artistIds: List<Long> = emptyList(),
    val album: String = "",
    val albumId: Long = 0,
    val picUrl: String = "",
    val durationMs: Long = 0,
    val trackNo: Int = 0,
    val discNo: Int = 0,
    val totalTracks: Int = 0,
    val albumArtist: String = "",
    val year: Int = 0,
    val company: String = "",
    val genre: String = "",
    val composer: String = "",
    val lyric: String = "",
    val lyricTranslated: String = "",
    val lyricMerged: String = "",
) {
    val artistsText: String get() = artists.joinToString("/")
    val durationSeconds: Double get() = durationMs / 1000.0
}

data class AlbumInfo(
    val id: Long = 0,
    val name: String = "",
    val artist: String = "",
    val artists: List<String> = emptyList(),
    val picUrl: String = "",
    val year: Int = 0,
    val publishTimeMs: Long = 0,
    val company: String = "",
    val description: String = "",
    val size: Int = 0,
    val tracks: List<SongInfo> = emptyList(),
)

data class ArtistInfo(
    val id: Long = 0,
    val name: String = "",
    val alias: List<String> = emptyList(),
    val picUrl: String = "",
    val albumSize: Int = 0,
    val musicSize: Int = 0,
    val songs: List<SongInfo> = emptyList(),
)

data class PlaylistInfo(
    val id: Long = 0,
    val name: String = "",
    val creator: String = "",
    val picUrl: String = "",
    val trackCount: Int = 0,
    val tracks: List<SongInfo> = emptyList(),
)

/** 按字段查找时的一个可采用的候选。 */
data class FieldCandidate(
    val source: String,
    val sourceId: Long,
    val group: String,
    val label: String,
    val subLabel: String = "",
    val picUrl: String = "",
    val score: Double = 1.0,
    val notes: List<String> = emptyList(),
    val song: SongInfo? = null,
    val album: AlbumInfo? = null,
    val artist: ArtistInfo? = null,
    val playlist: PlaylistInfo? = null,
    val tracks: List<SongInfo> = emptyList(),
    /** 已按当前文件解析好的字段值。 */
    val value: String = "",
    val valueHint: String = "",
) {
    val key: String get() = "$source:$sourceId"
}

data class FieldLookupResult(
    val fieldKey: String,
    val fieldLabel: String,
    val kind: QueryKind,
    val keyword: String,
    val note: String = "",
    val candidates: List<FieldCandidate> = emptyList(),
)

enum class QueryKind(val key: String, val label: String) {
    Song("song", "歌曲"),
    Album("album", "专辑"),
    Artist("artist", "歌手"),
    Playlist("playlist", "歌单");

    companion object {
        fun fromKey(key: String?): QueryKind =
            entries.firstOrNull { it.key == key?.lowercase() } ?: Song
    }
}

/** 不同端点的 JSON 结构差异较大，这里集中做宽松取值。 */
object Json {

    fun obj(element: JsonElement?): JsonObject? =
        (element as? JsonObject)

    fun array(element: JsonElement?): JsonArray? =
        (element as? JsonArray)

    fun str(element: JsonElement?, fallback: String = ""): String {
        val primitive = element as? JsonPrimitive ?: return fallback
        if (primitive is JsonNull) return fallback
        return primitive.content
    }

    fun long(element: JsonElement?, fallback: Long = 0): Long {
        val primitive = element as? JsonPrimitive ?: return fallback
        if (primitive is JsonNull) return fallback
        return primitive.longOrNull ?: primitive.content.toLongOrNull() ?: fallback
    }

    fun int(element: JsonElement?, fallback: Int = 0): Int = long(element, fallback.toLong()).toInt()

    fun bool(element: JsonElement?, fallback: Boolean = false): Boolean {
        val primitive = element as? JsonPrimitive ?: return fallback
        return when (primitive.content.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> fallback
        }
    }

    /** 从对象里取第一个非空字符串字段（端点字段名不一致时用）。 */
    fun strAny(element: JsonElement?, vararg names: String): String {
        val objectElement = element as? JsonObject ?: return ""
        for (name in names) {
            val value = str(objectElement[name])
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    fun longAny(element: JsonElement?, vararg names: String): Long {
        val objectElement = element as? JsonObject ?: return 0
        for (name in names) {
            if (name in objectElement) {
                val value = long(objectElement[name])
                if (value != 0L) return value
            }
        }
        return 0
    }

    fun strings(element: JsonElement?, vararg names: String): List<String> {
        val objectElement = element as? JsonObject ?: return emptyList()
        for (name in names) {
            val arr = objectElement[name] as? JsonArray ?: continue
            val values = arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
                .filter { it.isNotEmpty() }
            if (values.isNotEmpty()) return values
        }
        return emptyList()
    }

    /**
     * 找出响应里的资源数组。不同端点的结构差异：
     *   {"result":{"songs":[...]}}
     *   {"result":{"data":{"songs":[...]}}}
     *   {"songs":[...]}
     * 按常见路径优先，再退化为有限深度搜索。
     */
    fun findArray(root: JsonElement?, vararg names: String): JsonArray {
        val rootObject = root as? JsonObject ?: return JsonArray(emptyList())

        fun pick(container: JsonObject?): JsonArray? {
            if (container == null) return null
            for (name in names) {
                val candidate = container[name]
                if (candidate is JsonArray) return candidate
            }
            return null
        }

        pick(rootObject)?.let { return it }
        val result = rootObject["result"]
        pick(result as? JsonObject)?.let { return it }
        val data = (result as? JsonObject)?.get("data")
        pick(data as? JsonObject)?.let { return it }
        pick(rootObject["data"] as? JsonObject)?.let { return it }

        // 兜底：有限深度广度搜索
        val queue = ArrayDeque<Pair<JsonObject, Int>>()
        queue.add(rootObject to 0)
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            if (depth > 3) continue
            pick(current)?.let { return it }
            for (value in current.values) {
                if (value is JsonObject) queue.add(value to depth + 1)
            }
        }
        return JsonArray(emptyList())
    }
}
