package app.tagger.core

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import app.tagger.core.Json as J

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.TimeSource

class NeteaseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 带过期时间的缓存：反复核对同一首歌时不重复打接口。 */
class TtlCache<T>(private val ttlMs: Long = 30 * 60 * 1000L, private val maxItems: Int = 800) {
    private data class Entry<T>(val createdAt: Long, val value: T)
    private val data = LinkedHashMap<String, Entry<T>>()
    private val mutex = Mutex()

    var hits: Long = 0
        private set
    var misses: Long = 0
        private set

    suspend fun get(key: String): T? = mutex.withLock {
        val entry = data[key]
        if (entry == null) {
            misses++
            return@withLock null
        }
        if (now() - entry.createdAt > ttlMs) {
            data.remove(key)
            misses++
            return@withLock null
        }
        hits++
        entry.value
    }

    suspend fun put(key: String, value: T) = mutex.withLock {
        if (data.size >= maxItems) {
            val iterator = data.entries.iterator()
            var removed = 0
            while (iterator.hasNext() && removed < maxItems / 4 + 1) {
                iterator.next()
                iterator.remove()
                removed++
            }
        }
        data[key] = Entry(now(), value)
    }

    suspend fun clear() = mutex.withLock { data.clear() }

    suspend fun stats(): Triple<Int, Long, Long> = mutex.withLock {
        Triple(data.size, hits, misses)
    }

    private fun now(): Long = TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds
}

/** 令牌桶限流。 */
class RateLimiter(private val rate: Double = 3.0, private val burst: Double = 5.0) {
    private var tokens = burst
    private var last = TimeSource.Monotonic.markNow()
    private val mutex = Mutex()

    suspend fun acquire() {
        while (true) {
            val waitMs = mutex.withLock {
                val now = TimeSource.Monotonic.markNow()
                val elapsed = (now - last).inWholeMilliseconds / 1000.0
                last = now
                tokens = minOf(burst, tokens + elapsed * rate)
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    return
                }
                ((1.0 - tokens) / rate * 1000).toLong().coerceIn(1L, 500L)
            }
            delay(waitMs)
        }
    }
}

/**
 * 网易云 Web 端接口客户端。
 *
 * 关键设计：搜索端点会在限流时轮换（某个端点报「操作频繁」就换下一个），
 * 所有结果本地缓存 30 分钟。
 */
