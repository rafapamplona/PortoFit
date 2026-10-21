package com.example.portofit.ui.navigation

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.portofit.R
import com.example.portofit.ui.feature.collections.CollectionDetailScreen
import com.example.portofit.ui.feature.collections.CollectionDetailViewModel
import com.example.portofit.ui.feature.collections.CollectionsScreen
import com.example.portofit.ui.feature.collections.SaveToCollectionScreen
import com.example.portofit.ui.feature.collections.SaveToCollectionViewModel
import com.example.portofit.ui.feature.discover.DiscoverActions
import com.example.portofit.ui.feature.messaging.ConversationScreen
import com.example.portofit.ui.feature.messaging.ConversationViewModel
import com.example.portofit.ui.feature.messaging.InboxScreen
import com.example.portofit.ui.feature.messaging.ShareExperienceScreen
import com.example.portofit.ui.feature.messaging.ShareExperienceViewModel
import com.example.portofit.ui.feature.discover.DiscoverScreen
import com.example.portofit.ui.feature.stats.StatsActions
import com.example.portofit.ui.feature.stats.StatsScreen
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
    const val DISCOVER = "discover"
    const val STATS = "stats"
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
    const val COLLECTIONS = "collections"
    const val COLLECTION = "collection/{${CollectionDetailViewModel.COLLECTION_ID_ARG}}?${CollectionDetailViewModel.COLLECTION_NAME_ARG}={${CollectionDetailViewModel.COLLECTION_NAME_ARG}}"
    fun collection(id: String, name: String) =
        "collection/${Uri.encode(id)}?${CollectionDetailViewModel.COLLECTION_NAME_ARG}=${Uri.encode(name)}"
    const val SAVE = "experience/{${SaveToCollectionViewModel.EXPERIENCE_ID_ARG}}/save"
    fun save(experienceId: String) = "experience/${Uri.encode(experienceId)}/save"
    fun experience(experienceId: String) = "experience/${Uri.encode(experienceId)}"
    const val INBOX = "messages"
    /** Uma conversa pelo id (caixa, partilha) ou com uma pessoa (perfil: a conversa é aberta neste ecrã). */
    const val CONVERSATION = "conversation?${ConversationViewModel.CONVERSATION_ID_ARG}={${ConversationViewModel.CONVERSATION_ID_ARG}}" +
        "&${ConversationViewModel.PARTICIPANT_ID_ARG}={${ConversationViewModel.PARTICIPANT_ID_ARG}}" +
        "&${ConversationViewModel.PARTICIPANT_NAME_ARG}={${ConversationViewModel.PARTICIPANT_NAME_ARG}}"
    fun conversation(conversationId: String, username: String) =
        "conversation?${ConversationViewModel.CONVERSATION_ID_ARG}=${Uri.encode(conversationId)}&${ConversationViewModel.PARTICIPANT_NAME_ARG}=${Uri.encode(username)}"
    fun conversationWith(userId: String, username: String) =
        "conversation?${ConversationViewModel.PARTICIPANT_ID_ARG}=${Uri.encode(userId)}&${ConversationViewModel.PARTICIPANT_NAME_ARG}=${Uri.encode(username)}"
    const val SHARE = "experience/{${ShareExperienceViewModel.EXPERIENCE_ID_ARG}}/share"
    fun share(experienceId: String) = "experience/${Uri.encode(experienceId)}/share"
}

/** Destinos de topo da barra inferior (Pesquisar é o inicial). */
private enum class TopLevel(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    SEARCH(Routes.SEARCH, R.string.nav_search, R.drawable.ic_search),
    DISCOVER(Routes.DISCOVER, R.string.nav_discover, R.drawable.ic_explore),
    STATS(Routes.STATS, R.string.nav_stats, R.drawable.ic_bar_chart),
}

/**
 * Muda de destino de topo guardando a pilha e o estado do anterior (ViewModels, pesquisa, filtros, posição) e repondo
 * os do novo: alternar entre destinos nunca perde o que lá estava.
 */
