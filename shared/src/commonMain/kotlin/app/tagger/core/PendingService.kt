package app.tagger.core

import app.tagger.core.tags.TagChange
import app.tagger.core.tags.mime
import app.tagger.core.tags.TagEngine
import app.tagger.core.tags.TagWriteResult
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * 待写清单 + 人工确认。
 *
 * 核心约束：先算出每个文件的改动给人看，只有勾选并提交的条目才落盘。
 * 「没勾选就不写」这个语义由单元测试保证，不依赖界面。
 */

/** 某个文件的某个字段要写什么值（界面挑选的结果）。 */
data class FieldDecision(
    val path: String,
    val fieldKey: String,
    val value: String,
    val sourceKind: String = "",
    val sourceId: Long = 0,
    val coverUrl: String = "",
    val writeCover: Boolean = false,
    val writeLrc: Boolean = true,
    val lyricsMode: String = "merged",
)

/** 确认清单里的一行（一个文件）。 */
data class WriteProposal(
    val id: String,
    val path: String,
    val fileName: String,
    val fieldKey: String,
    val fieldLabel: String,
    val changes: List<TagChange> = emptyList(),
    val notes: List<String> = emptyList(),
    val coverUrl: String = "",
    val lrcText: String = "",
    val confirmed: Boolean = true,
    val writable: Boolean = true,
    val error: String = "",
    /** 真正要写进文件的字段（界面不直接展示）。 */
    val spec: Map<String, String> = emptyMap(),
) {
    val hasLrc: Boolean get() = lrcText.isNotEmpty()
    val changeCount: Int get() = changes.size

    val primaryHint: String
        get() = when {
            error.isNotEmpty() -> "异常：$error"
            changes.isEmpty() -> "无差异，写入会被跳过"
            notes.isNotEmpty() -> notes.first()
            else -> "${changes.size} 项改动"
        }

    fun withConfirmed(value: Boolean): WriteProposal = copy(confirmed = value)
}

data class StageSummary(
    val files: Int,
    val withChanges: Int,
    val noChange: Int,
    val failed: Int,
    val covers: Int,
    val lrc: Int,
    val notWritable: Int,
    val changeCount: Int,
    val byField: Map<String, Int>,
)

data class StagedWrite(
    val id: String,
    val title: String,
    val proposals: List<WriteProposal>,
    val backup: Boolean,
) {
    val summary: StageSummary by lazy {
        val byField = mutableMapOf<String, Int>()
        for (proposal in proposals) {
            for (change in proposal.changes) {
                byField[change.field] = (byField[change.field] ?: 0) + 1
            }
        }
        StageSummary(
            files = proposals.size,
            withChanges = proposals.count { it.changes.isNotEmpty() },
            noChange = proposals.count { it.changes.isEmpty() },
            failed = proposals.count { it.error.isNotEmpty() },
            covers = proposals.count { it.coverUrl.isNotEmpty() },
            lrc = proposals.count { it.hasLrc },
            notWritable = proposals.count { !it.writable },
            changeCount = proposals.sumOf { it.changes.size },
            byField = byField,
        )
    }

    val selectedCount: Int get() = proposals.count { it.confirmed }

    fun withConfirmation(decisions: Map<String, Boolean>): StagedWrite = copy(
        proposals = proposals.map { it.withConfirmed(decisions[it.id] ?: it.confirmed) },
    )

    fun selectAll(): StagedWrite = copy(proposals = proposals.map { it.withConfirmed(true) })

    fun selectNone(): StagedWrite = copy(proposals = proposals.map { it.withConfirmed(false) })

    fun selectChangedOnly(): StagedWrite = copy(
        proposals = proposals.map { it.withConfirmed(it.changes.isNotEmpty() && it.error.isEmpty()) },
    )
}

data class CommitSummary(
    val total: Int,
    val written: Int,
    val skipped: Int,
    val failed: Int,
    val changed: Int,
    val dryRun: Boolean,
    val messages: List<String>,
)

