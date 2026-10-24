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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zjh.akali.ui.MainViewModel
import com.zjh.akali.ui.list.ListScreen
import com.zjh.akali.ui.player.PlayerScreen
import com.zjh.akali.ui.settings.SettingsScreen
import com.zjh.akali.ui.theme.AkaliTheme
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.zjh.akali.ui.vocab.VocabWordScreen
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

private class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("list", "听力", Icons.Rounded.Headphones),
    Tab("vocab", "词汇", Icons.AutoMirrored.Rounded.MenuBook),
    Tab("settings", "我的", Icons.Rounded.Person),
)

@Composable
private fun AkaliRoot(vm: MainViewModel) {
    val nav = rememberNavController()
    val vocabVm: VocabViewModel = viewModel()
    // 打开时回到上次停的页签
    val start = remember { vm.lastTab.takeIf { saved -> TABS.any { it.route == saved } } ?: "list" }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val onTab = TABS.any { it.route == route }
    LaunchedEffect(route) { if (onTab && route != null) vm.lastTab = route }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (onTab) {
                Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
                }
            }
        },
    ) { inner ->
        // 页签之间轻轻淡入淡出；进入二级页面从右边滑入，返回时反向
        fun tabSwitch(from: String?, to: String?) = TABS.any { it.route == from } && TABS.any { it.route == to }
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = Modifier.padding(inner).consumeWindowInsets(inner),
            enterTransition = {
                if (tabSwitch(initialState.destination.route, targetState.destination.route)) fadeIn(tween(180))
                else slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(260))
            },
            exitTransition = {
                if (tabSwitch(initialState.destination.route, targetState.destination.route)) fadeOut(tween(120))
                else slideOutHorizontally(tween(260)) { -it / 8 } + fadeOut(tween(200))
            },
            popEnterTransition = {
                if (tabSwitch(initialState.destination.route, targetState.destination.route)) fadeIn(tween(180))
                else slideInHorizontally(tween(260)) { -it / 8 } + fadeIn(tween(260))
            },
            popExitTransition = {
                if (tabSwitch(initialState.destination.route, targetState.destination.route)) fadeOut(tween(120))
                else slideOutHorizontally(tween(260)) { it / 6 } + fadeOut(tween(200))
            },
        ) {
            composable("list") { ListScreen(vm = vm, onOpenPlayer = { nav.navigate("player") }) }
            composable("player") { PlayerScreen(vm, onBack = { nav.popBackStack() }) }
            composable("settings") { SettingsScreen(vm) }
            composable("vocab") {
                VocabListScreen(
                    vocabVm,
                    onOpen = { nav.navigate("vocab/$it") },
                    onOpenFavorites = { nav.navigate("favorites") },
                )
            }
            composable("favorites") {
                VocabFavoritesScreen(
                    vocabVm,
                    onShowPicture = { code, key -> nav.navigate("vocab/$code?entry=$key") },
                    onAi = { code, key -> nav.navigate("vocab/$code/word/$key") },
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
                    onBack = { nav.popBackStack() }, onAi = { key -> nav.navigate("vocab/$code/word/$key") },
                )
            }
            composable("vocab/{code}/word/{key}") { entry ->
                VocabWordScreen(
                    vocabVm,
                    entry.arguments?.getString("code").orEmpty(),
                    entry.arguments?.getString("key").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
