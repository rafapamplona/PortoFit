package com.example.portofit.data.photo

import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Problemas ao preparar uma fotografia (nada do rascunho de texto e estrelas se perde). */
enum class PhotoError {
    /** A aplicação de câmara não tirou a fotografia (cancelada ou sem resultado). */
    CAPTURE_CANCELLED,

    /** Não há aplicação de câmara (ou não pôde ser aberta). */
    CAMERA_UNAVAILABLE,

    /** O ficheiro escolhido não pôde ser lido ou não é uma imagem. */
    UNREADABLE,

    /** Menos de 200 px num dos lados, ou proporções que o servidor não aceita. */
    TOO_SMALL,

    /** Não foi possível gravar a fotografia preparada no armazenamento da app. */
    STORAGE,

    /** A fotografia preparada deixou de existir (ex.: a cache da app foi limpa). */
    MISSING,
}

/** Fotografia pronta a enviar: um JPEG na pasta privada da app, referido só pelo nome. */
data class PreparedPhoto(val name: String, val width: Int, val height: Int)

sealed interface PrepareResult {
    data class Success(val photo: PreparedPhoto) : PrepareResult
    data class Failure(val error: PhotoError) : PrepareResult
}

/** Ficheiro da app onde a aplicação de câmara grava a fotografia; [uri] é a URI segura (FileProvider). */
data class CaptureTarget(val name: String, val uri: String)

/**
 * Rascunhos de fotografias da app (docs/app-android.md §15). Só trabalha com ficheiros na sua pasta privada
 * (`cache/photo-drafts/`), referidos por nomes gerados pela app: o SavedStateHandle guarda só esses nomes,
 * nunca bitmaps nem ficheiros. Nunca apaga nada fora dessa pasta (fotografias da galeria incluídas).
 */
interface PhotoDrafts {
    /** Cria o ficheiro de destino da câmara (vazio) e devolve a URI segura para a aplicação de câmara. */
    fun newCaptureTarget(): CaptureTarget?

    /** Lê uma imagem escolhida no Photo Picker (URI `content://`) e prepara o JPEG. Fora da thread principal. */
    suspend fun prepareFromUri(uri: String): PrepareResult

    /** Prepara o JPEG a partir da fotografia que a câmara gravou em [captureName] (e apaga a captura). */
    suspend fun prepareFromCapture(captureName: String): PrepareResult

    fun exists(name: String): Boolean

    /** Ficheiro de um rascunho (para enviar ou pré-visualizar), ou null se não existir ou o nome não for da app. */
    fun file(name: String): File?

    fun delete(name: String)

    /** Apaga rascunhos esquecidos (ex.: a app foi terminada a meio) mais antigos do que [olderThanMillis]. */
    fun deleteStale(olderThanMillis: Long)
}

/**
 * Dimensões da fotografia preparada, segundo os limites do servidor (docs/experiences-api.md §12.2): lado
 * maior até 1600 px, cada lado com pelo menos 200 px. A redução é feita ao descodificar.
 */
object PhotoSizing {
    const val OUTPUT_MAX_SIDE = 1600
    const val MIN_SIDE = 200
    const val SERVER_MAX_SIDE = 8000
    const val JPEG_QUALITY = 85

    /** Tamanho final, ou null se a imagem não cumpre os mínimos do servidor. */
    fun target(width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0 || min(width, height) < MIN_SIDE) return null
        var scale = min(1.0, OUTPUT_MAX_SIDE.toDouble() / max(width, height))
        // Uma imagem muito alongada não pode ficar com o lado menor abaixo de 200 px.
        if (min(width, height) * scale < MIN_SIDE) scale = MIN_SIDE.toDouble() / min(width, height)
        val w = (width * scale).roundToInt()
        val h = (height * scale).roundToInt()
        if (max(w, h) > SERVER_MAX_SIDE) return null
        return w to h
    }

    /** Maior potência de 2 que, ao descodificar, ainda deixa a imagem com pelo menos o tamanho final. */
    fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }

    /** Nomes gerados pela app (nunca vindos de fora): só estes podem ser lidos ou apagados. */
    private val NAME = Regex("^(capture|photo)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$")

    fun isDraftName(name: String): Boolean = NAME.matches(name)
}
