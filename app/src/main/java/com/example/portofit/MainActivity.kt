package com.example.portofit

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import com.example.portofit.ui.components.LocalPhotoUrls
import com.example.portofit.notifications.NotificationIntents
import com.example.portofit.ui.components.PhotoUrls
import com.example.portofit.ui.navigation.PortoFitNavHost
import com.example.portofit.ui.theme.PortoFitTheme

class MainActivity : ComponentActivity() {

    private val localNetworkPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* o utilizador usa "Tentar novamente" */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestLocalNetworkAccessForDevelopment()
        // Só o intent original: ao recriar a atividade (rotação, regresso pelos recentes) o toque já foi tratado.
        if (savedInstanceState == null) receiveNotificationTap(intent)
        setContent {
            PortoFitTheme {
                // Endereço das fotografias resolvido a partir da configuração (nunca fixo nos modelos).
                CompositionLocalProvider(LocalPhotoUrls provides PhotoUrls((application as PortoFitApplication).container.experiencesBaseUrl)) {
                    PortoFitNavHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveNotificationTap(intent)
    }

    /** Ao voltar à app: a permissão pode ter mudado nas definições do Android (docs/notifications-api.md §15.2). */
    override fun onStart() {
        super.onStart()
        (application as PortoFitApplication).container.syncNotifications()
    }

    /** Toque numa notificação do sistema: a navegação decide o que abrir, depois de confirmar a conta (§15.6). */
    private fun receiveNotificationTap(intent: Intent?) {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        NotificationIntents.tapOf(intent)?.let { (application as PortoFitApplication).container.notificationTaps.value = it }
    }

    /**
     * Só em debug e Android 17+: o backend local (10.0.2.2 no emulador) é um endereço da rede local,
     * que exige ACCESS_LOCAL_NETWORK (declarada apenas em src/debug/AndroidManifest.xml).
     */
    private fun requestLocalNetworkAccessForDevelopment() {
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < ANDROID_17) return
        if (checkSelfPermission(ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            localNetworkPermission.launch(ACCESS_LOCAL_NETWORK)
        }
    }

    private companion object {
        const val ANDROID_17 = 37
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
