package app.tagger.ui

import app.tagger.core.*
import app.tagger.core.tags.Fields
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath

/** 平台相关的目录选择：桌面弹系统对话框，移动端可让用户手输路径。 */
interface FolderPicker {
    suspend fun pickFolder(currentDirectory: String): String?
}

/** 界面需要观察的全部状态。 */
data class UiState(
    val tracks: List<AudioTrack> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val activePath: String = "",
    val filter: String = "",
    val busy: Boolean = false,
    val scanning: Boolean = false,
    val statusText: String = "就绪 — 先选择音乐目录并扫描曲库",
    val lookup: FieldLookupResult? = null,
    val lookupLoading: Boolean = false,
    val pending: StagedWrite? = null,
    val drafts: Map<String, Map<String, String>> = emptyMap(),
    val logs: List<RunLog.Entry> = emptyList(),
    val directory: String = "",
    val showFolderDialog: Boolean = false,
    val progressText: String = "",
) {
    val activeTrack: AudioTrack?
        get() = tracks.firstOrNull { it.path == activePath }

    val filteredTracks: List<AudioTrack>
        get() {
            val keyword = filter.trim()
            if (keyword.isEmpty()) return tracks
            return tracks.filter {
                it.fileName.contains(keyword, ignoreCase = true) ||
                    it.tags.title.contains(keyword, ignoreCase = true) ||
                    it.tags.artist.contains(keyword, ignoreCase = true) ||
                    it.tags.album.contains(keyword, ignoreCase = true)
            }
        }

    val summary: Triple<Int, Int, Int>?
        get() = if (tracks.isEmpty()) {
            null
        } else {
            Triple(
                tracks.size,
                tracks.count { it.tags.hasCover },
                tracks.count { it.status == TrackStatus.Matched || it.status == TrackStatus.Written },
            )
        }
}

/**
 * 状态与动作。只依赖 common 代码，三端共用。
 * 状态通过 StateFlow 暴露，Compose 里用 collectAsState() 观察。
 */
