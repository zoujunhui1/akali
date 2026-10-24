package com.zjh.akali.ui.reading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zjh.akali.data.Article
import com.zjh.akali.data.NotebookState
import com.zjh.akali.ui.theme.cardBorder

/** 阅读入口：文章列表，右上角是生词本。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingListScreen(vm: ReadingViewModel, onOpen: (String) -> Unit, onOpenNotebook: () -> Unit) {
    val articles by vm.articles.collectAsStateWithLifecycle()
    val notebook by vm.notebook.collectAsStateWithLifecycle()
    val learning = notebook.count { it.notebookState == NotebookState.LEARNING }
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("阅读", fontWeight = FontWeight.Bold, color = colors.primary) },
                actions = {
                    IconButton(onClick = onOpenNotebook) {
                        BadgedBox(
                            badge = {
                                if (learning > 0) Badge(containerColor = colors.primary, contentColor = colors.onPrimary) { Text("$learning") }
                            },
                        ) { Icon(Icons.Rounded.Bookmarks, "生词本 $learning 个词", tint = colors.secondary) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        val list = articles
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding))
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "还没有文章。\n文章在电脑上导入后，会同步到这里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.code }) { ArticleCard(it) { onOpen(it.code) } }
            }
        }
    }
}

@Composable
private fun ArticleCard(article: Article, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val percent = ReadingText.percent(article.progress, article.paragraphs.size)
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(article.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val source = article.source.removePrefix("https://").removePrefix("http://").substringBefore('/')
            val parts = listOfNotNull(
                source.takeIf { it.isNotBlank() },
                "${ReadingText.minutes(article.wordCount)} 分钟",
                if (percent > 0) "读到 $percent%" else "未读",
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}
