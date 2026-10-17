@file:OptIn(com.moriafly.salt.ui.UnstableSaltUiApi::class)

package app.tagger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tagger.core.CommitResult
import app.tagger.core.StagedWrite
import app.tagger.core.WriteProposal
import app.tagger.core.tags.Fields
import com.moriafly.salt.ui.Button
import com.moriafly.salt.ui.ButtonType
import com.moriafly.salt.ui.Item
import com.moriafly.salt.ui.ItemArrowType
import com.moriafly.salt.ui.ItemCheck
import com.moriafly.salt.ui.ItemDivider
import com.moriafly.salt.ui.ItemSpacer
import com.moriafly.salt.ui.ItemTip
import com.moriafly.salt.ui.RoundedColumn
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.Text
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.window.Dialog
import com.moriafly.salt.ui.dialog.DialogTitle
import com.moriafly.salt.ui.outerPadding
import kotlinx.coroutines.launch

/**
 * 目录选择对话框（Salt 风格）。
 *
 * 注意：Compose 的 Dialog 会创建独立 composition，不继承外层 SaltTheme，
 * 所以对话框内容里要再包一次 TaggerTheme，否则会掉回默认浅色配色。
 */
@Composable
fun FolderDialog(state: TaggerState) {
    val ui by state.state.collectAsState()

    SaltDialog(onDismissRequest = { state.closeFolderDialog() }) {
            Column {
                var text by remember(ui.directory) { mutableStateOf(ui.directory) }
                var hint by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()

                DialogTitle(text = "选择音乐目录")
                ItemTip("输入音乐库的绝对路径。桌面版也可以直接用系统对话框选择。")

                RoundedColumn {
                    Column {
                        SaltTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                hint = ""
                            },
                            hint = "例如 E:\\Music",
                        )
                        if (hint.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(text = hint, color = SaltTheme.colors.highlight, fontSize = 12.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier.outerPadding(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val picked = state.pickFolder()
                                if (picked.isNullOrBlank()) {
                                    hint = "当前平台不支持系统对话框，请直接输入路径"
                                } else {
                                    text = picked
                                    hint = ""
                                }
                            }
                        },
                        text = "系统对话框",
                        type = ButtonType.Sub,
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { state.closeFolderDialog() },
                        text = "取消",
                        type = ButtonType.Sub,
                    )
                    Button(
                        onClick = {
                            if (text.isBlank()) {
                                hint = "请先填写目录"
                            } else {
                                scope.launch { state.applyDirectory(text.trim()) }
                            }
                        },
                        text = "使用并扫描",
                        type = ButtonType.Highlight,
                    )
                }
                ItemSpacer()
            }
        }
}

/**
 * 写入确认清单：把每个文件将要发生的改动摊开，逐条勾选后才落盘。
 * 这是「人工确认」的落点 —— 没勾选的条目绝不会被写入（由 PendingServiceTest 保证）。
 */
@Composable
fun ConfirmDialog(
    state: TaggerState,
    staged: StagedWrite,
    onDismiss: () -> Unit,
    onCommitted: () -> Unit,
) {
    val ui by state.state.collectAsState()

    SaltDialog(onDismissRequest = onDismiss) {
            Column {
                var current by remember(staged.id) { mutableStateOf(ui.pending ?: staged) }
                var dryRun by remember { mutableStateOf(false) }
                var result by remember { mutableStateOf<CommitResult?>(null) }
                var committing by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                DialogTitle(text = "写入确认清单")

                val committed = result
                if (committed != null) {
                    ResultSection(committed, onCommitted)
                } else {
                    val summary = current.summary
                    val selectedCount = current.selectedCount

                    ItemTip(
                        "${current.title} · ${summary.files} 个文件 · 已勾选 $selectedCount · " +
                            "将改动 ${summary.changeCount} 项",
                    )
                    SelectionBar(
                        dryRun = dryRun,
                        onDryRunChange = { dryRun = it },
                        onSelectAll = { current = current.selectAll() },
                        onSelectNone = { current = current.selectNone() },
                        onSelectChanged = { current = current.selectChangedOnly() },
                    )
                    ProposalList(
                        proposals = current.proposals,
                        onToggle = { id, checked ->
                            current = current.withConfirmation(mapOf(id to checked))
                            state.updateStaged(current)
                        },
                    )
                    Row(
                        modifier = Modifier.outerPadding(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = onDismiss, text = "取消", type = ButtonType.Sub)
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                scope.launch {
                                    committing = true
                                    try {
                                        result = state.commit(current, dryRun)
                                    } finally {
                                        committing = false
                                    }
                                }
                            },
                            text = when {
                                committing -> "处理中…"
                                dryRun -> "预演 $selectedCount 个"
                                else -> "确认写入 $selectedCount 个"
                            },
                            enabled = selectedCount > 0 && !committing,
                            type = ButtonType.Highlight,
                        )
                    }
                    ItemSpacer()
                }
            }
        }
}

