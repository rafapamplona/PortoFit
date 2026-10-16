package com.example.portofit.data.repository

import com.example.portofit.data.model.Page
import com.example.portofit.data.model.PlaceDetail
import com.example.portofit.data.model.PlaceSummary
import com.example.portofit.data.model.Sport
import com.example.portofit.data.remote.CatalogApi
import com.example.portofit.data.remote.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/** Acesso ao catálogo (modalidades e locais). */
interface CatalogRepository {
    suspend fun getSports(): DataResult<List<Sport>>

    suspend fun searchPlaces(query: String?, sportId: String?, page: Int, pageSize: Int): DataResult<Page<PlaceSummary>>

    suspend fun getPlace(placeId: String): DataResult<PlaceDetail>
}

/** Implementação sobre a API REST do catalog-service. Não tem dados locais de reserva. */
class NetworkCatalogRepository(private val api: CatalogApi) : CatalogRepository {

    override suspend fun getSports() = call { api.getSports().items.map { it.toModel() } }

    override suspend fun searchPlaces(query: String?, sportId: String?, page: Int, pageSize: Int) =
        call { api.searchPlaces(query, sportId, page, pageSize).toModel { it.toModel() } }

    override suspend fun getPlace(placeId: String) = call { api.getPlace(placeId).toModel() }

    private suspend fun <T> call(block: suspend () -> T): DataResult<T> =
        try {
            DataResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            DataResult.Failure(
                when (e.code()) {
                    404 -> DataError.NOT_FOUND
                    400 -> DataError.INVALID_REQUEST
                    in 500..599 -> DataError.SERVER
                    else -> DataError.UNEXPECTED
                },
            )
        } catch (e: IOException) {
            DataResult.Failure(DataError.NETWORK)
        } catch (e: SerializationException) {
            DataResult.Failure(DataError.UNEXPECTED)
        } catch (e: IllegalArgumentException) {
            // Também lançada pelo kotlinx.serialization quando o JSON não corresponde ao formato.
            DataResult.Failure(DataError.UNEXPECTED)
        }
}

/** Usado quando a app foi compilada sem URL do serviço (ex.: release nesta fase). */
class UnconfiguredCatalogRepository : CatalogRepository {
    private val failure = DataResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun getSports() = failure
    override suspend fun searchPlaces(query: String?, sportId: String?, page: Int, pageSize: Int) = failure
    override suspend fun getPlace(placeId: String) = failure
}
