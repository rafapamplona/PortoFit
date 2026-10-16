package com.example.portofit

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.example.portofit.ui.navigation.PortoFitNavHost
import com.example.portofit.ui.theme.PortoFitTheme

class MainActivity : ComponentActivity() {

    private val localNetworkPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* o utilizador usa "Tentar novamente" */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestLocalNetworkAccessForDevelopment()
        setContent {
            PortoFitTheme {
                PortoFitNavHost()
            }
        }
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
