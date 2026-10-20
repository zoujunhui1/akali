package com.zjh.akali.ui.player

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zjh.akali.data.PlayMode
import com.zjh.akali.ui.MainViewModel
import com.zjh.akali.ui.formatTime

private val SPEEDS = listOf(0.75f, 1f)

private fun speedLabel(s: Float) = if (s == 1f) "1x" else "${s}x"

private fun modeIcon(mode: PlayMode): ImageVector = when (mode) {
    PlayMode.LOOP_ALL -> Icons.Rounded.Repeat
    PlayMode.LOOP_ONE -> Icons.Rounded.RepeatOne
    PlayMode.SEQUENTIAL -> Icons.AutoMirrored.Rounded.ArrowForward
    PlayMode.SHUFFLE -> Icons.Rounded.Shuffle
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ps by vm.player.state.collectAsStateWithLifecycle()
    val lessons by vm.lessons.collectAsStateWithLifecycle()
    val showTranscript by vm.showTranscript.collectAsStateWithLifecycle()
    val lesson = lessons.orEmpty().firstOrNull { it.id == ps.currentLessonId }
    val colors = MaterialTheme.colorScheme

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            if (lesson == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("没有正在播放的课程", color = colors.onSurfaceVariant)
                }
            } else {
                Text(lesson.code, style = MaterialTheme.typography.labelLarge, color = colors.primary, fontWeight = FontWeight.Bold)
                Text(lesson.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))

                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = colors.surface,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("文稿", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = vm::toggleTranscript) {
                                Icon(
                                    if (showTranscript) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    null,
                                    Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(if (showTranscript) "隐藏" else "显示")
                            }
                        }
                        if (showTranscript) {
                            Text(
                                lesson.transcript.ifBlank { "（这一课没有文稿）" },
                                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                                fontSize = 18.sp,
                                lineHeight = 30.sp,
                            )
                        } else {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("文稿已隐藏，先听再看", color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            val duration = ps.durationMs.coerceAtLeast(1L).toFloat()
            var dragging by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragging ?: ps.positionMs.toFloat().coerceIn(0f, duration),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { vm.player.seekTo(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..duration,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime((dragging ?: ps.positionMs.toFloat()).toLong()), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(formatTime(ps.durationMs), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                LabeledButton(label = ps.mode.label, onClick = { vm.player.setMode(ps.mode.next()) }) {
                    Icon(modeIcon(ps.mode), "播放模式：${ps.mode.label}", tint = colors.primary)
                }
                IconButton(onClick = vm.player::previous, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "上一课", Modifier.size(32.dp))
                }
                Surface(shape = CircleShape, color = colors.primary, modifier = Modifier.size(72.dp)) {
                    IconButton(onClick = vm.player::togglePlay) {
                        Icon(
                            if (ps.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (ps.playWhenReady) "暂停" else "播放",
                            Modifier.size(40.dp),
                            tint = colors.onPrimary,
                        )
                    }
                }
                IconButton(onClick = vm.player::next, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.SkipNext, "下一课", Modifier.size(32.dp))
                }
                LabeledButton(label = "倍速", onClick = {
                    val i = SPEEDS.indexOfFirst { it == ps.speed }
                    vm.player.setSpeed(SPEEDS[(i + 1) % SPEEDS.size])
                }) {
                    Text(speedLabel(ps.speed), fontWeight = FontWeight.Bold, color = colors.primary)
                }
            }
        }
    }
}

@Composable
private fun LabeledButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { content() }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
