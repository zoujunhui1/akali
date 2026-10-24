package com.zjh.akali.ui.vocab

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zjh.akali.data.MarkState
import com.zjh.akali.ui.ConfirmDialog
import com.zjh.akali.ui.formatSize
import java.io.File

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
    // 图片区域占屏幕高度的比例，拖中间的分割条调整；每次进来都从 65% 开始
    var imageRatio by remember { mutableStateOf(0.65f) }
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
                    val imageHeight = maxHeight * imageRatio
                    val totalPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }
                    Column(Modifier.fillMaxSize()) {
                        if (showImage) {
                            PageHeader(pagerState, pages, dir, Modifier.fillMaxWidth().height(imageHeight)) { zoomPage = it }
                            // 图片和列表之间的分割条：往上拖列表变大，往下拖图片变大
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .draggable(
                                        orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                                        state = androidx.compose.foundation.gestures.rememberDraggableState { dy ->
                                            imageRatio = (imageRatio + dy / totalPx).coerceIn(0.15f, 0.80f)
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(colors.outlineVariant))
                                Box(Modifier.size(width = 44.dp, height = 5.dp).clip(CircleShape).background(colors.outline))
                            }
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

/** 固定在列表上方的页面图；多页的主题可以左右滑动换页，双指原地放大，点一下全屏。 */
@Composable
private fun PageHeader(state: PagerState, pages: List<Int>, dir: File, modifier: Modifier, onZoom: (Int) -> Unit) {
    // 当前这页放大了就不让翻页，免得拖图片时误翻
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(state.currentPage) { zoomed = false }
    Box(modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        HorizontalPager(state, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { pages[it] }) { i ->
            val bmp = rememberPageBitmap(File(dir, "page-${pages[i]}.jpg"))
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    ZoomableImage(
                        bmp,
                        onTap = { onZoom(pages[i]) },
                        onZoomedChange = { if (i == state.currentPage) zoomed = it },
                        modifier = Modifier.fillMaxSize(),
                    )
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
