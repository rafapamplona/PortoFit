package com.example.portofit.data.repository

import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.Page
import com.example.portofit.data.model.SavedAvailability
import com.example.portofit.data.model.SavedItem
import com.example.portofit.data.model.SavedItemsPage
import com.example.portofit.data.model.UserCollection
import com.example.portofit.data.remote.CollectionDto
import com.example.portofit.data.remote.CollectionItemsDto
import com.example.portofit.data.remote.CollectionNameDto
import com.example.portofit.data.remote.CollectionsApi
import com.example.portofit.data.remote.ExperienceDto
import com.example.portofit.data.remote.ProblemDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Falha de uma escrita nas listas. [UNCONFIRMED]: sem resposta ou erro do servidor — não se sabe se mudou; renomear,
 * eliminar, guardar e remover são idempotentes, por isso "Tentar novamente" é seguro (nunca automático).
 */
enum class CollectionError {
    UNCONFIRMED,
    /** A lista não existe (ou já foi eliminada). */
    COLLECTION_GONE,
    NAME_TAKEN,
    VALIDATION,
    COLLECTION_LIMIT,
    ITEM_LIMIT,
    /** Guardar: a experiência foi eliminada ou não existe (nada guardado). */
    EXPERIENCE_GONE,
    /** Guardar: o serviço das experiências não confirmou a experiência (nada guardado; definitivo para este pedido). */
    EXPERIENCES_UNAVAILABLE,
    SESSION_ENDED,
    SESSION_UNVERIFIED,
    NOT_CONFIGURED,
    UNEXPECTED,
}

sealed interface CollectionResult<out T> {
    data class Success<T>(val value: T) : CollectionResult<T>
    data class Failure(val error: CollectionError) : CollectionResult<Nothing>
}

/** Resultado de criar uma lista (mesma política da publicação: chave por tentativa lógica, §5.2). */
sealed interface CreateCollectionOutcome {
    data class Created(val collection: UserCollection, val replayed: Boolean) : CreateCollectionOutcome

    /** Resultado desconhecido: pode ter sido criada. Só é seguro repetir com a mesma chave e o mesmo nome. */
    data object Unconfirmed : CreateCollectionOutcome

    data class Rejected(val error: CollectionError) : CreateCollectionOutcome

    /** 409 DIFFERENT_CONTENT: esta chave já tinha criado uma lista com outro nome. */
    data class AlreadyCreated(val collectionId: String?) : CreateCollectionOutcome

    /** 409 COLLECTION_DELETED: foi criada e entretanto eliminada. */
    data object CreatedThenDeleted : CreateCollectionOutcome
}

interface CollectionsRepository {
    suspend fun list(page: Int, pageSize: Int): SessionResult<Page<UserCollection>>
    suspend fun get(collectionId: String): SessionResult<UserCollection>
    suspend fun create(idempotencyKey: String, name: String): CreateCollectionOutcome
    suspend fun rename(collectionId: String, name: String): CollectionResult<UserCollection>
    suspend fun delete(collectionId: String): CollectionResult<Unit>
    suspend fun items(collectionId: String, page: Int, pageSize: Int): SessionResult<SavedItemsPage>
    suspend fun add(collectionId: String, experienceId: String): CollectionResult<Unit>
    suspend fun remove(collectionId: String, experienceId: String): CollectionResult<Unit>
}

