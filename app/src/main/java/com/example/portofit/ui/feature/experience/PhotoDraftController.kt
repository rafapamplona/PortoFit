package com.example.portofit.ui.feature.experience

import androidx.lifecycle.SavedStateHandle
import com.example.portofit.data.photo.PhotoDrafts
import com.example.portofit.data.photo.PhotoError
import com.example.portofit.data.photo.PhotoSizing
import com.example.portofit.data.photo.PrepareResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Fotografia escolhida para o rascunho (antes de ser enviada). */
sealed interface PhotoDraftState {
    data object None : PhotoDraftState

    /** A preparar a nova; [previous] é a que continua escolhida se a nova falhar. */
    data class Preparing(val previous: String?) : PhotoDraftState

    /** JPEG preparado na pasta privada da app, referido pelo nome. */
    data class Ready(val name: String) : PhotoDraftState

    /** A referência restaurada já não tem ficheiro (ex.: a cache foi limpa): é preciso escolher de novo. */
    data object Missing : PhotoDraftState
}

/**
 * Escolha, captura e preparação de uma fotografia, para o ViewModel de publicação e o de edição.
 *
 * - O [savedState] guarda só nomes de ficheiros gerados pela app (o preparado e o de captura em curso),
 *   nunca bitmaps, ficheiros nem URIs de terceiros: a imagem escolhida no Photo Picker é logo copiada
 *   (preparada) para a pasta da app, por isso a autorização temporária dessa URI deixa de ser precisa.
 * - Uma captura em curso sobrevive à recriação do processo enquanto a aplicação de câmara está aberta.
 * - Uma falha ou um cancelamento nunca apaga a fotografia já escolhida, nem o texto e as estrelas.
 * - Só apaga ficheiros da app ([PhotoDrafts]); nunca fotografias da galeria.
 */
class PhotoDraftController(
    private val scope: CoroutineScope,
    private val drafts: PhotoDrafts,
    private val savedState: SavedStateHandle,
    private val keyPrefix: String,
) {
    private val photoKey = "$keyPrefix.photo"
    private val captureKey = "$keyPrefix.capture"

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<PhotoDraftState> = _state.asStateFlow()

    private val _error = MutableStateFlow<PhotoError?>(if (_state.value == PhotoDraftState.Missing) PhotoError.MISSING else null)
    val error: StateFlow<PhotoError?> = _error.asStateFlow()

    private var job: Job? = null

    /** Nome do JPEG pronto a enviar, ou null. */
    val readyName: String? get() = (_state.value as? PhotoDraftState.Ready)?.name

    fun readyFile(): File? = readyName?.let { drafts.file(it) }?.takeIf { it.isFile }

    /** Ficheiro para a pré-visualização (sem tocar no disco: o Coil trata de um ficheiro em falta). */
    fun previewFile(state: PhotoDraftState): File? = (state as? PhotoDraftState.Ready)?.let { drafts.file(it.name) }

    val busy: Boolean get() = _state.value is PhotoDraftState.Preparing

    /** Prepara o destino da câmara; devolve a URI segura a passar à aplicação de câmara, ou null (erro). */
    fun startCapture(): String? {
        if (busy) return null
        val target = drafts.newCaptureTarget() ?: run {
            _error.value = PhotoError.STORAGE
            return null
        }
        savedState.get<String>(captureKey)?.let { drafts.delete(it) }
        savedState[captureKey] = target.name
        _error.value = null
        return target.uri
    }

    fun onCaptureResult(taken: Boolean) {
        val capture = savedState.get<String>(captureKey) ?: return
        savedState.remove<String>(captureKey)
        if (!taken) {
            drafts.delete(capture)
            _error.value = PhotoError.CAPTURE_CANCELLED
            return
        }
        prepare { drafts.prepareFromCapture(capture) }
    }

    /** A aplicação de câmara não existe ou não pôde ser aberta. */
    fun onCaptureUnavailable() {
        savedState.get<String>(captureKey)?.let { drafts.delete(it) }
        savedState.remove<String>(captureKey)
        _error.value = PhotoError.CAMERA_UNAVAILABLE
    }

    /** Resultado do Photo Picker; null = cancelado (nada muda). */
    fun onPicked(uri: String?) {
        if (uri == null || busy) return
        prepare { drafts.prepareFromUri(uri) }
    }

    fun remove() {
        if (busy) return
        readyName?.let { drafts.delete(it) }
        savedState.remove<String>(photoKey)
        _state.value = PhotoDraftState.None
        _error.value = null
    }

    fun dismissError() {
        _error.value = null
    }

    /** O ficheiro preparado desapareceu (ex.: antes de enviar): passa a [PhotoDraftState.Missing]. */
    fun markMissingIfGone(): Boolean {
        val name = readyName ?: return false
        if (drafts.exists(name)) return false
        savedState.remove<String>(photoKey)
        _state.value = PhotoDraftState.Missing
        _error.value = PhotoError.MISSING
        return true
    }

    /** Apaga todos os ficheiros da app deste rascunho (ao publicar ou ao sair do ecrã). */
    fun clearFiles() {
        job?.cancel()
        readyName?.let { drafts.delete(it) }
        savedState.get<String>(captureKey)?.let { drafts.delete(it) }
        savedState.remove<String>(photoKey)
        savedState.remove<String>(captureKey)
        _state.value = PhotoDraftState.None
    }

    private fun prepare(block: suspend () -> PrepareResult) {
        val previous = readyName
        _state.value = PhotoDraftState.Preparing(previous)
        _error.value = null
        job = scope.launch {
            when (val result = block()) {
                is PrepareResult.Success -> {
                    previous?.let { drafts.delete(it) }
                    savedState[photoKey] = result.photo.name
                    _state.value = PhotoDraftState.Ready(result.photo.name)
                }
                is PrepareResult.Failure -> {
                    // A fotografia anterior (se havia) continua escolhida.
                    _state.value = previous?.let { PhotoDraftState.Ready(it) } ?: PhotoDraftState.None
                    _error.value = result.error
                }
            }
        }
    }

    private fun restore(): PhotoDraftState {
        val name = savedState.get<String>(photoKey) ?: return PhotoDraftState.None
        if (!PhotoSizing.isDraftName(name)) {
            savedState.remove<String>(photoKey)
            return PhotoDraftState.None
        }
        return if (drafts.exists(name)) PhotoDraftState.Ready(name) else PhotoDraftState.Missing.also { savedState.remove<String>(photoKey) }
    }
}
