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
import com.example.portofit.ui.feature.place.PlaceDetailScreen
import com.example.portofit.ui.feature.place.PlaceDetailViewModel
import com.example.portofit.ui.feature.search.SearchScreen

private object Routes {
    const val SEARCH = "search"
    const val PLACE = "place/{${PlaceDetailViewModel.PLACE_ID_ARG}}"
    fun place(placeId: String) = "place/${Uri.encode(placeId)}"
}

/** Navegação: pesquisa → detalhe do local. Voltar regressa à lista com a pesquisa intacta. */
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
