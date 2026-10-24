package com.zjh.akali.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zjh.akali.ui.MainViewModel
import com.zjh.akali.ui.SyncUiState
import com.zjh.akali.ui.formatSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lessons by vm.lessons.collectAsStateWithLifecycle()
    val storage by vm.storageBytes.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    val lastSyncAt by vm.lastSyncAt.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshStorage() }

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
            Section("云端同步") {
                if (!vm.serverConfigured) {
                    Text("这个安装包没有配置服务器，课程只用手机里已有的。", color = colors.onSurfaceVariant)
                } else {
                    Text(
                        "课程文稿、逐句时间戳和词表从服务器同步；音频不会自动下载，在列表里点哪课下哪课。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.sync() }, enabled = sync !is SyncUiState.Running) {
                        Text(if (sync is SyncUiState.Running) "同步中…" else "立即同步")
                    }
                    Spacer(Modifier.height(6.dp))
                    when (val s = sync) {
                        is SyncUiState.Error -> Text(s.message, color = colors.error)
                        is SyncUiState.Done -> Text(s.message, color = colors.secondary)
                        else -> Unit
                    }
                    if (lastSyncAt > 0) {
                        Text(
                            "上次同步：" + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(lastSyncAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }

            Section("存储") {
                Text("共 ${all.size} 课，已下载 ${all.count { it.audioDownloaded }} 课")
                Text("占用空间 ${formatSize(storage)}", color = colors.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    "在课程列表里长按选择课程，可以“清除音频”腾出空间（之后可重新下载）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
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