data class CommitItem(
    val path: String,
    val fileName: String,
    val ok: Boolean,
    val skipped: Boolean,
    val reason: String,
    val changes: List<TagChange>,
    val backupPath: String = "",
)

data class CommitResult(
    val summary: CommitSummary,
    val items: List<CommitItem>,
)

class PendingService(
    private val fileSystem: FileSystem,
    private val client: NeteaseClient,
    private val log: RunLog,
    private val settings: AppSettings,
) {

    private var counter = 0

    /**
     * 单字段模式：把人工挑选的字段值生成待确认清单（不落盘）。
     *
     * 同一个文件的多个字段会**合并成一条**清单 —— 否则确认列表里同一首歌会出现多行，
     * 提交时也会对同一文件反复写，容易出错。
     */
    fun stageFieldDecisions(decisions: List<FieldDecision>, title: String = "按字段写入"): StagedWrite {
        val proposals = mutableListOf<WriteProposal>()
        for ((path, group) in decisions.groupBy { it.path }) {
            counter += 1
            proposals += buildMergedFieldProposal(counter.toString(), path, group)
        }
        val staged = StagedWrite(
            id = "stage-$counter",
            title = title,
            proposals = proposals,
            backup = settings.backup,
        )
        log.info("生成待确认清单：${staged.summary.files} 个文件，${staged.summary.changeCount} 处改动")
        return staged
    }

    /** 手动编辑模式：只写界面上改过的字段。 */
    fun stageManualEdit(
        track: AudioTrack,
        spec: Map<String, String>,
        coverUrl: String = "",
        writeLrc: Boolean = true,
        title: String = "手动编辑",
    ): StagedWrite {
        counter += 1
        val snapshot = TagEngine.read(fileSystem, track.path.toPath())
        val proposal = WriteProposal(
            id = counter.toString(),
            path = track.path,
            fileName = track.fileName,
            fieldKey = "",
            fieldLabel = if (spec.size == 1) FieldCatalog.label(spec.keys.first()) else "手动编辑",
        )
        if (snapshot.error.isNotEmpty()) {
            return StagedWrite("stage-manual-$counter", title, listOf(proposal.copy(error = snapshot.error, writable = false)), settings.backup)
        }
        val changes = mutableListOf<TagChange>()
        val cleanedSpec = mutableMapOf<String, String>()
        for ((key, raw) in spec) {
            val value = raw.trim()
            val old = snapshot.get(key)
            if (old != value) changes += TagChange(key, old, value)
            if (value.isNotEmpty()) cleanedSpec[key] = value
        }
        if (coverUrl.isNotBlank()) {
            changes += TagChange(
                "cover",
                if (snapshot.hasCover) "${snapshot.coverMime} ${snapshot.coverSize} B" else "",
                "下载并内嵌",
            )
        }
        val lrc = if (writeLrc) spec["lyrics"]?.takeIf { it.isNotBlank() } ?: "" else ""
        val staged = StagedWrite(
            id = "stage-manual-$counter",
            title = title,
            proposals = listOf(
                proposal.copy(
                    changes = changes,
                    spec = cleanedSpec,
                    coverUrl = coverUrl,
                    lrcText = lrc,
                    writable = snapshot.writable,
                ),
            ),
            backup = settings.backup,
        )
        log.info("生成手动编辑清单：${staged.summary.changeCount} 处改动")
        return staged
    }

    /** 整曲模式：按字段集合 + 候选歌曲生成清单。 */
    suspend fun stageWholeSong(
        track: AudioTrack,
        song: SongInfo,
        fields: List<String>,
        lyricsMode: String,
        coverUrl: String = "",
    ): StagedWrite {
        counter += 1
        val snapshot = TagEngine.read(fileSystem, track.path.toPath())
        val spec = SearchService(client, log).buildSpec(song, fields, lyricsMode)
        val proposal = WriteProposal(
            id = counter.toString(),
            path = track.path,
            fileName = track.fileName,
            fieldKey = "",
            fieldLabel = "整曲",
        )
        if (snapshot.error.isNotEmpty()) {
            return StagedWrite("stage-song-$counter", "整曲写入", listOf(proposal.copy(error = snapshot.error, writable = false)), settings.backup)
        }
        val changes = mutableListOf<TagChange>()
        for ((key, value) in spec) {
            val old = snapshot.get(key)
            if (old != value) changes += TagChange(key, old, value)
        }
        val lrc = if ("lyrics" in fields) spec["lyrics"].orEmpty() else ""
        val url = coverUrl.ifBlank { NeteaseClient.coverUrl(song.picUrl, settings.coverSize) }
        if (settings.writeCover && url.isNotBlank()) {
            changes += TagChange(
                "cover",
                if (snapshot.hasCover) "${snapshot.coverMime} ${snapshot.coverSize} B" else "",
                "下载并内嵌",
            )
        }
        val staged = StagedWrite(
            id = "stage-song-$counter",
            title = "整曲写入",
            proposals = listOf(
                proposal.copy(
                    changes = changes,
                    spec = spec,
                    coverUrl = if (settings.writeCover) url else "",
                    lrcText = lrc,
                    writable = snapshot.writable,
                ),
            ),
            backup = settings.backup,
        )
        log.info("生成整曲待确认清单：${staged.summary.changeCount} 处改动")
        return staged
    }

    /**
     * 一个文件的多条字段决策合并成一条清单。
     * 后面出现的同名字段覆盖前面的值（界面点选顺序即优先级）。
     */
    private fun buildMergedFieldProposal(
        id: String,
        path: String,
        decisions: List<FieldDecision>,
    ): WriteProposal {
        val snapshot = TagEngine.read(fileSystem, path.toPath())
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        val label = if (decisions.size == 1) {
            FieldCatalog.label(decisions.first().fieldKey)
        } else {
            decisions.map { FieldCatalog.label(it.fieldKey) }.distinct().joinToString(" + ")
        }
        val proposal = WriteProposal(
            id = id,
            path = path,
            fileName = fileName,
            fieldKey = if (decisions.size == 1) decisions.first().fieldKey else "",
            fieldLabel = label,
        )
        if (snapshot.error.isNotEmpty()) {
            return proposal.copy(error = snapshot.error, writable = false)
        }

        val notes = mutableListOf<String>()
        val spec = mutableMapOf<String, String>()
        var wantedCover = ""
        var lrc = ""

        for (decision in decisions) {
            val value = decision.value.trim()
            if (value.isNotEmpty()) spec[decision.fieldKey] = value
            if (decision.writeCover && decision.coverUrl.isNotBlank()) wantedCover = decision.coverUrl
            if (decision.writeLrc && decision.fieldKey == "lyrics" && value.isNotEmpty()) lrc = value
        }

        val changes = mutableListOf<TagChange>()
        for ((key, newValue) in spec) {
            val old = snapshot.get(key)
            if (old != newValue) changes += TagChange(key, old, newValue)
        }
        if (wantedCover.isNotEmpty()) {
            if (!TagEngine.capsFor(path).cover) {
                notes += "该格式不支持内嵌封面，封面将被跳过"
            }
            changes += TagChange(
                "cover",
                if (snapshot.hasCover) "${snapshot.coverMime} ${snapshot.coverSize} B" else "",
                "下载并内嵌",
            )
        }
        if (spec.isEmpty() && changes.isEmpty()) notes += "没有需要写入的内容"

        return proposal.copy(
            changes = changes,
            notes = notes,
            spec = spec,
            coverUrl = wantedCover,
            lrcText = lrc,
            writable = snapshot.writable,
        )
    }

    /** 只对勾选的条目落盘。未勾选的直接跳过，文件不会被碰。 */
    suspend fun commit(staged: StagedWrite, dryRun: Boolean = false): CommitResult {
        val items = mutableListOf<CommitItem>()
        var written = 0
        var skipped = 0
        var failed = 0
        var changed = 0
        val messages = mutableListOf<String>()

        for (proposal in staged.proposals) {
            when {
                !proposal.confirmed -> {
                    skipped += 1
                    messages += "${proposal.fileName}: 未勾选，跳过"
                    items += CommitItem(proposal.path, proposal.fileName, false, true, "未勾选", emptyList())
                    continue
                }
                proposal.error.isNotEmpty() -> {
                    skipped += 1
                    messages += "${proposal.fileName}: ${proposal.error}"
                    items += CommitItem(proposal.path, proposal.fileName, false, true, proposal.error, emptyList())
                    continue
                }
                proposal.spec.isEmpty() && proposal.coverUrl.isEmpty() -> {
                    skipped += 1
                    messages += "${proposal.fileName}: 没有需要写入的内容"
                    items += CommitItem(proposal.path, proposal.fileName, false, true, "没有内容", emptyList())
                    continue
                }
            }

            var cover: ByteArray? = null
            var coverMime = ""
            if (proposal.coverUrl.isNotEmpty() && TagEngine.capsFor(proposal.path).cover) {
                try {
                    cover = client.fetchBytes(proposal.coverUrl)
                    coverMime = cover.mime()
                } catch (ex: Exception) {
                    messages += "${proposal.fileName}: 封面下载失败 ${ex.message}"
                }
            }

            val result: TagWriteResult = TagEngine.write(
                fileSystem = fileSystem,
                path = proposal.path.toPath(),
                spec = proposal.spec,
                cover = cover,
                coverMime = coverMime,
                dryRun = dryRun,
                backup = staged.backup,
            )

            if (result.ok) {
                written += 1
                changed += result.changes.size
                messages += "${proposal.fileName}: ${if (dryRun) "预演 " else ""}更新 ${result.changes.size} 项"
                if (!dryRun && proposal.lrcText.isNotEmpty()) {
                    try {
                        LrcFiles.write(fileSystem, proposal.path, proposal.lrcText)
                    } catch (ex: Exception) {
                        messages += "${proposal.fileName}: .lrc 写入失败 ${ex.message}"
                    }
                }
                log.info("${if (dryRun) "[预演] " else ""}${proposal.fileName} 更新 ${result.changes.size} 项")
                items += CommitItem(proposal.path, proposal.fileName, true, false, "", result.changes, result.backupPath)
            } else {
                failed += 1
                messages += "${proposal.fileName}: 写入失败 ${result.error}"
                log.error("${proposal.fileName} 写入失败：${result.error}")
                items += CommitItem(proposal.path, proposal.fileName, false, false, result.error, result.changes, result.backupPath)
            }
        }

        val summary = CommitSummary(
            total = staged.proposals.size,
            written = written,
            skipped = skipped,
            failed = failed,
            changed = changed,
            dryRun = dryRun,
            messages = messages,
        )
        log.info("确认写入完成：写入 $written / 跳过 $skipped / 失败 $failed，改动 $changed 项" +
            if (dryRun) "（预演）" else "")
        return CommitResult(summary, items)
    }
}

/** 同名 .lrc 歌词文件。 */
object LrcFiles {

    fun pathFor(audioPath: String): String {
        val dot = audioPath.lastIndexOf('.')
        val slash = maxOf(audioPath.lastIndexOf('/'), audioPath.lastIndexOf('\\'))
        return if (dot > slash) audioPath.substring(0, dot) + ".lrc" else "$audioPath.lrc"
    }

    fun exists(fileSystem: FileSystem, audioPath: String): Boolean =
        fileSystem.exists(pathFor(audioPath).toPath())

    fun read(fileSystem: FileSystem, audioPath: String): String {
        val path = pathFor(audioPath).toPath()
        return try {
            if (!fileSystem.exists(path)) "" else fileSystem.read(path) { readUtf8() }
        } catch (ex: Exception) {
            ""
        }
    }

    fun write(fileSystem: FileSystem, audioPath: String, content: String): String {
        val target = pathFor(audioPath).toPath()
        val bytes = content.encodeToByteArray()
        fileSystem.write(target) { write(bytes) }
        return target.toString()
    }
}
