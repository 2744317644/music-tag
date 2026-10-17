@file:OptIn(com.moriafly.salt.ui.UnstableSaltUiApi::class)

package app.tagger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tagger.core.AudioTrack
import app.tagger.core.FieldCandidate
import app.tagger.core.StagedWrite
import app.tagger.core.TrackStatus
import app.tagger.core.tags.Fields
import com.moriafly.salt.ui.Button
import com.moriafly.salt.ui.ButtonType
import com.moriafly.salt.ui.Item
import com.moriafly.salt.ui.ItemArrowType
import com.moriafly.salt.ui.ItemCheck
import com.moriafly.salt.ui.ItemDivider
import com.moriafly.salt.ui.ItemSpacer
import com.moriafly.salt.ui.ItemTip
import com.moriafly.salt.ui.ItemTitle
import com.moriafly.salt.ui.ItemValue
import com.moriafly.salt.ui.RoundedColumn
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.Surface
import com.moriafly.salt.ui.Text
import kotlinx.coroutines.launch

/**
 * 主界面：全部使用 SaltUI 组件与配色。
 *
 * 左：曲目列表（ItemCheck 多选）
 * 右：文件信息 + 标签逐项确认（Item 点击即查该字段）+ 查找结果 + 歌词
 */
@Composable
fun TaggerApp(state: TaggerState, initialReview: StagedWrite? = null) {
    val scope = rememberCoroutineScope()
    val ui by state.state.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }
    var confirmStaged by remember { mutableStateOf<StagedWrite?>(initialReview) }
    var showConfirmByArg by remember { mutableStateOf(initialReview != null) }

    TaggerTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(SaltTheme.colors.background),
            ) {
                SaltTopBar(
                    platformLabel = state.platformLabel,
                    busy = ui.busy,
                    scanning = ui.scanning,
                    hasTracks = ui.tracks.isNotEmpty(),
                    canMatch = ui.selectedPaths.isNotEmpty() || ui.activePath.isNotBlank(),
                    onPickFolder = { state.openFolderDialog() },
                    onScan = { scope.launch { state.scan() } },
                    onMatch = { scope.launch { state.matchSelected() } },
                    onMatchAll = { scope.launch { state.matchAll() } },
                    onOpenReview = {
                        scope.launch {
                            confirmStaged = ui.pending ?: state.stageWholeSong()
                            showConfirm = confirmStaged != null
                        }
                    },
                )
                SaltHairline()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    TrackPane(state, ui, Modifier.weight(1.35f).fillMaxHeight())
                    SaltVerticalHairline()
                    DetailPane(state, ui, Modifier.weight(1f).fillMaxHeight())
                }
                SaltHairline()
                SaltStatusBar(
                    statusText = ui.statusText,
                    progressText = ui.progressText,
                    busy = ui.busy,
                    summary = ui.summary,
                )
            }
        }
    }

    if (ui.showFolderDialog) FolderDialog(state)

    val staged = confirmStaged
    if ((showConfirm || showConfirmByArg) && staged != null) {
        ConfirmDialog(
            state = state,
            staged = staged,
            onDismiss = { showConfirm = false },
            onCommitted = {
                showConfirm = false
                confirmStaged = null
            },
        )
    }
}

