plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// URL do catalog-service. Por omissão, em debug, o PC visto a partir do emulador Android (10.0.2.2).
// Pode ser alterada sem editar código: ./gradlew assembleDebug -Pportofit.catalogBaseUrl=http://127.0.0.1:8081/
val catalogBaseUrlOverride: String? = providers.gradleProperty("portofit.catalogBaseUrl").orNull
// URL do users-service (mesma regra: em debug, o PC visto a partir do emulador).
val usersBaseUrlOverride: String? = providers.gradleProperty("portofit.usersBaseUrl").orNull
// URL do experiences-service (mesma regra).
val experiencesBaseUrlOverride: String? = providers.gradleProperty("portofit.experiencesBaseUrl").orNull
// URL do collections-service (mesma regra).
val collectionsBaseUrlOverride: String? = providers.gradleProperty("portofit.collectionsBaseUrl").orNull
// URL do messaging-service (mesma regra).
val messagingBaseUrlOverride: String? = providers.gradleProperty("portofit.messagingBaseUrl").orNull
// URL do notifications-service (mesma regra).
val notificationsBaseUrlOverride: String? = providers.gradleProperty("portofit.notificationsBaseUrl").orNull

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
            buildConfigField("String", "USERS_BASE_URL", "\"${usersBaseUrlOverride ?: "http://10.0.2.2:8082/"}\"")
            buildConfigField("String", "EXPERIENCES_BASE_URL", "\"${experiencesBaseUrlOverride ?: "http://10.0.2.2:8083/"}\"")
            buildConfigField("String", "COLLECTIONS_BASE_URL", "\"${collectionsBaseUrlOverride ?: "http://10.0.2.2:8084/"}\"")
            buildConfigField("String", "MESSAGING_BASE_URL", "\"${messagingBaseUrlOverride ?: "http://10.0.2.2:8085/"}\"")
            buildConfigField("String", "NOTIFICATIONS_BASE_URL", "\"${notificationsBaseUrlOverride ?: "http://10.0.2.2:8086/"}\"")
        }
        release {
            optimization {
                enable = false
            }
            // Ainda não há backend publicado: sem URL, a app mostra "serviço não configurado".
            buildConfigField("String", "CATALOG_BASE_URL", "\"${catalogBaseUrlOverride ?: ""}\"")
            buildConfigField("String", "USERS_BASE_URL", "\"${usersBaseUrlOverride ?: ""}\"")
            buildConfigField("String", "EXPERIENCES_BASE_URL", "\"${experiencesBaseUrlOverride ?: ""}\"")
            buildConfigField("String", "COLLECTIONS_BASE_URL", "\"${collectionsBaseUrlOverride ?: ""}\"")
            buildConfigField("String", "MESSAGING_BASE_URL", "\"${messagingBaseUrlOverride ?: ""}\"")
            buildConfigField("String", "NOTIFICATIONS_BASE_URL", "\"${notificationsBaseUrlOverride ?: ""}\"")
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
    // Fotografias das experiências em Compose (com o cliente OkHttp da app).
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Consulta periódica das notificações com a app fechada (docs/notifications-api.md §15.3).
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}