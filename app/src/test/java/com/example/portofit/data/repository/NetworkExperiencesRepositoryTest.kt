package com.example.portofit.data.repository

import com.example.portofit.data.auth.SessionManager
import com.example.portofit.data.model.ExperienceSort
import com.example.portofit.data.model.Comment
import com.example.portofit.data.remote.CommentBodyDto
import com.example.portofit.data.remote.CommentDto
import com.example.portofit.data.model.FeedFallbackReason
import com.example.portofit.data.model.FeedMatch
import com.example.portofit.data.model.FeedMode
import com.example.portofit.data.model.StatsPeriod
import com.example.portofit.data.model.StatsSort
import com.example.portofit.data.remote.ExperienceOfferingDto as OfferingRef
import com.example.portofit.data.remote.ExperiencePhotoDto
import com.example.portofit.data.remote.ExperienceStatsDto
import com.example.portofit.data.remote.FeedPageDto
import com.example.portofit.data.remote.StatsDto
import com.example.portofit.data.remote.StatsExperienceDto
import com.example.portofit.data.remote.StatsPageDto
import com.example.portofit.data.remote.StatsReceivedDto
import com.example.portofit.data.remote.MediaUploadDto
import com.example.portofit.data.remote.ExperienceAuthorDto
import com.example.portofit.data.remote.ExperienceDto
import com.example.portofit.data.remote.ExperienceOfferingDto
import com.example.portofit.data.remote.ExperiencesApi
import com.example.portofit.data.remote.PageDto
import com.example.portofit.data.remote.PlaceRefDto
import com.example.portofit.data.remote.PublishExperienceDto
import com.example.portofit.data.remote.SportRefDto
import com.example.portofit.testing.FakeAuthGateway
import com.example.portofit.testing.InMemorySessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okio.Buffer
import java.io.File
import java.nio.file.Files
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkExperiencesRepositoryTest {

    private val gateway = FakeAuthGateway()
    private val session = SessionManager(gateway, InMemorySessionStore(), CoroutineScope(Dispatchers.Unconfined), io = UnconfinedTestDispatcher())

    private val dto = ExperienceDto(
        id = "e1",
        author = ExperienceAuthorDto("user-1", "ana"),
        place = PlaceRefDto("p1", "Clube de Combate Ribeira (fictício)", true),
        offering = ExperienceOfferingDto("o1", "Boxe iniciação"),
        sport = SportRefDto("boxe", "Boxe"),
        text = "Boa aula.",
        stars = 4,
        createdAt = "2026-10-19T18:30:00.123456Z",
    )

    private class FakeApi : ExperiencesApi {
        /** (pedido, Authorization, Idempotency-Key) por ordem. */
        val calls = mutableListOf<Triple<String, String?, String?>>()
        val listParams = mutableListOf<List<Any?>>()
        var publishResponses = ArrayDeque<() -> Response<ExperienceDto>>()
        var editResponses = ArrayDeque<() -> Response<ExperienceDto>>()
        var deleteResponses = ArrayDeque<() -> Response<Unit>>()
        var page: () -> PageDto<ExperienceDto> = { PageDto(emptyList(), 0, 10, 0, 0) }
        var single: () -> ExperienceDto = { error("sem resposta") }

        override suspend fun list(placeId: String?, authorId: String?, sort: String, page: Int, size: Int): PageDto<ExperienceDto> {
            calls += Triple("list", null, null)
            listParams += listOf(placeId, authorId, sort, page, size)
            return this.page()
        }

        /** Authorization de cada leitura do detalhe (null = visitante). */
        val getAuthorizations = mutableListOf<String?>()
        var getResponses = ArrayDeque<() -> Response<ExperienceDto>>()

        override suspend fun get(experienceId: String, authorization: String?): Response<ExperienceDto> {
            getAuthorizations += authorization
            return getResponses.removeFirstOrNull()?.invoke() ?: Response.success(single())
        }

        // --- E3 ---
        var commentsPage: () -> PageDto<CommentDto> = { PageDto(emptyList(), 0, 20, 0, 0) }
        var postCommentResponses = ArrayDeque<() -> Response<CommentDto>>()
        val commentBodies = mutableListOf<CommentBodyDto>()
        var editCommentResponses = ArrayDeque<() -> Response<CommentDto>>()
        var deleteCommentResponses = ArrayDeque<() -> Response<Unit>>()
        var likeResponses = ArrayDeque<() -> Response<Unit>>()
        /** (Authorization, X-Viewer-Key) de cada visualização. */
        val views = mutableListOf<Pair<String?, String?>>()
        var viewResponse: () -> Response<Unit> = { Response.success(204, Unit) }

        override suspend fun comments(experienceId: String, page: Int, size: Int): PageDto<CommentDto> {
            calls += Triple("comments", null, null)
            return commentsPage()
        }

        override suspend fun postComment(authorization: String, idempotencyKey: String, experienceId: String, body: CommentBodyDto): Response<CommentDto> {
            calls += Triple("postComment", authorization, idempotencyKey)
            commentBodies += body
            return postCommentResponses.removeFirst()()
        }

        override suspend fun editComment(authorization: String, commentId: String, body: CommentBodyDto): Response<CommentDto> {
            calls += Triple("editComment", authorization, null)
            commentBodies += body
            return editCommentResponses.removeFirst()()
        }

        override suspend fun deleteComment(authorization: String, commentId: String): Response<Unit> {
            calls += Triple("deleteComment", authorization, null)
            return deleteCommentResponses.removeFirst()()
        }

        override suspend fun like(authorization: String, experienceId: String): Response<Unit> {
            calls += Triple("like", authorization, null)
            return likeResponses.removeFirst()()
        }

        override suspend fun unlike(authorization: String, experienceId: String): Response<Unit> {
            calls += Triple("unlike", authorization, null)
            return likeResponses.removeFirst()()
        }

        // --- E4 ---
        /** (pedido, Authorization) dos feeds e estatísticas. */
        val discoverCalls = mutableListOf<Pair<String, String?>>()
        var popularResponses = ArrayDeque<() -> Response<FeedPageDto>>()
        var personalResponses = ArrayDeque<() -> Response<FeedPageDto>>()
        var statsResponses = ArrayDeque<() -> Response<StatsDto>>()
        var statsPageResponses = ArrayDeque<() -> Response<StatsPageDto>>()

        override suspend fun popularFeed(sportId: String?, page: Int, size: Int, authorization: String?): Response<FeedPageDto> {
            discoverCalls += "popular" to authorization
            return popularResponses.removeFirst()()
        }

        override suspend fun personalFeed(authorization: String, sportId: String?, page: Int, size: Int): Response<FeedPageDto> {
            discoverCalls += "personal" to authorization
            return personalResponses.removeFirst()()
        }

        override suspend fun stats(authorization: String, period: String): Response<StatsDto> {
            discoverCalls += "stats:$period" to authorization
            return statsResponses.removeFirst()()
        }

        override suspend fun statsExperiences(authorization: String, period: String, sort: String, page: Int, size: Int): Response<StatsPageDto> {
            discoverCalls += "statsExperiences:$period:$sort:$page" to authorization
            return statsPageResponses.removeFirst()()
        }

        override suspend fun registerView(experienceId: String, authorization: String?, viewerKey: String?): Response<Unit> {
            views += authorization to viewerKey
            return viewResponse()
        }

        val publishBodies = mutableListOf<PublishExperienceDto>()
        val editBodies = mutableListOf<JsonObject>()
        val uploadParts = mutableListOf<MultipartBody.Part>()
        var uploadResponses = ArrayDeque<() -> Response<MediaUploadDto>>()

        override suspend fun publish(authorization: String, idempotencyKey: String, body: PublishExperienceDto): Response<ExperienceDto> {
            calls += Triple("publish", authorization, idempotencyKey)
            publishBodies += body
            return publishResponses.removeFirst()()
        }

        override suspend fun uploadPhoto(authorization: String, file: MultipartBody.Part): Response<MediaUploadDto> {
            calls += Triple("upload", authorization, null)
            uploadParts += file
            return uploadResponses.removeFirst()()
        }

        override suspend fun edit(authorization: String, experienceId: String, body: JsonObject): Response<ExperienceDto> {
            calls += Triple("edit", authorization, null)
            editBodies += body
            return editResponses.removeFirst()()
        }

        override suspend fun delete(authorization: String, experienceId: String): Response<Unit> {
            calls += Triple("delete", authorization, null)
            return deleteResponses.removeFirst()()
        }
    }

    private val api = FakeApi()
    private val repository = NetworkExperiencesRepository(api, session, Json { ignoreUnknownKeys = true }) { "installation-key" }

    private val commentDto = CommentDto("c1", "e1", ExperienceAuthorDto("user-2", "rui"), "Também gostei.", "2026-10-19T19:00:00Z")
    private val draft = ExperienceDraft("p1", "o1", "Boa aula.", 4)

    private fun <T> error(status: Int, body: String = "{}", headers: Map<String, String> = emptyMap()): Response<T> {
        val raw = okhttp3.Response.Builder()
            .code(status).message("erro").protocol(okhttp3.Protocol.HTTP_1_1)
            .request(okhttp3.Request.Builder().url("http://localhost/").build())
            .headers(headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
            .build()
        return Response.error(body.toResponseBody("application/problem+json".toMediaType()), raw)
    }

    @Test
    fun `listas sao publicas e convertidas no modelo`() = runTest {
        session.start()
        api.page = { PageDto(listOf(dto), 0, 10, 1, 1) }
        val page = (repository.list("p1", null, ExperienceSort.STARS, 0, 10) as DataResult.Success).value
        assertEquals(listOf(listOf("p1", null, "stars", 0, 10)), api.listParams)
        val experience = page.items.single()
        assertEquals("ana", experience.authorUsername)
        assertTrue(experience.placeDemo)
        assertEquals(Instant.parse("2026-10-19T18:30:00.123456Z"), experience.createdAt)
    }

    @Test
    fun `data invalida na resposta e erro inesperado`() = runTest {
        api.single = { dto.copy(createdAt = "ontem") }
        assertEquals(DataResult.Failure(DataError.UNEXPECTED), repository.get("e1"))
    }

    @Test
    fun `publicar sem sessao nao faz pedidos`() = runTest {
        session.start()
        assertEquals(PublishOutcome.Rejected(PublishRejection.SESSION_ENDED), repository.publish("k1", draft))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `publicar envia token e chave, e um 401 renova e repete com a mesma chave`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.publishResponses = ArrayDeque(listOf({ error(401) }, { Response.success(201, dto) }))

        val outcome = repository.publish("k1", draft) as PublishOutcome.Published

        assertEquals(listOf(Triple("publish", "Bearer a-login", "k1"), Triple("publish", "Bearer a-0", "k1")), api.calls)
        assertEquals(false, outcome.replayed)
        assertEquals("e1", outcome.experience.id)
    }

    @Test
    fun `repeticao idempotente devolvida com 200`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.publishResponses = ArrayDeque(listOf({ Response.success(dto, headersOf("Idempotent-Replayed", "true")) }))
        assertEquals(PublishOutcome.Published(repository.run { dto.toModel() }, replayed = true), repository.publish("k1", draft))
    }

    @Test
    fun `resultados desconhecidos e recusas definitivas`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.publishResponses = ArrayDeque(
            listOf(
                { throw IOException("timeout") },
                { error(503, """{"code":"SERVICE_UNAVAILABLE"}""") },
                { error(500, """{"code":"INTERNAL_ERROR"}""") },
                { error(503, """{"code":"CATALOG_UNAVAILABLE"}""") },
                { error(400, """{"code":"VALIDATION_FAILED"}""") },
                { error(400, """{"code":"UNKNOWN_OFFERING"}""") },
                { error(400, """{"code":"OFFERING_PLACE_MISMATCH"}""") },
                { error(400, """{"code":"OFFERING_RETIRED"}""") },
                { error(429, """{"code":"TOO_MANY_REQUESTS"}""", mapOf("Retry-After" to "1740")) },
                { error(409, """{"code":"IDEMPOTENCY_CONFLICT","reason":"DIFFERENT_CONTENT","experienceId":"e-old"}""") },
                { error(409, """{"code":"IDEMPOTENCY_CONFLICT","reason":"EXPERIENCE_DELETED"}""") },
                { Response.success(201, dto.copy(createdAt = "?")) },
            ),
        )
        val expected = listOf(
            PublishOutcome.Unconfirmed,
            PublishOutcome.Unconfirmed,
            PublishOutcome.Unconfirmed,
            PublishOutcome.Rejected(PublishRejection.CATALOG_UNAVAILABLE),
            PublishOutcome.Rejected(PublishRejection.VALIDATION),
            PublishOutcome.Rejected(PublishRejection.UNKNOWN_OFFERING),
            PublishOutcome.Rejected(PublishRejection.OFFERING_MISMATCH),
            PublishOutcome.Rejected(PublishRejection.OFFERING_RETIRED),
            PublishOutcome.Rejected(PublishRejection.TOO_MANY, 1740),
            PublishOutcome.AlreadyPublished("e-old"),
            PublishOutcome.PublishedThenDeleted,
            // Aceite, mas sem resposta legível: não se sabe o resultado.
            PublishOutcome.Unconfirmed,
        )
        for (outcome in expected) assertEquals(outcome, repository.publish("k1", draft))
    }

    @Test
    fun `editar e eliminar com token e erros classificados`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.editResponses = ArrayDeque(listOf({ Response.success(dto.copy(stars = 5)) }, { error(403) }, { throw IOException("sem rede") }))
        api.deleteResponses = ArrayDeque(listOf({ Response.success(204, Unit) }, { error(404) }, { error(503) }))

        assertEquals(5, (repository.edit("e1", null, 5) as MutationResult.Success).value.stars)
        assertEquals(MutationResult.Failure(MutationError.FORBIDDEN), repository.edit("e1", "x", null))
        assertEquals(MutationResult.Failure(MutationError.NETWORK), repository.edit("e1", "x", null))
        assertEquals(MutationResult.Success(Unit), repository.delete("e1"))
        assertEquals(MutationResult.Failure(MutationError.NOT_FOUND), repository.delete("e1"))
        assertEquals(MutationResult.Failure(MutationError.SERVER), repository.delete("e1"))
        assertTrue(api.calls.filter { it.first != "list" }.all { it.second == "Bearer a-login" })
    }

    // --- Fotografias (E2) ---

    @Test
    fun `corpo do PATCH - ausente mantem, null remove, id substitui`() {
        assertEquals("""{"stars":5}""", editBody(null, 5, PhotoChange.Keep).toString())
        assertEquals("""{"mediaId":null}""", editBody(null, null, PhotoChange.Remove).toString())
        assertEquals("""{"text":"Novo","mediaId":"m1"}""", editBody("Novo", null, PhotoChange.Replace("m1")).toString())
        assertEquals("{}", editBody(null, null, PhotoChange.Keep).toString())
    }

    @Test
    fun `editar envia o corpo com a alteracao da fotografia e classifica MEDIA_NOT_USABLE`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.editResponses = ArrayDeque(listOf({ Response.success(dto) }, { Response.success(dto) }, { error(400, """{"code":"MEDIA_NOT_USABLE"}""") }))
        repository.edit("e1", null, 3)
        repository.edit("e1", null, null, PhotoChange.Remove)
        assertEquals(MutationResult.Failure(MutationError.MEDIA_NOT_USABLE), repository.edit("e1", null, null, PhotoChange.Replace("m9")))
        assertEquals(listOf("""{"stars":3}""", """{"mediaId":null}""", """{"mediaId":"m9"}"""), api.editBodies.map { it.toString() })
    }

    @Test
    fun `publicar com fotografia envia o mediaId e sem fotografia nao envia o campo`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.publishResponses = ArrayDeque(listOf({ Response.success(201, dto) }, { Response.success(201, dto) }, { error(400, """{"code":"MEDIA_NOT_USABLE"}""") }))
        repository.publish("k1", draft.copy(mediaId = "m1"))
        repository.publish("k2", draft)
        assertEquals(PublishOutcome.Rejected(PublishRejection.MEDIA_NOT_USABLE), repository.publish("k3", draft.copy(mediaId = "m2")))
        assertEquals(listOf("m1", null, "m2"), api.publishBodies.map { it.mediaId })
        val encoded = Json.encodeToString(PublishExperienceDto.serializer(), api.publishBodies[1])
        assertTrue("sem o campo mediaId: $encoded", !encoded.contains("mediaId"))
    }

    @Test
    fun `fotografia da experiencia convertida com o caminho relativo`() = runTest {
        api.single = { dto.copy(photo = ExperiencePhotoDto("m1", "/api/v1/media/m1", 1600, 1200)) }
        val photo = (repository.get("e1") as DataResult.Success).value.photo!!
        assertEquals("/api/v1/media/m1", photo.path)
        assertEquals(1600 to 1200, photo.width to photo.height)
    }

    @Test
    fun `envio da fotografia em multipart com token e erros classificados`() = runTest {
        session.login("a@example.org", "uma frase longa")
        val file = Files.createTempFile("foto", ".jpg").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        api.uploadResponses = ArrayDeque(
            listOf(
                { error(401) },
                { Response.success(201, MediaUploadDto("m1", 1600, 1200)) },
                { error(400, """{"code":"INVALID_IMAGE"}""") },
                { error(413) },
                { error(429, "{}", mapOf("Retry-After" to "60")) },
                { error(503) },
                { throw IOException("sem rede") },
            ),
        )
        assertEquals(UploadOutcome.Uploaded("m1", 1600, 1200), repository.uploadPhoto(file))
        assertEquals(listOf(Triple("upload", "Bearer a-login", null), Triple("upload", "Bearer a-0", null)), api.calls)
        val part = api.uploadParts.last()
        assertTrue(part.headers?.get("Content-Disposition")!!.contains("name=\"file\""))
        assertEquals(3L, part.body.contentLength())
        assertEquals(UploadOutcome.Failed(UploadError.INVALID_IMAGE), repository.uploadPhoto(file))
        assertEquals(UploadOutcome.Failed(UploadError.TOO_LARGE), repository.uploadPhoto(file))
        assertEquals(UploadOutcome.Failed(UploadError.TOO_MANY_PENDING, 60), repository.uploadPhoto(file))
        assertEquals(UploadOutcome.Failed(UploadError.SERVER), repository.uploadPhoto(file))
        assertEquals(UploadOutcome.Failed(UploadError.NETWORK), repository.uploadPhoto(file))
    }

    @Test
    fun `envio sem sessao nao faz pedidos`() = runTest {
        session.start()
        val file = File.createTempFile("foto", ".jpg")
        assertEquals(UploadOutcome.Failed(UploadError.SESSION_ENDED), repository.uploadPhoto(file))
        assertTrue(api.calls.isEmpty())
    }

    // --- E3 ---

    @Test
    fun `detalhe com sessao leva o token e likedByMe ausente fica null`() = runTest {
        session.start()
        api.single = { dto.copy(likesCount = 3, commentsCount = 2) }
        val visitor = (repository.get("e1") as DataResult.Success).value
        assertNull(api.getAuthorizations.single())
        assertNull("ausente para visitantes: desconhecido, nunca false", visitor.likedByMe)
        assertEquals(3L, visitor.likesCount)
        assertEquals(2L, visitor.commentsCount)

        session.login("a@example.org", "uma frase longa")
        api.single = { dto.copy(likedByMe = false) }
        assertEquals(false, (repository.get("e1") as DataResult.Success).value.likedByMe)
        assertEquals("Bearer a-login", api.getAuthorizations.last())
    }

    @Test
    fun `detalhe com 401 renova uma vez e um servidor sem os campos novos da contagens a zero`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.getResponses = ArrayDeque(listOf({ error(401) }, { Response.success(dto) }))
        val experience = (repository.get("e1") as DataResult.Success).value
        assertEquals(listOf("Bearer a-login", "Bearer a-0"), api.getAuthorizations)
        assertEquals(0L, experience.likesCount)
        assertNull(experience.likedByMe)
        api.getResponses = ArrayDeque(listOf({ error(404) }))
        assertEquals(DataResult.Failure(DataError.NOT_FOUND), repository.get("e1"))
    }

    @Test
    fun `comentarios publicos convertidos e publicar com token e chave`() = runTest {
        session.start()
        api.commentsPage = { PageDto(listOf(commentDto), 0, 20, 1, 1) }
        val page = (repository.comments("e1", 0, 20) as DataResult.Success).value
        assertEquals(Comment("c1", "e1", "user-2", "rui", "Também gostei.", Instant.parse("2026-10-19T19:00:00Z"), null), page.items.single())
        assertEquals(CommentOutcome.Rejected(CommentRejection.SESSION_ENDED), repository.postComment("k1", "e1", "Olá"))
        assertEquals(listOf(Triple<String, String?, String?>("comments", null, null)), api.calls)

        session.login("a@example.org", "uma frase longa")
        api.postCommentResponses = ArrayDeque(
            listOf(
                { error(401) },
                { Response.success(201, commentDto) },
                { Response.success(commentDto, headersOf("Idempotent-Replayed", "true")) },
            ),
        )
        val posted = repository.postComment("k1", "e1", "Também gostei.") as CommentOutcome.Posted
        assertEquals(false, posted.replayed)
        // Um 401 renova e repete com a mesma chave (é a mesma tentativa).
        assertEquals(listOf("Bearer a-login" to "k1", "Bearer a-0" to "k1"), api.calls.filter { it.first == "postComment" }.map { it.second to it.third })
        assertEquals(CommentBodyDto("Também gostei."), api.commentBodies.last())
        assertEquals(true, (repository.postComment("k1", "e1", "Também gostei.") as CommentOutcome.Posted).replayed)
    }

    @Test
    fun `comentario - resultados desconhecidos e recusas definitivas`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.postCommentResponses = ArrayDeque(
            listOf(
                { throw IOException("timeout") },
                { error(503) },
                { error(400, """{"code":"VALIDATION_FAILED"}""") },
                { error(404, """{"code":"NOT_FOUND"}""") },
                { error(429, """{"code":"TOO_MANY_REQUESTS"}""", mapOf("Retry-After" to "120")) },
                { error(409, """{"code":"IDEMPOTENCY_CONFLICT","reason":"DIFFERENT_CONTENT","commentId":"c-old"}""") },
                { error(409, """{"code":"IDEMPOTENCY_CONFLICT","reason":"COMMENT_DELETED"}""") },
            ),
        )
        val expected = listOf(
            CommentOutcome.Unconfirmed,
            CommentOutcome.Unconfirmed,
            CommentOutcome.Rejected(CommentRejection.VALIDATION),
            CommentOutcome.Rejected(CommentRejection.EXPERIENCE_GONE),
            CommentOutcome.Rejected(CommentRejection.TOO_MANY, 120),
            CommentOutcome.AlreadyPosted("c-old"),
            CommentOutcome.PostedThenDeleted,
        )
        for (outcome in expected) assertEquals(outcome, repository.postComment("k1", "e1", "Olá"))
    }

    @Test
    fun `editar e eliminar comentario com erros classificados`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.editCommentResponses = ArrayDeque(listOf({ Response.success(commentDto.copy(text = "Novo", editedAt = "2026-10-19T19:05:00Z")) }, { error(403) }))
        api.deleteCommentResponses = ArrayDeque(listOf({ Response.success(204, Unit) }, { error(404) }, { throw IOException("sem rede") }))
        assertEquals("Novo", (repository.editComment("c1", "Novo") as MutationResult.Success).value.text)
        assertEquals(MutationResult.Failure(MutationError.FORBIDDEN), repository.editComment("c1", "Novo"))
        assertEquals(MutationResult.Success(Unit), repository.deleteComment("c1"))
        assertEquals(MutationResult.Failure(MutationError.NOT_FOUND), repository.deleteComment("c1"))
        assertEquals(MutationResult.Failure(MutationError.NETWORK), repository.deleteComment("c1"))
    }

    @Test
    fun `gostos - confirmado, proprio, desconhecido e sem sessao`() = runTest {
        session.start()
        assertEquals(LikeResult.Failed(LikeError.SESSION_ENDED), repository.setLiked("e1", true))
        assertTrue(api.calls.isEmpty())

        session.login("a@example.org", "uma frase longa")
        api.likeResponses = ArrayDeque(
            listOf(
                { Response.success(204, Unit) },
                { Response.success(204, Unit) },
                { error(400, """{"code":"CANNOT_LIKE_OWN"}""") },
                { throw IOException("timeout") },
                { error(503) },
                { error(404) },
                { error(429, "{}", mapOf("Retry-After" to "60")) },
            ),
        )
        assertEquals(LikeResult.Confirmed, repository.setLiked("e1", true))
        assertEquals(LikeResult.Confirmed, repository.setLiked("e1", false))
        assertEquals(listOf("like", "unlike"), api.calls.map { it.first })
        assertEquals(LikeResult.Failed(LikeError.CANNOT_LIKE_OWN), repository.setLiked("e1", true))
        assertEquals(LikeResult.Failed(LikeError.UNCONFIRMED), repository.setLiked("e1", true))
        assertEquals(LikeResult.Failed(LikeError.UNCONFIRMED), repository.setLiked("e1", true))
        assertEquals(LikeResult.Failed(LikeError.EXPERIENCE_GONE), repository.setLiked("e1", true))
        assertEquals(LikeResult.Failed(LikeError.TOO_MANY, 60), repository.setLiked("e1", true))
    }

    @Test
    fun `visualizacao - visitante com a chave da instalacao, sessao com o token e uma so tentativa`() = runTest {
        session.start()
        assertTrue(repository.registerView("e1"))
        assertEquals(null to "installation-key", api.views.single())

        session.login("a@example.org", "uma frase longa")
        assertTrue(repository.registerView("e1"))
        assertEquals("Bearer a-login" to null, api.views.last())

        api.viewResponse = { throw IOException("sem rede") }
        assertEquals(false, repository.registerView("e1"))
        api.viewResponse = { error(503) }
        assertEquals(false, repository.registerView("e1"))
        // Cada chamada é um único pedido: nenhuma repetição automática.
        assertEquals(4, api.views.size)
    }

    // --- E4: feeds e dashboard ---

    private val discover = NetworkDiscoverRepository(api, session)

    private fun feedDto(mode: String, reason: String? = null, matchedBy: List<String>? = null) = FeedPageDto(
        items = listOf(dto.copy(matchedBy = matchedBy)), page = 0, size = 10, totalItems = 1, totalPages = 1,
        mode = mode, fallbackReason = reason, recentSince = if (mode == "PERSONAL") null else "2026-09-19T00:00:00Z", generatedAt = "2026-10-19T21:30:00Z",
    )

    @Test
    fun `feed popular publico ou com token, e pessoal so com sessao`() = runTest {
        session.start()
        api.popularResponses = ArrayDeque(listOf({ Response.success(feedDto("POPULAR")) }))
        val popular = (discover.popularFeed(null, 0, 10) as DataResult.Success).value
        assertEquals(FeedMode.POPULAR, popular.mode)
        assertNull(popular.fallbackReason)
        assertEquals("popular" to null, api.discoverCalls.single())
        assertEquals(SessionResult.SessionEnded, discover.personalFeed(null, 0, 10))
        assertEquals(1, api.discoverCalls.size)

        session.login("a@example.org", "uma frase longa")
        api.personalResponses = ArrayDeque(listOf({ error(401) }, { Response.success(feedDto("PERSONAL", matchedBy = listOf("FOLLOWING", "INTEREST", "OUTRO"))) }))
        val personal = (discover.personalFeed("boxe", 0, 10) as SessionResult.Success).value
        assertEquals(FeedMode.PERSONAL, personal.mode)
        assertEquals(setOf(FeedMatch.FOLLOWING, FeedMatch.INTEREST), personal.page.items.single().matchedBy)
        // Um 401 renova uma vez e repete.
        assertEquals(listOf("personal" to "Bearer a-login", "personal" to "Bearer a-0"), api.discoverCalls.drop(1))
    }

    @Test
    fun `modos e motivos interpretados sem nunca inventar personalizacao`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.personalResponses = ArrayDeque(
            listOf(
                { Response.success(feedDto("POPULAR_FALLBACK", "USERS_UNAVAILABLE")) },
                { Response.success(feedDto("POPULAR", "NO_PREFERENCES")) },
                { Response.success(feedDto("POPULAR", "NO_MATCHES")) },
                // Motivo desconhecido num fallback → indisponibilidade; modo desconhecido → erro, nunca pessoal.
                { Response.success(feedDto("POPULAR_FALLBACK", "NOVO_MOTIVO")) },
                { Response.success(feedDto("MISTO")) },
                { error(503) },
                { throw IOException("sem rede") },
            ),
        )
        suspend fun page() = discover.personalFeed(null, 0, 10)
        assertEquals(FeedFallbackReason.USERS_UNAVAILABLE, (page() as SessionResult.Success).value.fallbackReason)
        assertEquals(FeedFallbackReason.NO_PREFERENCES, (page() as SessionResult.Success).value.fallbackReason)
        assertEquals(FeedFallbackReason.NO_MATCHES, (page() as SessionResult.Success).value.fallbackReason)
        assertEquals(FeedFallbackReason.USERS_UNAVAILABLE, (page() as SessionResult.Success).value.fallbackReason)
        assertEquals(SessionResult.Failure(DataError.UNEXPECTED), page())
        assertEquals(SessionResult.Failure(DataError.SERVER), page())
        assertEquals(SessionResult.Failure(DataError.NETWORK), page())
    }

    @Test
    fun `estatisticas com token, intervalo do servidor e sessao terminada distinta de falha temporaria`() = runTest {
        session.login("a@example.org", "uma frase longa")
        api.statsResponses = ArrayDeque(
            listOf(
                {
                    Response.success(
                        StatsDto(
                            period = "LAST_7_DAYS", from = "2026-10-13T00:00:00Z", to = "2026-10-19T21:30:00Z", generatedAt = "2026-10-19T21:30:00Z",
                            received = StatsReceivedDto(likes = 2, comments = 1, interactions = 3, views = 4), publications = 1,
                            starsGiven = mapOf("4" to 1L),
                        ),
                    )
                },
                { error(503) },
            ),
        )
        val totals = (discover.stats(StatsPeriod.LAST_7_DAYS) as SessionResult.Success).value
        assertEquals(Instant.parse("2026-10-13T00:00:00Z"), totals.window.from)
        assertEquals(Instant.parse("2026-10-19T21:30:00Z"), totals.window.generatedAt)
        assertEquals(mapOf(1 to 0L, 2 to 0L, 3 to 0L, 4 to 1L, 5 to 0L), totals.starsGiven)
        assertEquals(3L, totals.interactions)
        assertEquals("stats:LAST_7_DAYS" to "Bearer a-login", api.discoverCalls.last())
        assertEquals(SessionResult.Failure(DataError.SERVER), discover.stats(StatsPeriod.ALL))

        api.statsPageResponses = ArrayDeque(
            listOf({
                Response.success(
                    StatsPageDto(
                        items = listOf(
                            ExperienceStatsDto(
                                StatsExperienceDto("e1", PlaceRefDto("p1", "Local", true), OfferingRef("o1", "Boxe iniciação"), SportRefDto("boxe", "Boxe"), 4, "2026-10-10T10:00:00Z"),
                                likes = 1, comments = 2, interactions = 3, views = 4,
                            ),
                        ),
                        page = 0, size = 10, totalItems = 1, totalPages = 1, period = "ALL", from = null, to = "2026-10-19T21:30:00Z", generatedAt = "2026-10-19T21:30:00Z",
                    ),
                )
            }),
        )
        val page = (discover.statsExperiences(StatsPeriod.ALL, StatsSort.VIEWS, 0, 10) as SessionResult.Success).value
        assertNull(page.window.from)
        assertEquals("e1", page.page.items.single().experienceId)
        assertEquals("statsExperiences:ALL:views:0" to "Bearer a-login", api.discoverCalls.last())

        // Renovação recusada → sessão terminada (entrar de novo).
        gateway.onRefresh = { com.example.portofit.data.auth.GatewayResult.Rejected(401, "INVALID_REFRESH_TOKEN", emptyList(), null) }
        api.statsResponses = ArrayDeque(listOf({ error(401) }))
        assertEquals(SessionResult.SessionEnded, discover.stats(StatsPeriod.ALL))
    }
}
