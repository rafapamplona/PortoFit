package com.example.portofit.di

import android.content.Context
import com.example.portofit.data.auth.AuthGateway
import com.example.portofit.data.auth.FileSessionStore
import com.example.portofit.data.auth.KeystoreSecretCipher
import com.example.portofit.data.auth.RetrofitAuthGateway
import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.auth.UnconfiguredAuthGateway
import com.example.portofit.data.photo.AndroidPhotoDrafts
import com.example.portofit.data.photo.PhotoDrafts
import com.example.portofit.data.remote.CatalogApi
import com.example.portofit.data.remote.ExperiencesApi
import com.example.portofit.data.remote.PeopleApi
import com.example.portofit.data.remote.ProfileApi
import com.example.portofit.data.remote.UsersApi
import com.example.portofit.data.repository.CatalogRepository
import com.example.portofit.data.repository.ExperienceChanges
import com.example.portofit.data.repository.ExperiencesRepository
import com.example.portofit.data.repository.NetworkExperiencesRepository
import com.example.portofit.data.repository.PreferencesViewerKeys
import com.example.portofit.data.repository.UnconfiguredExperiencesRepository
import com.example.portofit.data.repository.NetworkCatalogRepository
import com.example.portofit.data.repository.NetworkPeopleRepository
import com.example.portofit.data.repository.PeopleRepository
import com.example.portofit.data.repository.NetworkProfileRepository
import com.example.portofit.data.repository.ProfileRepository
import com.example.portofit.data.repository.UnconfiguredCatalogRepository
import com.example.portofit.data.repository.UnconfiguredPeopleRepository
import com.example.portofit.data.repository.UnconfiguredProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File

/**
 * Injeção de dependências manual: cria uma única vez os objetos partilhados pela app.
 * Criado em PortoFitApplication; os ViewModels obtêm daqui os repositórios e a sessão.
 */
class AppContainer(context: Context, catalogBaseUrl: String, usersBaseUrl: String, val experiencesBaseUrl: String) {

    private val json = Json {
        // Campos novos acrescentados pelo backend não partem a app.
        ignoreUnknownKeys = true
    }

    // Regras de cada cliente (novas tentativas, ligações paradas, tempos) em HttpClients.kt.
    private val httpClient = HttpClients.catalog()
    private val usersHttpClient = HttpClients.users(httpClient)
    private val sessionHttpClient = HttpClients.session(usersHttpClient)
    private val experiencesHttpClient = HttpClients.experiences(httpClient)

    /** Sessão da app: corre num âmbito próprio, independente de ecrãs e ViewModels. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val catalogRepository: CatalogRepository =
        if (catalogBaseUrl.isBlank()) {
            UnconfiguredCatalogRepository()
        } else {
            retrofit(catalogBaseUrl, httpClient).create(CatalogApi::class.java).let(::NetworkCatalogRepository)
        }

    private val usersUrl = usersBaseUrl.takeIf { it.isNotBlank() }

    private val authGateway: AuthGateway =
        usersUrl?.let { RetrofitAuthGateway(retrofit(it, sessionHttpClient).create(UsersApi::class.java), json) }
            ?: UnconfiguredAuthGateway()

    val sessionManager = SessionManager(
        gateway = authGateway,
        // files/session/ está excluída das cópias de segurança (res/xml/*_rules.xml).
        store = FileSessionStore(File(context.filesDir, "session/session.bin"), KeystoreSecretCipher()),
        scope = appScope,
    ).also { it.start() }

    val profileRepository: ProfileRepository =
        usersUrl?.let { NetworkProfileRepository(retrofit(it, usersHttpClient).create(ProfileApi::class.java), sessionManager, json) }
            ?: UnconfiguredProfileRepository()

    /** Pessoas: pesquisa, perfis e listas públicos; com sessão, o perfil inclui a relação e é possível seguir. */
    val peopleRepository: PeopleRepository =
        usersUrl?.let { NetworkPeopleRepository(retrofit(it, usersHttpClient).create(PeopleApi::class.java), sessionManager, json) }
            ?: UnconfiguredPeopleRepository()

    /**
     * Experiências (docs/experiences-api.md): consulta pública; publicar, editar, eliminar, comentar e gostar com
     * sessão; visualizações com sessão ou com a chave aleatória da instalação.
     */
    val experiencesRepository: ExperiencesRepository =
        experiencesBaseUrl.takeIf { it.isNotBlank() }
            ?.let {
                NetworkExperiencesRepository(
                    retrofit(it, experiencesHttpClient).create(ExperiencesApi::class.java),
                    sessionManager,
                    json,
                    PreferencesViewerKeys(context),
                )
            }
            ?: UnconfiguredExperiencesRepository()

    /** Avisos de publicação/edição/eliminação, para atualizar listas e avaliações noutros ecrãs. */
    val experienceChanges = ExperienceChanges()

    /** Rascunhos de fotografias na pasta privada da app; os esquecidos há mais de 3 dias são apagados. */
    val photoDrafts: PhotoDrafts = AndroidPhotoDrafts(context.applicationContext).also { drafts ->
        appScope.launch(Dispatchers.IO) { drafts.deleteStale(STALE_DRAFT_MILLIS) }
    }

    /** Cliente das fotografias (Coil): o mesmo do experiences-service, que respeita a cache HTTP do servidor. */
    val imageHttpClient: OkHttpClient get() = experiencesHttpClient

    private companion object {
        const val STALE_DRAFT_MILLIS = 3L * 24 * 60 * 60 * 1000
    }

    private fun retrofit(baseUrl: String, client: OkHttpClient) = Retrofit.Builder()
        .baseUrl(baseUrl.trimEnd('/') + "/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}
