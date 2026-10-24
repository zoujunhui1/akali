package com.zjh.akali.ui.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.ui.text.TextRange
import com.zjh.akali.ui.theme.Inter
import com.zjh.akali.ui.theme.Literata
import com.zjh.akali.ui.theme.withFont
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zjh.akali.data.Article
import com.zjh.akali.data.DictResult
import com.zjh.akali.data.NotebookState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** 选中的一个词：所在段落、所在句子、词在段落文字里的位置，以及它在窗口里的位置（小卡片贴着它弹出）。 */
private data class Pick(val paragraph: Int, val sentence: Int, val range: IntRange, val token: String, val anchor: Rect)

/** 选中的一段话：所在段落、在段落文字里的位置、文字本身。 */
private data class Passage(val paragraph: Int, val range: IntRange, val text: String)

/** 一次最多讲这么多字符（服务器上限 1500，留点余量） */
private const val MAX_PASSAGE = 1200

/** 收藏着（不会）的词的底色：淡黄，深浅色模式下都看得清 */
private val FAVORITE_COLOR = Color(0xFFFFC107).copy(alpha = 0.38f)
private val WORD_PATTERN = Regex("\\p{L}[\\p{L}'’-]*")

private val FONT_SIZES = listOf(16, 18, 20, 22, 24)

/** 文章页：长按选词或拖动选一段，菜单里查字典、让 AI 讲；段落右边的“译”可看中文翻译。 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ArticleScreen(vm: ReadingViewModel, code: String, onBack: () -> Unit) {
    val article by remember(code) { vm.article(code) }.collectAsStateWithLifecycle(initialValue = null)
    val translations by vm.translations.collectAsStateWithLifecycle()
    val fontIndex by vm.fontIndex.collectAsStateWithLifecycle()
    val favorites by vm.favoriteKeys.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var pick by remember { mutableStateOf<Pick?>(null) }
    var passage by remember { mutableStateOf<Passage?>(null) }
    var askNow by remember { mutableStateOf(false) } // 菜单里选了“AI 讲解”：词面板一打开就去问
    val scope = rememberCoroutineScope()
    var restored by remember(code) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    DisposableEffect(code) { onDispose { vm.clearTranslations() } }
    // 接着上次读到的地方（第 0 项是标题，所以段落序号 + 1）
    LaunchedEffect(article?.code) {
        val a = article ?: return@LaunchedEffect
        if (!restored) {
            restored = true
            if (a.progress > 0) listState.scrollToItem(a.progress + 1)
        }
    }
    LaunchedEffect(code, restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.debounce(800).collect { vm.saveProgress(code, (it - 1).coerceAtLeast(0)) }
    }

    Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(article?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = vm::cycleFontSize) { Icon(Icons.Rounded.FormatSize, "字号", tint = colors.onSurfaceVariant) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        val a = article
        if (a == null) {
            Box(Modifier.fillMaxSize().padding(padding))
        } else {
            val paragraphs = remember(a.body) { a.paragraphs }
            val joined = remember(a.body) { paragraphs.map { ReadingText.join(it) } }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "title") {
                    Column {
                        Text(a.title, style = MaterialTheme.typography.headlineSmall, fontFamily = Literata, fontWeight = FontWeight.Bold)
                        if (a.source.isNotBlank()) Text(a.source, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                }
                items(paragraphs.size, key = { "p$it" }) { index ->
                    val text = joined[index]
                    val key = "$code/$index"
                    val picked = pick?.takeIf { it.paragraph == index }
                    val passageHere = passage?.takeIf { it.paragraph == index }
                    Column {
                        Row(verticalAlignment = Alignment.Top) {
                            ParagraphView(
                                text = text,
                                fontSize = FONT_SIZES[fontIndex],
                                highlightSentence = picked?.sentence,
                                highlightWord = picked?.range,
                                highlightPassage = passageHere?.range,
                                favorites = favorites,
                                keyOf = vm::keyOf,
                                modifier = Modifier.weight(1f),
                                onWord = { range, ask, anchor ->
                                    askNow = ask
                                    pick = Pick(index, text.sentenceIndexAt(range.first), range, text.text.substring(range.first, range.last + 1), anchor)
                                },
                                onFavorite = { range ->
                                    val word = text.text.substring(range.first, range.last + 1)
                                    if (vm.keyOf(word) in favorites) vm.unfavorite(word)
                                    else {
                                        val meaning = vm.lookup(word)?.entry?.meaningLines?.firstOrNull().orEmpty()
                                        vm.addToNotebook(word, paragraphs[index][text.sentenceIndexAt(range.first)], a.code, meaning.take(200))
                                    }
                                },
                                onPassage = { range ->
                                    val chosen = text.text.substring(range.first, range.last + 1)
                                    if (chosen.length > MAX_PASSAGE) scope.launch { snackbar.showSnackbar("选得太长了，一次最多 $MAX_PASSAGE 个字符，选短一点") }
                                    else passage = Passage(index, range, chosen)
                                },
                            )
                            IconButton(
                                onClick = {
                                    if (translations[key] != null) vm.hideTranslation(key) else vm.translateParagraph(key, paragraphs[index].joinToString(" "))
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.Translate, "翻译这一段",
                                    tint = if (translations[key] != null) colors.primary else colors.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        when (val t = translations[key]) {
                            is Help.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp, end = 36.dp))
                            is Help.Ready -> Text(
                                t.value, Modifier.padding(top = 6.dp, end = 36.dp),
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            )
                            is Help.Failed -> Text(t.message, Modifier.padding(top = 6.dp), color = colors.error, style = MaterialTheme.typography.bodySmall)
                            else -> Unit
                        }
                    }
                }
            }

            pick?.let { p ->
                val sentence = paragraphs[p.paragraph][p.sentence]
                WordCard(
                    vm = vm, article = a, token = p.token, sentence = sentence, askNow = askNow, anchor = p.anchor,
                    onSentence = {
                        val r = joined[p.paragraph].ranges[p.sentence]
                        passage = Passage(p.paragraph, r, paragraphs[p.paragraph][p.sentence]); pick = null
                    },
                    onClose = { pick = null; vm.clearWordHelp() },
                )
            }
            passage?.let { ps ->
                SentenceSheet(vm = vm, sentence = ps.text, onClose = { passage = null; vm.clearSentenceHelp() })
            }
        }
    }
}

/**
 * 一段文字。长按选中一个词或拖动选一大段，弹出菜单：单个词有“查字典”“AI 讲解”，一段话有“AI 讲解”。
 * 用只读输入框显示，这样选中、拖动把手都是系统自带的手感；被讲解的词和句子有底色。
 */
