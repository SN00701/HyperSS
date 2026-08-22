package com.hyperss.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hyperss.app.ui.capture.CaptureSetupScreen
import com.hyperss.app.ui.home.HomeScreen
import com.hyperss.app.ui.project.ProjectDetailScreen
import com.hyperss.app.ui.project.ProjectSettingsScreen
import com.hyperss.app.ui.reader.PdfReaderScreen
import com.hyperss.app.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val CAPTURE = "capture"
    const val READER = "reader"
    const val PROJECT = "project/{projectId}"
    const val PROJECT_SETTINGS = "project_settings/{projectId}"
    fun project(id: Long) = "project/$id"
    fun projectSettings(id: Long) = "project_settings/$id"
}

@Composable
fun AppNavHost(modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenProject = { id -> nav.navigate(Routes.project(id)) },
                onProjectSettings = { id -> nav.navigate(Routes.projectSettings(id)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenReader = { nav.navigate(Routes.READER) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.READER) {
            PdfReaderScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.CAPTURE) {
            CaptureSetupScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = Routes.PROJECT,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("projectId") ?: -1L
            ProjectDetailScreen(projectId = id, onBack = { nav.popBackStack() })
        }
        composable(
            route = Routes.PROJECT_SETTINGS,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("projectId") ?: -1L
            ProjectSettingsScreen(projectId = id, onBack = { nav.popBackStack() })
        }
    }
}