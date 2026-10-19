package com.example.portofit.ui.feature.experience

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.SubcomposeAsyncImage
import com.example.portofit.R
import com.example.portofit.data.model.ExperiencePhoto
import com.example.portofit.data.photo.PhotoError
import com.example.portofit.data.repository.UploadError
import com.example.portofit.ui.components.LocalPhotoUrls
import java.io.File

/**
 * Fotografia de uma experiência publicada, pelo URL do serviço configurado. Enquanto carrega mostra um espaço
 * reservado; se falhar mostra só um aviso nesse espaço — o texto da experiência nunca fica escondido.
 */
@Composable
fun ExperiencePhotoView(photo: ExperiencePhoto, modifier: Modifier = Modifier, crop: Boolean = false) {
    val url = LocalPhotoUrls.current.resolve(photo.path)
    val ratio = if (photo.width > 0 && photo.height > 0) photo.width.toFloat() / photo.height else 4f / 3
    val shape = MaterialTheme.shapes.medium
    val frame = modifier.fillMaxWidth().clip(shape).let { if (crop) it.aspectRatio(16f / 9) else it.aspectRatio(ratio.coerceIn(0.5f, 2.5f)) }
    if (url == null) {
        PhotoUnavailable(frame)
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = stringResource(R.string.photo_description),
        modifier = frame,
        contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
        loading = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) },
        error = { PhotoUnavailable(Modifier.fillMaxSize()) },
    )
}

@Composable
private fun PhotoUnavailable(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.photo_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp),
        )
    }
}

/** Ações da secção de fotografia (o ViewModel decide; a secção só lança o Photo Picker e a câmara). */
class PhotoActions(
    val onPicked: (String?) -> Unit,
    /** Prepara o ficheiro da câmara e devolve a URI segura, ou null. */
    val onStartCapture: () -> String?,
    val onCaptureResult: (Boolean) -> Unit,
    val onCaptureUnavailable: () -> Unit,
    val onRemove: () -> Unit,
    val onDismissError: () -> Unit,
)

/**
 * Escolher (Photo Picker, sem permissão de armazenamento) ou tirar (aplicação de câmara do sistema, com uma URI
 * do FileProvider: fotografia em resolução completa, não a miniatura) uma fotografia, com pré-visualização e
 * "Remover". Nos ecrãs de publicação e de edição.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoDraftSection(
    photo: PhotoDraftState,
    error: PhotoError?,
    previewFile: File?,
    enabled: Boolean,
    actions: PhotoActions,
    /** Na edição: a fotografia atual (mostrada se não houver uma nova). */
    current: ExperiencePhoto? = null,
    removingCurrent: Boolean = false,
    onKeepCurrent: (() -> Unit)? = null,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> actions.onPicked(uri?.toString()) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken -> actions.onCaptureResult(taken) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.photo_section), style = MaterialTheme.typography.titleSmall)
        when {
            photo is PhotoDraftState.Preparing -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.photo_preparing), style = MaterialTheme.typography.bodySmall)
                }
            }
            photo is PhotoDraftState.Ready && previewFile != null -> coil3.compose.AsyncImage(
                model = previewFile,
                contentDescription = stringResource(R.string.photo_preview_description),
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Fit,
            )
            current != null && !removingCurrent && photo !is PhotoDraftState.Missing -> ExperiencePhotoView(current)
            removingCurrent && current != null -> Text(stringResource(R.string.photo_will_be_removed), style = MaterialTheme.typography.bodyMedium)
            else -> Text(stringResource(R.string.photo_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let {
            Text(stringResource(photoErrorMessage(it)), color = if (it == PhotoError.CAPTURE_CANCELLED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    actions.onDismissError()
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                enabled = enabled && photo !is PhotoDraftState.Preparing,
            ) { Text(stringResource(R.string.photo_pick)) }
            OutlinedButton(
                onClick = {
                    val target = actions.onStartCapture() ?: return@OutlinedButton
                    try {
                        camera.launch(target.toUri())
                    } catch (e: ActivityNotFoundException) {
                        actions.onCaptureUnavailable()
                    }
                },
                enabled = enabled && photo !is PhotoDraftState.Preparing,
            ) { Text(stringResource(R.string.photo_take)) }
            val hasNew = photo is PhotoDraftState.Ready || photo is PhotoDraftState.Missing
            if (hasNew || (current != null && !removingCurrent)) {
                TextButton(onClick = actions.onRemove, enabled = enabled) {
                    Text(stringResource(if (hasNew && current != null) R.string.photo_discard_new else R.string.photo_remove))
                }
            }
            if (removingCurrent && current != null && onKeepCurrent != null) {
                TextButton(onClick = onKeepCurrent, enabled = enabled) { Text(stringResource(R.string.photo_keep_current)) }
            }
        }
    }
}

@StringRes
fun photoErrorMessage(error: PhotoError): Int = when (error) {
    PhotoError.CAPTURE_CANCELLED -> R.string.photo_error_cancelled
    PhotoError.CAMERA_UNAVAILABLE -> R.string.photo_error_camera
    PhotoError.UNREADABLE -> R.string.photo_error_unreadable
    PhotoError.TOO_SMALL -> R.string.photo_error_too_small
    PhotoError.STORAGE -> R.string.photo_error_storage
    PhotoError.MISSING -> R.string.photo_error_missing
}

@StringRes
fun uploadErrorMessage(error: UploadError): Int = when (error) {
    UploadError.NETWORK -> R.string.upload_error_network
    UploadError.SERVER -> R.string.upload_error_server
    UploadError.TOO_LARGE -> R.string.upload_error_too_large
    UploadError.INVALID_IMAGE -> R.string.upload_error_invalid
    UploadError.TOO_MANY_PENDING -> R.string.upload_error_too_many
    UploadError.SESSION_ENDED -> R.string.publish_error_session_ended
    UploadError.SESSION_UNVERIFIED -> R.string.profile_error_session_unverified
    UploadError.NOT_CONFIGURED -> R.string.experiences_not_configured
    UploadError.UNEXPECTED -> R.string.error_unexpected
}
