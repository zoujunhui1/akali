package com.zjh.akali.ui.vocab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zjh.akali.data.EntryNote
import com.zjh.akali.data.MarkState
import com.zjh.akali.data.VocabEntry

/** 收藏星标和“已会”文字的颜色（深浅两套主题各一组）。 */
internal object MarkColors {
    @Composable
    fun star(): Color = if (isSystemInDarkTheme()) Color(0xFFFFC857) else Color(0xFFD99A00)

    @Composable
    fun mastered(): Color = if (isSystemInDarkTheme()) Color(0xFF7FD1A8) else Color(0xFF3E8E6A)
}

/**
 * 一个词条：点一下播放发音，长按选标记（普通 / 已收藏 / 已会），小箭头展开释义和例句。
 * [onShowPicture] 不为空时多一个“看图”按钮（收藏页用它跳到这个词所在的书页）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EntryRow(
    entry: VocabEntry,
    hidden: Boolean,
    playing: Boolean,
    expanded: Boolean,
    note: EntryNote?,
    generating: Boolean,
    mark: MarkState,
    highlight: Boolean,
    onPlay: () -> Unit,
    onToggle: () -> Unit,
    onGenerate: (force: Boolean) -> Unit,
    onMark: (MarkState) -> Unit,
    onAi: () -> Unit,
    onShowPicture: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val textColor = if (mark == MarkState.MASTERED) MarkColors.mastered() else colors.onSurface
    Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = if (playing) colors.primaryContainer else colors.surface),
        border = if (highlight) BorderStroke(2.dp, colors.primary) else com.zjh.akali.ui.theme.cardBorder(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MarkState.entries.forEach { state ->
                    DropdownMenuItem(
                        text = { Text(state.label) },
                        leadingIcon = { RadioButton(selected = state == mark, onClick = null) },
                        onClick = {
                            menu = false
                            onMark(state)
                        },
                    )
                }
            }
        }
        Row(
            Modifier.combinedClickable(
                onClick = onPlay,
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    menu = true
                },
            )
                .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterStart) {
                Text(entry.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.primary)
            }
            if (hidden) {
                Text("点一下听发音，再看词", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            } else {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (mark == MarkState.FAVORITE) {
                        Icon(Icons.Rounded.Star, "已收藏", Modifier.padding(end = 6.dp).size(18.dp), tint = MarkColors.star())
                    }
                    Text(entry.text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, color = textColor)
                    if (mark == MarkState.MASTERED) {
                        Icon(Icons.Rounded.Check, "已会", Modifier.padding(start = 6.dp).size(18.dp), tint = MarkColors.mastered())
                    }
                }
                if (onShowPicture != null) {
                    IconButton(onClick = onShowPicture) { Icon(Icons.Rounded.Image, "看图", tint = colors.secondary) }
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        "释义和例句",
                        tint = if (note != null) colors.secondary else colors.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onAi) { Icon(Icons.Rounded.AutoAwesome, "问 AI", tint = colors.secondary) }
            }
            Icon(
                Icons.AutoMirrored.Rounded.VolumeUp,
                "播放发音",
                Modifier.padding(horizontal = 10.dp),
                tint = if (playing) colors.primary else colors.onSurfaceVariant,
            )
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(start = 44.dp, end = 14.dp, bottom = 12.dp)) {
                when {
                    note != null -> {
                        Text(note.zh, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.secondary)
                        Spacer(Modifier.height(4.dp))
                        Text(note.definition, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(note.example, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(note.exampleZh, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        if (generating) {
                            CircularProgressIndicator(Modifier.padding(top = 6.dp).size(18.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = { onGenerate(true) }) { Text("重新生成") }
                        }
                    }
                    generating -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    else -> Button(onClick = { onGenerate(false) }) { Text("生成释义和例句") }
                }
            }
        }
    }
}
