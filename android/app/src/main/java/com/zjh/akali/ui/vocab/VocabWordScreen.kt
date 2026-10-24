package com.zjh.akali.ui.vocab

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zjh.akali.data.ChatMessage
import kotlinx.coroutines.launch
import java.io.File

/** 一个词的 AI 页：进来先给讲解（中文意思、英文解释、例句），下面可以接着问这个词。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabWordScreen(vm: VocabViewModel, code: String, key: String, onBack: () -> Unit) {
    val lesson by remember(code) { vm.lesson(code) }.collectAsStateWithLifecycle(initialValue = null)
    val explaining by vm.explaining.collectAsStateWithLifecycle()
    val chats by vm.chats.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val playing by vm.playingClip.collectAsStateWithLifecycle()
    val id = "$code/$key"
    val messages = chats[id].orEmpty()
    val entry = lesson?.entryList?.firstOrNull { it.key == key }
    val note = lesson?.noteMap?.get(key)
    val generating = id in explaining
    var draft by remember { mutableStateOf("") }
    var zoomPage by remember { mutableStateOf<Int?>(null) }
    var voiceMode by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val colors = MaterialTheme.colorScheme

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val speech = rememberSpeechInput(
        onText = { heard -> entry?.let { vm.sendChat(code, key, it.text, heard) } },
        onMessage = { text -> scope.launch { snackbar.showSnackbar(text) } },
    )
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // 点进来就是想看讲解：还没有的话自动生成一次（已经有的直接显示，不再请求）
    LaunchedEffect(lesson != null, note == null) {
        if (lesson != null && entry != null && note == null && !generating) vm.explain(code, key)
    }
    LaunchedEffect(messages.size, sending) {
        val last = messages.size + (if (sending) 1 else 0) // 前面还有一项讲解卡片
        if (messages.isNotEmpty() || sending) listState.animateScrollToItem(last)
    }

    Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(entry?.text ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
                actions = {
                    if (messages.isNotEmpty()) {
                        IconButton(onClick = { vm.clearChat(code, key) }) { Icon(Icons.Rounded.DeleteSweep, "清空追问") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                if (messages.isEmpty() && note != null && !sending) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("和相近的词有什么区别", "再给我几个例句").forEach { q ->
                            AssistChip(
                                onClick = { entry?.let { vm.sendChat(code, key, it.text, q) } },
                                label = { Text(q, maxLines = 1, softWrap = false) },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { voiceMode = !voiceMode }) {
                        Icon(
                            if (voiceMode) Icons.Rounded.Keyboard else Icons.Rounded.MicNone,
                            if (voiceMode) "改用键盘输入" else "改用语音输入",
                            tint = colors.onSurfaceVariant,
                        )
                    }
                    if (voiceMode) {
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val holding = speech.listening
                        Box(
                            Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(MaterialTheme.shapes.extraLarge)
                                .background(
                                    when {
                                        holding && speech.willCancel -> colors.errorContainer
                                        holding -> colors.primaryContainer
                                        else -> colors.surface
                                    },
                                )
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown()
                                        speech.press()
                                        val cancelDistance = with(density) { 72.dp.toPx() }
                                        var cancel = false
                                        do {
                                            val change = awaitPointerEvent().changes.first()
                                            cancel = change.position.y < -cancelDistance
                                            speech.willCancel = cancel && speech.listening
                                            change.consume()
                                        } while (change.pressed)
                                        speech.release(cancel)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                when {
                                    speech.processing -> "识别中…"
                                    holding && speech.willCancel -> "松开取消"
                                    holding -> "松开发送，上滑取消"
                                    else -> "按住 说话"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (holding && speech.willCancel) colors.onErrorContainer else colors.onSurface,
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it.take(600) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("接着问这个词…") },
                            maxLines = 4,
                        )
                        val canSend = draft.isNotBlank() && !sending && entry != null
                        IconButton(
                            enabled = canSend,
                            onClick = {
                                entry?.let { vm.sendChat(code, key, it.text, draft) }
                                draft = ""
                            },
                        ) { Icon(Icons.AutoMirrored.Rounded.Send, "发送", tint = if (canSend) colors.primary else colors.onSurfaceVariant) }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "explain") {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        val l = lesson
                        if (l != null && l.downloaded && entry != null) {
                            val bmp = rememberPageBitmap(File(vm.dir(code), "page-${entry.page}.jpg"))
                            if (bmp != null) {
                                Image(
                                    bmp, "点击放大",
                                    Modifier.fillMaxWidth().height(200.dp).clip(MaterialTheme.shapes.small).clickable { zoomPage = entry.page },
                                    contentScale = ContentScale.Fit,
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                        if (entry != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.primary)
                                Spacer(Modifier.size(10.dp))
                                Text(entry.text, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                if (l != null && l.downloaded) {
                                    IconButton(onClick = { vm.playClip(code, entry.audio) }) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.VolumeUp, "播放发音",
                                            tint = if (playing == "$code/${entry.audio}") colors.primary else colors.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        when {
                            note != null -> {
                                Text(note.zh, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.secondary)
                                Spacer(Modifier.height(6.dp))
                                Text(note.definition, style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(10.dp))
                                Text(note.example, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(note.exampleZh, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                                if (generating) {
                                    CircularProgressIndicator(Modifier.padding(top = 8.dp).size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    TextButton(onClick = { vm.explain(code, key, true) }) { Text("重新生成") }
                                }
                            }
                            generating -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                                Spacer(Modifier.size(12.dp))
                                Text("AI 正在讲解…", color = colors.onSurfaceVariant)
                            }
                            else -> Button(onClick = { vm.explain(code, key) }) { Text("让 AI 讲解这个词") }
                        }
                    }
                }
            }
            items(messages) { WordBubble(it) }
            if (sending) item { WordBubble(ChatMessage("assistant", ""), waiting = true) }
        }
    }
    zoomPage?.let { ZoomDialog(File(vm.dir(code), "page-$it.jpg")) { zoomPage = null } }
}

@Composable
private fun WordBubble(m: ChatMessage, waiting: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val mine = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (mine) colors.primary else colors.surface,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            if (waiting) {
                CircularProgressIndicator(Modifier.padding(14.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    m.content,
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (mine) colors.onPrimary else colors.onSurface,
                )
            }
        }
    }
}