private fun NavController.navigateToTopLevel(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/**
 * Barra inferior (E4) com Pesquisar (inicial), Descobrir e Estatísticas, visível só nesses três ecrãs; os detalhes,
 * o login e a publicação abrem por cima, sem a barra, como antes.
 *
 * Navegação: pesquisa → detalhe do local → publicar (com sessão) / detalhe da experiência; pesquisa → entrar ↔
 * criar conta; pesquisa → o meu perfil; pesquisa de pessoas → perfil público ↔ seguidores/seguidos; perfis →
 * detalhe da experiência → local ou autor. O login regressa ao ecrã que o abriu, sem executar ações.
 * Voltar regressa ao ecrã anterior com o estado intacto.
 */
@Composable
fun PortoFitNavHost() {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    Scaffold(
        // As margens do sistema são tratadas pelos ecrãs (e pela barra): aqui só a altura da barra.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (TopLevel.entries.any { it.route == currentRoute }) {
                NavigationBar {
                    TopLevel.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { if (currentRoute != destination.route) navController.navigateToTopLevel(destination.route) },
                            icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        PortoFitGraph(navController, Modifier.padding(padding).consumeWindowInsets(padding))
    }
}

@Composable
private fun PortoFitGraph(navController: NavHostController, modifier: Modifier) {
    NavHost(navController = navController, startDestination = Routes.SEARCH, modifier = modifier) {
        composable(Routes.DISCOVER) { entry ->
            DiscoverScreen(
                DiscoverActions(
                    onOpenExperience = { id -> if (entry.isResumed()) navController.navigate(Routes.experience(id)) },
                    onOpenAuthor = { userId -> if (entry.isResumed()) navController.navigate(Routes.person(userId)) },
                    // O login regressa a Descobrir; nada é executado sozinho.
                    onLogin = { if (entry.isResumed()) navController.navigate(Routes.LOGIN) },
                    onProfile = { if (entry.isResumed()) navController.navigate(Routes.PROFILE) },
                    onEditInterests = { if (entry.isResumed()) navController.navigate(Routes.PROFILE) },
                    onFindPeople = { if (entry.isResumed()) navController.navigateToTopLevel(Routes.SEARCH) },
                    onCollections = { if (entry.isResumed()) navController.navigate(Routes.COLLECTIONS) },
                    onMessages = { if (entry.isResumed()) navController.navigate(Routes.INBOX) },
                ),
            )
        }
        composable(Routes.STATS) { entry ->
            StatsScreen(
                StatsActions(
                    onOpenExperience = { id -> if (entry.isResumed()) navController.navigate(Routes.experience(id)) },
                    // Depois de entrar, o login regressa a Estatísticas.
                    onLogin = { if (entry.isResumed()) navController.navigate(Routes.LOGIN) },
                    onProfile = { if (entry.isResumed()) navController.navigate(Routes.PROFILE) },
                    onCollections = { if (entry.isResumed()) navController.navigate(Routes.COLLECTIONS) },
                    onMessages = { if (entry.isResumed()) navController.navigate(Routes.INBOX) },
                ),
            )
        }
        composable(Routes.INBOX) { entry ->
            InboxScreen(
                onBack = { if (entry.isResumed()) navController.navigateUp() },
                onOpenConversation = { id, username -> if (entry.isResumed()) navController.navigate(Routes.conversation(id, username)) },
                // Sem sessão: entrar e regressar à caixa.
                onLogin = { if (entry.isResumed()) navController.navigate(Routes.LOGIN) },
            )
        }
        composable(
            route = Routes.CONVERSATION,
            arguments = listOf(
                navArgument(ConversationViewModel.CONVERSATION_ID_ARG) { type = NavType.StringType; defaultValue = "" },
                navArgument(ConversationViewModel.PARTICIPANT_ID_ARG) { type = NavType.StringType; defaultValue = "" },
                navArgument(ConversationViewModel.PARTICIPANT_NAME_ARG) { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            ConversationScreen(
                initialName = entry.arguments?.getString(ConversationViewModel.PARTICIPANT_NAME_ARG).orEmpty(),
                onBack = { if (entry.isResumed()) navController.navigateUp() },
                onOpenExperience = { id -> if (entry.isResumed()) navController.navigate(Routes.experience(id)) },
                // O login regressa a esta conversa; nada é criado nem enviado sozinho.
                onLogin = { if (entry.isResumed()) navController.navigate(Routes.LOGIN) },
            )
        }
        composable(
            route = Routes.SHARE,
            arguments = listOf(navArgument(ShareExperienceViewModel.EXPERIENCE_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            ShareExperienceScreen(
                onBack = { if (entry.isResumed()) navController.navigateUp() },
                // O login regressa ao seletor; enviar exige um novo toque.
                onLogin = { if (entry.isResumed()) navController.navigate(Routes.LOGIN) },
                onOpenConversation = { id, username ->
                    if (entry.isResumed()) navController.navigate(Routes.conversation(id, username)) { popUpTo(Routes.SHARE) { inclusive = true } }
                },
            )
        }
        composable(Routes.COLLECTIONS) { entry ->
            CollectionsScreen(
                onBack = { if (entry.isResumed()) navController.navigateUp() },
                onOpenCollection = { c -> if (entry.isResumed()) navController.navigate(Routes.collection(c.id, c.name)) },
                // Sem sessão: entrar e regressar a esta página.
                onLogin = { if (entry.isResumed()) navController.navigate(Routes.LOGIN) },
            )
        }
        composable(
            route = Routes.COLLECTION,
            arguments = listOf(
                navArgument(CollectionDetailViewModel.COLLECTION_ID_ARG) { type = NavType.StringType },
                navArgument(CollectionDetailViewModel.COLLECTION_NAME_ARG) { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            CollectionDetailScreen(
                initialName = entry.arguments?.getString(CollectionDetailViewModel.COLLECTION_NAME_ARG).orEmpty(),
                onBack = { if (entry.isResumed()) navController.navigateUp() },
                onOpenExperience = { id -> if (entry.isResumed()) navController.navigate(Routes.experience(id)) },
                onOpenAuthor = { userId -> if (entry.isResumed()) navController.navigate(Routes.person(userId)) },
                onLogin = { if (entry.isResumed()) navController.navigate(Routes.LOGIN) },
            )
        }
        composable(
            route = Routes.SAVE,
            arguments = listOf(navArgument(SaveToCollectionViewModel.EXPERIENCE_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            SaveToCollectionScreen(
                onBack = { if (entry.isResumed()) navController.navigateUp() },
                // O login regressa ao seletor; guardar exige um novo toque.
                onLogin = { if (entry.isResumed()) navController.navigate(Routes.LOGIN) },
            )
        }
        composable(Routes.SEARCH) { entry ->
            SearchScreen(
                onPlaceClick = { placeId ->
                    // Evita abrir o detalhe duas vezes com toques repetidos durante a transição.
                    if (entry.isResumed()) navController.navigate(Routes.place(placeId))
                },
                onLoginClick = { if (entry.isResumed()) navController.navigate(Routes.LOGIN) },
                onProfileClick = { if (entry.isResumed()) navController.navigate(Routes.PROFILE) },
                onPersonClick = { userId -> if (entry.isResumed()) navController.navigate(Routes.person(userId)) },
                onCollectionsClick = { if (entry.isResumed()) navController.navigate(Routes.COLLECTIONS) },
                onMessagesClick = { if (entry.isResumed()) navController.navigate(Routes.INBOX) },
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
                // Com sessão: abre a conversa (criada ou devolvida pelo servidor). Sem sessão, o perfil envia para o login.
                onMessage = { userId, username -> if (entry.isResumed()) navController.navigate(Routes.conversationWith(userId, username)) },
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
                onSaveToList = { id -> if (entry.isResumed()) navController.navigate(Routes.save(id)) },
                onShare = { id -> if (entry.isResumed()) navController.navigate(Routes.share(id)) },
            )
        }
    }
}

private fun NavBackStackEntry.isResumed() = lifecycle.currentState == Lifecycle.State.RESUMED
