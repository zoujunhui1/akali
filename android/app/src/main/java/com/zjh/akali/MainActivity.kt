package com.zjh.akali

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zjh.akali.ui.MainViewModel
import com.zjh.akali.ui.list.ListScreen
import com.zjh.akali.ui.player.PlayerScreen
import com.zjh.akali.ui.settings.SettingsScreen
import com.zjh.akali.ui.theme.AkaliTheme
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.zjh.akali.ui.vocab.VocabChatScreen
import com.zjh.akali.ui.vocab.VocabFavoritesScreen
import com.zjh.akali.ui.vocab.VocabLessonScreen
import com.zjh.akali.ui.vocab.VocabListScreen
import com.zjh.akali.ui.vocab.VocabViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果在设置页会显示 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askNotificationPermission()
        setContent {
            AkaliTheme { AkaliRoot(viewModel) }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun AkaliRoot(vm: MainViewModel) {
    val nav = rememberNavController()
    val vocabVm: VocabViewModel = viewModel()
    NavHost(navController = nav, startDestination = "list") {
        composable("list") {
            ListScreen(
                vm = vm,
                onOpenPlayer = { nav.navigate("player") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenVocab = { nav.navigate("vocab") },
            )
        }
        composable("player") { PlayerScreen(vm, onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
        composable("vocab") {
            VocabListScreen(
                vocabVm,
                onOpen = { nav.navigate("vocab/$it") },
                onOpenFavorites = { nav.navigate("favorites") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("favorites") {
            VocabFavoritesScreen(
                vocabVm,
                onShowPicture = { code, key -> nav.navigate("vocab/$code?entry=$key") },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "vocab/{code}?entry={entry}",
            arguments = listOf(navArgument("entry") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val code = entry.arguments?.getString("code").orEmpty()
            VocabLessonScreen(
                vocabVm, code, focusKey = entry.arguments?.getString("entry"),
                onBack = { nav.popBackStack() }, onChat = { nav.navigate("vocab/$code/chat") },
            )
        }
        composable("vocab/{code}/chat") { entry ->
            VocabChatScreen(vocabVm, entry.arguments?.getString("code").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}
