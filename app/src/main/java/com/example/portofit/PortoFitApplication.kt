package com.example.portofit

import android.app.Application
import com.example.portofit.di.AppContainer

class PortoFitApplication : Application() {

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
}
