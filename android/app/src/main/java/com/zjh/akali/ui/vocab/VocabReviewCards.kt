package com.zjh.akali.ui.vocab

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zjh.akali.data.MarkState
import com.zjh.akali.data.VocabLesson
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import java.io.File

/**
 * 收藏词的卡片复习：先给图和图上的编号，自己想出单词，再点“显示答案”听发音。
 * “认识了”会把这个词标成已会，“还不会”留在收藏里下次再来。
 */
@Composable
internal fun FavoriteCards(vm: VocabViewModel, lessons: List<VocabLesson>, marks: Map<String, MarkState>, modifier: Modifier, onExit: () -> Unit) {
    var round by remember { mutableStateOf(0) }
    val favorites = lessons.flatMap { l -> l.entryList.filter { marks["${l.code}/${it.key}"] == MarkState.FAVORITE }.map { l to it } }
    val skipped = favorites.count { !it.first.downloaded }
    // 每一轮开始时洗一次牌；中途标成已会的词不会让牌堆变化
    val deck = remember(round) { favorites.filter { it.first.downloaded }.map { "${it.first.code}/${it.second.key}" }.shuffled() }
    var index by remember(round) { mutableStateOf(0) }
    var known by remember(round) { mutableStateOf(0) }
    var zoomPage by remember { mutableStateOf<Int?>(null) }
    val colors = MaterialTheme.colorScheme

    val haptic = LocalHapticFeedback.current

    // 换下一张时，旧卡片往左滑出、新卡片从右滑入
    AnimatedContent(
        targetState = index,
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        transitionSpec = {
            (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(260))) togetherWith
                (slideOutHorizontally(tween(260)) { -it / 3 } + fadeOut(tween(160)))
        },
        label = "card",
    ) { idx ->
    var shown by remember(round, idx) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        val current = deck.getOrNull(idx)
        val lesson = current?.let { id -> lessons.firstOrNull { it.code == id.substringBefore('/') } }
        val entry = current?.let { id -> lesson?.entryList?.firstOrNull { it.key == id.substringAfter('/') } }
        if (lesson == null || entry == null) {
            Spacer(Modifier.weight(1f))
            Text(
                if (deck.isEmpty()) "收藏的词所在的主题都还没下载，\n先到主题页下载图片和发音。" else "这一轮复习完了，认识了 $known 个。",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (skipped > 0 && deck.isNotEmpty()) {
                Text("另有 $skipped 个词的主题没下载，已跳过。", Modifier.padding(top = 8.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))
            if (deck.isNotEmpty() && favorites.any { it.first.downloaded }) {
                Button(onClick = { round++ }) { Text("再来一轮") }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = onExit) { Text("回到列表") }
            Spacer(Modifier.weight(1f))
        } else {
            Text(
                "${idx + 1} / ${deck.size} · ${lesson.title}",
                Modifier.padding(vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            val bmp = rememberPageBitmap(File(vm.dir(lesson.code), "page-${entry.page}.jpg"))
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(MaterialTheme.shapes.medium).background(colors.surface).clickable { zoomPage = entry.page },
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) Image(bmp, "点击放大", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) else CircularProgressIndicator(Modifier.size(24.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
                    Text(entry.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
                }
                Spacer(Modifier.size(12.dp))
                if (shown) {
                    Text(entry.text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { vm.playClip(lesson.code, entry.audio) }) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, "发音", tint = colors.primary) }
                } else {
                    Text("在图上找到这个编号，想一想它是什么", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!shown) {
                Button(
                    onClick = {
                        shown = true
                        vm.playClip(lesson.code, entry.audio)
                    },
                    Modifier.fillMaxWidth(),
                ) { Text("显示答案") }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { index++ }, Modifier.weight(1f)) { Text("还不会") }
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            vm.setMark(lesson.code, entry.key, MarkState.MASTERED)
                            known++
                            index++
                        },
                        Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("认识了")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            zoomPage?.let { ZoomDialog(File(vm.dir(lesson.code), "page-$it.jpg")) { zoomPage = null } }
        }
    }
    }
}