class TaggerState(
    private val fileSystem: FileSystem,
    private val folderPicker: FolderPicker,
    private val settingsStore: SettingsStore = SettingsStore(fileSystem),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val platformLabel: String = "平台：${Platform.name}"

    private var settings: AppSettings = settingsStore.load()
    private val _state = MutableStateFlow(UiState(directory = settings.lastDirectory))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val wholeMatches = mutableMapOf<String, SongInfo>()

    private val log = RunLog(fileSystem, "${Platform.appDataDirectory()}/logs/run.log").also {
        it.info("启动：${Platform.name}")
    }
    private val client = NeteaseClient(
        HttpClientFactory.create(),
        cookie = settings.cookie,
        rate = settings.rateLimit,
    )
    private val search = SearchService(client, log, ioDispatcher)
    private val pendingService = PendingService(fileSystem, client, log, settings)

    private fun mutate(transform: (UiState) -> UiState) {
        _state.update(transform)
        _state.update { it.copy(logs = log.snapshot) }
    }

    fun currentSettings(): AppSettings = settings

    // ---------------------------------------------------------------- 交互

    fun setFilter(value: String) = mutate { it.copy(filter = value) }

    fun toggleSelection(path: String) = mutate {
        val next = it.selectedPaths.toMutableSet()
        if (!next.add(path)) next.remove(path)
        it.copy(selectedPaths = next)
    }

    fun selectAll() = mutate { it.copy(selectedPaths = it.filteredTracks.map { t -> t.path }.toSet()) }

    fun selectNone() = mutate { it.copy(selectedPaths = emptySet()) }

    fun selectIncomplete() = mutate {
        it.copy(
            selectedPaths = it.filteredTracks.filter { track ->
                track.tags.title.isBlank() || track.tags.artist.isBlank() ||
                    track.tags.album.isBlank() || !track.tags.hasCover || track.tags.lyrics.isBlank()
            }.map { track -> track.path }.toSet(),
        )
    }

    fun activate(track: AudioTrack) = mutate { it.copy(activePath = track.path) }

    fun openFolderDialog() = mutate { it.copy(showFolderDialog = true) }

    fun closeFolderDialog() = mutate { it.copy(showFolderDialog = false) }

    suspend fun pickFolder(): String? = folderPicker.pickFolder(_state.value.directory)

    suspend fun applyDirectory(directory: String) {
        settings = settings.copy(lastDirectory = directory)
        settingsStore.save(settings)
        mutate { it.copy(directory = directory, showFolderDialog = false) }
        scan()
    }

    // ---------------------------------------------------------------- 扫描

    suspend fun scan() {
        val directory = _state.value.directory.ifBlank { settings.lastDirectory }
        if (directory.isBlank()) {
            mutate { it.copy(statusText = "先选择一个音乐目录", showFolderDialog = true) }
            return
        }
        val root = directory.toPath()
        if (!fileSystem.exists(root)) {
            mutate { it.copy(statusText = "目录不存在：$directory") }
            return
        }

        mutate { it.copy(busy = true, scanning = true, statusText = "正在扫描…", progressText = "") }
        try {
            val files = withContext(ioDispatcher) {
                Library.listAudioFiles(fileSystem, root, settings.recursive)
            }
            log.info("发现 ${files.size} 个音频文件（$directory）")
            val tracks = mutableListOf<AudioTrack>()
            for ((index, file) in files.withIndex()) {
                val snapshot = withContext(ioDispatcher) {
                    app.tagger.core.tags.TagEngine.read(fileSystem, file)
                }
                tracks += Library.toTrack(fileSystem, file, root, snapshot)
                if (index % 5 == 0) {
                    mutate {
                        it.copy(progressText = "读取标签 ${index + 1}/${files.size}")
                    }
                }
            }
            mutate {
                it.copy(
                    tracks = tracks,
                    selectedPaths = emptySet(),
                    drafts = emptyMap(),
                    activePath = tracks.firstOrNull()?.path ?: "",
                    statusText = "扫描完成：${tracks.size} 个文件",
                    progressText = "",
                )
            }
            log.info("扫描完成：${tracks.size} 个文件")
        } catch (ex: kotlinx.coroutines.CancellationException) {
            // 扫描被取消（例如界面作用域被销毁）不应报成"扫描失败"
            log.warn("扫描已取消")
            mutate { it.copy(statusText = "扫描已取消", progressText = "") }
            throw ex
        } catch (ex: Exception) {
            log.error("扫描失败：${ex.message}")
            mutate { it.copy(statusText = "扫描失败：${ex.message}", progressText = "") }
        } finally {
            mutate { it.copy(busy = false, scanning = false) }
        }
    }

    // ---------------------------------------------------------------- 查找

    suspend fun lookupField(fieldKey: String) {
        val track = _state.value.activeTrack ?: return
        mutate { it.copy(lookupLoading = true, statusText = "按「${Fields.label(fieldKey)}」查找中…") }
        try {
            val kind = FieldCatalog.defaultQuery(fieldKey)
            val result = search.searchField(
                track,
                fieldKey,
                kind,
                limit = 12,
                lyricsMode = settings.lyricsMode,
            )
            mutate {
                it.copy(
                    lookup = result,
                    statusText = "找到 ${result.candidates.size} 个候选",
                )
            }
        } catch (ex: Exception) {
            log.error("查找失败：${ex.message}")
            mutate { it.copy(statusText = "查找失败：${ex.message}") }
        } finally {
            mutate { it.copy(lookupLoading = false) }
        }
    }

    fun resolveCandidate(fieldKey: String, candidate: FieldCandidate): Pair<String, String> {
        val track = _state.value.activeTrack ?: return "" to "没有选中曲目"
        return search.resolveForTrack(fieldKey, candidate, track, settings.lyricsMode)
    }

    /** 采用候选：生成确认清单（不落盘）。 */
    fun applyCandidate(candidate: FieldCandidate) {
        val snapshot = _state.value
        val track = snapshot.activeTrack ?: return
        val lookup = snapshot.lookup ?: return
        val fieldKey = lookup.fieldKey
        val (value, hint) = resolveCandidate(fieldKey, candidate)
        if (value.isEmpty()) {
            mutate { it.copy(statusText = "这个候选取不到值：$hint") }
            return
        }
        val decision = FieldDecision(
            path = track.path,
            fieldKey = fieldKey,
            value = value,
            sourceKind = candidate.source,
            sourceId = candidate.sourceId,
            coverUrl = candidate.song?.picUrl ?: candidate.album?.picUrl ?: candidate.artist?.picUrl ?: "",
            writeCover = false,
            writeLrc = settings.writeLrc && fieldKey == "lyrics",
            lyricsMode = settings.lyricsMode,
        )
        val staged = pendingService.stageFieldDecisions(
            listOf(decision),
            "按「${Fields.label(fieldKey)}」写入",
        )
        val drafts = snapshot.drafts.toMutableMap()
        drafts[track.path] = (drafts[track.path] ?: emptyMap()) + (fieldKey to value)
        mutate {
            it.copy(
                pending = staged,
                drafts = drafts,
                statusText = "已加入确认清单：${Fields.label(fieldKey)} = ${value.take(24)}",
            )
        }
    }

    // ---------------------------------------------------------------- 整曲匹配

    suspend fun matchSelected() {
        val snapshot = _state.value
        val paths = if (snapshot.selectedPaths.isEmpty()) {
            listOfNotNull(snapshot.activePath.takeIf { it.isNotBlank() })
        } else {
            snapshot.selectedPaths.toList()
        }
        if (paths.isEmpty()) {
            mutate { it.copy(statusText = "先选中要匹配的曲目") }
            return
        }
        runMatch(paths)
    }

    suspend fun matchAll() {
        val paths = _state.value.tracks.map { it.path }
        if (paths.isEmpty()) {
            mutate { it.copy(statusText = "曲库是空的") }
            return
        }
        runMatch(paths)
    }

    private suspend fun runMatch(paths: List<String>) {
        mutate { it.copy(busy = true, statusText = "整曲匹配中…", progressText = "") }
        try {
            val snapshot = _state.value
            val targets = snapshot.tracks.filter { it.path in paths }
            val updated = search.matchMany(targets, settings.workers) { done, total, _ ->
                mutate { it.copy(progressText = "整曲匹配 $done/$total") }
            }
            // 记录匹配到的歌曲，供生成整曲清单使用
            for (track in updated) {
                if (track.status == TrackStatus.Matched || track.status == TrackStatus.Ambiguous) {
                    val query = FieldCatalog.keywordFor(track.tags, track.fileName, "title")
                    if (query.isNotBlank()) {
                        runCatching { client.searchSongs(query, 1).firstOrNull() }
                            .getOrNull()
                            ?.let { wholeMatches[track.path] = it }
                    }
                }
            }
            val byPath = updated.associateBy { it.path }
            val matched = updated.count { it.status == TrackStatus.Matched }
            val ambiguous = updated.count { it.status == TrackStatus.Ambiguous }
            mutate {
                it.copy(
                    tracks = it.tracks.map { track -> byPath[track.path] ?: track },
                    statusText = "整曲匹配完成：$matched 首已匹配，$ambiguous 首待确认（记得进确认清单核对）",
                    progressText = "",
                )
            }
            log.info("整曲匹配完成：$matched 已匹配，$ambiguous 待确认")
        } catch (ex: Exception) {
            log.error("匹配失败：${ex.message}")
            mutate { it.copy(statusText = "匹配失败：${ex.message}", progressText = "") }
        } finally {
            mutate { it.copy(busy = false) }
        }
    }

    /** 用当前曲目的整曲匹配结果生成待确认清单。 */
    suspend fun stageWholeSong(): StagedWrite? {
        val snapshot = _state.value
        val track = snapshot.activeTrack ?: return null
        var song = wholeMatches[track.path]
        if (song == null) {
            mutate { it.copy(busy = true, statusText = "正在匹配…") }
            try {
                val match = search.matchWhole(track)
                song = match.song
                if (song != null) wholeMatches[track.path] = song
                else mutate { it.copy(statusText = "没有找到候选（关键词「${match.query}」）") }
            } catch (ex: Exception) {
                log.error("匹配失败：${ex.message}")
                mutate { it.copy(statusText = "匹配失败：${ex.message}") }
            } finally {
                mutate { it.copy(busy = false) }
            }
        }
        val resolved = song ?: return null
        return pendingService.stageWholeSong(
            track,
            resolved,
            settings.writeFields,
            settings.lyricsMode,
        )
    }

    // ---------------------------------------------------------------- 写入

    suspend fun commit(staged: StagedWrite, dryRun: Boolean): CommitResult {
        mutate { it.copy(busy = true, statusText = if (dryRun) "预演中…" else "写入中…") }
        try {
            val result = pendingService.commit(staged, dryRun)
            mutate {
                it.copy(
                    statusText = if (dryRun) {
                        "预演完成：将写入 ${result.summary.written} 个文件、改动 ${result.summary.changed} 项"
                    } else {
                        "写入完成：成功 ${result.summary.written} / 跳过 ${result.summary.skipped} / 失败 ${result.summary.failed}"
                    },
                )
            }
            if (!dryRun && result.summary.written > 0) {
                refreshTracks(result.items.filter { it.ok }.map { item -> item.path })
                mutate { it.copy(pending = staged.selectNone(), drafts = emptyMap()) }
            }
            return result
        } finally {
            mutate { it.copy(busy = false) }
        }
    }

    /** 写入后重新读取受影响文件的标签。 */
    private suspend fun refreshTracks(paths: List<String>) {
        val snapshot = _state.value
        val updated = snapshot.tracks.map { track ->
            if (track.path !in paths) return@map track
            val fresh = withContext(ioDispatcher) {
                app.tagger.core.tags.TagEngine.read(fileSystem, track.path.toPath())
            }
            Library.toTrack(fileSystem, track.path.toPath(), null, fresh)
                .copy(status = TrackStatus.Written)
        }
        mutate { it.copy(tracks = updated) }
    }

    fun updateStaged(staged: StagedWrite) = mutate { it.copy(pending = staged) }

    // ---------------------------------------------------------------- 设置

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settings = transform(settings)
        settingsStore.save(settings)
        mutate { it.copy() }
    }

    fun logEntries(): List<RunLog.Entry> = log.snapshot
}

/** 设置读写：路径固定在平台数据目录。 */
class SettingsStore(private val fileSystem: FileSystem) {
    private val path: String = AppSettings.settingsPath()

    fun load(): AppSettings = AppSettings.load(fileSystem, path)

    fun save(settings: AppSettings) = AppSettings.save(fileSystem, settings, path)
}
