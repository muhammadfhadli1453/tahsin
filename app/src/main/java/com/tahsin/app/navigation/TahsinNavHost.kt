package com.tahsin.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tahsin.app.domain.model.AyahRange
import com.tahsin.app.domain.model.SessionMode
import com.tahsin.app.ui.history.HistoryScreen
import com.tahsin.app.ui.home.HomeScreen
import com.tahsin.app.ui.picker.RangePickerScreen
import com.tahsin.app.ui.search.SearchScreen
import com.tahsin.app.ui.session.SessionScreen
import com.tahsin.app.ui.session.SummaryScreen
import com.tahsin.app.ui.settings.SettingsScreen
import com.tahsin.app.ui.surahlist.SurahListScreen

object Routes {
    const val HOME = "home"
    const val SURAH_LIST = "surahs/{mode}"
    const val RANGE_PICKER = "surahs/{mode}/{surahId}?ayah={ayah}"
    const val SESSION = "session/{mode}/{surahId}/{start}/{end}"
    const val SUMMARY = "summary/{sessionId}"
    const val HISTORY = "history"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    fun surahList(mode: SessionMode) = "surahs/${mode.name}"

    fun rangePicker(mode: SessionMode, surahId: Int, ayah: Int? = null) =
        "surahs/${mode.name}/$surahId" + if (ayah != null) "?ayah=$ayah" else ""

    fun session(mode: SessionMode, range: AyahRange) =
        "session/${mode.name}/${range.surahId}/${range.startAyah}/${range.endAyah}"

    fun summary(sessionId: Long) = "summary/$sessionId"
}

private const val NO_AYAH = -1

@Composable
fun TahsinNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            HomeScreen(
                onBrowse = { mode -> navController.navigate(Routes.surahList(mode)) },
                onResume = { surahId, ayahNumber ->
                    navController.navigate(
                        Routes.rangePicker(SessionMode.Recite, surahId, ayahNumber),
                    )
                },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSession = { sessionId -> navController.navigate(Routes.summary(sessionId)) },
            )
        }

        composable(
            route = Routes.SURAH_LIST,
            arguments = listOf(navArgument("mode") { type = NavType.StringType }),
        ) { entry ->
            val mode = entry.sessionMode()
            SurahListScreen(
                mode = mode,
                onBack = { navController.popBackStack() },
                onSurahSelected = { surahId ->
                    navController.navigate(Routes.rangePicker(mode, surahId))
                },
            )
        }

        composable(
            route = Routes.RANGE_PICKER,
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType },
                navArgument("surahId") { type = NavType.IntType },
                navArgument("ayah") {
                    type = NavType.IntType
                    defaultValue = NO_AYAH
                },
            ),
        ) { entry ->
            val surahId = entry.arguments?.getInt("surahId") ?: return@composable
            val ayah = entry.arguments?.getInt("ayah")?.takeIf { it != NO_AYAH }
            RangePickerScreen(
                mode = entry.sessionMode(),
                surahId = surahId,
                initialAyah = ayah,
                onBack = { navController.popBackStack() },
                onStart = { mode, range -> navController.navigate(Routes.session(mode, range)) },
            )
        }

        composable(
            route = Routes.SESSION,
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType },
                navArgument("surahId") { type = NavType.IntType },
                navArgument("start") { type = NavType.IntType },
                navArgument("end") { type = NavType.IntType },
            ),
        ) { entry ->
            val arguments = entry.arguments ?: return@composable
            val range = AyahRange(
                surahId = arguments.getInt("surahId"),
                startAyah = arguments.getInt("start"),
                endAyah = arguments.getInt("end"),
            )
            SessionScreen(
                mode = entry.sessionMode(),
                range = range,
                onBack = { navController.popBackStack() },
                onOpenSummary = { sessionId ->
                    navController.navigate(Routes.summary(sessionId)) {
                        // Leaving the session behind stops the user stumbling back
                        // into a finished run with a released model.
                        popUpTo(Routes.SESSION) { inclusive = true }
                    }
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.SUMMARY,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) { entry ->
            val sessionId = entry.arguments?.getLong("sessionId") ?: return@composable
            SummaryScreen(
                sessionId = sessionId,
                onBack = { navController.popBackStack() },
                onOpenPassage = { surahId, ayahNumber ->
                    navController.navigate(
                        Routes.rangePicker(SessionMode.Recite, surahId, ayahNumber),
                    )
                },
            )
        }

        composable(Routes.HISTORY) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenSession = { sessionId -> navController.navigate(Routes.summary(sessionId)) },
                onOpenPassage = { surahId, ayahNumber ->
                    navController.navigate(
                        Routes.rangePicker(SessionMode.Recite, surahId, ayahNumber),
                    )
                },
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenPassage = { surahId, ayahNumber ->
                    navController.navigate(
                        Routes.rangePicker(SessionMode.Recite, surahId, ayahNumber),
                    )
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

private fun androidx.navigation.NavBackStackEntry.sessionMode(): SessionMode =
    arguments?.getString("mode")
        ?.let { name -> runCatching { SessionMode.valueOf(name) }.getOrNull() }
        ?: SessionMode.Recite
