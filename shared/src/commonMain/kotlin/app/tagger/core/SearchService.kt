package app.tagger.core

import app.tagger.core.tags.FieldQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 按字段查找：指定一个标签字段，去网易云查候选，只返回该字段的值。
 * 只查、不写 —— 写入统一走 PendingService 的人工确认清单。
 */
class SearchService(
    private val client: NeteaseClient,
    private val log: RunLog,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    companion object {
        const val CANDIDATE_LIMIT = 60
    }

    suspend fun searchField(
        track: AudioTrack,
        fieldKey: String,
        kind: FieldQuery,
        keyword: String = "",
        limit: Int = 10,
        lyricsMode: String = "merged",
    ): FieldLookupResult {
        val spec = app.tagger.core.tags.Fields.get(fieldKey)
            ?: throw IllegalArgumentException("未知字段：$fieldKey")
        val actualKind = if (kind in spec.queryKinds) kind else spec.defaultKind
        val actualKeyword = if (keyword.isBlank()) {
            FieldCatalog.keywordFor(track.tags, track.fileName, fieldKey)
        } else {
            keyword.trim()
        }
        if (actualKeyword.isBlank()) {
            throw IllegalArgumentException("搜索关键词为空：请先给这首歌填一个标题，或手动输入关键词")
        }

        val result = FieldLookupResult(
            fieldKey = fieldKey,
            fieldLabel = spec.label,
            kind = actualKind,
            keyword = actualKeyword,
        )

        val candidates = when (actualKind) {
            FieldQuery.Song -> searchSongs(track, fieldKey, actualKeyword, limit)
            FieldQuery.Album -> searchAlbums(track, fieldKey, actualKeyword, limit)
            FieldQuery.Artist -> searchArtists(track, fieldKey, actualKeyword, limit)
            FieldQuery.Playlist -> searchPlaylists(track, fieldKey, actualKeyword, limit)
        }

        val sorted = if (actualKind == FieldQuery.Song) {
            candidates.sortedByDescending { it.score }
        } else {
            candidates
        }
        log.info("按「${spec.label}」查找「$actualKeyword」（${actualKind.label}）→ ${sorted.size} 个候选")
        return result.copy(candidates = sorted.take(CANDIDATE_LIMIT))
    }

    private suspend fun searchSongs(
        track: AudioTrack,
        fieldKey: String,
        keyword: String,
        limit: Int,
    ): List<FieldCandidate> = coroutineScope {
        val songs = client.searchSongs(keyword, limit)
        songs.map { summary ->
            async(dispatcher) {
                val song = enrich(summary, fieldKey)
                val score = Matcher.score(track.tags, song, track.fileName, track.durationSeconds)
                FieldCandidate(
                    source = "song",
                    sourceId = song.id,
                    group = "单曲",
                    label = song.name,
                    subLabel = listOf(song.artistsText, song.album).filter { it.isNotEmpty() }.joinToString(" · "),
                    picUrl = NeteaseClient.coverUrl(song.picUrl, 200),
                    score = score.value,
                    notes = score.notes,
                    song = song,
                )
            }
        }.awaitAll()
    }

    private suspend fun enrich(summary: SongInfo, fieldKey: String): SongInfo {
        val needsDetail = fieldKey in setOf("year", "copyright", "album_artist", "track", "disc")
        val needsLyric = fieldKey == "lyrics"
        val needsCover = summary.picUrl.isEmpty()
        if (!needsDetail && !needsLyric && !needsCover) return summary

        var song = summary
        try {
            if (needsDetail || needsCover) {
                val detail = client.songDetail(summary.id)
                song = song.copy(
                    year = if (detail.year > 0) detail.year else song.year,
                    company = detail.company.ifEmpty { song.company },
                    albumArtist = detail.albumArtist.ifEmpty { song.albumArtist },
                    trackNo = if (detail.trackNo > 0) detail.trackNo else song.trackNo,
                    picUrl = song.picUrl.ifEmpty { detail.picUrl },
                    album = song.album.ifEmpty { detail.album },
                )
            }
            if (needsLyric) {
                val (lyric, translated, _) = client.lyric(song.id)
                song = song.copy(
                    lyric = lyric,
                    lyricTranslated = translated,
                    lyricMerged = Lyrics.merge(lyric, translated),
                )
            }
        } catch (ex: Exception) {
            log.warn("补全歌曲信息失败 ${summary.id}: ${ex.message}")
        }
        return song
    }

    private suspend fun searchAlbums(
        track: AudioTrack,
        fieldKey: String,
        keyword: String,
        limit: Int,
    ): List<FieldCandidate> = coroutineScope {
        val albums = client.searchAlbums(keyword, limit)
        albums.map { summary ->
            async(dispatcher) {
                val detail = try {
                    client.albumDetail(summary.id)
                } catch (ex: Exception) {
                    log.warn("取专辑 ${summary.id} 详情失败: ${ex.message}")
                    summary
                }
                val album = if (detail.tracks.isEmpty()) {
                    detail.copy(
                        name = detail.name.ifEmpty { summary.name },
                        picUrl = detail.picUrl.ifEmpty { summary.picUrl },
                        year = if (detail.year > 0) detail.year else summary.year,
                        artist = detail.artist.ifEmpty { summary.artist },
                        size = if (detail.size > 0) detail.size else summary.size,
                    )
                } else {
                    detail
                }
                val candidate = FieldCandidate(
                    source = "album",
                    sourceId = album.id,
                    group = "专辑",
                    label = album.name,
                    subLabel = listOf(
                        album.artist.ifEmpty { album.artists.joinToString("/") },
                        if (album.year > 0) album.year.toString() else "",
                        if (album.size > 0) "${album.size} 首" else "",
                    ).filter { it.isNotEmpty() }.joinToString(" · "),
                    picUrl = NeteaseClient.coverUrl(album.picUrl, 200),
                    album = album,
                    tracks = album.tracks,
                    score = 1.0,
                )
                // 音轨号按当前文件先解析一次，界面直接展示
                val value = resolveForTrack(fieldKey, candidate, track)
                candidate.copy(value = value.first, valueHint = value.second)
            }
        }.awaitAll()
    }

    private suspend fun searchArtists(
        track: AudioTrack,
        fieldKey: String,
        keyword: String,
        limit: Int,
    ): List<FieldCandidate> = coroutineScope {
        val artists = client.searchArtists(keyword, maxOf(3, limit / 2))
        val result = mutableListOf<FieldCandidate>()
        for (artist in artists) {
            val songs = try {
                client.artistSongs(artist.id, CANDIDATE_LIMIT, artist.name)
            } catch (ex: Exception) {
                log.warn("取歌手 ${artist.id} 作品失败: ${ex.message}")
                emptyList()
            }
            result += FieldCandidate(
                source = "artist",
                sourceId = artist.id,
                group = "歌手",
                label = artist.name,
                subLabel = listOf(
                    if (artist.musicSize > 0) "${artist.musicSize} 首作品" else "",
                    artist.alias.joinToString("/"),
                ).filter { it.isNotEmpty() }.joinToString(" · "),
                picUrl = NeteaseClient.coverUrl(artist.picUrl, 200),
                artist = artist.copy(songs = songs),
                tracks = songs,
                score = 1.0,
            )
            val songCandidates = songs.map { song ->
                async(dispatcher) {
                    val score = Matcher.score(track.tags, song, track.fileName, track.durationSeconds)
                    FieldCandidate(
                        source = "song",
                        sourceId = song.id,
                        group = "歌手 · ${artist.name}",
                        label = song.name,
                        subLabel = listOf(song.artistsText, song.album).filter { it.isNotEmpty() }.joinToString(" · "),
                        picUrl = NeteaseClient.coverUrl(song.picUrl, 200),
                        song = song,
                        score = score.value,
                        notes = score.notes,
                    )
                }
            }.awaitAll()
            result += songCandidates
            if (result.size >= CANDIDATE_LIMIT) break
        }
        result
    }

    private suspend fun searchPlaylists(
        track: AudioTrack,
        fieldKey: String,
        keyword: String,
        limit: Int,
    ): List<FieldCandidate> = coroutineScope {
        val playlists = client.searchPlaylists(keyword, maxOf(3, limit / 2))
        val result = mutableListOf<FieldCandidate>()
        for (summary in playlists) {
            val detail = try {
                client.playlist(summary.id, CANDIDATE_LIMIT)
            } catch (ex: Exception) {
                log.warn("取歌单 ${summary.id} 失败: ${ex.message}")
                PlaylistInfo(id = summary.id, name = summary.name)
            }
            result += FieldCandidate(
                source = "playlist",
                sourceId = detail.id,
                group = "歌单",
                label = detail.name,
                subLabel = listOf(
                    detail.creator,
                    if (detail.trackCount > 0) "${detail.trackCount} 首" else "",
                ).filter { it.isNotEmpty() }.joinToString(" · "),
                picUrl = NeteaseClient.coverUrl(detail.picUrl, 200),
                playlist = detail,
                tracks = detail.tracks,
                score = 1.0,
            )
            val songCandidates = detail.tracks.map { song ->
                async(dispatcher) {
                    val score = Matcher.score(track.tags, song, track.fileName, track.durationSeconds)
                    FieldCandidate(
                        source = "song",
                        sourceId = song.id,
                        group = "歌单 · ${detail.name}",
                        label = song.name,
                        subLabel = listOf(song.artistsText, song.album).filter { it.isNotEmpty() }.joinToString(" · "),
                        picUrl = NeteaseClient.coverUrl(song.picUrl, 200),
                        song = song,
                        score = score.value,
                        notes = score.notes,
                    )
                }
            }.awaitAll()
            result += songCandidates
            if (result.size >= CANDIDATE_LIMIT) break
        }
        result
    }

    /** 从候选里取出「对当前文件而言」该字段应写入的值。 */
    fun resolveForTrack(fieldKey: String, candidate: FieldCandidate, track: AudioTrack, lyricsMode: String = "merged"): Pair<String, String> {
        if (fieldKey == "track" || fieldKey == "disc") {
            val matched = trackForFile(candidate, track)
            if (matched != null) {
                val hint = "按专辑曲目对到《${matched.name}》"
                val value = if (fieldKey == "track") {
                    if (matched.trackNo > 0) matched.trackNo.toString() else ""
                } else {
                    if (matched.discNo > 0) matched.discNo.toString() else ""
                }
                return if (value.isNotEmpty()) value to hint else "" to "$hint，但没有这一项"
            }
            return "" to "该候选没有曲目清单，无法定位音轨号（请用专辑查询）"
        }

        if (fieldKey == "lyrics") {
            val song = candidate.song ?: return "" to "该候选没有歌词"
            val text = when (lyricsMode) {
                "original" -> song.lyric
                "translated" -> song.lyricTranslated.ifEmpty { song.lyric }
                else -> song.lyricMerged.ifEmpty { song.lyric }
            }
            return if (text.isBlank()) "" to "该候选没有歌词" else text to ""
        }

        val spec = app.tagger.core.tags.Fields.get(fieldKey)
        val value = spec?.let { resolveValue(it.key, candidate) }?.trim() ?: ""
        return if (value.isNotEmpty()) value to "" else "" to "该候选没有此字段的值"
    }

    private fun resolveValue(fieldKey: String, candidate: FieldCandidate): String = when (fieldKey) {
        "title" -> candidate.song?.name ?: candidate.album?.name ?: candidate.artist?.name ?: candidate.playlist?.name ?: ""
        "artist" -> candidate.song?.artistsText ?: ""
        "album" -> candidate.song?.album ?: candidate.album?.name ?: ""
        "album_artist" -> candidate.song?.albumArtist
            ?: candidate.album?.artist
            ?: candidate.album?.artists?.firstOrNull()
            ?: candidate.artist?.name
            ?: ""
        "year" -> candidate.song?.year?.takeIf { it > 0 }?.toString()
            ?: candidate.album?.year?.takeIf { it > 0 }?.toString()
            ?: ""
        "genre" -> candidate.song?.genre ?: ""
        "composer" -> candidate.song?.composer ?: ""
        "copyright" -> candidate.song?.company ?: candidate.album?.company ?: ""
        else -> ""
    }

    private fun trackForFile(candidate: FieldCandidate, track: AudioTrack): SongInfo? {
        if (candidate.tracks.isEmpty()) return null
        val parsed = FileNames.parse(track.fileName)
        val probe = track.tags.title.ifBlank { parsed.title }
        return candidate.tracks.maxByOrNull { song ->
            var score = Matcher.similarity(probe, song.name)
            if (song.trackNo in 1..100 && track.tags.track.isNotEmpty()) {
                val current = track.tags.track.substringBefore('/').trim().toIntOrNull()
                if (current != null && current == song.trackNo) score += 0.05
            }
            score
        }
    }

    /** 整曲匹配：按文件名/标签猜关键词，取最优候选并补全歌词。 */
    suspend fun matchWhole(track: AudioTrack, limit: Int = 8): WholeMatch {
        val keyword = FieldCatalog.keywordFor(track.tags, track.fileName, "title")
        if (keyword.isBlank()) return WholeMatch(null, 0.0, listOf("无法生成搜索关键词"), "")
        val songs = client.searchSongs(keyword, limit)
        val (best, score) = Matcher.bestOf(track.tags, songs, track.fileName, track.durationSeconds)
            ?: return WholeMatch(null, 0.0, listOf("无搜索结果"), keyword)
        if (best == null) return WholeMatch(null, 0.0, score.notes, keyword)

        var song = best
        try {
            val detail = client.songDetail(best.id)
            song = song.copy(
                year = if (detail.year > 0) detail.year else song.year,
                company = detail.company.ifEmpty { song.company },
                albumArtist = detail.albumArtist.ifEmpty { song.albumArtist },
                trackNo = if (detail.trackNo > 0) detail.trackNo else song.trackNo,
                picUrl = song.picUrl.ifEmpty { detail.picUrl },
            )
            val (lyric, translated, _) = client.lyric(song.id)
            song = song.copy(
                lyric = lyric,
                lyricTranslated = translated,
                lyricMerged = Lyrics.merge(lyric, translated),
            )
        } catch (ex: Exception) {
            log.warn("整曲匹配补全失败 ${best.id}: ${ex.message}")
        }
        return WholeMatch(song, score.value, score.notes, keyword)
    }

    /** 批量整曲匹配（并发受 workers 限制）。 */
    suspend fun matchMany(
        tracks: List<AudioTrack>,
        workers: Int = 4,
        onProgress: ((Int, Int, AudioTrack) -> Unit)? = null,
    ): List<AudioTrack> = coroutineScope {
        val semaphore = Semaphore(maxOf(1, workers))
        var done = 0
        val results = tracks.map { track ->
            async(dispatcher) {
                semaphore.withPermit {
                    val updated = try {
                        val match = matchWhole(track)
                        when {
                            match.song == null -> track.copy(status = TrackStatus.NotFound, query = match.query)
                            match.score >= 0.72 -> track.copy(
                                status = TrackStatus.Matched,
                                score = match.score,
                                query = match.query,
                            )
                            else -> track.copy(
                                status = TrackStatus.Ambiguous,
                                score = match.score,
                                query = match.query,
                            )
                        }
                    } catch (ex: Exception) {
                        log.error("${track.fileName} 匹配失败：${ex.message}")
                        track.copy(status = TrackStatus.Error)
                    }
                    done += 1
                    onProgress?.invoke(done, tracks.size, updated)
                    updated
                }
            }
        }.awaitAll()
        results
    }

    /** 把整曲匹配结果转成写入字段。 */
    fun buildSpec(song: SongInfo, fields: List<String>, lyricsMode: String): Map<String, String> {
        val all = mapOf(
            "title" to song.name,
            "artist" to song.artistsText,
            "album" to song.album,
            "album_artist" to song.albumArtist,
            "track" to if (song.trackNo > 0) song.trackNo.toString() else "",
            "disc" to if (song.discNo > 0) song.discNo.toString() else "",
            "year" to if (song.year > 0) song.year.toString() else "",
            "genre" to song.genre,
            "composer" to song.composer,
            "copyright" to song.company,
            "lyrics" to when (lyricsMode) {
                "original" -> song.lyric
                "translated" -> song.lyricTranslated.ifEmpty { song.lyric }
                else -> song.lyricMerged.ifEmpty { song.lyric }
            },
        )
        return all.filterKeys { it in fields }.filterValues { it.isNotBlank() }
    }
}

data class WholeMatch(
    val song: SongInfo?,
    val score: Double,
    val notes: List<String>,
    val query: String,
)
