@file:OptIn(com.moriafly.salt.ui.UnstableSaltUiApi::class)

package app.tagger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moriafly.salt.ui.Button
import com.moriafly.salt.ui.ButtonType
import com.moriafly.salt.ui.Item
import com.moriafly.salt.ui.ItemArrowType
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.Surface
import com.moriafly.salt.ui.Text
import com.moriafly.salt.ui.popup.PopupMenu

/**
 * 顶栏：Salt 风格的操作区。
 * 窄窗口下自动换行，避免按钮被挤出屏幕。
 */
@Composable
fun SaltTopBar(
    platformLabel: String,
    busy: Boolean,
    scanning: Boolean,
    hasTracks: Boolean,
    canMatch: Boolean,
    onPickFolder: () -> Unit,
    onScan: () -> Unit,
    onMatch: () -> Unit,
    onMatchAll: () -> Unit,
    onOpenReview: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "网易云标签刮削器",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                SaltSubText(platformLabel, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            FlowRowWithSpacing {
                Button(
                    onClick = onScan,
                    text = if (scanning) "扫描中…" else "扫描曲库",
                    enabled = !busy,
                    type = ButtonType.Highlight,
                )
                Button(
                    onClick = onPickFolder,
                    text = "选择目录",
                    enabled = !busy,
                    type = ButtonType.Sub,
                )
                Button(
                    onClick = onMatch,
                    text = "整曲匹配",
                    enabled = !busy && canMatch,
                    type = ButtonType.Sub,
                )
                Button(
                    onClick = onOpenReview,
                    text = "写入确认清单",
                    enabled = !busy,
                    type = ButtonType.Sub,
                )
                // 更多操作收进 Salt 的 PopupMenu
                androidx.compose.foundation.layout.Box {
                    Button(
                        onClick = { menuExpanded = true },
                        text = "更多",
                        enabled = !busy,
                        type = ButtonType.Sub,
                    )
                    PopupMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        Item(
                            onClick = {
                                menuExpanded = false
                                if (hasTracks && !busy) onMatchAll()
                            },
                            text = "整曲匹配全部",
                            sub = if (hasTracks) "${' '}" else "曲库为空",
                            enabled = !busy && hasTracks,
                            arrowType = ItemArrowType.None,
                        )
                        Item(
                            onClick = {
                                menuExpanded = false
                                if (!busy) onScan()
                            },
                            text = "重新扫描",
                            enabled = !busy,
                            arrowType = ItemArrowType.None,
                        )
                    }
                }
            }
        }
    }
}

/** 简单的自动换行容器：宽度不够就换到下一行。 */
@Composable
fun FlowRowWithSpacing(
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

/** 底部状态栏。 */
@Composable
fun SaltStatusBar(
    statusText: String,
    progressText: String,
    busy: Boolean,
    summary: Triple<Int, Int, Int>?,
) {
    Surface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SaltSubText(statusText, Modifier.weight(1f), maxLines = 1)
            if (progressText.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                SaltSubText(progressText, color = SaltTheme.colors.highlight, maxLines = 1)
            }
            if (busy) {
                Spacer(Modifier.width(8.dp))
                Text(".", color = SaltTheme.colors.highlight, fontSize = 12.sp)
            }
            summary?.let { (total, covers, matched) ->
                Spacer(Modifier.width(10.dp))
                SaltSubText("共 $total 首 · 封面 $covers · 已匹配 $matched", maxLines = 1)
            }
        }
    }
}
