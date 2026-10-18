package com.example.portofit.di

import android.content.Context
import com.example.portofit.data.auth.AuthGateway
import com.example.portofit.data.auth.FileSessionStore
import com.example.portofit.data.auth.KeystoreSecretCipher
import com.example.portofit.data.auth.RetrofitAuthGateway
import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.auth.UnconfiguredAuthGateway
import com.example.portofit.data.remote.CatalogApi
import com.example.portofit.data.remote.ProfileApi
import com.example.portofit.data.remote.UsersApi
import com.example.portofit.data.repository.CatalogRepository
import com.example.portofit.data.repository.NetworkCatalogRepository
import com.example.portofit.data.repository.NetworkProfileRepository
import com.example.portofit.data.repository.ProfileRepository
import com.example.portofit.data.repository.UnconfiguredCatalogRepository
import com.example.portofit.data.repository.UnconfiguredProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Injeção de dependências manual: cria uma única vez os objetos partilhados pela app.
 * Criado em PortoFitApplication; os ViewModels obtêm daqui os repositórios e a sessão.
 */
class AppContainer(context: Context, catalogBaseUrl: String, usersBaseUrl: String) {

    private val json = Json {
        // Campos novos acrescentados pelo backend não partem a app.
        ignoreUnknownKeys = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Cliente do users-service (perfil). Ligações paradas são descartadas ao fim de 5 s, antes de o
     * servidor as fechar (Tomcat: 10 s); sem isto, um pedido depois de uns segundos parado reutilizava
     * uma ligação já fechada e falhava. Mais tempo de leitura: o BCrypt demora no login.
     * Sem registo de pedidos: nunca escrever palavras-passe nem tokens em logs.
     */
    private val usersHttpClient = httpClient.newBuilder()
        .connectionPool(ConnectionPool(2, 5, TimeUnit.SECONDS))
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * Cliente das chamadas de sessão (registo, login, refresh, logout): o mesmo, mas sem novas tentativas
     * automáticas — uma renovação repetida às cegas pode reenviar um refresh token já consumido.
     */
    private val sessionHttpClient = usersHttpClient.newBuilder()
        .retryOnConnectionFailure(false)
        .build()

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

    private fun retrofit(baseUrl: String, client: OkHttpClient) = Retrofit.Builder()
        .baseUrl(baseUrl.trimEnd('/') + "/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
}
