package com.example.portofit.data.repository

import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.auth.SessionState
import com.example.portofit.data.model.Experience
import com.example.portofit.data.model.ExperiencePhoto
import com.example.portofit.data.model.ExperienceSort
import com.example.portofit.data.model.Page
import com.example.portofit.data.remote.ExperienceDto
import com.example.portofit.data.remote.ExperiencesApi
import com.example.portofit.data.remote.ProblemDto
import com.example.portofit.data.remote.PublishExperienceDto
import com.example.portofit.data.remote.toModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/** Conteúdo a publicar, já normalizado; [mediaId] é a fotografia já enviada (E2), ou null. */
data class ExperienceDraft(val placeId: String, val offeringId: String, val text: String, val stars: Int, val mediaId: String? = null)

/** Alteração da fotografia num PATCH (docs/experiences-api.md §12.1). */
sealed interface PhotoChange {
    /** Campo `mediaId` ausente: a fotografia atual mantém-se. */
    data object Keep : PhotoChange

    /** `"mediaId": null`: remove a fotografia. */
    data object Remove : PhotoChange

    /** `"mediaId": "<id>"`: associa ou substitui (fotografia já enviada). */
    data class Replace(val mediaId: String) : PhotoChange
}

/** Corpo do PATCH: só os campos a alterar; nunca envia `mediaId: null` por omissão. */
fun editBody(text: String?, stars: Int?, photo: PhotoChange): JsonObject = buildJsonObject {
    text?.let { put("text", JsonPrimitive(it)) }
    stars?.let { put("stars", JsonPrimitive(it)) }
    when (photo) {
        PhotoChange.Keep -> Unit
        PhotoChange.Remove -> put("mediaId", JsonNull)
        is PhotoChange.Replace -> put("mediaId", JsonPrimitive(photo.mediaId))
    }
}

enum class UploadError { NETWORK, SERVER, TOO_LARGE, INVALID_IMAGE, TOO_MANY_PENDING, SESSION_ENDED, SESSION_UNVERIFIED, NOT_CONFIGURED, UNEXPECTED }

sealed interface UploadOutcome {
    data class Uploaded(val mediaId: String, val width: Int, val height: Int) : UploadOutcome
    data class Failed(val error: UploadError, val retryAfterSeconds: Long? = null) : UploadOutcome
}

/** Por que motivo uma publicação **não** foi feita (nada ficou gravado; docs/experiences-api.md §5). */
enum class PublishRejection {
    VALIDATION,
    UNKNOWN_OFFERING,
    OFFERING_MISMATCH,
    OFFERING_RETIRED,
    CATALOG_UNAVAILABLE,

    /** A fotografia enviada já não pode ser usada (expirou ou já foi usada): é preciso enviá-la de novo. */
    MEDIA_NOT_USABLE,
    TOO_MANY,
    SESSION_ENDED,
    SESSION_UNVERIFIED,
    NOT_CONFIGURED,
    UNEXPECTED,
}

sealed interface PublishOutcome {
    /** 201, ou 200 com `Idempotent-Replayed` ([replayed]): a experiência existe. */
    data class Published(val experience: Experience, val replayed: Boolean) : PublishOutcome

    /**
     * Resultado desconhecido (sem resposta, timeout, erro do servidor): pode ter sido publicada. Só é seguro
     * repetir com a **mesma** chave e o **mesmo** conteúdo.
     */
    data object Unconfirmed : PublishOutcome

    data class Rejected(val reason: PublishRejection, val retryAfterSeconds: Long? = null) : PublishOutcome

    /** 409 DIFFERENT_CONTENT: esta chave já tinha publicado outra versão. */
    data class AlreadyPublished(val experienceId: String?) : PublishOutcome

    /** 409 EXPERIENCE_DELETED: foi publicada e entretanto eliminada; nada é devolvido. */
    data object PublishedThenDeleted : PublishOutcome
}

enum class MutationError { NETWORK, SERVER, SESSION_ENDED, SESSION_UNVERIFIED, FORBIDDEN, NOT_FOUND, VALIDATION, MEDIA_NOT_USABLE, NOT_CONFIGURED, UNEXPECTED }

sealed interface MutationResult<out T> {
    data class Success<T>(val value: T) : MutationResult<T>
    data class Failure(val error: MutationError) : MutationResult<Nothing>
}

interface ExperiencesRepository {
    /** Lista pública de um local ([placeId]) ou de um autor ([authorId]). */
    suspend fun list(placeId: String?, authorId: String?, sort: ExperienceSort, page: Int, pageSize: Int): DataResult<Page<Experience>>

    suspend fun get(experienceId: String): DataResult<Experience>

    /** [idempotencyKey] identifica a tentativa lógica: repetir a mesma tentativa usa a mesma chave. */
    suspend fun publish(idempotencyKey: String, draft: ExperienceDraft): PublishOutcome

