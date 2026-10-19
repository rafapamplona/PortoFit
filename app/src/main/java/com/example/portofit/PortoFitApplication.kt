package com.example.portofit

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.example.portofit.di.AppContainer

class PortoFitApplication : Application(), SingletonImageLoader.Factory {

    /** Dependências partilhadas pela app inteira (ver di/AppContainer.kt). */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(
            context = this,
            catalogBaseUrl = BuildConfig.CATALOG_BASE_URL,
            usersBaseUrl = BuildConfig.USERS_BASE_URL,
            experiencesBaseUrl = BuildConfig.EXPERIENCES_BASE_URL,
        )
    }

    /** Carregador de imagens do Coil com o cliente HTTP da app (mesma configuração de rede e pool). */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.imageHttpClient })) }
            .build()
}
