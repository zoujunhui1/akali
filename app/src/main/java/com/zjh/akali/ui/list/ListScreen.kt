package com.zjh.akali.ui.list

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zjh.akali.data.Lesson
import com.zjh.akali.data.SortOrder
import com.zjh.akali.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListScreen(vm: MainViewModel, onOpenPlayer: () -> Unit, onOpenSettings: () -> Unit) {
    val lessons by vm.lessons.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val addState by vm.add.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }

    val selecting = selection.isNotEmpty()
    val current = lessons.orEmpty().firstOrNull { it.id == player.currentLessonId }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("已选 ${selection.size} 课") },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Rounded.Close, "取消选择") }
                    },
                    actions = {
                        IconButton(onClick = vm::clearSelectedAudio) { Icon(Icons.Rounded.DeleteSweep, "清除音频") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "彻底删除") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            } else {
                TopAppBar(
                    title = { Text("akali", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                    actions = {
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "排序") }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.label) },
                                        leadingIcon = { RadioButton(selected = order == sort, onClick = null) },
                                        onClick = {
                                            vm.setSort(order)
                                            sortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { showAdd = true }) { Icon(Icons.Rounded.Add, "添加课程") }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "设置") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        bottomBar = {
            if (current != null && !selecting) {
                MiniPlayer(
                    title = "${current.code}  ${current.title}",
                    playing = player.playWhenReady,
                    progress = if (player.durationMs > 0) player.positionMs.toFloat() / player.durationMs else 0f,
                    onOpen = onOpenPlayer,
                    onToggle = vm.player::togglePlay,
                )
            }
        },
    ) { padding ->
        val list = lessons
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding))
            list.isEmpty() -> EmptyState(Modifier.padding(padding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { lesson ->
                    LessonCard(
                        lesson = lesson,
                        selected = lesson.id in selection,
                        selecting = selecting,
                        isCurrent = lesson.id == player.currentLessonId,
                        isPlaying = lesson.id == player.currentLessonId && player.playWhenReady,
                        isDownloading = lesson.id in downloading,
                        onClick = {
                            if (selecting) vm.toggleSelect(lesson.id) else if (vm.play(lesson)) onOpenPlayer()
                        },
                        onLongClick = { vm.toggleSelect(lesson.id) },
                        onDownload = { vm.redownload(lesson.id) },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddLessonSheet(
            state = addState,
            onSubmit = vm::addFromUrl,
            onDismiss = {
                showAdd = false
                vm.resetAdd()
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("彻底删除 ${selection.size} 课？") },
            text = { Text("音频和文稿都会删除，不能恢复。只想腾出空间的话，用“清除音频”，之后还能重新下载。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSelected()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.GraphicEq, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("还没有课程", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "点右上角 ＋ 粘贴 ELLLO 课程链接，\n或在设置里导入已有的课程文件夹。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LessonCard(
    lesson: Lesson,
    selected: Boolean,
    selecting: Boolean,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isDownloading: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = if (selected) colors.primaryContainer else colors.surface),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(MaterialTheme.shapes.small).background(if (selected) colors.surface else colors.primaryContainer)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    lesson.code,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    lesson.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCurrent) colors.primary else colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                if (lesson.audioDownloaded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = colors.secondary)
                        Spacer(Modifier.width(4.dp))
                        Text("已下载", style = MaterialTheme.typography.labelMedium, color = colors.secondary)
                    }
                } else {
                    Text(
                        if (lesson.sourceUrl == null) "未下载 · 无来源链接" else "未下载",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                selecting -> Checkbox(checked = selected, onCheckedChange = null)
                isDownloading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                !lesson.audioDownloaded -> IconButton(onClick = onDownload, enabled = lesson.sourceUrl != null) {
                    Icon(Icons.Rounded.CloudDownload, "下载音频", tint = colors.secondary)
                }
                isPlaying -> Icon(Icons.Rounded.GraphicEq, "正在播放", tint = colors.primary)
            }
        }
    }
}

@Composable
private fun MiniPlayer(title: String, playing: Boolean, progress: Float, onOpen: () -> Unit, onToggle: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge).clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp)) {
                    IconButton(onClick = onToggle) {
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (playing) "暂停" else "播放",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(end = 10.dp).height(3.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}
