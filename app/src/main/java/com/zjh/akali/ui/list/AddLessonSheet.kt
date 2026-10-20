package com.zjh.akali.ui.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zjh.akali.ui.AddUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLessonSheet(state: AddUiState, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val working = state is AddUiState.Working
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(state) {
        if (state is AddUiState.Success) text = ""
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("添加课程", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "粘贴 ELLLO 的课程页面链接，会自动下载音频和文稿。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("课程链接") },
                placeholder = { Text("https://elllo.org/video/C1ADV/…") },
                singleLine = true,
                enabled = !working,
                trailingIcon = {
                    IconButton(
                        enabled = !working,
                        onClick = {
                            scope.launch {
                                val pasted = clipboard.getClipEntry()?.clipData?.getItemAt(0)?.coerceToText(context)?.toString()
                                if (!pasted.isNullOrBlank()) text = pasted
                            }
                        },
                    ) { Icon(Icons.Rounded.ContentPaste, "粘贴") }
                },
            )
            Spacer(Modifier.height(12.dp))
            when (state) {
                is AddUiState.Working -> {
                    Text(state.status, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    if (state.progress != null) {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(12.dp))
                }
                is AddUiState.Success -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(12.dp))
                }
                is AddUiState.Error -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                }
                AddUiState.Idle -> Unit
            }
            Button(
                onClick = { onSubmit(text) },
                enabled = text.isNotBlank() && !working,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("添加") }
        }
    }
}
