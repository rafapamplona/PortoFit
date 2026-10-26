package com.example.portofit.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Uma posição do aparelho. [ageMillis]: há quanto tempo foi obtida (0 para uma posição atual); [approximate]: só com
 * a permissão de localização aproximada (erro típico de 1 a 3 km).
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val ageMillis: Long,
    val approximate: Boolean,
)

sealed interface LocationResult {
    data class Found(val fix: LocationFix) : LocationResult

    /** Sem permissão de localização (nem aproximada). */
    data object PermissionMissing : LocationResult

    /** A localização do aparelho está desligada. */
    data object Disabled : LocationResult

    /** Nenhuma posição dentro do prazo, nem uma anterior. */
    data object Timeout : LocationResult

    data object Unavailable : LocationResult
}

/** Uma posição de cada vez, quando pedida: nunca em segundo plano nem em acompanhamento contínuo. */
interface LocationProvider {
    suspend fun current(): LocationResult
}

/**
 * Sobre o LocationManager do Android (sem serviços externos): um pedido pontual a cada fornecedor permitido, todos ao
 * mesmo tempo e dentro de [timeoutMillis]; a primeira posição ganha e os outros pedidos são cancelados. (Um a um, um
 * fornecedor que nunca responde — ex.: o "fused" sem rede, que não liga o GPS — gastava o prazo todo.) Sem resposta, a
 * posição anterior mais recente dos mesmos fornecedores (a idade vai no resultado, para quem a usa a validar). Nunca
 * regista coordenadas.
 */
class AndroidLocationProvider(context: Context, private val timeoutMillis: Long = TIMEOUT_MILLIS) : LocationProvider {

    private val context = context.applicationContext
    private val manager: LocationManager? = this.context.getSystemService(LocationManager::class.java)

    override suspend fun current(): LocationResult {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!fine && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return LocationResult.PermissionMissing
        val manager = manager ?: return LocationResult.Unavailable
        if (!LocationManagerCompat.isLocationEnabled(manager)) return LocationResult.Disabled
        val providers = candidates(fine).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return LocationResult.Disabled
        return try {
            val fresh = withTimeoutOrNull(timeoutMillis) { firstFix(manager, providers) }
            val location = fresh ?: lastKnown(manager, providers) ?: return LocationResult.Timeout
            LocationResult.Found(
                LocationFix(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    ageMillis = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0),
                    approximate = !fine,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            // Permissão retirada entretanto.
            LocationResult.PermissionMissing
        } catch (e: IllegalArgumentException) {
            LocationResult.Unavailable
        }
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Com só a aproximada, o GPS não pode ser usado; o fornecedor "fused" (Android 12+) devolve-a já aproximada. */
    private fun candidates(fine: Boolean) = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        if (fine) add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }

    /** A primeira posição de qualquer dos fornecedores; null se todos responderem sem posição. */
    private suspend fun firstFix(manager: LocationManager, providers: List<String>): Location? = coroutineScope {
        val first = CompletableDeferred<Location?>()
        val requests = providers.map { provider -> launch { request(manager, provider)?.let { first.complete(it) } } }
        launch {
            requests.joinAll()
            first.complete(null)
        }
        first.await().also { coroutineContext.cancelChildren() }
    }

    // A permissão é verificada em current(), antes de qualquer pedido.
    @SuppressLint("MissingPermission")
    private suspend fun request(manager: LocationManager, provider: String): Location? = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) { location ->
            if (continuation.isActive) continuation.resume(location)
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager, providers: List<String>): Location? =
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.elapsedRealtimeNanos }

    private companion object {
        /** Prazo total para uma posição atual. */
        const val TIMEOUT_MILLIS = 15_000L
    }
}
