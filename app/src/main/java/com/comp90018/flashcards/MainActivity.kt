package com.comp90018.flashcards

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.comp90018.flashcards.data.auth.AuthState
import com.comp90018.flashcards.ui.auth.LoginScreen
import com.comp90018.flashcards.ui.auth.SessionViewModel
import com.comp90018.flashcards.ui.card.AddEditCardScreen
import com.comp90018.flashcards.ui.deck.ChooseModeScreen
import com.comp90018.flashcards.ui.deck.DeckListScreen
import com.comp90018.flashcards.ui.deck.DeckPageScreen
import com.comp90018.flashcards.ui.play.ChooseTimerScreen
import com.comp90018.flashcards.ui.play.DuoScreen
import com.comp90018.flashcards.ui.study.StudyScreen
import com.comp90018.flashcards.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FlashcardApp()
                }
            }
        }
    }
}

@Composable
private fun FlashcardApp(sessionViewModel: SessionViewModel = hiltViewModel()) {
    val authState by sessionViewModel.authState.collectAsState()
    if (authState is AuthState.Loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val navController = rememberNavController()
    val startDestination = if (authState is AuthState.SignedIn) "home" else "login"
    AuthNavigationEffect(authState = authState, navController = navController)
    FlashcardNavHost(navController = navController, startDestination = startDestination)
}

@Composable
private fun FlashcardNavHost(
    navController: NavHostController,
    startDestination: String,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable("login") {
            LoginScreen()
        }
        composable("home") {
            DeckListScreen(
                onNavigateToDeck = { deckId ->
                    navController.navigate("deck/$deckId")
                },
            )
        }
        deckPlayRoutes(navController)
        duoRoutes(navController)
        composable(
            route = "add_edit_card/{deckId}?cardId={cardId}",
            arguments =
                listOf(
                    navArgument("deckId") { type = NavType.StringType },
                    navArgument("cardId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
        ) {
            AddEditCardScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "study/{deckId}",
            arguments = listOf(navArgument("deckId") { type = NavType.StringType }),
        ) {
            StudyScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * The deck page and the choose-mode page that follows it when the user taps Play.
 */
private fun NavGraphBuilder.deckPlayRoutes(navController: NavHostController) {
    composable(
        route = "deck/{deckId}",
        arguments = listOf(navArgument("deckId") { type = NavType.StringType }),
    ) {
        DeckPageScreen(
            onNavigateBack = { navController.popBackStack() },
            onPlay = { deckId ->
                navController.navigate("choose_mode/$deckId")
            },
            onAddCard = { deckId ->
                navController.navigate("add_edit_card/$deckId")
            },
            onEditCard = { deckId, cardId ->
                navController.navigate("add_edit_card/$deckId?cardId=$cardId")
            },
        )
    }
    composable(
        route = "choose_mode/{deckId}",
        arguments = listOf(navArgument("deckId") { type = NavType.StringType }),
    ) {
        ChooseModeScreen(
            onNavigateBack = { navController.popBackStack() },
            onPlaySolo = { deckId ->
                navController.navigate("study/$deckId")
            },
            onPlayDuo = { deckId ->
                navController.navigate("choose_timer/$deckId")
            },
        )
    }
}

/**
 * The 2-player flow: pick the countdown, then play. Finishing a round returns to choose mode.
 */
private fun NavGraphBuilder.duoRoutes(navController: NavHostController) {
    composable(
        route = "choose_timer/{deckId}",
        arguments = listOf(navArgument("deckId") { type = NavType.StringType }),
    ) {
        ChooseTimerScreen(
            onNavigateBack = { navController.popBackStack() },
            onStart = { deckId, seconds ->
                navController.navigate("duo/$deckId/$seconds")
            },
        )
    }
    composable(
        route = "duo/{deckId}/{seconds}",
        arguments =
            listOf(
                navArgument("deckId") { type = NavType.StringType },
                navArgument("seconds") { type = NavType.IntType },
            ),
    ) {
        DuoScreen(
            onNavigateBack = { navController.popBackStack("choose_mode/{deckId}", inclusive = false) },
        )
    }
}

@Composable
private fun AuthNavigationEffect(
    authState: AuthState,
    navController: NavHostController,
) {
    LaunchedEffect(authState) {
        val signedIn = authState is AuthState.SignedIn
        val route = navController.currentDestination?.route
        if (!signedIn && route != null && route != "login") {
            navController.navigate("login") {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        } else if (signedIn && route == "login") {
            navController.navigate("home") {
                popUpTo("login") { inclusive = true }
                launchSingleTop = true
            }
        }
    }
}
