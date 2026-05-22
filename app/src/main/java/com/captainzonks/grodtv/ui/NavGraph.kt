package com.captainzonks.grodtv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.captainzonks.grodtv.appContainer
import com.captainzonks.grodtv.settings.Settings
import com.captainzonks.grodtv.ui.screens.FirstRunScreen
import com.captainzonks.grodtv.ui.screens.HomeScreen
import com.captainzonks.grodtv.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

object Routes {
    const val Home = "home"
    const val Settings = "settings"
    const val FirstRun = "first_run"
}

@Composable
fun GrodTvNav() {
    val context = LocalContext.current
    val container = remember { context.appContainer }
    val settings: Settings by container.settings.collectAsState()
    val nav: NavHostController = rememberNavController()
    val scope = rememberCoroutineScope()

    val startDestination = if (settings.firstRunSeen) Routes.Home else Routes.FirstRun

    NavHost(navController = nav, startDestination = startDestination) {
        composable(Routes.Home) {
            HomeScreen(
                onOpenSettings = { nav.navigate(Routes.Settings) },
            )
        }
        composable(Routes.Settings) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.FirstRun) {
            FirstRunScreen(
                onDismiss = {
                    scope.launch {
                        container.settingsStore.setFirstRunSeen(true)
                        nav.navigate(Routes.Home) {
                            popUpTo(Routes.FirstRun) { inclusive = true }
                        }
                    }
                },
            )
        }
    }
}
