package com.example.portofit.di

import com.example.portofit.data.remote.CatalogApi
import com.example.portofit.data.repository.CatalogRepository
import com.example.portofit.data.repository.NetworkCatalogRepository
import com.example.portofit.data.repository.UnconfiguredCatalogRepository
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Injeção de dependências manual: cria uma única vez os objetos partilhados pela app.
 * Criado em PortoFitApplication; os ViewModels obtêm daqui os repositórios.
 */
class AppContainer(catalogBaseUrl: String) {

    private val json = Json {
        // Campos novos acrescentados pelo backend não partem a app.
        ignoreUnknownKeys = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    val catalogRepository: CatalogRepository =
        if (catalogBaseUrl.isBlank()) {
            UnconfiguredCatalogRepository()
        } else {
            val api = Retrofit.Builder()
                .baseUrl(catalogBaseUrl.trimEnd('/') + "/")
                .client(httpClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(CatalogApi::class.java)
            NetworkCatalogRepository(api)
        }
}
