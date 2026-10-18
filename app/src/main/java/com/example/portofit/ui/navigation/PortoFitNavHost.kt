package com.example.portofit.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.portofit.ui.feature.auth.LoginScreen
import com.example.portofit.ui.feature.auth.RegisterScreen
import com.example.portofit.ui.feature.people.PublicProfileScreen
import com.example.portofit.ui.feature.people.PublicProfileViewModel
import com.example.portofit.ui.feature.place.PlaceDetailScreen
import com.example.portofit.ui.feature.profile.ProfileScreen
import com.example.portofit.ui.feature.place.PlaceDetailViewModel
import com.example.portofit.ui.feature.search.SearchScreen

private object Routes {
    const val SEARCH = "search"
    const val PLACE = "place/{${PlaceDetailViewModel.PLACE_ID_ARG}}"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val PROFILE = "profile"
    const val PERSON = "user/{${PublicProfileViewModel.USER_ID_ARG}}"
    fun person(userId: String) = "user/${Uri.encode(userId)}"
    fun place(placeId: String) = "place/${Uri.encode(placeId)}"
}

/** Navegação: pesquisa → detalhe do local; pesquisa → entrar ↔ criar conta; pesquisa → o meu perfil; pesquisa de pessoas → perfil público. Voltar regressa à lista com a pesquisa intacta. */
@Composable
fun PortoFitNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.SEARCH) {
        composable(Routes.SEARCH) { entry ->
            SearchScreen(
                onPlaceClick = { placeId ->
                    // Evita abrir o detalhe duas vezes com toques repetidos durante a transição.
                    if (entry.isResumed()) navController.navigate(Routes.place(placeId))
                },
                onLoginClick = { if (entry.isResumed()) navController.navigate(Routes.LOGIN) },
                onProfileClick = { if (entry.isResumed()) navController.navigate(Routes.PROFILE) },
                onPersonClick = { userId -> if (entry.isResumed()) navController.navigate(Routes.person(userId)) },
            )
        }
        composable(Routes.LOGIN) { entry ->
            LoginScreen(
                onBack = { if (entry.isResumed()) navController.navigateUp() },
                onRegisterClick = {
                    // O registo substitui o login na pilha: voltar regressa à pesquisa.
                    if (entry.isResumed()) navController.navigate(Routes.REGISTER) { popUpTo(Routes.LOGIN) { inclusive = true } }
                },
                onDone = { navController.popBackStack(Routes.SEARCH, inclusive = false) },
            )
        }
        composable(
            route = Routes.PERSON,
            arguments = listOf(navArgument(PublicProfileViewModel.USER_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            PublicProfileScreen(
                onBack = { if (entry.isResumed()) navController.navigateUp() },
                onEditOwnProfile = { if (entry.isResumed()) navController.navigate(Routes.PROFILE) },
            )
        }
        composable(Routes.PROFILE) { entry ->
            ProfileScreen(onBack = { if (entry.isResumed()) navController.navigateUp() })
        }
        composable(Routes.REGISTER) { entry ->
            RegisterScreen(
                onBack = { if (entry.isResumed()) navController.navigateUp() },
                onDone = { navController.popBackStack(Routes.SEARCH, inclusive = false) },
            )
        }
        composable(
            route = Routes.PLACE,
            arguments = listOf(navArgument(PlaceDetailViewModel.PLACE_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            PlaceDetailScreen(onBack = { if (entry.isResumed()) navController.navigateUp() })
        }
    }
}

private fun NavBackStackEntry.isResumed() = lifecycle.currentState == Lifecycle.State.RESUMED
