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
import com.example.portofit.ui.feature.experience.ExperienceDetailScreen
import com.example.portofit.ui.feature.experience.ExperienceDetailViewModel
import com.example.portofit.ui.feature.experience.PublishExperienceScreen
import com.example.portofit.ui.feature.experience.PublishExperienceViewModel
import com.example.portofit.ui.feature.people.FollowListKind
import com.example.portofit.ui.feature.people.FollowListScreen
import com.example.portofit.ui.feature.people.FollowListViewModel
import com.example.portofit.ui.feature.people.PublicProfileScreen
import com.example.portofit.ui.feature.people.PublicProfileViewModel
import com.example.portofit.ui.feature.place.PlaceDetailActions
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
    const val FOLLOW_LIST = "user/{${FollowListViewModel.USER_ID_ARG}}/list/{${FollowListViewModel.KIND_ARG}}"
    fun followList(userId: String, kind: FollowListKind) = "user/${Uri.encode(userId)}/list/${kind.name}"
    fun place(placeId: String) = "place/${Uri.encode(placeId)}"
    const val PUBLISH = "place/{${PublishExperienceViewModel.PLACE_ID_ARG}}/publish/{${PublishExperienceViewModel.OFFERING_ID_ARG}}" +
        "?${PublishExperienceViewModel.PLACE_NAME_ARG}={${PublishExperienceViewModel.PLACE_NAME_ARG}}" +
        "&${PublishExperienceViewModel.OFFERING_NAME_ARG}={${PublishExperienceViewModel.OFFERING_NAME_ARG}}"
    fun publish(placeId: String, offeringId: String, placeName: String, offeringName: String) =
        "place/${Uri.encode(placeId)}/publish/${Uri.encode(offeringId)}" +
            "?${PublishExperienceViewModel.PLACE_NAME_ARG}=${Uri.encode(placeName)}&${PublishExperienceViewModel.OFFERING_NAME_ARG}=${Uri.encode(offeringName)}"
    const val EXPERIENCE = "experience/{${ExperienceDetailViewModel.EXPERIENCE_ID_ARG}}"
    fun experience(experienceId: String) = "experience/${Uri.encode(experienceId)}"
}

