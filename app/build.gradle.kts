plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// URL do catalog-service. Por omissão, em debug, o PC visto a partir do emulador Android (10.0.2.2).
// Pode ser alterada sem editar código: ./gradlew assembleDebug -Pportofit.catalogBaseUrl=http://127.0.0.1:8081/
val catalogBaseUrlOverride: String? = providers.gradleProperty("portofit.catalogBaseUrl").orNull

android {
    namespace = "com.example.portofit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.portofit"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "CATALOG_BASE_URL", "\"${catalogBaseUrlOverride ?: "http://10.0.2.2:8081/"}\"")
        }
        release {
            optimization {
                enable = false
            }
            // Ainda não há backend publicado: sem URL, a app mostra "serviço não configurado".
            buildConfigField("String", "CATALOG_BASE_URL", "\"${catalogBaseUrlOverride ?: ""}\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}