@Composable
private fun SelectionBar(
    dryRun: Boolean,
    onDryRunChange: (Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onSelectChanged: () -> Unit,
) {
    RoundedColumn {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onSelectAll, text = "全选", type = ButtonType.Sub)
            Button(onClick = onSelectNone, text = "全不选", type = ButtonType.Sub)
            Button(onClick = onSelectChanged, text = "只选有改动", type = ButtonType.Sub)
        }
        ItemDivider()
        Item(
            onClick = { onDryRunChange(!dryRun) },
            text = "只预演，不落盘",
            sub = if (dryRun) "已开启：只算差异，文件不会被修改" else "关闭",
            textColor = if (dryRun) SaltTheme.colors.highlight else SaltTheme.colors.text,
            arrowType = ItemArrowType.None,
        )
    }
}

@Composable
private fun ProposalList(proposals: List<WriteProposal>, onToggle: (String, Boolean) -> Unit) {
    RoundedColumn {
        Box(Modifier.heightIn(max = 340.dp)) {
            LazyColumn {
                items(proposals, key = { it.id }) { proposal ->
                    ProposalItem(
                        proposal = proposal,
                        onToggle = { checked -> onToggle(proposal.id, checked) },
                    )
                    ItemDivider()
                }
            }
        }
    }
}

@Composable
private fun ResultSection(result: CommitResult, onCommitted: () -> Unit) {
    ItemTip(
        (if (result.summary.dryRun) "预演结果（文件未改动）· " else "写入结果 · ") +
            "写入 ${result.summary.written} · 跳过 ${result.summary.skipped} · " +
            "失败 ${result.summary.failed} · 改动 ${result.summary.changed} 项",
    )
    RoundedColumn {
        Box(Modifier.heightIn(max = 320.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (message in result.summary.messages.take(60)) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = SaltTheme.colors.subText,
                    )
                }
            }
        }
    }
    Row(
        modifier = Modifier.outerPadding(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Button(onClick = onCommitted, text = "完成", type = ButtonType.Highlight)
    }
    ItemSpacer()
}

@Composable
private fun ProposalItem(proposal: WriteProposal, onToggle: (Boolean) -> Unit) {
    val detail = when {
        proposal.error.isNotEmpty() -> "异常：" + proposal.error
        proposal.changes.isEmpty() -> "无差异，写入会被跳过"
        else -> proposal.changes.take(4).joinToString("；") { change ->
            val label = Fields.label(change.field)
            val old = change.old.replace("\n", " ⏎ ").take(24).ifEmpty { "空" }
            val new = change.new.replace("\n", " ⏎ ").take(24).ifEmpty { "清空" }
            "$label：$old → $new"
        } + if (proposal.changes.size > 4) "；另有 ${proposal.changes.size - 4} 项" else ""
    }
    ItemCheck(
        state = proposal.confirmed,
        onChange = onToggle,
        text = proposal.fileName,
        sub = if (proposal.fieldLabel.isEmpty()) detail else "[${proposal.fieldLabel}] $detail",
        enabled = proposal.error.isEmpty(),
    )
}

/**
 * Salt 风格的对话框容器。
 *
 * 不用 SaltUI 的 BasicDialog：它在自己的 composition 里解析 SaltTheme.colors，
 * 那时外层的 SaltTheme 还没生效，背景会掉回浅色，而正文是深色主题 → 白底白字。
 * 这里自己搭容器，配色一定跟着 TaggerTheme 走。
 */
@Composable
private fun SaltDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        TaggerTheme {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(SaltTheme.shapes.large)
                    .background(SaltTheme.colors.popup)
                    .padding(vertical = 8.dp),
            ) {
                content()
            }
        }
    }
}
