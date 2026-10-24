package com.zjh.akali.ui.vocab

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.ui.graphics.Color
import com.zjh.akali.data.MarkState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zjh.akali.data.EntryNote
import com.zjh.akali.data.VocabEntry
import com.zjh.akali.data.VocabLesson
import com.zjh.akali.ui.ConfirmDialog
import com.zjh.akali.ui.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 词汇入口：按 Unit 分组列出所有主题。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabListScreen(vm: VocabViewModel, onOpen: (String) -> Unit, onOpenFavorites: () -> Unit) {
    val lessons by vm.lessons.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    // 每个主题收藏了几个词；只数还存在的词条（主题被服务器改过、词条没了的不算）
    val favoritesByTopic: Map<String, Int> = lessons.orEmpty().associate { lesson ->
        lesson.code to lesson.entryList.count { marks["${lesson.code}/${it.key}"] == MarkState.FAVORITE }
    }
    val totalFavorites = favoritesByTopic.values.sum()
    // 每个主题已经标成“已会”的词数，用来画进度
    val masteredByTopic: Map<String, Int> = lessons.orEmpty().associate { lesson ->
        lesson.code to lesson.entryList.count { marks["${lesson.code}/${it.key}"] == MarkState.MASTERED }
    }
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("词汇", fontWeight = FontWeight.Bold, color = colors.primary) },
                actions = {
                    IconButton(onClick = onOpenFavorites) {
                        BadgedBox(
                            badge = {
                                if (totalFavorites > 0) {
                                    Badge(containerColor = colors.primary, contentColor = colors.onPrimary) { Text("$totalFavorites") }
                                }
                            },
                        ) { Icon(Icons.Rounded.Star, "收藏 $totalFavorites 个词", tint = MarkColors.star()) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        val list = lessons
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding))
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "还没有词汇主题。\n在 Mac 上传好后，到设置里点“立即同步”。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                list.groupBy { it.unit }.forEach { (unit, topics) ->
                    item(key = "unit$unit") {
                        val known = topics.sumOf { masteredByTopic[it.code] ?: 0 }
                        val all = topics.sumOf { it.entryList.size }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Unit $unit",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.secondary,
                            )
                            if (known > 0) {
                                Text("已会 $known / $all", style = MaterialTheme.typography.labelMedium, color = MarkColors.mastered())
                            }
                        }
                    }
                    items(topics, key = { it.code }) { topic ->
                        TopicCard(
                            topic,
                            favorites = favoritesByTopic[topic.code] ?: 0,
                            modifier = Modifier.animateItem(),
                        ) { onOpen(topic.code) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicCard(topic: VocabLesson, favorites: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = com.zjh.akali.ui.theme.cardBorder(),
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(MaterialTheme.shapes.small).background(colors.primaryContainer).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(topic.code, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    topic.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                val words = topic.entryList.size
                if (topic.downloaded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = colors.secondary)
                        Spacer(Modifier.width(4.dp))
                        Text("已下载 · $words 条", style = MaterialTheme.typography.labelMedium, color = colors.secondary)
                    }
                } else {
                    Text(
                        if (topic.bundleSize > 0) "未下载 · $words 条 · ${formatSize(topic.bundleSize)}" else "服务器上还没有文件",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            if (favorites > 0) {
                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Star, "收藏 $favorites 个词", Modifier.size(18.dp), tint = MarkColors.star())
                    Spacer(Modifier.width(2.dp))
                    Text("$favorites", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MarkColors.star())
                }
            }
        }
    }
}

