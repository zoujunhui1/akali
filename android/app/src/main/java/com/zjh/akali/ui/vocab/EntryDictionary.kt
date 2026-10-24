package com.zjh.akali.ui.vocab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zjh.akali.data.DictResult
import com.zjh.akali.data.DictState
import com.zjh.akali.ui.reading.MeaningRow
import com.zjh.akali.ui.reading.Pill

/** 词条展开后的字典内容：音标、词性 + 中文意思、英文释义、词形变化、考试标签。字典还没下载时提示下载。 */
@Composable
internal fun EntryDictionary(text: String, state: DictState, lookup: (String) -> DictResult?, onDownload: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val result = remember(text, state.ready) { lookup(text) }
    val entry = result?.entry
    Column(Modifier.fillMaxWidth()) {
        when {
            entry != null -> {
                if (entry.phonetic.isNotBlank()) Pill("/${entry.phonetic}/", colors.surfaceContainerHighest, colors.onSurfaceVariant)
                result.looked.takeIf { it != entry.word }?.let {
                    Text("$it → ${entry.word}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { entry.meaningLines.forEach { MeaningRow(it) } }
                if (entry.definitionLines.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        entry.definitionLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
                    }
                }
                if (entry.forms.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(entry.forms, style = MaterialTheme.typography.labelMedium, color = colors.secondary)
                }
                if (entry.tagLabels.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        entry.tagLabels.forEach { Pill(it, colors.secondaryContainer, colors.onSecondaryContainer) }
                    }
                }
            }
            state.ready -> Text("字典里没有这个词（短语字典查不到，可以让 AI 讲）", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            else -> {
                Text("字典还没下载，下载后离线秒查。", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                if (state.downloading) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                else OutlinedButton(onClick = onDownload) { Text("下载字典") }
            }
        }
    }
}
