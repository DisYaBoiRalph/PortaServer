package com.fossylabs.portaserver.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.fossylabs.portaserver.AppDestinations
import com.fossylabs.portaserver.ui.screens.LlmScreen
import com.fossylabs.portaserver.ui.screens.SettingsScreen
import com.fossylabs.portaserver.ui.screens.SqlScreen

@Composable
fun AppNavGraph(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = AppDestinations.LLM.route,
        modifier = modifier,
    ) {
        composable(AppDestinations.LLM.route) { LlmScreen() }
        composable(AppDestinations.SQL.route) { SqlScreen() }
        composable(AppDestinations.SETTINGS.route) { SettingsScreen() }
    }
}
