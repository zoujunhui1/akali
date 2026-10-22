package com.zjh.akali.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zjh.akali.ui.ImportUiState
import com.zjh.akali.ui.MainViewModel
import com.zjh.akali.ui.formatSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lessons by vm.lessons.collectAsStateWithLifecycle()
    val storage by vm.storageBytes.collectAsStateWithLifecycle()
    val import by vm.import.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshStorage() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.importFolder(uri)
    }
    val all = lessons.orEmpty()
    val colors = MaterialTheme.colorScheme

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Section("存储") {
                Text("共 ${all.size} 课，已下载 ${all.count { it.audioDownloaded }} 课")
                Text("占用空间 ${formatSize(storage)}", color = colors.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    "在课程列表里长按选择课程，可以“清除音频”腾出空间（之后可重新下载），或彻底删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            Section("导入已有课程") {
                Text(
                    "选择存放课程的文件夹（里面每课一个子文件夹，含 mp3 和 transcript.txt），已存在的课程会自动跳过。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { picker.launch(null) }, enabled = import !is ImportUiState.Running) { Text("选择文件夹导入") }
                when (val s = import) {
                    is ImportUiState.Running -> {
                        Spacer(Modifier.height(8.dp))
                        Text("导入中 ${s.done}/${s.total}")
                        LinearProgressIndicator(
                            progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is ImportUiState.Finished -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "导入 ${s.result.imported} 课" +
                                (if (s.result.updated > 0) "，补充时间戳 ${s.result.updated} 课" else "") +
                                "，跳过 ${s.result.skipped} 课（已存在）",
                            color = colors.secondary,
                        )
                        if (s.result.failed.isNotEmpty()) {
                            Text("失败 ${s.result.failed.size} 课：", color = colors.error)
                            s.result.failed.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
                        }
                    }
                    null -> Unit
                }
            }

            Section("后台播放（一加手机必看）") {
                val notifOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
                Text(if (notifOn) "通知权限：已开启" else "通知权限：未开启，锁屏和通知栏将没有播放控制", color = if (notifOn) colors.secondary else colors.error)
                Spacer(Modifier.height(6.dp))
                Text(
                    "一加的电池管理会杀后台应用。请把本应用的电池设为“不受限制”：应用信息 → 电池 → 不受限制（允许后台活动）。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                }) { Text("打开应用信息") }
                if (!notifOn) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }) { Text("打开通知设置") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
    )
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}
