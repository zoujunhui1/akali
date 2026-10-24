package com.zjh.akali.ui.vocab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zjh.akali.data.MarkState
import com.zjh.akali.data.VocabEntry
import com.zjh.akali.data.VocabLesson
import kotlinx.coroutines.launch

/** 收藏页：所有主题里收藏的词放在一起，按主题分组；可以自测复习，也可以跳到词所在的书页看图。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabFavoritesScreen(vm: VocabViewModel, onShowPicture: (code: String, key: String) -> Unit, onBack: () -> Unit) {
    val lessons by vm.lessons.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val playing by vm.playingClip.collectAsStateWithLifecycle()
    val explaining by vm.explaining.collectAsStateWithLifecycle()
    var review by remember { mutableStateOf(false) }
    var cards by remember { mutableStateOf(false) }
    val revealed = remember { mutableStateListOf<String>() }
    var expanded by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // 有收藏词的主题，和每个主题里收藏的词（保持书里的顺序）
    val groups: List<Pair<VocabLesson, List<VocabEntry>>> = lessons.orEmpty().mapNotNull { lesson ->
        val favorites = lesson.entryList.filter { marks["${lesson.code}/${it.key}"] == MarkState.FAVORITE }
        if (favorites.isEmpty()) null else lesson to favorites
    }
    val total = groups.sumOf { it.second.size }

    Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (cards) "卡片复习" else if (total > 0) "收藏 · $total 个词" else "收藏", fontWeight = FontWeight.Bold, color = colors.primary) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
                actions = {
                    if (total > 0 || cards) {
                        IconButton(onClick = { cards = !cards }) {
                            Icon(Icons.Rounded.Style, "卡片复习", tint = if (cards) colors.primary else colors.onSurfaceVariant)
                        }
                    }
                    if (total > 0 && !cards) {
                        IconButton(
                            onClick = {
                                review = !review
                                revealed.clear()
                            },
                        ) { Icon(Icons.Rounded.Quiz, "自测复习", tint = if (review) colors.primary else colors.onSurfaceVariant) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        if (cards) {
            FavoriteCards(vm, lessons.orEmpty(), marks, Modifier.padding(padding)) { cards = false }
        } else if (total == 0) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "还没有收藏的词。\n在主题页里长按一个词，选“已收藏”，它就会出现在这里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEach { (lesson, favorites) ->
                    item(key = "head-${lesson.code}") {
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.clip(MaterialTheme.shapes.small).background(colors.primaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(lesson.code, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
                            }
                            Text(
                                lesson.title,
                                Modifier.padding(start = 10.dp),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.secondary,
                            )
                        }
                    }
                    items(favorites, key = { "${lesson.code}/${it.key}" }) { e ->
                        val id = "${lesson.code}/${e.key}"
                        val hidden = review && id !in revealed
                        EntryRow(
                            entry = e,
                            hidden = hidden,
                            playing = playing == "${lesson.code}/${e.audio}",
                            expanded = expanded == id && !hidden,
                            note = lesson.noteMap[e.key],
                            generating = id in explaining,
                            mark = MarkState.FAVORITE,
                            highlight = false,
                            onPlay = {
                                if (hidden) revealed.add(id)
                                if (lesson.downloaded) {
                                    vm.playClip(lesson.code, e.audio)
                                } else {
                                    scope.launch { snackbar.showSnackbar("这个主题的发音还没下载，点“看图”进去下载") }
                                }
                            },
                            onToggle = { expanded = if (expanded == id) null else id },
                            onGenerate = { force -> vm.explain(lesson.code, e.key, force) },
                            onMark = { vm.setMark(lesson.code, e.key, it) },
                            onShowPicture = { onShowPicture(lesson.code, e.key) },
                        )
                    }
                }
            }
        }
    }
}