@Composable
private fun ParagraphView(
    text: ParagraphText,
    fontSize: Int,
    highlightSentence: Int?,
    highlightWord: IntRange?,
    highlightPassage: IntRange?,
    favorites: Set<String>,
    keyOf: (String) -> String,
    modifier: Modifier,
    onWord: (range: IntRange, ask: Boolean, anchor: Rect) -> Unit,
    onFavorite: (IntRange) -> Unit,
    onPassage: (IntRange) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var selection by remember(text) { mutableStateOf(TextRange.Zero) }
    val annotated: AnnotatedString = remember(text, highlightSentence, highlightWord, highlightPassage, favorites, colors) {
        buildAnnotatedString {
            append(text.text)
            if (favorites.isNotEmpty()) {
                for (m in WORD_PATTERN.findAll(text.text)) {
                    if (keyOf(m.value) in favorites) addStyle(SpanStyle(background = FAVORITE_COLOR), m.range.first, m.range.last + 1)
                }
            }
            highlightSentence?.let { text.ranges.getOrNull(it) }?.let {
                addStyle(SpanStyle(background = colors.secondaryContainer.copy(alpha = 0.55f)), it.first, it.last + 1)
            }
            highlightPassage?.let { addStyle(SpanStyle(background = colors.secondaryContainer.copy(alpha = 0.55f)), it.first, it.last + 1) }
            highlightWord?.let { addStyle(SpanStyle(background = colors.primaryContainer, fontWeight = FontWeight.Bold), it.first, it.last + 1) }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val anchorOf = { r: IntRange ->
        val box = layout?.getPathForRange(r.first, r.last + 1)?.getBounds() ?: Rect.Zero
        val origin = coords?.takeIf { it.isAttached }?.positionInWindow() ?: Offset.Zero
        box.translate(origin)
    }
    run {
        BasicTextField(
            value = TextFieldValue(annotated, selection),
            onValueChange = { selection = it.selection },
            readOnly = true,
            onTextLayout = { layout = it },
            modifier = modifier
                .onGloballyPositioned { coords = it }
                // 菜单一行宽度有限：只留“复制”和我们自己的几项，去掉系统的“全选”“朗读”“AI 写作”等
                .filterTextContextMenuComponents { it.key == TextContextMenuKeys.CopyKey || it.key is String }
                .appendTextContextMenuComponents {
                    val sel = selection
                    val start = minOf(sel.start, sel.end)
                    val end = maxOf(sel.start, sel.end)
                    val word = ReadingText.singleWord(text.text, start, end)
                    val done = { selection = TextRange(end) }
                    if (word != null) {
                        val saved = keyOf(text.text.substring(word.first, word.last + 1)) in favorites
                        item("lookup", "查词") { close(); done(); onWord(word, false, anchorOf(word)) }
                        item("ask", "AI讲解") { close(); done(); onWord(word, true, anchorOf(word)) }
                        item("fav", if (saved) "♥" else "♡") { close(); done(); onFavorite(word) }
                    } else if (end > start) {
                        item("ask-passage", "AI讲解") { close(); done(); onPassage(start until end) }
                    }
                },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = Literata, fontSize = fontSize.sp, lineHeight = (fontSize * 1.75f).sp, color = colors.onBackground,
            ),
            cursorBrush = SolidColor(Color.Transparent),
        )
    }
}

/** 把小卡片放在词的下面（放不下就放上面），左右不出屏幕。[anchor] 是词在窗口里的位置。 */
private class CardPosition(private val anchor: Rect, private val gapPx: Int, private val marginPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchor.center.x.toInt() - popupContentSize.width / 2).coerceIn(marginPx, (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx))
        val below = anchor.bottom.toInt() + gapPx
        val above = anchor.top.toInt() - gapPx - popupContentSize.height
        val y = if (below + popupContentSize.height <= windowSize.height - marginPx || above < marginPx) below else above
        return IntOffset(x, y.coerceIn(marginPx, (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)))
    }
}