/**
 * Navegação: pesquisa → detalhe do local → publicar (com sessão) / detalhe da experiência; pesquisa → entrar ↔
 * criar conta; pesquisa → o meu perfil; pesquisa de pessoas → perfil público ↔ seguidores/seguidos; perfis →
 * detalhe da experiência → local ou autor. O login regressa ao ecrã que o abriu, sem executar ações.
 * Voltar regressa ao ecrã anterior com o estado intacto.
 */
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
                    // O registo substitui o login na pilha: voltar regressa ao ecrã que abriu o login.
                    if (entry.isResumed()) navController.navigate(Routes.REGISTER) { popUpTo(Routes.LOGIN) { inclusive = true } }
                },
                // Regressa ao ecrã que abriu o login (pesquisa ou perfil público); só uma vez.
                onDone = { if (navController.currentBackStackEntry == entry) navController.popBackStack() },
            )
        }
        composable(
            route = Routes.PERSON,
            arguments = listOf(navArgument(PublicProfileViewModel.USER_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            PublicProfileScreen(
                onBack = { if (entry.isResumed()) navController.navigateUp() },
                onEditOwnProfile = { if (entry.isResumed()) navController.navigate(Routes.PROFILE) },
                // Depois de entrar, o login regressa a este perfil; seguir exige novo toque.
                onLoginRequired = { if (entry.isResumed()) navController.navigate(Routes.LOGIN) },
                onOpenList = { userId, kind -> if (entry.isResumed()) navController.navigate(Routes.followList(userId, kind)) },
                onOpenExperience = { id -> if (entry.isResumed()) navController.navigate(Routes.experience(id)) },
            )
        }
        composable(
            route = Routes.FOLLOW_LIST,
            arguments = listOf(
                navArgument(FollowListViewModel.USER_ID_ARG) { type = NavType.StringType },
                navArgument(FollowListViewModel.KIND_ARG) { type = NavType.StringType },
            ),
        ) { entry ->
            FollowListScreen(
                onBack = { if (entry.isResumed()) navController.navigateUp() },
                onPersonClick = { userId -> if (entry.isResumed()) navController.navigate(Routes.person(userId)) },
            )
        }
        composable(Routes.PROFILE) { entry ->
            ProfileScreen(
                onBack = { if (entry.isResumed()) navController.navigateUp() },
                onOpenExperience = { id -> if (entry.isResumed()) navController.navigate(Routes.experience(id)) },
            )
        }
        composable(Routes.REGISTER) { entry ->
            RegisterScreen(
                onBack = { if (entry.isResumed()) navController.navigateUp() },
                // Regressa ao ecrã que abriu o login (pesquisa ou perfil público); só uma vez.
                onDone = { if (navController.currentBackStackEntry == entry) navController.popBackStack() },
            )
        }
        composable(
            route = Routes.PLACE,
            arguments = listOf(navArgument(PlaceDetailViewModel.PLACE_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            PlaceDetailScreen(
                PlaceDetailActions(
                    onBack = { if (entry.isResumed()) navController.navigateUp() },
                    onPublish = { place, offering, signedIn ->
                        if (entry.isResumed()) {
                            // Sem sessão: entrar e regressar a este local; publicar exige um novo toque.
                            navController.navigate(if (signedIn) Routes.publish(place.id, offering.id, place.name, offering.name) else Routes.LOGIN)
                        }
                    },
                    onOpenExperience = { id -> if (entry.isResumed()) navController.navigate(Routes.experience(id)) },
                    onOpenAuthor = { userId -> if (entry.isResumed()) navController.navigate(Routes.person(userId)) },
                ),
            )
        }
        composable(
            route = Routes.PUBLISH,
            arguments = listOf(
                navArgument(PublishExperienceViewModel.PLACE_ID_ARG) { type = NavType.StringType },
                navArgument(PublishExperienceViewModel.OFFERING_ID_ARG) { type = NavType.StringType },
                navArgument(PublishExperienceViewModel.PLACE_NAME_ARG) { type = NavType.StringType; defaultValue = "" },
                navArgument(PublishExperienceViewModel.OFFERING_NAME_ARG) { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            PublishExperienceScreen(
                onBack = { if (entry.isResumed()) navController.navigateUp() },
                // O login regressa a este formulário (texto, estrelas e chave preservados); publicar exige novo toque.
                onLoginRequired = { if (entry.isResumed()) navController.navigate(Routes.LOGIN) },
                // Publicada: regressa ao local, que atualiza a lista e as avaliações.
                onPublished = { if (navController.currentBackStackEntry == entry) navController.popBackStack() },
                onOpenExperience = { id ->
                    if (entry.isResumed()) navController.navigate(Routes.experience(id)) { popUpTo(Routes.PUBLISH) { inclusive = true } }
                },
            )
        }
        composable(
            route = Routes.EXPERIENCE,
            arguments = listOf(navArgument(ExperienceDetailViewModel.EXPERIENCE_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            ExperienceDetailScreen(
                // Também usado depois de eliminar: regressa ao ecrã anterior (que atualiza a lista).
                onBack = { if (navController.currentBackStackEntry == entry) navController.popBackStack() },
                onOpenAuthor = { userId -> if (entry.isResumed()) navController.navigate(Routes.person(userId)) },
                onOpenPlace = { placeId -> if (entry.isResumed()) navController.navigate(Routes.place(placeId)) },
                onLoginRequired = { if (entry.isResumed()) navController.navigate(Routes.LOGIN) },
            )
        }
    }
}

private fun NavBackStackEntry.isResumed() = lifecycle.currentState == Lifecycle.State.RESUMED
