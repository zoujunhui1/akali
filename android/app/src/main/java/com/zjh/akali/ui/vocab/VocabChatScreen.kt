package com.zjh.akali.ui.vocab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DeleteSweep
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zjh.akali.data.ChatMessage

/** 和 AI 聊当前主题：AI 知道这个主题的词，用简单英文聊，纠正错误。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabChatScreen(vm: VocabViewModel, code: String, onBack: () -> Unit) {
    val lesson by remember(code) { vm.lesson(code) }.collectAsStateWithLifecycle(initialValue = null)
    val chats by vm.chats.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val messages = chats[code].orEmpty()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val colors = MaterialTheme.colorScheme
    val title = lesson?.title ?: code

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(messages.size, sending) {
        val last = messages.size + (if (sending) 1 else 0) - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("聊 $title", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
                actions = {
                    if (messages.isNotEmpty()) {
                        IconButton(onClick = { vm.clearChat(code) }) { Icon(Icons.Rounded.DeleteSweep, "重新开始") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(600) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("用英文说点什么…") },
                    maxLines = 4,
                )
                val canSend = draft.isNotBlank() && !sending
                IconButton(
                    enabled = canSend,
                    onClick = {
                        vm.sendChat(code, draft)
                        draft = ""
                    },
                ) { Icon(Icons.AutoMirrored.Rounded.Send, "发送", tint = if (canSend) colors.primary else colors.onSurfaceVariant) }
            }
        },
    ) { padding ->
        if (messages.isEmpty() && !sending) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "和 AI 用英文聊聊「$title」吧。\n你说错了它会帮你纠正，想听中文解释就直接用中文问。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages) { Bubble(it) }
                if (sending) item { Bubble(ChatMessage("assistant", ""), waiting = true) }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, waiting: Boolean = false) {
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