    /** null = não alterar. A atividade não é editável. [photo] segue a semântica do PATCH (§12.1). */
    suspend fun edit(experienceId: String, text: String?, stars: Int?, photo: PhotoChange = PhotoChange.Keep): MutationResult<Experience>

    /** Envia a fotografia preparada (JPEG da app); fica pendente até ser associada. */
    suspend fun uploadPhoto(file: File): UploadOutcome

    suspend fun delete(experienceId: String): MutationResult<Unit>
}

/**
 * Publicar, editar e eliminar levam o access token do [SessionManager]; perante um 401 renova uma vez e
 * repete (na publicação, com a mesma chave: é a mesma tentativa).
 */
class NetworkExperiencesRepository(
    private val api: ExperiencesApi,
    private val session: SessionManager,
    private val json: Json,
) : ExperiencesRepository {

    override suspend fun list(placeId: String?, authorId: String?, sort: ExperienceSort, page: Int, pageSize: Int) =
        apiCall { api.list(placeId, authorId, sort.apiValue, page, pageSize).toModel { it.toModel() } }

    override suspend fun get(experienceId: String) = apiCall { api.get(experienceId).toModel() }

    override suspend fun publish(idempotencyKey: String, draft: ExperienceDraft): PublishOutcome {
        val body = PublishExperienceDto(draft.placeId, draft.offeringId, draft.text, draft.stars, draft.mediaId)
        val response = try {
            authorized { api.publish(it, idempotencyKey, body) } ?: return PublishOutcome.Rejected(sessionRejection())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Pode ter chegado ao servidor: só a repetição com a mesma chave é segura.
            return PublishOutcome.Unconfirmed
        }
        if (response.isSuccessful) {
            val experience = response.body()?.let { runCatching { it.toModel() }.getOrNull() } ?: return PublishOutcome.Unconfirmed
            val replayed = response.code() == 200 || response.headers()["Idempotent-Replayed"] == "true"
            return PublishOutcome.Published(experience, replayed)
        }
        val problem = problem(response)
        return when {
            response.code() == 409 && problem?.reason == "EXPERIENCE_DELETED" -> PublishOutcome.PublishedThenDeleted
            response.code() == 409 -> PublishOutcome.AlreadyPublished(problem?.experienceId)
            response.code() == 401 -> PublishOutcome.Rejected(PublishRejection.SESSION_ENDED)
            response.code() == 429 -> PublishOutcome.Rejected(PublishRejection.TOO_MANY, response.headers()["Retry-After"]?.toLongOrNull())
            problem?.code == "UNKNOWN_OFFERING" -> PublishOutcome.Rejected(PublishRejection.UNKNOWN_OFFERING)
            problem?.code == "OFFERING_PLACE_MISMATCH" -> PublishOutcome.Rejected(PublishRejection.OFFERING_MISMATCH)
            problem?.code == "OFFERING_RETIRED" -> PublishOutcome.Rejected(PublishRejection.OFFERING_RETIRED)
            problem?.code == "CATALOG_UNAVAILABLE" -> PublishOutcome.Rejected(PublishRejection.CATALOG_UNAVAILABLE)
            problem?.code == "MEDIA_NOT_USABLE" -> PublishOutcome.Rejected(PublishRejection.MEDIA_NOT_USABLE)
            response.code() == 400 || response.code() == 413 -> PublishOutcome.Rejected(PublishRejection.VALIDATION)
            // Outros 5xx: o servidor pode ter gravado antes de falhar; tratar como resultado desconhecido.
            response.code() in 500..599 -> PublishOutcome.Unconfirmed
            else -> PublishOutcome.Rejected(PublishRejection.UNEXPECTED)
        }
    }

    override suspend fun edit(experienceId: String, text: String?, stars: Int?, photo: PhotoChange): MutationResult<Experience> =
        mutation({ api.edit(it, experienceId, editBody(text, stars, photo)) }) { it.body()!!.toModel() }

    override suspend fun uploadPhoto(file: File): UploadOutcome {
        // O nome e o tipo enviados são informativos: o servidor decide pelo conteúdo.
        val part = MultipartBody.Part.createFormData("file", "foto.jpg", file.asRequestBody("image/jpeg".toMediaType()))
        val response = try {
            authorized { api.uploadPhoto(it, part) }
                ?: return UploadOutcome.Failed(if (session.state.value is SessionState.SignedOut) UploadError.SESSION_ENDED else UploadError.SESSION_UNVERIFIED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return UploadOutcome.Failed(UploadError.NETWORK)
        }
        if (response.isSuccessful) {
            val body = response.body() ?: return UploadOutcome.Failed(UploadError.UNEXPECTED)
            return UploadOutcome.Uploaded(body.mediaId, body.width, body.height)
        }
        return UploadOutcome.Failed(
            when (response.code()) {
                401 -> UploadError.SESSION_ENDED
                400 -> UploadError.INVALID_IMAGE
                413 -> UploadError.TOO_LARGE
                429 -> UploadError.TOO_MANY_PENDING
                in 500..599 -> UploadError.SERVER
                else -> UploadError.UNEXPECTED
            },
            retryAfterSeconds = response.headers()["Retry-After"]?.toLongOrNull()?.takeIf { response.code() == 429 },
        )
    }

    override suspend fun delete(experienceId: String): MutationResult<Unit> = mutation({ api.delete(it, experienceId) }) { }

    private suspend fun <R, T> mutation(call: suspend (String) -> Response<R>, onSuccess: (Response<R>) -> T): MutationResult<T> =
        try {
            val response = authorized(call) ?: return MutationResult.Failure(mutationSessionError())
            if (response.isSuccessful) {
                MutationResult.Success(onSuccess(response))
            } else if (response.code() == 400 && problem(response)?.code == "MEDIA_NOT_USABLE") {
                MutationResult.Failure(MutationError.MEDIA_NOT_USABLE)
            } else {
                MutationResult.Failure(
                    when (response.code()) {
                        401 -> MutationError.SESSION_ENDED
                        403 -> MutationError.FORBIDDEN
                        404 -> MutationError.NOT_FOUND
                        400, 413 -> MutationError.VALIDATION
                        in 500..599 -> MutationError.SERVER
                        else -> MutationError.UNEXPECTED
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            MutationResult.Failure(MutationError.NETWORK)
        } catch (e: RuntimeException) {
            MutationResult.Failure(MutationError.UNEXPECTED)
        }

    /** null quando não há token (sem sessão ou sessão por confirmar): o pedido não chega a ser enviado. */
    private suspend fun <R> authorized(call: suspend (String) -> Response<R>): Response<R>? {
        val token = session.accessToken() ?: return null
        val response = call("Bearer $token")
        if (response.code() != 401) return response
        val fresh = session.accessTokenAfterUnauthorized(token) ?: return null
        return call("Bearer $fresh")
    }

    private fun sessionRejection() =
        if (session.state.value is SessionState.SignedOut) PublishRejection.SESSION_ENDED else PublishRejection.SESSION_UNVERIFIED

    private fun mutationSessionError() =
        if (session.state.value is SessionState.SignedOut) MutationError.SESSION_ENDED else MutationError.SESSION_UNVERIFIED

    private fun problem(response: Response<*>): ProblemDto? =
        runCatching { response.errorBody()?.string()?.let { json.decodeFromString<ProblemDto>(it) } }.getOrNull()
}

fun ExperienceDto.toModel(): Experience = try {
    Experience(
        id = id,
        authorId = author.userId,
        authorUsername = author.username,
        placeId = place.id,
        placeName = place.name,
        placeDemo = place.demo,
        offeringId = offering.id,
        offeringName = offering.name,
        sportName = sport.name,
        text = text,
        stars = stars,
        createdAt = Instant.parse(createdAt),
        editedAt = editedAt?.let(Instant::parse),
        photo = photo?.let { ExperiencePhoto(it.mediaId, it.url, it.width, it.height) },
    )
} catch (e: DateTimeParseException) {
    throw SerializationException("Data inválida")
}

class UnconfiguredExperiencesRepository : ExperiencesRepository {
    private val failure = DataResult.Failure(DataError.NOT_CONFIGURED)
    override suspend fun list(placeId: String?, authorId: String?, sort: ExperienceSort, page: Int, pageSize: Int) = failure
    override suspend fun get(experienceId: String) = failure
    override suspend fun publish(idempotencyKey: String, draft: ExperienceDraft) = PublishOutcome.Rejected(PublishRejection.NOT_CONFIGURED)
    override suspend fun edit(experienceId: String, text: String?, stars: Int?, photo: PhotoChange) = MutationResult.Failure(MutationError.NOT_CONFIGURED)
    override suspend fun uploadPhoto(file: File) = UploadOutcome.Failed(UploadError.NOT_CONFIGURED)
    override suspend fun delete(experienceId: String) = MutationResult.Failure(MutationError.NOT_CONFIGURED)
}

/** Uma experiência foi publicada, editada ou eliminada: listas e avaliações do local e do autor mudaram. */
data class ExperienceChange(val experienceId: String, val placeId: String, val authorId: String)

/**
 * Avisos de alterações entre ecrãs (o detalhe do local e os perfis ficam na pilha de navegação e
 * atualizam-se ao receber um aviso).
 */
class ExperienceChanges {
    private val _events = MutableSharedFlow<ExperienceChange>(extraBufferCapacity = 16)
    val events: SharedFlow<ExperienceChange> = _events.asSharedFlow()

    fun notify(change: ExperienceChange) {
        _events.tryEmit(change)
    }
}
