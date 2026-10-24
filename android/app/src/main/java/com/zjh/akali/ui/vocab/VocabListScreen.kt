package com.zjh.akali.ui.vocab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Star
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
import com.zjh.akali.data.MarkState
import com.zjh.akali.data.VocabLesson
import com.zjh.akali.ui.formatSize

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
                        Text(
                            "Unit $unit",
                            Modifier.padding(top = 10.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.secondary,
                        )
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