/** 一个主题：每页先放页面图，下面是这一页的词条，点词播放发音。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabLessonScreen(vm: VocabViewModel, code: String, focusKey: String?, onBack: () -> Unit, onAi: (key: String) -> Unit) {
    val lesson by remember(code) { vm.lesson(code) }.collectAsStateWithLifecycle(initialValue = null)
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val playing by vm.playingClip.collectAsStateWithLifecycle()
    val explaining by vm.explaining.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var confirmClear by remember { mutableStateOf(false) }
    var selfTest by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var showImage by remember { mutableStateOf(true) }
    var zoomPage by remember { mutableStateOf<Int?>(null) }
    val revealed = remember { mutableStateListOf<String>() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val colors = MaterialTheme.colorScheme
    if (confirmClear) {
        ConfirmDialog(
            title = "清除图片和发音",
            message = "清除「${lesson?.title ?: code}」下载到手机上的图片和发音？已生成的释义不会丢，之后可以重新下载。",
            confirmText = "清除",
            onConfirm = { vm.clear(code) },
            onDismiss = { confirmClear = false },
        )
    }

    Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(lesson?.title ?: code, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
                actions = {
                    if (lesson?.downloaded == true) {
                        IconButton(
                            onClick = {
                                selfTest = !selfTest
                                revealed.clear()
                            },
                        ) { Icon(Icons.Rounded.Quiz, "看图自测", tint = if (selfTest) colors.primary else colors.onSurfaceVariant) }
                        IconButton(onClick = { showImage = !showImage }) {
                            Icon(Icons.Rounded.Image, "显示或收起图片", tint = if (showImage) colors.primary else colors.onSurfaceVariant)
                        }
                        IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, "清除图片和发音") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        val l = lesson
        when {
            l == null -> Box(Modifier.fillMaxSize().padding(padding))
            !l.downloaded -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${l.entryList.size} 条词汇", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    when {
                        code in downloading -> CircularProgressIndicator()
                        l.bundleSize > 0 -> Button(onClick = { vm.download(code) }) {
                            Icon(Icons.Rounded.CloudDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("下载图片和发音（${formatSize(l.bundleSize)}）")
                        }
                        else -> Text("服务器上还没有文件", color = colors.onSurfaceVariant)
                    }
                }
            }
            else -> {
                val dir = vm.dir(code)
                val pages = l.pageList
                val notes = l.noteMap
                val ordered = pages.flatMap { page -> l.entryList.filter { it.page == page } }
                val pagerState = rememberPagerState { pages.size }
                // 词条列表滑到哪一页，上面的图就换成哪一页；图本身不跟着列表滚动
                val visiblePage by remember { derivedStateOf { ordered.getOrNull(listState.firstVisibleItemIndex)?.page } }
                LaunchedEffect(visiblePage) {
                    val at = pages.indexOf(visiblePage)
                    if (at >= 0 && at != pagerState.currentPage) pagerState.animateScrollToPage(at)
                }
                // 从收藏页点“看图”过来时，滚到这个词的位置
                LaunchedEffect(focusKey) {
                    val at = ordered.indexOfFirst { it.key == focusKey }
                    if (at >= 0) listState.scrollToItem(at)
                }
                BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                    val imageHeight = maxHeight * 0.65f
                    Column(Modifier.fillMaxSize()) {
                        if (showImage) {
                            PageHeader(pagerState, pages, dir, Modifier.fillMaxWidth().height(imageHeight)) { zoomPage = it }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(ordered, key = { it.key }) { e ->
                                val hidden = selfTest && e.key !in revealed
                                EntryRow(
                                    modifier = Modifier.animateItem(),
                                    entry = e,
                                    hidden = hidden,
                                    playing = playing == "$code/${e.audio}",
                                    expanded = expanded == e.key && !hidden,
                                    note = notes[e.key],
                                    generating = "$code/${e.key}" in explaining,
                                    mark = marks["$code/${e.key}"] ?: MarkState.NONE,
                                    highlight = e.key == focusKey,
                                    onPlay = {
                                        if (hidden) revealed.add(e.key)
                                        vm.playClip(code, e.audio)
                                    },
                                    onToggle = { expanded = if (expanded == e.key) null else e.key },
                                    onGenerate = { force -> vm.explain(code, e.key, force) },
                                    onMark = { vm.setMark(code, e.key, it) },
                                    onAi = { onAi(e.key) },
                                )
                            }
                        }
                    }
                }
                zoomPage?.let { ZoomDialog(File(dir, "page-$it.jpg")) { zoomPage = null } }
            }
        }
    }
}

@Composable
internal fun rememberPageBitmap(file: File): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    return bitmap
}

/** 固定在列表上方的页面图；多页的主题可以左右滑动换页，点一下全屏放大。 */
@Composable
private fun PageHeader(state: PagerState, pages: List<Int>, dir: File, modifier: Modifier, onZoom: (Int) -> Unit) {
    Box(modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        HorizontalPager(state, Modifier.fillMaxSize(), key = { pages[it] }) { i ->
            val bmp = rememberPageBitmap(File(dir, "page-${pages[i]}.jpg"))
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface).clickable { onZoom(pages[i]) },
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(bmp, "点击放大", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
        if (pages.size > 1) {
            Text(
                "${state.currentPage + 1}/${pages.size}",
                Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0x99000000), MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** 全屏看图：双指缩放、拖动，双击放大或还原。 */
@Composable
internal fun ZoomDialog(file: File, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val bmp = rememberPageBitmap(file)
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var size by remember { mutableStateOf(IntSize.Zero) }
        fun limit(o: Offset, s: Float): Offset {
            val maxX = size.width * (s - 1f) / 2f
            val maxY = size.height * (s - 1f) / 2f
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (bmp != null) {
                Image(
                    bmp, null,
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { size = it }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                            })
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offset = limit(offset + pan, scale)
                            }
                        }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                    contentScale = ContentScale.Fit,
                )
            } else {
                CircularProgressIndicator()
            }
            IconButton(onClick = onClose, Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(Icons.Rounded.Close, "关闭", tint = Color.White)
            }
        }
    }
}

/** 收藏星标和“已会”文字的颜色（深浅两套主题各一组）。 */
private object MarkColors {
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
