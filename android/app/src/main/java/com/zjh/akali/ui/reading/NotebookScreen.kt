package com.zjh.akali.ui.reading

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zjh.akali.data.NotebookState
import com.zjh.akali.data.NotebookWord
import com.zjh.akali.ui.theme.cardBorder

/** 生词本：读文章时收的词，每个词带着它原来的句子。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotebookScreen(vm: ReadingViewModel, onBack: () -> Unit, onReview: () -> Unit) {
    val words by vm.notebook.collectAsStateWithLifecycle()
    val learning = words.count { it.notebookState == NotebookState.LEARNING }
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text(if (words.isEmpty()) "生词本" else "生词本 · ${words.size} 个词", fontWeight = FontWeight.Bold, color = colors.primary) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
                actions = {
                    if (learning > 0) IconButton(onClick = onReview) { Icon(Icons.Rounded.Style, "挖空复习", tint = colors.primary) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        if (words.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text("读文章时点一个词，选“加入生词本”，它就会连同这句话一起出现在这里。", color = colors.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(words, key = { it.id }) { w -> WordCard(w, vm, Modifier.animateItem()) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WordCard(w: NotebookWord, vm: ReadingViewModel, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val mastered = w.notebookState == NotebookState.MASTERED
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = cardBorder(),
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).combinedClickable(
            onClick = { vm.speak(w.word) },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                menu = true
            },
        ),
    ) {
        Box {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (mastered) "改回学习中" else "标为已会") },
                    onClick = {
                        menu = false
                        vm.setNotebookState(w.id, if (mastered) NotebookState.LEARNING else NotebookState.MASTERED)
                    },
                )
                DropdownMenuItem(text = { Text("从生词本删除") }, onClick = { menu = false; vm.setNotebookState(w.id, NotebookState.NONE) })
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    w.word, style = MaterialTheme.typography.titleMedium,
                    color = if (mastered) colors.onSurfaceVariant else colors.onSurface, modifier = Modifier.weight(1f, fill = false),
                )
                if (mastered) Icon(Icons.Rounded.CheckCircle, "已会", Modifier.padding(start = 6.dp).size(18.dp), tint = colors.secondary)
                Spacer(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, "发音", tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            if (w.meaning.isNotBlank()) Text(w.meaning, style = MaterialTheme.typography.bodyMedium, color = colors.secondary)
            Spacer(Modifier.height(6.dp))
            Text(highlighted(w.sentence, w.word), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

/** 把句子里的生词加粗。 */
@Composable
private fun highlighted(sentence: String, word: String) = buildAnnotatedString {
    val match = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).find(sentence)
    if (match == null) {
        append(sentence)
    } else {
        append(sentence.substring(0, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) { append(match.value) }
        append(sentence.substring(match.range.last + 1))
    }
}

/** 挖空复习：把句子里的生词挖空，先想词，再点“显示答案”。认识了就标成已会。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookReviewScreen(vm: ReadingViewModel, onBack: () -> Unit) {
    val words by vm.notebook.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var round by remember { mutableStateOf(0) }
    // 每一轮开始时洗一次牌；中途标成已会的词不会让牌堆变化
    val deck = remember(round) { reviewDeck(words).shuffled() }
    var index by remember(round) { mutableStateOf(0) }
    var known by remember(round) { mutableStateOf(0) }
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("生词复习", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = index,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            transitionSpec = {
                (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(260))) togetherWith
                    (slideOutHorizontally(tween(260)) { -it / 3 } + fadeOut(tween(160)))
            },
            label = "review",
        ) { idx ->
            var shown by remember(round, idx) { mutableStateOf(false) }
            val current = deck.getOrNull(idx)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (current == null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (deck.isEmpty()) "生词本里没有在学的词。" else "这一轮复习完了，认识了 $known 个。",
                        style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    if (deck.isNotEmpty() && reviewDeck(words).isNotEmpty()) {
                        Button(onClick = { round++ }) { Text("再来一轮") }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedButton(onClick = onBack) { Text("回到生词本") }
                    Spacer(Modifier.weight(1f))
                } else {
                    Text("${idx + 1} / ${deck.size}", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                        border = cardBorder(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(
                                if (shown) highlighted(current.sentence, current.word) else buildAnnotatedString { append(ReadingText.blank(current.sentence, current.word)) },
                                style = MaterialTheme.typography.titleMedium.copy(lineHeight = MaterialTheme.typography.titleMedium.lineHeight * 1.3f),
                            )
                            if (shown) {
                                Spacer(Modifier.height(16.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(current.word, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f, fill = false))
                                    IconButton(onClick = { vm.speak(current.word) }) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, "发音", tint = colors.primary) }
                                }
                                if (current.meaning.isNotBlank()) Text(current.meaning, style = MaterialTheme.typography.bodyLarge, color = colors.secondary)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (!shown) {
                        Button(onClick = { shown = true; vm.speak(current.word) }, Modifier.fillMaxWidth()) { Text("显示答案") }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { index++ }, Modifier.weight(1f)) { Text("还不会") }
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    vm.setNotebookState(current.id, NotebookState.MASTERED)
                                    known++
                                    index++
                                },
                                Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("认识了")
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** 复习用的牌：生词本里还在学的词。 */
internal fun reviewDeck(words: List<NotebookWord>): List<NotebookWord> = words.filter { it.notebookState == NotebookState.LEARNING }