/** Todas as chamadas com o access token do [SessionManager]; um 401 renova uma vez e repete (na criação, com a mesma chave). */
class NetworkCollectionsRepository(
    private val api: CollectionsApi,
    private val session: SessionManager,
    private val json: Json,
) : CollectionsRepository {

    override suspend fun list(page: Int, pageSize: Int) =
        read({ api.list(it, page, pageSize) }) { dto -> Page(dto.items.map { it.toModel() }, dto.page, dto.totalItems, dto.totalPages) }

    override suspend fun get(collectionId: String) = read({ api.get(it, collectionId) }) { it.toModel() }

    override suspend fun items(collectionId: String, page: Int, pageSize: Int) = read({ api.items(it, collectionId, page, pageSize) }) { it.toModel() }

    override suspend fun create(idempotencyKey: String, name: String): CreateCollectionOutcome {
        val response = try {
            authorized { api.create(it, idempotencyKey, CollectionNameDto(name)) } ?: return CreateCollectionOutcome.Rejected(sessionError())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return CreateCollectionOutcome.Unconfirmed
        }
        if (response.isSuccessful) {
            val collection = response.body()?.let { runCatching { it.toModel() }.getOrNull() } ?: return CreateCollectionOutcome.Unconfirmed
            return CreateCollectionOutcome.Created(collection, replayed = response.code() == 200 || response.headers()["Idempotent-Replayed"] == "true")
        }
        val problem = problem(response)
        return when {
            response.code() == 409 && problem?.reason == "COLLECTION_DELETED" -> CreateCollectionOutcome.CreatedThenDeleted
            response.code() == 409 && problem?.code == "IDEMPOTENCY_CONFLICT" -> CreateCollectionOutcome.AlreadyCreated(problem.collectionId)
            response.code() in 500..599 -> CreateCollectionOutcome.Unconfirmed
            else -> CreateCollectionOutcome.Rejected(errorOf(response.code(), problem?.code))
        }
    }

    override suspend fun rename(collectionId: String, name: String) =
        write({ api.rename(it, collectionId, CollectionNameDto(name)) }) { it.body()!!.toModel() }

    override suspend fun delete(collectionId: String) = write({ api.delete(it, collectionId) }) { }

    override suspend fun add(collectionId: String, experienceId: String) = write({ api.addItem(it, collectionId, experienceId) }) { }

    override suspend fun remove(collectionId: String, experienceId: String) = write({ api.removeItem(it, collectionId, experienceId) }) { }

    private suspend fun <D, T> read(call: suspend (String) -> Response<D>, convert: (D) -> T): SessionResult<T> =
        try {
            val response = authorized(call) ?: return if (session.state.value is SessionState.SignedIn) SessionResult.SessionUnverified else SessionResult.SessionEnded
            when {
                response.isSuccessful -> SessionResult.Success(convert(response.body()!!))
                response.code() == 401 -> SessionResult.SessionEnded
                else -> SessionResult.Failure(
                    when (response.code()) {
                        404 -> DataError.NOT_FOUND
                        400 -> DataError.INVALID_REQUEST
                        in 500..599 -> DataError.SERVER
                        else -> DataError.UNEXPECTED
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SessionResult.Failure(DataError.NETWORK)
        } catch (e: SerializationException) {
            SessionResult.Failure(DataError.UNEXPECTED)
        } catch (e: IllegalArgumentException) {
            SessionResult.Failure(DataError.UNEXPECTED)
        }

    private suspend fun <R, T> write(call: suspend (String) -> Response<R>, onSuccess: (Response<R>) -> T): CollectionResult<T> =
        try {
            val response = authorized(call) ?: return CollectionResult.Failure(sessionError())
            if (response.isSuccessful) {
                CollectionResult.Success(onSuccess(response))
            } else {
                val problem = problem(response)
                // 503 EXPERIENCES_UNAVAILABLE é definitivo (nada guardado); outros 5xx: resultado desconhecido.
                if (response.code() in 500..599 && problem?.code != "EXPERIENCES_UNAVAILABLE") {
                    CollectionResult.Failure(CollectionError.UNCONFIRMED)
                } else {
                    CollectionResult.Failure(errorOf(response.code(), problem?.code))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            CollectionResult.Failure(CollectionError.UNCONFIRMED)
        } catch (e: RuntimeException) {
            CollectionResult.Failure(CollectionError.UNEXPECTED)
        }

    private fun errorOf(status: Int, code: String?): CollectionError = when {
        code == "NAME_TAKEN" -> CollectionError.NAME_TAKEN
        code == "COLLECTION_LIMIT_REACHED" -> CollectionError.COLLECTION_LIMIT
        code == "ITEM_LIMIT_REACHED" -> CollectionError.ITEM_LIMIT
        code == "EXPERIENCE_NOT_FOUND" -> CollectionError.EXPERIENCE_GONE
        code == "EXPERIENCES_UNAVAILABLE" -> CollectionError.EXPERIENCES_UNAVAILABLE
        status == 401 -> CollectionError.SESSION_ENDED
        status == 404 -> CollectionError.COLLECTION_GONE
        status == 400 || status == 413 -> CollectionError.VALIDATION
        else -> CollectionError.UNEXPECTED
    }

    /** null quando não há token (sem sessão ou sessão por confirmar): o pedido não chega a ser enviado. */
    private suspend fun <R> authorized(call: suspend (String) -> Response<R>): Response<R>? {
        val token = session.accessToken() ?: return null
        val response = call("Bearer $token")
        if (response.code() != 401) return response
        val fresh = session.accessTokenAfterUnauthorized(token) ?: return null
        return call("Bearer $fresh")
    }

    private fun sessionError() =
        if (session.state.value is SessionState.SignedOut) CollectionError.SESSION_ENDED else CollectionError.SESSION_UNVERIFIED

    private fun problem(response: Response<*>): ProblemDto? =
        runCatching { response.errorBody()?.string()?.let { json.decodeFromString<ProblemDto>(it) } }.getOrNull()

    /** Itens: só AVAILABLE traz conteúdo; um conteúdo ilegível conta como UNKNOWN (nunca como eliminado). */
    private fun CollectionItemsDto.toModel(): SavedItemsPage {
        val available = experiencesStatus != "UNAVAILABLE"
        val items = items.map { item ->
            val availability = SavedAvailability.entries.firstOrNull { it.name == item.availability } ?: SavedAvailability.UNKNOWN
            val experience = if (availability == SavedAvailability.AVAILABLE) {
                item.experience?.let { runCatching { json.decodeFromJsonElement(ExperienceDto.serializer(), it).toModel() }.getOrNull() }
            } else {
                null
            }
            SavedItem(
                experienceId = item.experienceId,
                addedAt = instant(item.addedAt),
                availability = if (availability == SavedAvailability.AVAILABLE && experience == null) SavedAvailability.UNKNOWN else availability,
                experience = experience,
            )
        }
        return SavedItemsPage(Page(items, page, totalItems, totalPages), available)
    }
}

private fun instant(value: String): Instant = try {
    Instant.parse(value)
} catch (e: DateTimeParseException) {
    throw SerializationException("Data inválida")
}

fun CollectionDto.toModel() = UserCollection(id, name, itemCount, instant(createdAt), instant(updatedAt))

class UnconfiguredCollectionsRepository : CollectionsRepository {
    override suspend fun list(page: Int, pageSize: Int) = SessionResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun get(collectionId: String) = SessionResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun create(idempotencyKey: String, name: String) = CreateCollectionOutcome.Rejected(CollectionError.NOT_CONFIGURED)
    override suspend fun rename(collectionId: String, name: String) = CollectionResult.Failure(CollectionError.NOT_CONFIGURED)
    override suspend fun delete(collectionId: String) = CollectionResult.Failure(CollectionError.NOT_CONFIGURED)
    override suspend fun items(collectionId: String, page: Int, pageSize: Int) = SessionResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun add(collectionId: String, experienceId: String) = CollectionResult.Failure(CollectionError.NOT_CONFIGURED)
    override suspend fun remove(collectionId: String, experienceId: String) = CollectionResult.Failure(CollectionError.NOT_CONFIGURED)
}

/** Uma lista mudou (criada, renomeada, eliminada ou com itens alterados): os outros ecrãs atualizam-na. */
class CollectionChanges {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun notify(collectionId: String) {
        _events.tryEmit(collectionId)
    }
}