class NeteaseClient(
    private val httpClient: HttpClient,
    cookie: String = "",
    rate: Double = 3.0,
) : AutoCloseable {

    private val limiter = RateLimiter(rate)
    val cache = TtlCache<JsonElement>()

    val lastError: String
        get() = _lastError
    private var _lastError: String = ""

    private val userAgent = USER_AGENT
    private val extraCookie = cookie

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private val searchEndpoints: Map<String, List<Pair<String, String>>> = mapOf(
        "songs" to listOf(
            "GET" to "/api/search/get",
            "GET" to "/api/cloudsearch/pc",
            "POST" to "/search/get",
            "GET" to "/api/cloudsearch/get/web",
            "POST" to "/cloudsearch/get/web",
            "GET" to "/api/search/get/web",
        ),
        "albums" to listOf(
            "GET" to "/api/search/get",
            "GET" to "/api/cloudsearch/pc",
            "POST" to "/search/get",
            "GET" to "/api/cloudsearch/get/web",
            "POST" to "/cloudsearch/get/web",
        ),
        "artists" to listOf(
            "GET" to "/api/search/get",
            "GET" to "/api/cloudsearch/pc",
            "POST" to "/search/get",
            "GET" to "/api/cloudsearch/get/web",
            "POST" to "/cloudsearch/get/web",
        ),
        "playlists" to listOf(
            "GET" to "/api/cloudsearch/pc",
            "GET" to "/api/search/get",
            "POST" to "/search/get",
            "GET" to "/api/cloudsearch/get/web",
            "POST" to "/cloudsearch/get/web",
        ),
    )

    private val typeCodes = mapOf("songs" to 1, "albums" to 10, "artists" to 100, "playlists" to 1000)

    // ---------------------------------------------------------------- 底层请求

    private fun headers(): Map<String, String> = buildMap {
        put(HttpHeaders.UserAgent, userAgent)
        put(HttpHeaders.Referrer, "https://music.163.com/")
        put("Origin", "https://music.163.com")
        put(HttpHeaders.Accept, "*/*")
        put(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en;q=0.8")
        put(HttpHeaders.Cookie, buildCookie())
    }

    private fun buildCookie(): String =
        (if (extraCookie.isBlank()) "" else "$extraCookie; ") + "os=pc; appver=2.9.7; NMTID=1"

    private suspend fun postEncrypted(path: String, payloadJson: String, useEapi: Boolean): JsonElement {
        limiter.acquire()
        val url = (if (useEapi) "https://music.163.com/eapi" else "https://music.163.com/weapi") + path
        val form = if (useEapi) NeteaseCrypto.eapi(path, payloadJson) else NeteaseCrypto.weapi(payloadJson)
        val response = httpClient.submitForm(
            url = url,
            formParameters = Parameters.build {
                form.forEach { (key, value) -> append(key, value) }
            },
        ) {
            headers().forEach { (key, value) -> header(key, value) }
        }
        val text = response.bodyAsText()
        if (text.isBlank()) throw NeteaseException("接口返回空响应（HTTP ${response.status.value}，可能被限流）")
        return json.parseToJsonElement(text)
    }

    private suspend fun call(path: String, payloadJson: String): JsonElement {
        val errors = mutableListOf<String>()
        for (useEapi in listOf(false, true)) {
            try {
                val body = postEncrypted(path, payloadJson, useEapi)
                val code = J.long(J.obj(body)?.get("code"))
                val hasResult = J.obj(body)?.containsKey("result") == true
                if (code == 200L || code == 0L || code == -1L || hasResult) return body
                errors += "${if (useEapi) "eapi" else "weapi"} code=$code"
            } catch (ex: Exception) {
                errors += "${if (useEapi) "eapi" else "weapi"} ${ex.message}"
            }
        }
        throw NeteaseException(errors.joinToString("；"))
    }

    private suspend fun getPublic(path: String, params: Map<String, String>): JsonElement {
        limiter.acquire()
        val response = httpClient.get("https://music.163.com$path") {
            headers().forEach { (key, value) -> header(key, value) }
            params.forEach { (key, value) -> parameter(key, value) }
        }
        val text = response.bodyAsText()
        if (text.isBlank()) throw NeteaseException("接口返回空响应（HTTP ${response.status.value}）")
        return json.parseToJsonElement(text)
    }

    private fun isRateLimited(body: JsonElement): Boolean {
        val obj = J.obj(body) ?: return false
        val code = J.long(obj["code"])
        if (code == 405L || code == 50000005L) return true
        val message = J.str(obj["message"]) + J.str(obj["msg"])
        return RATE_HINTS.any { message.contains(it, ignoreCase = true) }
    }

    // ---------------------------------------------------------------- 搜索

    private suspend fun searchRaw(kind: String, keyword: String, limit: Int, offset: Int): List<JsonElement> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return emptyList()
        val typeCode = typeCodes[kind] ?: 1
        val cacheKey = "search:$kind:$trimmed:$limit:$offset"
        cache.get(cacheKey)?.let { cached ->
            (cached as? JsonArray)?.let { return it.toList() }
        }

        val attempts = mutableListOf<String>()
        val rateLimited = mutableListOf<String>()
        for (round in 0 until 2) {
            for ((method, path) in searchEndpoints[kind].orEmpty()) {
                val label = "$method $path"
                try {
                    val body = if (method == "POST") {
                        call(path, buildJsonObject {
                            put("s", trimmed)
                            put("type", typeCode)
                            put("limit", limit)
                            put("offset", offset)
                            put("total", true)
                        }.toString())
                    } else {
                        getPublic(
                            path,
                            mapOf(
                                "s" to trimmed,
                                "type" to typeCode.toString(),
                                "limit" to limit.toString(),
                                "offset" to offset.toString(),
                            ),
                        )
                    }
                    if (isRateLimited(body)) {
                        rateLimited += label
                        continue
                    }
                    val items = J.findArray(body, kind).toList()
                    if (items.isNotEmpty()) {
                        cache.put(cacheKey, JsonArray(items))
                        return items
                    }
                    attempts += "$label: 结果为空"
                } catch (ex: Exception) {
                    attempts += "$label: ${ex.message}"
                }
            }
            if (round == 0) delay(2000)
        }

        if (rateLimited.isNotEmpty() && attempts.isEmpty()) {
            _lastError = "所有搜索端点都在限流：" + rateLimited.take(4).joinToString("、")
            throw NeteaseException("网易云提示「操作频繁」，暂时查不了「$trimmed」。稍等十几秒再试，或在设置里调低并发。")
        }
        _lastError = attempts.takeLast(3).joinToString("；")
        if (rateLimited.isNotEmpty()) {
            throw NeteaseException("网易云限流中（$_lastError）。稍等片刻再试。")
        }
        cache.put(cacheKey, JsonArray(emptyList()))
        return emptyList()
    }

    suspend fun searchSongs(keyword: String, limit: Int = 10, offset: Int = 0): List<SongInfo> =
        searchRaw("songs", keyword, limit, offset)
            .filter { J.long(J.obj(it)?.get("id")) > 0 }
            .map { parseSongSummary(it) }

    suspend fun searchAlbums(keyword: String, limit: Int = 10): List<AlbumInfo> =
        searchRaw("albums", keyword, limit, 0)
            .filter { J.long(J.obj(it)?.get("id")) > 0 }
            .map { element ->
                val obj = J.obj(element)
                AlbumInfo(
                    id = J.long(obj?.get("id")),
                    name = J.str(obj?.get("name")),
                    artist = J.str(J.obj(obj?.get("artist"))?.get("name")),
                    picUrl = J.strAny(element, "picUrl", "blurPicUrl"),
                    publishTimeMs = J.longAny(element, "publishTime"),
                    year = toYear(J.longAny(element, "publishTime")),
                    company = J.str(obj?.get("company")),
                    size = J.int(obj?.get("size")),
                )
            }

    suspend fun searchArtists(keyword: String, limit: Int = 8): List<ArtistInfo> =
        searchRaw("artists", keyword, limit, 0)
            .filter { J.long(J.obj(it)?.get("id")) > 0 }
            .map { element ->
                val obj = J.obj(element)
                ArtistInfo(
                    id = J.long(obj?.get("id")),
                    name = J.str(obj?.get("name")),
                    picUrl = J.strAny(element, "picUrl", "img1v1Url"),
                    albumSize = J.int(obj?.get("albumSize")),
                    musicSize = J.int(obj?.get("musicSize")),
                    alias = J.strings(element, "alias", "alia"),
                )
            }

    suspend fun searchPlaylists(keyword: String, limit: Int = 8): List<PlaylistInfo> =
        searchRaw("playlists", keyword, limit, 0)
            .filter { J.long(J.obj(it)?.get("id")) > 0 }
            .map { element ->
                val obj = J.obj(element)
                PlaylistInfo(
                    id = J.long(obj?.get("id")),
                    name = J.str(obj?.get("name")),
                    picUrl = J.strAny(element, "coverImgUrl", "picUrl"),
                    creator = J.str(J.obj(obj?.get("creator"))?.get("nickname")),
                    trackCount = J.int(obj?.get("trackCount")),
                )
            }

    // ---------------------------------------------------------------- 详情

    suspend fun songDetail(songId: Long): SongInfo {
        val cacheKey = "song:$songId"
        cache.get(cacheKey)?.let { return parseSongDetail(it) }

        // /v3/song/detail 需要 {"c":"[{\"id\":123}]"}
        val payload = """{"c":"[{\"id\":$songId}]"}"""
        val errors = mutableListOf<String>()
        var body: JsonElement? = null
        try {
            body = call("/v3/song/detail", payload)
        } catch (ex: Exception) {
            errors += "weapi ${ex.message}"
        }
        val first = body?.let { J.findArray(it, "songs").firstOrNull() }
        if (first == null) {
            try {
                val publicBody = getPublic(
                    "/api/song/detail",
                    mapOf("id" to songId.toString(), "ids" to "[$songId]"),
                )
                val song = J.findArray(publicBody, "songs").firstOrNull()
                    ?: throw NeteaseException("未找到歌曲 $songId")
                cache.put(cacheKey, song)
                return parseSongDetail(song)
            } catch (ex: Exception) {
                errors += "public ${ex.message}"
                throw NeteaseException("取歌曲 $songId 失败：${errors.joinToString("；")}")
            }
        }
        cache.put(cacheKey, first)
        return parseSongDetail(first)
    }

    suspend fun albumDetail(albumId: Long): AlbumInfo {
        val cacheKey = "album:$albumId"
        cache.get(cacheKey)?.let { return parseAlbumDetail(it) }

        val errors = mutableListOf<String>()
        var body: JsonElement? = null
        try {
            body = call("/v1/album/$albumId", "{}")
        } catch (ex: Exception) {
            errors += "weapi ${ex.message}"
        }
        if (body == null || J.obj(body)?.containsKey("album") != true) {
            try {
                body = getPublic("/api/album/$albumId", emptyMap())
            } catch (ex: Exception) {
                errors += "public ${ex.message}"
                throw NeteaseException("取专辑 $albumId 失败：${errors.joinToString("；")}")
            }
        }
        cache.put(cacheKey, body)
        return parseAlbumDetail(body)
    }

    suspend fun artistSongs(artistId: Long, limit: Int = 50, artistName: String = ""): List<SongInfo> {
        val cacheKey = "artist_songs:$artistId:$limit"
        cache.get(cacheKey)?.let { cached ->
            (cached as? JsonArray)?.let { arr ->
                return arr.mapNotNull { parseSongFromOwnCache(it) }
            }
        }

        val result = mutableListOf<JsonElement>()
        val errors = mutableListOf<String>()
        try {
            val body = call("/v1/artist/songs", buildJsonObject {
                put("id", artistId)
                put("offset", 0)
                put("limit", limit)
                put("order", "hot")
            }.toString())
            result.addAll(J.findArray(body, "songs", "hotSongs"))
        } catch (ex: Exception) {
            errors += "weapi ${ex.message}"
        }
        if (result.isEmpty()) {
            try {
                val body = getPublic("/api/artist/$artistId", emptyMap())
                result.addAll(J.findArray(body, "hotSongs", "songs"))
            } catch (ex: Exception) {
                errors += "public ${ex.message}"
            }
        }
        if (result.isEmpty()) {
            if (errors.isNotEmpty()) throw NeteaseException("取歌手 $artistId 作品失败：${errors.joinToString("；")}")
            return emptyList()
        }
        cache.put(cacheKey, JsonArray(result))
        return result.mapNotNull { element ->
            val song = parseSongSummary(element)
            if (song.artists.isEmpty() && artistName.isNotEmpty()) song.copy(artists = listOf(artistName)) else song
        }
    }

    suspend fun playlist(playlistId: Long, limit: Int = 100): PlaylistInfo {
        val cacheKey = "playlist:$playlistId:$limit"
        cache.get(cacheKey)?.let { return parsePlaylist(it) }

        val body = call("/v6/playlist/detail", buildJsonObject {
            put("id", playlistId)
            put("n", limit)
            put("s", 0)
        }.toString())
        cache.put(cacheKey, body)
        return parsePlaylist(body)
    }

    suspend fun lyric(songId: Long): Triple<String, String, Boolean> {
        val cacheKey = "lyric:$songId"
        cache.get(cacheKey)?.let { cached ->
            (cached as? JsonArray)?.let { arr ->
                val values = arr.map { J.str(it) }
                if (values.size >= 3) return Triple(values[0], values[1], values[2] == "1")
            }
        }

        val errors = mutableListOf<String>()
        var body: JsonElement? = null
        try {
            body = call("/song/lyric", buildJsonObject {
                put("id", songId)
                put("lv", -1)
                put("kv", -1)
                put("tv", -1)
            }.toString())
        } catch (ex: Exception) {
            errors += "weapi ${ex.message}"
        }
        if (body == null || J.obj(body)?.containsKey("lrc") != true) {
            try {
                body = getPublic(
                    "/api/song/lyric",
                    mapOf("id" to songId.toString(), "lv" to "-1", "kv" to "-1", "tv" to "-1"),
                )
            } catch (ex: Exception) {
                errors += "public ${ex.message}"
                throw NeteaseException("取歌词 $songId 失败：${errors.joinToString("；")}")
            }
        }
        val lyric = J.str(J.obj(J.obj(body)?.get("lrc"))?.get("lyric"))
        val translated = J.str(J.obj(J.obj(body)?.get("tlyric"))?.get("lyric"))
        cache.put(cacheKey, JsonArray(listOf(
            JsonPrimitive(lyric),
            JsonPrimitive(translated),
            JsonPrimitive(if (translated.isNotEmpty()) "1" else "0"),
        )))
        return Triple(lyric, translated, translated.isNotEmpty())
    }

    suspend fun fetchBytes(url: String): ByteArray {
        val normalized = if (url.startsWith("http://")) "https://" + url.substring(7) else url
        val cacheKey = "img:$normalized"
        cache.get(cacheKey)?.let { cached ->
            (cached as? JsonPrimitive)?.content?.let { return Crypto.base64Decode(it) }
        }
        limiter.acquire()
        val response = httpClient.get(normalized) {
            header(HttpHeaders.UserAgent, userAgent)
        }
        val bytes = response.body<ByteArray>()
        cache.put(cacheKey, JsonPrimitive(Crypto.base64Encode(bytes)))
        return bytes
    }

    // ---------------------------------------------------------------- 解析

    private fun parseSongSummary(element: JsonElement): SongInfo {
        val obj = J.obj(element) ?: return SongInfo()
        val artists = mutableListOf<String>()
        val artistIds = mutableListOf<Long>()
        for (key in listOf("artists", "ar")) {
            val arr = J.array(obj[key]) ?: continue
            if (artists.isNotEmpty()) break
            for (item in arr) {
                val name = J.str(J.obj(item)?.get("name"))
                if (name.isNotEmpty()) artists += name
                val id = J.long(J.obj(item)?.get("id"))
                if (id > 0) artistIds += id
            }
        }

        var album = ""
        var albumId = 0L
        var picUrl = ""
        var company = ""
        var year = 0
        var trackNo = 0
        var albumArtist = ""
        for (key in listOf("album", "al")) {
            val albumObj = J.obj(obj[key]) ?: continue
            if (album.isEmpty()) album = J.str(albumObj["name"])
            if (albumId == 0L) albumId = J.long(albumObj["id"])
            if (picUrl.isEmpty()) picUrl = J.strAny(albumObj, "picUrl", "blurPicUrl", "img1v1Url")
            if (company.isEmpty()) company = J.str(albumObj["company"])
            if (year == 0) year = toYear(J.long(albumObj["publishTime"]))
            if (trackNo == 0) trackNo = J.int(albumObj["no"])
            if (albumArtist.isEmpty()) albumArtist = J.str(J.obj(albumObj["artist"])?.get("name"))
            if (album.isNotEmpty() && picUrl.isNotEmpty()) break
        }

        val duration = if (J.long(obj["duration"]) > 0) J.long(obj["duration"]) else J.long(obj["dt"])
        if (trackNo == 0) trackNo = J.int(obj["no"])
        val disc = if (J.int(obj["disc"]) > 0) J.int(obj["disc"]) else J.int(obj["cd"])

        return SongInfo(
            id = J.long(obj["id"]),
            name = J.str(obj["name"]),
            alias = J.strings(element, "alias", "alia"),
            artists = artists,
            artistIds = artistIds,
            album = album,
            albumId = albumId,
            picUrl = picUrl,
            durationMs = duration,
            trackNo = trackNo,
            discNo = disc,
            albumArtist = albumArtist.ifEmpty { artists.firstOrNull() ?: "" },
            year = year,
            company = company,
        )
    }

    private fun parseSongDetail(element: JsonElement): SongInfo {
        val song = parseSongSummary(element)
        return if (song.albumArtist.isEmpty()) song.copy(albumArtist = song.artists.firstOrNull() ?: "") else song
    }

    private fun parseSongFromOwnCache(element: JsonElement): SongInfo? {
        val obj = J.obj(element) ?: return null
        val id = J.long(obj["id"])
        if (id <= 0) return null
        val albumObj = J.obj(obj["album"])
        return SongInfo(
            id = id,
            name = J.str(obj["name"]),
            artists = J.strings(element, "artists"),
            album = J.str(albumObj?.get("name")),
            albumId = J.long(albumObj?.get("id")),
            picUrl = J.str(albumObj?.get("picUrl")),
            durationMs = J.long(obj["duration"]),
            trackNo = J.int(albumObj?.get("no")),
            discNo = J.int(obj["disc"]),
        )
    }

    private fun parseAlbumDetail(body: JsonElement?): AlbumInfo {
        val root = J.obj(body) ?: return AlbumInfo()
        val albumObj = J.obj(root["album"])
        val songsElement = root["songs"] ?: albumObj?.get("songs")
        val songs = J.array(songsElement) ?: JsonArray(emptyList())

        val publishTime = J.long(albumObj?.get("publishTime"))
        val year = toYear(publishTime)
        val artistNames = J.strings(albumObj, "artists")
        val artist = J.str(J.obj(albumObj?.get("artist"))?.get("name"))
            .ifEmpty { artistNames.firstOrNull() ?: "" }
        val albumName = J.str(albumObj?.get("name"))
        val picUrl = J.strAny(albumObj, "picUrl", "blurPicUrl")

        val tracks = songs.mapIndexed { index, element ->
            val song = parseSongSummary(element)
            song.copy(
                trackNo = if (song.trackNo > 0) song.trackNo else index + 1,
                year = if (song.year > 0) song.year else year,
                albumArtist = song.albumArtist.ifEmpty { artist },
                picUrl = song.picUrl.ifEmpty { picUrl },
                album = song.album.ifEmpty { albumName },
            )
        }

        return AlbumInfo(
            id = J.long(albumObj?.get("id")),
            name = albumName,
            artist = artist,
            artists = artistNames,
            picUrl = picUrl,
            year = year,
            publishTimeMs = publishTime,
            company = J.str(albumObj?.get("company")),
            description = J.str(albumObj?.get("description")),
            size = J.int(albumObj?.get("size")).let { if (it > 0) it else tracks.size },
            tracks = tracks,
        )
    }

    private fun parsePlaylist(body: JsonElement?): PlaylistInfo {
        val playlist = J.obj(J.obj(body)?.get("playlist"))
        val tracks = J.array(playlist?.get("tracks"))?.map { parseSongSummary(it) } ?: emptyList()
        return PlaylistInfo(
            id = J.long(playlist?.get("id")),
            name = J.str(playlist?.get("name")),
            creator = J.str(J.obj(playlist?.get("creator"))?.get("nickname")),
            picUrl = J.str(playlist?.get("coverImgUrl")),
            trackCount = J.int(playlist?.get("trackCount")),
            tracks = tracks,
        )
    }

    override fun close() {
        // HttpClient 由调用方持有生命周期
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val RATE_HINTS = listOf("操作频繁", "稍候", "频繁", "too frequent", "rate")

        fun toYear(publishTimeMs: Long): Int {
            if (publishTimeMs <= 0) return 0
            return yearFromEpochSeconds(publishTimeMs / 1000)
        }

        fun coverUrl(url: String?, size: Int = 600): String {
            if (url.isNullOrBlank()) return ""
            val base = url.substringBefore('?')
            return "$base?param=${size}y$size"
        }

        private fun yearFromEpochSeconds(seconds: Long): Int {
            // 以 1970 为基准的民用历算法（避免依赖 kotlinx-datetime）
            var days = seconds / 86400
            var year = 1970
            while (true) {
                val daysInYear = if (isLeapYear(year)) 366 else 365
                if (days < daysInYear) break
                days -= daysInYear
                year++
            }
            return year
        }

        private fun isLeapYear(year: Int): Boolean =
            (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    }
}
