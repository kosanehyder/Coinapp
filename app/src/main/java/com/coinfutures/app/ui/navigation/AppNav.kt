package com.coinfutures.app.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.coinfutures.app.AppContainer
import com.coinfutures.app.ui.activity.ActivityScreen
import com.coinfutures.app.ui.analysis.AnalysisScreen
import com.coinfutures.app.ui.home.HomeScreen
import com.coinfutures.app.ui.l10n.LocalStrings
import com.coinfutures.app.ui.paper.PaperTradeScreen
import com.coinfutures.app.ui.risk.RiskScreen
import com.coinfutures.app.ui.search.SearchScreen
import com.coinfutures.app.ui.settings.SettingsScreen

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

object Routes {
    const val HOME = "home"
    const val SEARCH = "search?q={q}"
    const val ACTIVITY = "activity"
    const val SETTINGS = "settings"
    const val ANALYSIS = "analysis/{id}?plan={plan}&event={event}"
    const val RISK = "risk/{id}"
    const val PAPER = "paper/{tradeId}"

    fun analysis(id: String, plan: String? = null, event: Long? = null) =
        "analysis/${Uri.encode(id)}" + "?plan=${Uri.encode(plan ?: "")}&event=${event ?: -1}"
    fun search(q: String? = null) = "search?q=${Uri.encode(q ?: "")}"
    fun risk(id: String) = "risk/${Uri.encode(id)}"
    fun paper(tradeId: Long) = "paper/$tradeId"
}

private data class TopLevel(val route: String, val label: String, val icon: ImageVector)

/** Deep link from a notification: instrument, plan key and event id. */
data class PendingDeepLink(val instrumentId: String, val planKey: String?, val eventId: Long?)

@Composable
fun AppRoot(deepLink: PendingDeepLink?, onDeepLinkConsumed: () -> Unit) {
    val nav = rememberNavController()
    val s = LocalStrings.current
    val tops = listOf(
        TopLevel(Routes.HOME, s.navMarkets, Icons.AutoMirrored.Filled.ShowChart),
        TopLevel(Routes.ACTIVITY, s.navActivity, Icons.Filled.History),
        TopLevel(Routes.SETTINGS, s.navSettings, Icons.Filled.Settings),
    )
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = tops.any { it.route == route }

    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            nav.navigate(Routes.analysis(deepLink.instrumentId, deepLink.planKey, deepLink.eventId))
            onDeepLinkConsumed()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tops.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.navigateTop(t.route) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(bottom = inner.calculateBottomPadding()),
            enterTransition = { fadeIn(tween(220)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(260), initialOffset = { it / 12 }) },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(160)) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(240), targetOffset = { it / 12 }) },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onSearch = { q -> nav.navigate(Routes.search(q)) },
                    onOpen = { nav.navigate(Routes.analysis(it)) },
                )
            }
            composable(Routes.SEARCH, arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" })) { e ->
                SearchScreen(initialQuery = e.arguments?.getString("q").orEmpty(), onBack = { nav.popBackStack() }, onOpen = { id ->
                    nav.navigate(Routes.analysis(id)) { popUpTo(Routes.SEARCH) { inclusive = true } }
                })
            }
            composable(
                Routes.ANALYSIS,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("plan") { type = NavType.StringType; defaultValue = "" },
                    navArgument("event") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) { e ->
                val id = e.arguments?.getString("id").orEmpty()
                AnalysisScreen(
                    instrumentId = id,
                    fromPlanKey = e.arguments?.getString("plan")?.takeIf { it.isNotBlank() },
                    fromEventId = e.arguments?.getLong("event")?.takeIf { it > 0 },
                    onBack = { nav.popBackStack() },
                    onRisk = { nav.navigate(Routes.risk(id)) },
                    onPaperTrade = { nav.navigate(Routes.paper(it)) },
                )
            }
            composable(Routes.RISK, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                RiskScreen(instrumentId = e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable(Routes.PAPER, arguments = listOf(navArgument("tradeId") { type = NavType.LongType })) { e ->
                PaperTradeScreen(tradeId = e.arguments?.getLong("tradeId") ?: 0L, onBack = { nav.popBackStack() }, onOpenMarket = { nav.navigate(Routes.analysis(it)) })
            }
            composable(Routes.ACTIVITY) {
                ActivityScreen(
                    onOpenMarket = { id, plan -> nav.navigate(Routes.analysis(id, plan)) },
                    onOpenPaper = { nav.navigate(Routes.paper(it)) },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen() }
        }
    }
}

private fun NavHostController.navigateTop(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
