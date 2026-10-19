package com.example.portofit.data.repository

import com.example.portofit.data.model.OfferingSummary
import com.example.portofit.data.model.Page
import com.example.portofit.data.model.PlaceDetail
import com.example.portofit.data.model.PlaceSummary
import com.example.portofit.data.model.Sport
import com.example.portofit.data.remote.CatalogApi
import com.example.portofit.data.remote.toModel

/** Acesso ao catálogo (modalidades e locais). */
interface CatalogRepository {
    suspend fun getSports(): DataResult<List<Sport>>

    suspend fun searchPlaces(query: String?, sportId: String?, page: Int, pageSize: Int): DataResult<Page<PlaceSummary>>

    suspend fun getPlace(placeId: String): DataResult<PlaceDetail>

    suspend fun getOffering(offeringId: String): DataResult<OfferingSummary>
}

/** Implementação sobre a API REST do catalog-service. Não tem dados locais de reserva. */
class NetworkCatalogRepository(private val api: CatalogApi) : CatalogRepository {

    override suspend fun getSports() = call { api.getSports().items.map { it.toModel() } }

    override suspend fun searchPlaces(query: String?, sportId: String?, page: Int, pageSize: Int) =
        call { api.searchPlaces(query, sportId, page, pageSize).toModel { it.toModel() } }

    override suspend fun getPlace(placeId: String) = call { api.getPlace(placeId).toModel() }

    override suspend fun getOffering(offeringId: String) = call { api.getOffering(offeringId).toModel() }

    private suspend fun <T> call(block: suspend () -> T): DataResult<T> = apiCall(block)
}

/** Usado quando a app foi compilada sem URL do serviço (ex.: release nesta fase). */
class UnconfiguredCatalogRepository : CatalogRepository {
    private val failure = DataResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun getSports() = failure
    override suspend fun searchPlaces(query: String?, sportId: String?, page: Int, pageSize: Int) = failure
    override suspend fun getPlace(placeId: String) = failure
    override suspend fun getOffering(offeringId: String) = failure
}