@Composable
private fun TrackPane(state: TaggerState, ui: UiState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Column(Modifier.padding(horizontal = SaltTheme.dimens.padding, vertical = 8.dp)) {
            SaltTextField(
                value = ui.filter,
                onValueChange = { state.setFilter(it) },
                hint = "筛选文件名 / 标题 / 艺术家",
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { state.selectAll() },
                    text = "全选",
                    type = ButtonType.Sub,
                    enabled = ui.tracks.isNotEmpty(),
                )
                Button(
                    onClick = { state.selectNone() },
                    text = "清空",
                    type = ButtonType.Sub,
                    enabled = ui.selectedPaths.isNotEmpty(),
                )
                Button(
                    onClick = { state.selectIncomplete() },
                    text = "选信息不全",
                    type = ButtonType.Sub,
                    enabled = ui.tracks.isNotEmpty(),
                )
            }
        }

        val tracks = ui.filteredTracks
        RoundedColumn(Modifier.weight(1f)) {
            if (tracks.isEmpty()) {
                ItemTip(
                    if (ui.tracks.isEmpty()) {
                        "还没有曲库：点上方「选择目录」，再「扫描曲库」"
                    } else {
                        "没有符合筛选条件的曲目"
                    },
                )
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(tracks, key = { it.path }) { track ->
                        val selected = ui.selectedPaths.contains(track.path)
                        val active = ui.activePath == track.path
                        ItemCheck(
                            state = selected,
                            onChange = {
                                state.toggleSelection(track.path)
                                state.activate(track)
                            },
                            text = track.fileName,
                            sub = trackSubtitle(track),
                            modifier = Modifier.background(
                                if (active) {
                                    SaltTheme.colors.highlight.copy(alpha = 0.12f)
                                } else {
                                    androidx.compose.ui.graphics.Color.Transparent
                                },
                            ),
                        )
                        ItemDivider()
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun trackSubtitle(track: AudioTrack): String {
    val title = track.tags.title.ifBlank { "（无标题）" }
    val artist = track.tags.artist.ifBlank { "（无艺术家）" }
    val extra = buildString {
        append(track.format)
        if (track.tags.hasCover) append(" · 有封面")
        if (track.status != TrackStatus.Pending) append(" · ${track.status.label}")
        if (track.error.isNotEmpty()) append(" · ${track.error}")
    }
    return "$title — $artist · $extra"
}

@Composable
private fun DetailPane(state: TaggerState, ui: UiState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val track = ui.activeTrack

    if (track == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            ItemTip("在左侧点一首歌，右侧可以逐个标签查找、确认、写入")
        }
        return
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // 文件信息
        RoundedColumn {
            ItemTitle("当前文件")
            Item(
                onClick = {},
                text = track.fileName,
                sub = track.path,
                arrowType = ItemArrowType.None,
            )
            ItemValue(
                text = "格式 / 时长",
                sub = "${track.format} · ${formatDuration(track.durationSeconds)}" +
                    if (track.tags.hasCover) " · 封面 ${track.tags.coverSize / 1024} KB" else "",
            )
        }

        // 标签逐项确认
        RoundedColumn {
            ItemTitle("标签逐项确认")
            ItemTip("点哪个标签，就只查、只写哪个标签")
            val drafts = ui.drafts[track.path].orEmpty()
            for (key in Fields.order) {
                val current = track.tags.get(key)
                val staged = drafts[key]
                Item(
                    onClick = { scope.launch { state.lookupField(key) } },
                    text = Fields.label(key),
                    sub = when {
                        staged != null -> "${current.ifBlank { "（空）" }}  →  ${staged.take(40)}"
                        current.isBlank() -> "（空）· 点击查找"
                        else -> current.take(80)
                    },
                    textColor = if (staged != null) SaltTheme.colors.highlight else SaltTheme.colors.text,
                    subColor = if (staged != null) SaltTheme.colors.highlight else SaltTheme.colors.subText,
                    enabled = !ui.busy,
                    arrowType = ItemArrowType.Arrow,
                )
                if (key != Fields.order.last()) ItemDivider()
            }
        }

        // 查找结果
        RoundedColumn {
            val lookup = ui.lookup
            ItemTitle(if (lookup == null) "查找结果" else "查找「${lookup.fieldLabel}」")
            when {
                ui.lookupLoading -> {
                    ItemTip("查找中…")
                    ItemSpacer()
                }

                lookup == null -> {
                    ItemTip("点上面任一标签，就会按那个标签去搜")
                    ItemSpacer()
                }

                lookup.candidates.isEmpty() -> {
                    ItemTip("没有结果。换个关键词或换一种查找方式再试。")
                    ItemSpacer()
                }

                else -> {
                    ItemTip("关键词「${lookup.keyword}」· ${lookup.candidates.size} 个候选")
                    for (candidate in lookup.candidates.take(24)) {
                        CandidateItem(state, lookup.fieldKey, candidate)
                        ItemDivider()
                    }
                }
            }
        }

        // 歌词
        RoundedColumn {
            ItemTitle("歌词")
            ItemValue(
                text = "内嵌歌词（LRC）",
                sub = track.tags.lyrics.ifBlank { "（无内嵌歌词）" },
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CandidateItem(state: TaggerState, fieldKey: String, candidate: FieldCandidate) {
    val (value, hint) = state.resolveCandidate(fieldKey, candidate)
    val sub = buildString {
        if (candidate.subLabel.isNotEmpty()) append(candidate.subLabel)
        if (value.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append("将写入：")
            append(value.replace("\n", " ⏎ ").take(40))
        } else if (hint.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(hint)
        }
    }
    Item(
        onClick = { state.applyCandidate(candidate) },
        text = candidate.label,
        sub = sub,
        arrowType = ItemArrowType.None,
        textColor = if (value.isNotEmpty()) SaltTheme.colors.text else SaltTheme.colors.subText,
    )
}

/** 细分隔线。 */
@Composable
fun SaltHairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(Dp.Hairline)
            .background(SaltTheme.colors.stroke),
    )
}

@Composable
private fun SaltVerticalHairline() {
    Box(
        Modifier
            .fillMaxHeight()
            .width(Dp.Hairline)
            .background(SaltTheme.colors.stroke),
    )
}
