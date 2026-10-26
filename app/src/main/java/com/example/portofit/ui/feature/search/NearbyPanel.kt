package com.example.portofit.ui.feature.search

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.portofit.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Proximidade na pesquisa de locais (docs/catalog-api.md §3.2): "Usar a minha localização", raio, estado e falhas.
 * A permissão só é pedida depois do toque (a aproximada basta). Uma recusa ou uma falha nunca impede a pesquisa por
 * texto e modalidade, que continua por cima.
 */
@Composable
fun NearbyPanel(
    nearby: NearbyState,
    radiusKm: Int,
    onUseLocation: () -> Unit,
    onPermissionDenied: (blocked: Boolean) -> Unit,
    onRadiusSelected: (Int) -> Unit,
    onDisable: () -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) {
            onUseLocation()
        } else {
            // Sem justificação a mostrar depois de uma recusa: o Android já não volta a perguntar.
            val activity = context.findActivity()
            onPermissionDenied(activity != null && LOCATION_PERMISSIONS.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) })
        }
    }
    val useLocation = {
        if (LOCATION_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) onUseLocation()
        else launcher.launch(LOCATION_PERMISSIONS)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (nearby) {
            NearbyState.Off -> {
                OutlinedButton(onClick = useLocation, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.nearby_use_location)) }
                Note(stringResource(R.string.nearby_privacy))
            }
            NearbyState.Locating -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.nearby_locating), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDisable) { Text(stringResource(R.string.nearby_cancel)) }
            }
            is NearbyState.Active -> {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.nearby_active, radiusKm),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        if (nearby.approximate) Note(stringResource(R.string.nearby_approximate), MaterialTheme.colorScheme.onSecondaryContainer)
                        nearby.ageMinutes?.let { Note(stringResource(R.string.nearby_old_fix, it), MaterialTheme.colorScheme.onSecondaryContainer) }
                    }
                }
                RadiusChips(radiusKm, onRadiusSelected)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = useLocation) { Text(stringResource(R.string.nearby_refresh)) }
                    TextButton(onClick = onDisable) { Text(stringResource(R.string.nearby_disable)) }
                }
            }
            is NearbyState.Failed -> {
                Text(
                    stringResource(failureMessage(nearby.reason)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (nearby.reason) {
                        LocationFailure.PERMISSION_BLOCKED -> OutlinedButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                            },
                        ) { Text(stringResource(R.string.nearby_open_app_settings)) }
                        LocationFailure.DISABLED -> OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                            Text(stringResource(R.string.nearby_open_location_settings))
                        }
                        else -> OutlinedButton(onClick = useLocation) { Text(stringResource(R.string.retry)) }
                    }
                    TextButton(onClick = onDisable) { Text(stringResource(R.string.nearby_dismiss)) }
                }
            }
        }
    }
}

@Composable
private fun RadiusChips(radiusKm: Int, onRadiusSelected: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SearchViewModel.RADIUS_OPTIONS) { option ->
            FilterChip(
                selected = option == radiusKm,
                onClick = { onRadiusSelected(option) },
                label = { Text(stringResource(R.string.nearby_radius_option, option)) },
            )
        }
    }
}

@Composable
private fun Note(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@StringRes
private fun failureMessage(reason: LocationFailure): Int = when (reason) {
    LocationFailure.PERMISSION_DENIED -> R.string.nearby_error_denied
    LocationFailure.PERMISSION_BLOCKED -> R.string.nearby_error_blocked
    LocationFailure.DISABLED -> R.string.nearby_error_disabled
    LocationFailure.TIMEOUT -> R.string.nearby_error_timeout
    LocationFailure.TOO_OLD -> R.string.nearby_error_too_old
    LocationFailure.UNAVAILABLE -> R.string.nearby_error_unavailable
}

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Distância em linha reta como o utilizador a lê: abaixo de 1 km em metros (às dezenas), até 10 km com uma casa
 * decimal, acima disso em km inteiros. Português de Portugal ("1,2 km").
 */
fun formatDistance(km: Double): String {
    val pt = Locale.forLanguageTag("pt-PT")
    val meters = (km * 100).roundToInt() * 10
    return when {
        meters < 1000 -> "$meters m"
        km < 10 -> String.format(pt, "%.1f km", km)
        else -> "${km.roundToInt()} km"
    }
}
