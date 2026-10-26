package com.example.portofit.testing

import com.example.portofit.data.location.LocationProvider
import com.example.portofit.data.location.LocationResult
import com.example.portofit.data.model.NearbyFilter
import com.example.portofit.data.model.OfferingSummary
import com.example.portofit.data.model.Page
import com.example.portofit.data.model.PlaceDetail
import com.example.portofit.data.model.PlaceSummary
import com.example.portofit.data.model.Rating
import com.example.portofit.data.model.Sport
import com.example.portofit.data.repository.CatalogRepository
import com.example.portofit.data.repository.DataError
import com.example.portofit.data.repository.DataResult

/** Pedido de pesquisa recebido pelo repositório falso. */
data class SearchCall(val query: String?, val sportId: String?, val page: Int, val pageSize: Int, val near: NearbyFilter? = null)

/**
 * Repositório controlado pelos testes: cada resposta é decidida por uma função, que pode
 * suspender (ex.: CompletableDeferred) para simular respostas lentas ou fora de ordem.
 */
class FakeCatalogRepository : CatalogRepository {

    val searchCalls = mutableListOf<SearchCall>()
    var sportsCalls = 0

    var sportsResult: suspend () -> DataResult<List<Sport>> = { DataResult.Success(listOf(BOXE, NATACAO)) }
    var searchResult: suspend (SearchCall) -> DataResult<Page<PlaceSummary>> = { call -> DataResult.Success(pageOf(call.page, 1)) }
    var placeResult: suspend (String) -> DataResult<PlaceDetail> = { DataResult.Failure(DataError.NOT_FOUND) }

    override suspend fun getSports(): DataResult<List<Sport>> {
        sportsCalls++
        return sportsResult()
    }

    override suspend fun searchPlaces(query: String?, sportId: String?, page: Int, pageSize: Int, near: NearbyFilter?): DataResult<Page<PlaceSummary>> {
        val call = SearchCall(query, sportId, page, pageSize, near)
        searchCalls += call
        return searchResult(call)
    }

    override suspend fun getPlace(placeId: String): DataResult<PlaceDetail> = placeResult(placeId)

    var offeringResult: suspend (String) -> DataResult<OfferingSummary> = { DataResult.Failure(DataError.NETWORK) }

    override suspend fun getOffering(offeringId: String): DataResult<OfferingSummary> = offeringResult(offeringId)

    companion object {
        val BOXE = Sport("boxe", "Boxe")
        val NATACAO = Sport("natacao", "Natação")

        fun place(id: String) = PlaceSummary(
            id = id,
            name = "Local $id",
            parish = "Bonfim",
            city = "Porto",
            sports = listOf(BOXE),
            rating = Rating.Unavailable,
            isDemo = true,
        )

        /** Página [page] de [totalPages], com locais "p<página>-<n>". */
        fun pageOf(page: Int, totalPages: Int, perPage: Int = 2) = Page(
            items = (1..perPage).map { place("p$page-$it") },
            page = page,
            totalItems = (totalPages * perPage).toLong(),
            totalPages = totalPages,
        )
    }
}

/** Localização controlada pelos testes: cada pedido devolve [result] (que pode suspender) e fica contado. */
class FakeLocationProvider(var result: suspend () -> LocationResult = { LocationResult.Timeout }) : LocationProvider {
    var calls = 0

    override suspend fun current(): LocationResult {
        calls++
        return result()
    }
}