/** 点“查字典”或“AI 讲解”后贴着词弹出的小卡片：词和爱心、音标和发音、中文意思、右下角 AI 按钮。 */
@Composable
private fun WordCard(
    vm: ReadingViewModel, article: Article, token: String, sentence: String, askNow: Boolean, anchor: Rect,
    onSentence: () -> Unit, onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val dictState by vm.dictState.collectAsStateWithLifecycle()
    val help by vm.wordHelp.collectAsStateWithLifecycle()
    val favorites by vm.favoriteKeys.collectAsStateWithLifecycle()
    val result: DictResult? = remember(token, dictState.ready) { vm.lookup(token) }
    val entry = result?.entry
    val saved = vm.keyOf(token) in favorites
    LaunchedEffect(token, sentence) { if (askNow) vm.askWord(token, sentence) }
    val width = minOf(320.dp, LocalConfiguration.current.screenWidthDp.dp - 32.dp)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    val position = remember(anchor) { CardPosition(anchor, with(density) { 8.dp.roundToPx() }, with(density) { 12.dp.roundToPx() }) }
    Popup(popupPositionProvider = position, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh, shadowElevation = 12.dp) {
            MaterialTheme(colorScheme = colors, shapes = MaterialTheme.shapes, typography = MaterialTheme.typography.withFont(Inter)) { Column(Modifier.width(width).heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp)) {
                // 词 + 爱心
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(token, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            if (saved) vm.unfavorite(token)
                            else {
                                val meaning = (help as? Help.Ready)?.value?.zh ?: entry?.meaningLines?.firstOrNull().orEmpty()
                                vm.addToNotebook(token, sentence, article.code, meaning.take(200))
                            }
                        },
                    ) {
                        Icon(
                            if (saved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (saved) "取消收藏" else "收藏",
                            tint = if (saved) Color(0xFFFF3B30) else colors.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                // 音标小块 + 喇叭
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry != null && entry.phonetic.isNotBlank()) Pill("/${entry.phonetic}/", colors.surfaceContainerHighest, colors.onSurfaceVariant)
                    IconButton(onClick = { vm.speak(token) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, "发音", tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
                if (entry != null) {
                    val shown = result.looked.takeIf { it != entry.word }?.let { "$it → ${entry.word}" }
                    if (shown != null) Text(shown, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    // 词性是一个彩色小块，意思放在旁边
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        entry.meaningLines.forEach { line -> MeaningRow(line) }
                    }
                    result.base?.let { base ->
                        Spacer(Modifier.height(10.dp))
                        Text("原形 ${base.word}：${base.meaningLines.firstOrNull().orEmpty()}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    if (entry.definitionLines.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            entry.definitionLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
                        }
                    }
                    if (entry.forms.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(entry.forms, style = MaterialTheme.typography.labelMedium, color = colors.secondary)
                    }
                    if (entry.tagLabels.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            entry.tagLabels.forEach { Pill(it, colors.secondaryContainer, colors.onSecondaryContainer) }
                        }
                    }
                } else if (dictState.ready) {
                    Spacer(Modifier.height(10.dp))
                    Text("字典里没有这个词", color = colors.onSurfaceVariant)
                } else {
                    Spacer(Modifier.height(10.dp))
                    Text("字典还没下载，可以点右下角的 AI 按钮，或下载字典后离线秒查。", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    if (dictState.downloading) LinearProgressIndicator(progress = { dictState.progress }, modifier = Modifier.fillMaxWidth())
                    else OutlinedButton(onClick = vm::downloadDict) { Text("下载字典") }
                }
                when (val h = help) {
                    is Help.Ready -> Column(Modifier.padding(top = 14.dp)) {
                        HorizontalDivider(color = colors.outlineVariant)
                        Spacer(Modifier.height(12.dp))
                        Text("在这句话里", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        Text(h.value.zh, style = MaterialTheme.typography.titleLarge, color = colors.primary, fontWeight = FontWeight.Bold)
                        Text(h.value.definition, style = MaterialTheme.typography.bodyLarge)
                        if (h.value.note.isNotBlank()) Text(h.value.note, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        TextButton(onClick = onSentence, contentPadding = PaddingValues(0.dp)) { Text("讲整句") }
                    }
                    is Help.Loading -> Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text("AI 正在讲解…", color = colors.onSurfaceVariant)
                    }
                    is Help.Failed -> Text(h.message, Modifier.padding(top = 14.dp), color = colors.error, style = MaterialTheme.typography.bodyMedium)
                    is Help.Idle -> Unit
                }
                // 右下角：AI 按钮
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Spacer(Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceContainerHighest) {
                        IconButton(onClick = { vm.askWord(token, sentence) }, enabled = help !is Help.Loading, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.AutoAwesome, "结合这句话讲", tint = colors.primary, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            } }
        }
    }
}

/** 一个圆角小块（音标、考试标签）。 */
@Composable
internal fun Pill(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = container) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelLarge, color = content)
    }
}

private val POS_PATTERN = Regex("^([a-zA-Z]{1,8}\\.)\\s*(.*)$")

/** 字典里的一行“n. 边缘, 端”：词性放成左边的彩色小块，意思放右边。没有词性的行只显示意思。 */
@Composable
internal fun MeaningRow(line: String) {
    val colors = MaterialTheme.colorScheme
    val m = POS_PATTERN.find(line.trim())
    Row(verticalAlignment = Alignment.Top) {
        if (m != null) {
            Surface(shape = RoundedCornerShape(10.dp), color = colors.primaryContainer, modifier = Modifier.width(46.dp)) {
                Text(
                    m.groupValues[1], Modifier.padding(vertical = 4.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(m.groupValues[2], Modifier.weight(1f).padding(top = 2.dp), style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(line, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SentenceSheet(vm: ReadingViewModel, sentence: String, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val help by vm.sentenceHelp.collectAsStateWithLifecycle()
    LaunchedEffect(sentence) { vm.askSentence(sentence) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(sentence, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(top = 8.dp))
                IconButton(onClick = { vm.speak(sentence) }) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, "朗读", tint = colors.primary) }
            }
            Spacer(Modifier.height(12.dp))
            when (val h = help) {
                is Help.Ready -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Section("翻译") { Text(h.value.zh, style = MaterialTheme.typography.bodyLarge) }
                    if (h.value.grammar.isNotBlank()) Section("语法") { Text(h.value.grammar, style = MaterialTheme.typography.bodyMedium) }
                    if (h.value.simple.isNotBlank()) Section("换个简单的说法") { Text(h.value.simple, style = MaterialTheme.typography.bodyLarge) }
                }
                is Help.Failed -> Column {
                    Text(h.message, color = colors.error)
                    TextButton(onClick = { vm.askSentence(sentence) }) { Text("重试") }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("AI 正在讲解…", color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(2.dp))
        content()
    }
}
