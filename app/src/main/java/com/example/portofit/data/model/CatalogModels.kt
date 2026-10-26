package com.example.portofit.data.model

// Modelos usados pela interface, independentes do formato JSON do backend.

data class Sport(
    val id: String,
    val name: String,
)

/** Avaliação agregada de um local ou atividade (ver docs/catalog-api.md §2.4). */
sealed interface Rating {
    data class Available(val average: Double, val peopleCount: Int) : Rating

    /** Ainda ninguém avaliou. */
    data object NoRatings : Rating

    /** Não foi possível obter a avaliação agora. Nunca é mostrada como zero estrelas. */
    data object Unavailable : Rating
}

enum class ActivityLevel { BEGINNER, INTERMEDIATE, ADVANCED, ALL_LEVELS, UNKNOWN }

/**
 * Estado no catálogo (docs/catalog-api.md §2.2–2.3). Dados retirados continuam legíveis pelo id, mas não
 * aceitam publicações. Um valor desconhecido é tratado como RETIRED (não se oferece publicar).
 */
enum class CatalogStatus { ACTIVE, RETIRED }

data class PlaceSummary(
    val id: String,
    val name: String,
    val parish: String?,
    val city: String,
    val sports: List<Sport>,
    val rating: Rating,
    val isDemo: Boolean,
    /** Distância em linha reta (km, 2 casas decimais), só numa pesquisa por proximidade. */
    val distanceKm: Double? = null,
)

/**
 * Pesquisa por proximidade (docs/catalog-api.md §3.2): coordenadas do utilizador e raio em km (0,1 a 50). Só existe em
 * memória, enquanto a pesquisa está ativa: nunca é guardada nem registada.
 */
data class NearbyFilter(val latitude: Double, val longitude: Double, val radiusKm: Int)

data class Address(
    val street: String,
    val postalCode: String,
    val parish: String?,
    val city: String,
)

data class Offering(
    val id: String,
    val name: String,
    val sport: Sport,
    val description: String?,
    val level: ActivityLevel,
    val scheduleNotes: String?,
    val rating: Rating,
    /** Estado efetivo (RETIRED se a atividade ou o local estiverem retirados). */
    val status: CatalogStatus = CatalogStatus.ACTIVE,
) {
    val acceptsExperiences: Boolean get() = status == CatalogStatus.ACTIVE
}

data class PlaceDetail(
    val id: String,
    val name: String,
    val description: String?,
    val address: Address,
    val phone: String?,
    val website: String?,
    val rating: Rating,
    /** Modalidades das atividades oferecidas, sem repetições, pela ordem das atividades. */
    val sports: List<Sport>,
    val offerings: List<Offering>,
    val isDemo: Boolean,
    val status: CatalogStatus = CatalogStatus.ACTIVE,
) {
    val isRetired: Boolean get() = status == CatalogStatus.RETIRED
}

/** Atividade vista isoladamente (GET /offerings/{id}), com o estado efetivo. */
data class OfferingSummary(
    val id: String,
    val name: String,
    val status: CatalogStatus,
    val placeId: String,
    val placeName: String,
)

/** Uma página de resultados; [page] começa em 0. */
data class Page<T>(
    val items: List<T>,
    val page: Int,
    val totalItems: Long,
    val totalPages: Int,
) {
    val hasMore: Boolean get() = page + 1 < totalPages
}
