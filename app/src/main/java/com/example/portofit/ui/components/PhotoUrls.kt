package com.example.portofit.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Resolve o caminho relativo de uma fotografia (`/api/v1/media/{mediaId}`) com o URL configurado do
 * experiences-service (BuildConfig, via AppContainer). Os modelos guardam só o caminho; nenhum endereço
 * (nem 10.0.2.2 nem nomes internos do Docker) fica fixo neles. Só caminhos de fotografias deste serviço são
 * aceites: um URL absoluto ou para outro sítio dá null (sem imagem).
 */
class PhotoUrls(baseUrl: String) {
    private val base: HttpUrl? = baseUrl.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()

    fun resolve(path: String): String? {
        if (base == null || !MEDIA_PATH.matches(path)) return null
        return base.resolve(path)?.takeIf { it.host == base.host && it.port == base.port }?.toString()
    }

    private companion object {
        val MEDIA_PATH = Regex("^/api/v1/media/[0-9a-fA-F-]{36}$")
    }
}

/** Fornecido na MainActivity a partir do AppContainer. */
val LocalPhotoUrls = staticCompositionLocalOf { PhotoUrls("") }
