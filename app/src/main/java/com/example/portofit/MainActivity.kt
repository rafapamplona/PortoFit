package com.example.portofit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.portofit.ui.navigation.PortoFitNavHost
import com.example.portofit.ui.theme.PortoFitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PortoFitTheme {
                PortoFitNavHost()
            }
        }
    }
}
