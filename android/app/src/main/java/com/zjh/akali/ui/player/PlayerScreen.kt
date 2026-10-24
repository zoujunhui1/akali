package com.zjh.akali.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.zjh.akali.data.MarkType
import com.zjh.akali.data.VocabMark
import com.zjh.akali.data.VocabMarks
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zjh.akali.data.PlayMode
import com.zjh.akali.data.Sentence
import com.zjh.akali.data.Timestamps
import com.zjh.akali.ui.MainViewModel
import com.zjh.akali.ui.formatTime

private val SPEEDS = listOf(0.75f, 1f)

private fun speedLabel(s: Float) = if (s == 1f) "1x" else "${s}x"

private fun modeIcon(mode: PlayMode): ImageVector = when (mode) {
    PlayMode.LOOP_ALL -> Icons.Rounded.Repeat
    PlayMode.LOOP_ONE -> Icons.Rounded.RepeatOne
    PlayMode.SEQUENTIAL -> Icons.AutoMirrored.Rounded.ArrowForward
    PlayMode.SHUFFLE -> Icons.Rounded.Shuffle
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ps by vm.player.state.collectAsStateWithLifecycle()
    val lessons by vm.lessons.collectAsStateWithLifecycle()
    val showTranscript by vm.showTranscript.collectAsStateWithLifecycle()
    val lesson = lessons.orEmpty().firstOrNull { it.id == ps.currentLessonId }
    val colors = MaterialTheme.colorScheme
    val sentences = remember(lesson?.id, lesson?.timestamps) { Timestamps.parse(lesson?.timestamps) }
    LaunchedEffect(lesson?.id, sentences) { vm.player.setSentences(sentences) }
    val marks = remember(lesson?.id, lesson?.vocab) { VocabMarks.parse(lesson?.vocab) }
    var popup by remember { mutableStateOf<List<VocabMark>?>(null) }
    val currentIndex = Timestamps.indexAt(sentences, ps.positionMs)

    popup?.let { MarkSheet(it, onDismiss = { popup = null }) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            if (lesson == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("没有正在播放的课程", color = colors.onSurfaceVariant)
                }
            } else {
                Text(lesson.code, style = MaterialTheme.typography.labelLarge, color = colors.primary, fontWeight = FontWeight.Bold)
                Text(lesson.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))

                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = colors.surface,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("文稿", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = vm::toggleTranscript) {
                                Icon(
                                    if (showTranscript) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    null,
                                    Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(if (showTranscript) "隐藏" else "显示")
                            }
                        }
                        if (showTranscript && sentences.isNotEmpty()) {
                            SentenceList(
                                sentences = sentences,
                                currentIndex = currentIndex,
                                marks = marks,
                                onMarkClick = { popup = it },
                                onClick = vm.player::playSentence,
                                modifier = Modifier.weight(1f),
                            )
                        } else if (showTranscript) {
                            Text(
                                lesson.transcript.ifBlank { "（这一课没有文稿）" },
                                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                                fontSize = 18.sp,
                                lineHeight = 30.sp,
                            )
                        } else {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("文稿已隐藏，先听再看", color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            val duration = ps.durationMs.coerceAtLeast(1L).toFloat()
            var dragging by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragging ?: ps.positionMs.toFloat().coerceIn(0f, duration),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { vm.player.seekTo(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..duration,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime((dragging ?: ps.positionMs.toFloat()).toLong()), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(formatTime(ps.durationMs), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }

            if (sentences.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    LabeledButton(label = "上一句", onClick = { vm.player.stepSentence(-1) }) {
                        Icon(Icons.Rounded.FastRewind, "上一句")
                    }
                    LoopToggle(on = ps.loopSentence, onClick = vm.player::toggleSentenceLoop)
                    LabeledButton(label = "下一句", onClick = { vm.player.stepSentence(1) }) {
                        Icon(Icons.Rounded.FastForward, "下一句")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                LabeledButton(label = ps.mode.label, onClick = { vm.player.setMode(ps.mode.next()) }) {
                    Icon(modeIcon(ps.mode), "播放模式：${ps.mode.label}", tint = colors.primary)
                }
                IconButton(onClick = vm.player::previous, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "上一课", Modifier.size(32.dp))
                }
                Surface(shape = CircleShape, color = colors.primary, modifier = Modifier.size(72.dp)) {
                    IconButton(onClick = vm.player::togglePlay) {
                        Icon(
                            if (ps.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (ps.playWhenReady) "暂停" else "播放",
                            Modifier.size(40.dp),
                            tint = colors.onPrimary,
                        )
                    }
                }
                IconButton(onClick = vm.player::next, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.SkipNext, "下一课", Modifier.size(32.dp))
                }
                LabeledButton(label = "倍速", onClick = {
                    val i = SPEEDS.indexOfFirst { it == ps.speed }
                    vm.player.setSpeed(SPEEDS[(i + 1) % SPEEDS.size])
                }) {
                    Text(speedLabel(ps.speed), fontWeight = FontWeight.Bold, color = colors.primary)
                }
            }
        }
    }
}

@Composable
private fun LabeledButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { content() }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class MarkColors(val word: Color, val phrase: Color, val sentence: Color)

@Composable
private fun markColors(): MarkColors =
    if (isSystemInDarkTheme()) MarkColors(Color(0xFFFFB454), Color(0xFFB59CE6), Color(0xFF4CC3BE))
    else MarkColors(Color(0xFFE07B00), Color(0xFF7B5CC4), Color(0xFF1E9E9A))

private fun MarkColors.of(type: MarkType) = when (type) {
    MarkType.Word -> word
    MarkType.Phrase -> phrase
    MarkType.Sentence -> sentence
}

private const val SENTENCE_CHIP = "句型"

@Composable
private fun SentenceList(
    sentences: List<Sentence>,
    currentIndex: Int,
    marks: List<VocabMark>,
    onMarkClick: (List<VocabMark>) -> Unit,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val markColors = markColors()
    val listState = rememberLazyListState()
    // 当前句变了就滚动跟随，放在第二行，上面留一句上下文；用户正在手动滑动时不打断
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem((currentIndex - 1).coerceAtLeast(0))
        }
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        itemsIndexed(sentences) { i, s ->
            val newParagraph = i > 0 && sentences[i - 1].paragraph != s.paragraph
            if (newParagraph) Spacer(Modifier.height(10.dp))
            val inSentence = remember(marks, i, s.text) { VocabMarks.forSentence(marks, i, s.text) }
            val sentenceMarks = inSentence.filter { it.second.type == MarkType.Sentence }.map { it.second }
            val text = remember(inSentence, markColors) {
                buildAnnotatedString {
                    append(s.text)
                    // 先铺词组，再铺单词，重叠时单词盖在词组上
                    inSentence.filter { it.second.type == MarkType.Phrase }.forEach { (r, m) ->
                        addStyle(SpanStyle(background = markColors.of(m.type).copy(alpha = 0.22f)), r.first, r.last + 1)
                    }
                    inSentence.filter { it.second.type == MarkType.Word }.forEach { (r, m) ->
                        addStyle(SpanStyle(background = markColors.of(m.type).copy(alpha = 0.28f)), r.first, r.last + 1)
                    }
                    if (sentenceMarks.isNotEmpty()) {
                        append("  ")
                        withStyle(SpanStyle(color = markColors.sentence, fontWeight = FontWeight.Bold, background = markColors.sentence.copy(alpha = 0.16f))) {
                            append(" $SENTENCE_CHIP ")
                        }
                    }
                }
            }
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text,
                fontSize = 18.sp,
                lineHeight = 28.sp,
                fontWeight = if (i == currentIndex) FontWeight.SemiBold else FontWeight.Normal,
                color = if (i == currentIndex) colors.onSurface else colors.onSurface.copy(alpha = 0.72f),
                onTextLayout = { layout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (i == currentIndex) colors.primary.copy(alpha = 0.14f) else colors.surface)
                    .pointerInput(inSentence) {
                        detectTapGestures { pos ->
                            val offset = layout?.getOffsetForPosition(pos)
                            val hit = offset?.let { o ->
                                inSentence.filter { it.second.type != MarkType.Sentence && o in it.first }.map { it.second }
                            }.orEmpty()
                            when {
                                hit.isNotEmpty() -> onMarkClick(hit)
                                offset != null && offset >= s.text.length + 2 && sentenceMarks.isNotEmpty() -> onMarkClick(sentenceMarks)
                                else -> onClick(i)
                            }
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkSheet(marks: List<VocabMark>, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val markColors = markColors()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            marks.forEach { m ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            when (m.type) { MarkType.Word -> "单词"; MarkType.Phrase -> "词组"; MarkType.Sentence -> "句型" },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = markColors.of(m.type),
                            modifier = Modifier
                                .background(markColors.of(m.type).copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                        if (m.star) Text("⭐重点", style = MaterialTheme.typography.labelMedium)
                        val dot = when (m.mastery) { "常错" -> "🔴"; "复习中" -> "🟡"; "已掌握" -> "🟢"; else -> "" }
                        if (dot.isNotEmpty()) Text("$dot ${m.mastery}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    Text(m.en, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (m.ipa.isNotBlank()) Text(m.ipa, color = colors.onSurfaceVariant)
                    if (m.zh.isNotBlank()) Text(m.zh, fontSize = 18.sp, color = colors.primary, fontWeight = FontWeight.SemiBold)
                    if (m.example.isNotBlank()) Text(m.example, fontSize = 16.sp, lineHeight = 24.sp, color = colors.onSurface.copy(alpha = 0.8f))
                    if (m.note.isNotBlank()) Text("备注：" + m.note, fontSize = 15.sp, lineHeight = 22.sp, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LoopToggle(on: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = if (on) colors.primary else colors.surface,
            modifier = Modifier.size(48.dp),
        ) {
            IconButton(onClick = onClick) {
                Icon(Icons.Rounded.RepeatOne, if (on) "关闭单句循环" else "开启单句循环", tint = if (on) colors.onPrimary else colors.primary)
            }
        }
        Text(
            if (on) "循环中" else "单句循环",
            style = MaterialTheme.typography.labelSmall,
            color = if (on) colors.primary else colors.onSurfaceVariant,
        )
    }
}
